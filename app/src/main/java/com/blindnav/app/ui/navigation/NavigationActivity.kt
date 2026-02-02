package com.blindnav.app.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.blindnav.app.data.db.BlindNavDatabase
import com.blindnav.app.data.db.entity.EventType
import com.blindnav.app.data.db.entity.MapEvent
import com.blindnav.app.data.db.entity.PathPoint
import com.blindnav.app.data.sensors.LocationSensorManager
import com.blindnav.app.data.sensors.UserOrientation
import com.blindnav.app.databinding.ActivityNavigationBinding
import com.blindnav.app.domain.navigation.TacticalNavigationEngine
import com.blindnav.app.ui.audio.PriorityAudioManager
import com.blindnav.app.ui.feedback.FeedbackManager
import com.blindnav.app.ui.feedback.SonarFeedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * NavigationActivity - Pantalla de Navegación con Cámara AR
 * 
 * MODOS:
 * - MODE_NAVIGATION: Seguir ruta guardada o destino nuevo
 * - MODE_RECORDING: Grabar nueva ruta (Modo Creador)
 */
class NavigationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NavigationActivity"
        
        // Modos de operación
        const val EXTRA_MODE = "mode"
        const val MODE_NAVIGATION = "navigation"
        const val MODE_RECORDING = "recording"
        
        // Extras para navegación
        const val EXTRA_ROUTE_ID = "route_id"
        const val EXTRA_DESTINATION_NAME = "dest_name"
        const val EXTRA_DESTINATION_LAT = "dest_lat"
        const val EXTRA_DESTINATION_LON = "dest_lon"
        
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
    }

    private lateinit var binding: ActivityNavigationBinding
    
    // Core dependencies
    private lateinit var database: BlindNavDatabase
    private lateinit var audioManager: PriorityAudioManager
    private lateinit var feedbackManager: FeedbackManager
    private lateinit var locationSensorManager: LocationSensorManager
    private lateinit var tacticalEngine: TacticalNavigationEngine
    private lateinit var sonarFeedback: SonarFeedback
    
    // Camera
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    
    // State
    private var mode = MODE_NAVIGATION
    private var routeId: Long = -1
    private var destinationName = ""
    private var destinationLat = 0.0
    private var destinationLon = 0.0
    private var currentRouteId: Long = -1
    private var navigationJob: Job? = null

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startCamera()
            startNavigation()
        } else {
            audioManager.speakSystem("Se requieren permisos para navegar")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityNavigationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        parseIntent()
        initializeDependencies()
        setupUI()
        checkPermissionsAndStart()
    }

    private fun parseIntent() {
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_NAVIGATION
        routeId = intent.getLongExtra(EXTRA_ROUTE_ID, -1)
        destinationName = intent.getStringExtra(EXTRA_DESTINATION_NAME) ?: ""
        destinationLat = intent.getDoubleExtra(EXTRA_DESTINATION_LAT, 0.0)
        destinationLon = intent.getDoubleExtra(EXTRA_DESTINATION_LON, 0.0)
        
        Log.d(TAG, "Mode: $mode, Route: $routeId, Dest: $destinationName")
    }

    private fun initializeDependencies() {
        database = BlindNavDatabase.getInstance(this)
        audioManager = PriorityAudioManager(this)
        feedbackManager = FeedbackManager(this)
        locationSensorManager = LocationSensorManager(this)
        tacticalEngine = TacticalNavigationEngine()
        sonarFeedback = SonarFeedback()
    }

    private fun setupUI() {
        // Modo Grabación
        if (mode == MODE_RECORDING) {
            binding.tvNavigationStatus.text = "🎬 GRABANDO RUTA"
            binding.tvNavigationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
            binding.recordingPanel.visibility = View.VISIBLE
            binding.directionCard.visibility = View.GONE
            
            setupRecordingButtons()
        } else {
            binding.tvDestinationName.text = "→ $destinationName"
        }
        
        // Botón Detener
        binding.btnStop.setOnClickListener {
            stopAndExit()
        }
        
        // Switch Sonar
        binding.switchSonar.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                sonarFeedback.start()
            } else {
                sonarFeedback.stop()
            }
        }
    }

    private fun setupRecordingButtons() {
        binding.btnCrossing.setOnClickListener {
            reportEvent(EventType.CROSSING)
        }
        
        binding.btnObstacle.setOnClickListener {
            reportEvent(EventType.OBSTACLE_TEMPORARY)
        }
        
        binding.btnTurn.setOnClickListener {
            reportEvent(EventType.TURN)
        }
    }

    private fun checkPermissionsAndStart() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isEmpty()) {
            startCamera()
            startNavigation()
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    // ============================================
    // CAMERA
    // ============================================

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        
        // Preview only (no ImageAnalysis for simplicity in MVP)
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
        
        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview
            )
            Log.d(TAG, "✅ Camera bound successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
        }
    }

    // ============================================
    // NAVIGATION
    // ============================================

    private fun startNavigation() {
        Log.d(TAG, "Starting navigation in mode: $mode")
        
        locationSensorManager.startTracking()
        
        if (binding.switchSonar.isChecked) {
            sonarFeedback.start()
        }
        
        // Observe location & direction
        navigationJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                locationSensorManager.orientationFlow.collect { orientation ->
                    updateDirectionUI(orientation)
                    
                    // Si estamos grabando, guardar PathPoint periódicamente
                    if (mode == MODE_RECORDING && currentRouteId > 0) {
                        savePathPointPeriodically(orientation)
                    }
                }
            }
        }
        
        // Crear ruta si estamos en modo grabación
        if (mode == MODE_RECORDING) {
            lifecycleScope.launch {
                currentRouteId = createNewRoute()
                audioManager.speakNavigation("Grabación iniciada. Camina y marca los eventos.")
            }
        } else {
            audioManager.speakNavigation("Navegación iniciada. Dirección: 12 en punto.")
        }
    }

    private fun updateDirectionUI(orientation: UserOrientation) {
        if (mode == MODE_NAVIGATION && (destinationLat != 0.0 || destinationLon != 0.0)) {
            // Create user location
            val userLocation = orientation.toLocation()
            
            // Create target location
            val targetLocation = Location("target").apply {
                latitude = destinationLat
                longitude = destinationLon
            }
            
            // Calculate tactical direction using the correct API
            val direction = tacticalEngine.calculateDirection(
                userHeading = orientation.bearing,
                userLocation = userLocation,
                targetLocation = targetLocation
            )
            
            // Update UI
            binding.tvClockDirection.text = getClockText(direction.clockHour)
            binding.tvDistanceToTarget.text = formatDistance(direction.distanceMeters)
            
            // Rotate arrow
            val rotation = (direction.clockHour - 12) * 30f
            binding.tvDirectionArrow.rotation = rotation
            
            // Check if arrived
            if (direction.distanceMeters < 15) {
                audioManager.speakNavigation("Has llegado a tu destino")
                feedbackManager.vibrateSuccess()
                stopAndExit()
            }
        }
    }

    private fun getClockText(hour: Int): String {
        return when (hour) {
            12 -> "12 en punto"
            1 -> "1 en punto"
            2 -> "2 en punto"
            3 -> "3 en punto"
            6 -> "6 en punto"
            9 -> "9 en punto"
            else -> "$hour en punto"
        }
    }

    private fun formatDistance(meters: Float): String {
        return if (meters >= 1000) {
            String.format("%.1f km", meters / 1000)
        } else {
            "${meters.toInt()}m"
        }
    }

    // ============================================
    // RECORDING
    // ============================================

    private suspend fun createNewRoute(): Long {
        val route = com.blindnav.app.data.db.entity.Route(
            name = "Ruta ${java.text.SimpleDateFormat("dd/MM HH:mm").format(java.util.Date())}",
            description = "Ruta grabada",
            createdAt = System.currentTimeMillis(),
            isActive = true
        )
        return database.routeDao().insert(route)
    }

    private var lastPathPointTime = 0L
    
    private fun savePathPointPeriodically(orientation: UserOrientation) {
        val now = System.currentTimeMillis()
        if (now - lastPathPointTime > 3000) { // Cada 3 segundos
            lastPathPointTime = now
            
            lifecycleScope.launch {
                val pathPoint = PathPoint(
                    routeId = currentRouteId,
                    latitude = orientation.latitude,
                    longitude = orientation.longitude,
                    timestamp = now
                )
                database.pathPointDao().insert(pathPoint)
            }
        }
    }

    private fun reportEvent(type: EventType) {
        lifecycleScope.launch {
            val orientation = locationSensorManager.getCurrentOrientation()
            val orderIndex = database.mapEventDao().getEventCount(currentRouteId)
            
            val event = MapEvent(
                routeId = currentRouteId,
                type = type,
                latitude = orientation.latitude,
                longitude = orientation.longitude,
                bearing = orientation.bearing,
                gpsAccuracy = orientation.accuracy,
                description = type.displayName,
                orderIndex = orderIndex,
                createdAt = System.currentTimeMillis()
            )
            database.mapEventDao().insert(event)
            
            val feedback = when (type) {
                EventType.CROSSING -> "Cruce marcado"
                EventType.OBSTACLE_TEMPORARY -> "Obstáculo marcado"
                EventType.TURN -> "Giro marcado"
                else -> "Evento marcado"
            }
            
            audioManager.speakSystem(feedback)
            feedbackManager.vibrateConfirmation()
        }
    }

    // ============================================
    // CLEANUP
    // ============================================

    private fun stopAndExit() {
        navigationJob?.cancel()
        locationSensorManager.stopTracking()
        sonarFeedback.stop()
        
        if (mode == MODE_RECORDING && currentRouteId > 0) {
            audioManager.speakSystem("Grabación guardada")
        }
        
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        
        if (::audioManager.isInitialized) audioManager.release()
        if (::feedbackManager.isInitialized) feedbackManager.release()
        if (::sonarFeedback.isInitialized) sonarFeedback.release()
        if (::locationSensorManager.isInitialized) locationSensorManager.stopTracking()
    }
}
