package com.blindnav.app.ui.navigation

import android.Manifest
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.blindnav.app.domain.navigation.RouteGeometry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.room.withTransaction
import com.blindnav.app.domain.navigation.RecordedInstructionParser
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.os.PowerManager
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.blindnav.app.data.db.BlindNavDatabase
import com.blindnav.app.data.db.entity.EventType
import com.blindnav.app.data.db.entity.MapEvent
import com.blindnav.app.data.db.entity.PathPoint
import com.blindnav.app.data.CameraSource
import com.blindnav.app.data.osm.OSRMRouteProvider
import com.blindnav.app.data.repository.RealRouteRepository
import com.blindnav.app.data.sensors.LocationSensorManager
import com.blindnav.app.data.sensors.UserOrientation
import com.blindnav.app.databinding.ActivityNavigationBinding
import com.blindnav.app.domain.SafetyAnalyzer
import com.blindnav.app.domain.model.HazardLevel
import com.blindnav.app.domain.model.SafetyAnalysisResult
import com.blindnav.app.domain.navigation.GeoCoordinate
import com.blindnav.app.domain.navigation.GuidedNavigationManager
import com.blindnav.app.domain.navigation.NavigationEvent
import com.blindnav.app.domain.navigation.SavedRouteBuilder
import com.blindnav.app.domain.navigation.TacticalNavigationEngine
import com.blindnav.app.ui.audio.PriorityAudioManager
import com.blindnav.app.ui.feedback.FeedbackManager
import com.blindnav.app.ui.feedback.SonarFeedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.config.Configuration

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
        private const val OSM_USER_AGENT = "BlindNavApp/1.0 (blindnavapp.navigation@gmail.com)"
        
        // Modos de operación
        const val EXTRA_MODE = "mode"
        const val MODE_NAVIGATION = "navigation"
        const val MODE_RECORDING = "recording"
        const val MODE_SAFETY = "safety"
        
        // Extras para navegación
        const val EXTRA_ROUTE_ID = "route_id"
        const val EXTRA_DESTINATION_NAME = "dest_name"
        const val EXTRA_DESTINATION_LAT = "dest_lat"
        const val EXTRA_DESTINATION_LON = "dest_lon"
        
        private val NAVIGATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION
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
    private lateinit var guidedNavigationManager: GuidedNavigationManager
    private lateinit var routeRepository: RealRouteRepository
    
    // Camera
    private lateinit var safetyAnalyzer: SafetyAnalyzer
    private lateinit var cameraSource: CameraSource
    private var isCameraEnabled = false
    private var latestSafetyResult: SafetyAnalysisResult? = null
    private var lastCameraAlertMs = 0L
    private var lastAnnouncedHazard = HazardLevel.SAFE
    private var detectorUnavailableAnnounced = false
    
    // Map
    private var currentPolyline: org.osmdroid.views.overlay.Polyline? = null
    private var activeRouteGeometry: List<GeoPoint> = emptyList()
    private var myLocationOverlay: MyLocationNewOverlay? = null
    
    // State
    private var mode = MODE_NAVIGATION
    private var routeId: Long = -1
    private var destinationName = ""
    private var destinationLat = 0.0
    private var destinationLon = 0.0
    private var currentRouteId: Long = -1
    private var navigationJob: Job? = null
    private var gpsMonitorJob: Job? = null
    
    // 🔄 AUTO-RECÁLCULO: Cooldown para evitar bucles infinitos
    private var lastRerouteTime: Long = 0
    private val REROUTE_COOLDOWN_MS = 10_000L // 10 segundos
    
    // 🔋 WAKELOCK: Mantener GPS/TTS activos con pantalla apagada
    private var wakeLock: PowerManager.WakeLock? = null
    
    // 🔄 Flag para distinguir cálculo inicial vs recálculo
    private var isReroute = false
    private var lastGpsFixTimestamp = 0L
    private var gpsSignalWarningSent = false
    private var routeCalculationInProgress = false
    private var savedRouteLoadAttempted = false
    private var isFollowingSavedPath = false
    private val recordingMutex = Mutex()
    private var pendingManeuver: MapEvent? = null
    private var dictationAnchor: UserOrientation? = null
    private var dictationType: EventType? = null
    private var instructionEditor: AlertDialog? = null
    private var recordingWriteInProgress = false
    private var stoppingRecording = false
    private lateinit var finishManeuverButton: MaterialButton

    private val dictationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val anchor = dictationAnchor
        val type = dictationType
        dictationAnchor = null
        dictationType = null
        if (anchor != null) {
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (result.resultCode == RESULT_OK && !text.isNullOrBlank()) {
                reviewInstruction(text, anchor, type)
            } else {
                audioManager.speakSystem("Dictado cancelado. No se ha guardado ninguna instrucción.")
            }
        }
    }

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            if (mode != MODE_SAFETY) initializeMap()
            startNavigation()
        } else {
            audioManager.speakSystem("Se requieren permisos para navegar")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        Configuration.getInstance().userAgentValue = OSM_USER_AGENT
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        
        binding = ActivityNavigationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        parseIntent()
        currentRouteId = savedInstanceState?.getLong("recordingRouteId", -1L) ?: -1L
        if (savedInstanceState?.containsKey("dictationLatitude") == true) {
            dictationAnchor = UserOrientation(savedInstanceState.getDouble("dictationLatitude"),
                savedInstanceState.getDouble("dictationLongitude"), savedInstanceState.getFloat("dictationBearing"),
                savedInstanceState.getFloat("dictationAccuracy"), timestamp = savedInstanceState.getLong("dictationTimestamp"))
            dictationType = savedInstanceState.getString("dictationType")?.let { EventType.valueOf(it) }
        }
        initializeDependencies()
        setupUI()
        if (mode == MODE_RECORDING && currentRouteId > 0) {
            lifecycleScope.launch {
                pendingManeuver = database.mapEventDao().getEventsByRoute(currentRouteId).first()
                    .lastOrNull { (it.hasExplicitEnd && it.endedAt == null) ||
                        (it.type == EventType.TURN && it.targetBearing == null) }
                updateRecordingButtons()
            }
        }
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
        guidedNavigationManager = GuidedNavigationManager(
            context = this,
            locationSensorManager = locationSensorManager,
            audioManager = audioManager
        )
        routeRepository = RealRouteRepository(com.blindnav.app.data.osm.OSRMRouteProvider(this))
        safetyAnalyzer = SafetyAnalyzer()
        cameraSource = CameraSource(this, safetyAnalyzer)
    }

    private fun setupUI() {
        // Modo Grabación
        if (mode == MODE_RECORDING) {
            binding.tvNavigationStatus.text = "🎬 GRABANDO RUTA"
            binding.tvNavigationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
            binding.recordingPanel.visibility = View.VISIBLE
            binding.directionCard.visibility = View.GONE
            
            setupRecordingButtons()
        } else if (mode == MODE_SAFETY) {
            binding.tvNavigationStatus.text = "PROTECCIÓN DE OBSTÁCULOS"
            binding.tvDestinationName.text = "Cámara y avisos activos"
            binding.tvNextCheckpoint.text = "Apunta el móvil hacia delante"
            binding.directionCard.visibility = View.GONE
            binding.mapView.visibility = View.GONE
            binding.switchCamera.isChecked = true
            binding.switchCamera.isEnabled = false
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

        binding.switchCamera.isEnabled = false
        binding.switchCamera.isChecked = mode == MODE_SAFETY
        binding.switchCamera.contentDescription = "Detección disponible solo en exploración"
        if (mode != MODE_SAFETY) {
            (binding.switchCamera.parent.parent as View).visibility = View.GONE
        }
        binding.tvCurrentInstruction.setOnClickListener { guidedNavigationManager.repeatInstruction() }
        binding.tvCurrentInstruction.contentDescription = "Instrucción actual. Toca para repetir"

        binding.btnDescribeObstacle.setOnClickListener { describeObstacle() }
        binding.fabCamera.visibility = View.GONE
    }

    private fun setupRecordingButtons() {
        binding.btnStop.text = "GUARDAR RUTA"
        binding.switchSonar.isChecked = false
        binding.tvDestinationName.text = "Grabar con botones"
        binding.tvNextCheckpoint.text = "Apunta el móvil hacia delante y marca el inicio"
        binding.btnCrossing.setOnClickListener {
            beginRecordedSection(EventType.CROSSING, "Paso de cebra. Comprueba si es seguro cruzar. Mantén el rumbo grabado hasta el final marcado.")
        }
        binding.btnStraight.setOnClickListener {
            beginRecordedSection(EventType.INFO, "Empieza el tramo recto. Mantén el rumbo grabado hasta el final marcado.")
        }
        binding.btnObstacle.setOnClickListener {
            beginDictation(EventType.OBSTACLE_TEMPORARY)
        }
        binding.btnTurn.setOnClickListener {
            beginRecordedSection(EventType.TURN, "Gira a la izquierda.")
        }
        binding.btnTurnRight.setOnClickListener {
            beginRecordedSection(EventType.TURN, "Gira a la derecha.")
        }
        val panel = binding.recordingActions
        panel.addView(MaterialButton(this).apply {
            text = "DICTAR INSTRUCCIÓN"
            minHeight = (56 * resources.displayMetrics.density).toInt()
            setOnClickListener { beginDictation() }
        })
        panel.addView(MaterialButton(this).apply {
            text = "ESCRIBIR INSTRUCCIÓN"
            setOnClickListener { captureAnchor()?.let { reviewInstruction("", it, null) } }
        })
        finishManeuverButton = binding.btnFinishManeuver.apply {
            setOnClickListener { finishRecordedManeuver() }
        }
        binding.btnCancelManeuver.setOnClickListener { cancelRecordedSection() }
    }

    private fun updateRecordingButtons() {
        val pending = pendingManeuver
        finishManeuverButton.visibility = if (pending == null) View.GONE else View.VISIBLE
        binding.btnCancelManeuver.visibility = finishManeuverButton.visibility
        if (pending != null) {
            val label = when (pending.type) {
                EventType.CROSSING -> "PASO DE CEBRA"
                EventType.TURN -> "GIRO"
                else -> "TRAMO RECTO"
            }
            finishManeuverButton.text = "$label: ACABA AQUÍ"
            binding.tvNextCheckpoint.text = "$label iniciado. Pulsa Acaba aquí al llegar al final."
            (binding.recordingActions.parent as android.widget.ScrollView).smoothScrollTo(0, 0)
        }
        // Keep Finish within reach and prevent overlapping sections.
        for (index in 0 until binding.recordingActions.childCount) {
            val child = binding.recordingActions.getChildAt(index)
            if (child is MaterialButton && child != finishManeuverButton && child != binding.btnCancelManeuver) {
                child.visibility = if (pending == null) View.VISIBLE else View.GONE
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val requiredPermissions = if (mode == MODE_SAFETY) {
            arrayOf(Manifest.permission.CAMERA)
        } else {
            NAVIGATION_PERMISSIONS
        }
        // 🛡️ VALIDACIÓN: Verificar permisos CRÍTICOS antes de continuar
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isEmpty()) {
            Log.d(TAG, "✅ Todos los permisos concedidos")
            if (mode != MODE_SAFETY) initializeMap()
            startNavigation()
        } else {
            Log.w(TAG, "⚠️ Permisos faltantes: ${missingPermissions.joinToString()}")
            audioManager.speakSystem("Se necesitan los permisos indicados para este modo")
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // ============================================
    // CAMERA
    // ============================================

    private fun startCamera() {
        if (mode != MODE_SAFETY) return
        lifecycleScope.launch {
            try {
                cameraSource.startCamera(this@NavigationActivity, binding.viewFinder)
            } catch (e: Exception) {
                Log.e(TAG, "Error iniciando análisis de cámara", e)
                showDetectorUnavailable("Cámara no disponible")
            }
        }
    }

    private fun toggleCamera(enabled: Boolean) {
        if (enabled && mode != MODE_SAFETY) return
        if (!::cameraSource.isInitialized) return
        if (isCameraEnabled == enabled) return
        isCameraEnabled = enabled
        if (enabled) {
            binding.mapView.visibility = View.GONE
            binding.viewFinder.visibility = View.VISIBLE
            binding.boundingBoxOverlay.visibility = View.VISIBLE
            binding.btnDescribeObstacle.visibility = View.VISIBLE
            startCamera()
        } else {
            if (mode != MODE_SAFETY) binding.mapView.visibility = View.VISIBLE
            binding.viewFinder.visibility = View.GONE
            binding.boundingBoxOverlay.visibility = View.GONE
            binding.btnDescribeObstacle.visibility = View.GONE
            binding.boundingBoxOverlay.clear()
            cameraSource.stopCamera()
        }
    }

    private fun observeCameraAnalysis() {
        if (mode != MODE_SAFETY) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                cameraSource.analysisResultFlow.collect { result ->
                    latestSafetyResult = result
                    if (!result.isOperational) {
                        showDetectorUnavailable(result.errorMessage ?: "Detector no disponible")
                        return@collect
                    }
                    detectorUnavailableAnnounced = false

                    binding.boundingBoxOverlay.updateObstacles(
                        result.obstacles,
                        result.hazardLevel,
                        result.imageWidth,
                        result.imageHeight
                    )
                    val obstacle = result.primaryObstacle
                    if (obstacle != null && result.hazardLevel != HazardLevel.SAFE) {
                        val label = localizedObjectLabel(obstacle.label)
                        binding.hazardCard.visibility = View.VISIBLE
                        binding.tvHazardWarning.text = if (result.hazardLevel == HazardLevel.CRITICAL) {
                            "PELIGRO: $label muy cerca"
                        } else {
                            "Precaución: $label delante"
                        }
                        val now = System.currentTimeMillis()
                        val repeatInterval = if (result.hazardLevel == HazardLevel.CRITICAL) 1_500L else 3_000L
                        if (result.hazardLevel != lastAnnouncedHazard || now - lastCameraAlertMs >= repeatInterval) {
                            audioManager.alertSafety(
                                result.hazardLevel,
                                if (result.hazardLevel == HazardLevel.CRITICAL) "Alto. Obstáculo delante" else null
                            )
                            lastAnnouncedHazard = result.hazardLevel
                            lastCameraAlertMs = now
                        }
                        sonarFeedback.processProximity(
                            obstacle.boundingBox.height() / result.imageHeight.coerceAtLeast(1).toFloat()
                        )
                    } else {
                        binding.hazardCard.visibility = View.GONE
                        sonarFeedback.processProximity(0f)
                        lastAnnouncedHazard = HazardLevel.SAFE
                    }
                }
            }
        }
    }

    private fun showDetectorUnavailable(reason: String) {
        binding.hazardCard.visibility = View.VISIBLE
        binding.tvHazardWarning.text = "DETECCIÓN NO DISPONIBLE"
        if (!detectorUnavailableAnnounced) {
            audioManager.alertSafety(HazardLevel.WARNING)
            audioManager.speakSystem("Detección no disponible. Detente y comprueba el entorno.")
            detectorUnavailableAnnounced = true
        }
        Log.e(TAG, reason)
    }

    private fun describeObstacle() {
        val result = latestSafetyResult
        if (result == null || !result.isOperational) {
            audioManager.speakSystem("No puedo analizar la imagen ahora. Detente y comprueba el entorno.")
            return
        }
        val obstacle = result.primaryObstacle
        if (obstacle == null) {
            audioManager.speakSystem("No identifico ningún objeto en la imagen. Esto no confirma que el paso esté libre.")
            return
        }
        val horizontalPosition = when {
            obstacle.centerX < 256 -> "a la izquierda"
            obstacle.centerX > 384 -> "a la derecha"
            else -> "en el centro"
        }
        val proximity = when {
            obstacle.boundingBox.height() >= 192 -> "muy cerca"
            obstacle.boundingBox.height() >= 72 -> "a media distancia"
            else -> "lejos"
        }
        val label = localizedObjectLabel(obstacle.label, "un objeto no identificado")
        audioManager.speakSystem("Detecto $label $horizontalPosition, $proximity.")
    }

    private fun localizedObjectLabel(label: String?, fallback: String = "obstáculo"): String {
        return when (label?.trim()?.lowercase()) {
            "fashion good" -> "un objeto de ropa"
            "food" -> "comida"
            "home good" -> "un objeto doméstico"
            "place" -> "una estructura"
            "plant" -> "una planta"
            null, "" -> fallback
            else -> label
        }
    }
    
    // ============================================
    // MAP
    // ============================================
    
    /**
     * 🗺️ Inicializar MapView de OSMDroid con FOLLOW ME real
     */
    private fun initializeMap() {
        Log.d(TAG, "🗺️ Inicializando mapa OSM con GPS tracking")
        
        // Configurar OSMDroid
        Configuration.getInstance().apply {
            userAgentValue = OSM_USER_AGENT
            isDebugMode = false
        }
        
        binding.mapView.apply {
            setMultiTouchControls(true)
            controller.setZoom(18.0) // Zoom urbano (se ajustará al primer fix GPS)
            // Sin centro inicial: se centrará en el primer fix GPS real
        }
        
        // 🎯 CRÍTICO: MyLocationNewOverlay - Icono "Mi Ubicación" + FOLLOW ME
        myLocationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(this), binding.mapView).apply {
            enableMyLocation() // Activa GPS tracking
            enableFollowLocation() // Activa "Follow Me" (cámara sigue al usuario)
            
            // 🚀 PRIMER FIX GPS: Cuando GPS obtiene primera ubicación real
            runOnFirstFix {
                runOnUiThread {
                    val myLocation = myLocation // GeoPoint con ubicación real
                    if (myLocation != null) {
                        Log.d(TAG, "✅ GPS FIRST FIX: Lat=${myLocation.latitude}, Lon=${myLocation.longitude}")
                        
                        // Centrar mapa en ubicación real con animación
                        binding.mapView.controller.animateTo(myLocation)
                        binding.mapView.controller.setZoom(19.0) // Zoom callejero
                        
                        // ¡AQUÍ calculamos la ruta REAL desde ubicación actual!
                        calculateRouteFromCurrentLocation()
                        
                        audioManager.speakSystem("Ubicación detectada. Calculando ruta.")
                    } else {
                        Log.w(TAG, "⚠️ GPS First Fix devolvió null")
                    }
                }
            }
        }
        
        // Añadir overlay al mapa
        binding.mapView.overlays.add(myLocationOverlay)
        
        Log.d(TAG, "✅ Mapa inicializado con GPS tracking activo")
    }
    
    /**
     * 🗺️ Dibujar ruta en el mapa con polyline gruesa y de alto contraste
     */
    private fun drawRouteOnMap(polyline: org.osmdroid.views.overlay.Polyline) {
        // Eliminar polyline anterior si existe
        currentPolyline?.let {
            binding.mapView.overlays.remove(it)
        }
        
        // 🎨 Configurar polyline para MÁXIMA visibilidad
        polyline.outlinePaint.apply {
            color = 0xFF0099FF.toInt() // Azul eléctrico (alto contraste)
            strokeWidth = 15f // Grosor visible bajo el sol
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            isAntiAlias = true
        }
        
        // Agregar al mapa (debajo del icono de ubicación)
        binding.mapView.overlays.add(0, polyline) // Índice 0 = debajo de otros overlays
        currentPolyline = polyline
        activeRouteGeometry = polyline.actualPoints.toList()
        
        // Centrar mapa en el inicio de la ruta (pero Follow Me lo ajustará)
        if (polyline.actualPoints.isNotEmpty()) {
            binding.mapView.controller.setCenter(polyline.actualPoints.first())
        }
        
        // 🔄 FORZAR REPINTADO
        binding.mapView.invalidate()
        binding.mapView.postInvalidate()
        
        Log.d(TAG, "✅ Ruta dibujada: ${polyline.actualPoints.size} puntos")
    }
    
    /**
     * 🚀 Calcular ruta REAL desde ubicación GPS actual hasta destino
     * Se llama automáticamente cuando GPS obtiene primer fix
     */
    private fun calculateRouteFromCurrentLocation() {
        if (mode == MODE_RECORDING) {
            Log.d(TAG, "Modo grabación: no calcular ruta automáticamente")
            return
        }

        val lastLocation = locationSensorManager.getFreshLocation()
        if (lastLocation == null) {
            Log.w(TAG, "No se puede iniciar: el GPS aún no es reciente y preciso")
            binding.tvNavigationStatus.text = "ESPERANDO GPS PRECISO..."
            return
        }

        if (routeId > 0 && !savedRouteLoadAttempted && !isReroute) {
            savedRouteLoadAttempted = true
            loadSavedRoute(lastLocation)
            return
        }

        if (routeCalculationInProgress) return
        val startLat = lastLocation.latitude
        val startLon = lastLocation.longitude
        
        // Validar que tenemos coordenadas de destino
        if (destinationLat == 0.0 && destinationLon == 0.0) {
            Log.w(TAG, "⚠️ No hay coordenadas de destino")
            binding.tvNavigationStatus.text = "RUTA GUARDADA SIN TRAZADO"
            audioManager.speakSystem("Esta ruta no contiene suficientes puntos para navegar.")
            return
        }

        routeCalculationInProgress = true
        
        Log.d(TAG, "🚀 Calculando ruta: ($startLat, $startLon) -> ($destinationLat, $destinationLon)")

        binding.tvNavigationStatus.text = if (isReroute) {
            "🔄 RECALCULANDO RUTA..."
        } else {
            "🔄 BUSCANDO RUTA..."
        }
        binding.tvNavigationStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 🌐 Llamada HTTP a OSRM
                // Obtener checkpoints guardados para fusionar
                val savedCheckpoints: List<com.blindnav.app.data.db.entity.Checkpoint> = if (routeId > 0) {
                    database.checkpointDao().getCheckpointsByRoute(routeId).first()
                } else {
                    emptyList()
                }
                
                val routeResponse = routeRepository.getRoute(
                    start = GeoPoint(startLat, startLon),
                    end = GeoPoint(destinationLat, destinationLon),
                    routeId = routeId,
                    savedCheckpoints = savedCheckpoints
                )
                
                // ✅ Actualizar UI en Main Thread
                withContext(Dispatchers.Main) {
                    when (routeResponse) {
                        is OSRMRouteProvider.RouteFetchResult.Success -> {
                            val routeResult = routeResponse.routeResult
                        Log.d(TAG, "✅ Ruta calculada: ${routeResult.checkpoints.size} checkpoints, ${routeResult.totalDistance}m")
                        Log.d(TAG, "✅ Geometría completa: ${routeResult.road.mRouteHigh.size} puntos (sigue calles)")
                        
                        // 🎯 Iniciar navegación guiada con los checkpoints
                        guidedNavigationManager.startNavigation(
                            routeResult.checkpoints,
                            routeResult.road.mRouteHigh.map { GeoCoordinate(it.latitude, it.longitude) }
                        )
                        
                            // 🗺️ Dibujar ruta con geometría detallada + nodos de instrucción
                            drawRouteOnMap(routeResult.polyline)
                            activeRouteGeometry = routeResult.road.mRouteHigh.toList()
                        
                        // 📢 Feedback al usuario (diferente para recálculo vs inicial)
                        val distanceKm = routeResult.totalDistance / 1000
                        val distanceText = if (distanceKm >= 1.0) {
                            String.format("%.1f kilómetros", distanceKm)
                        } else {
                            "${routeResult.totalDistance.toInt()} metros"
                        }
                        
                        if (isReroute) {
                            binding.tvNavigationStatus.text = "✅ RUTA ACTUALIZADA"
                            audioManager.speakNavigation("Nueva ruta calculada. $distanceText.")
                            isReroute = false
                        } else {
                            binding.tvNavigationStatus.text = "✅ RUTA CALCULADA"
                            audioManager.speakNavigation("Ruta calculada. $distanceText. Navegación iniciada.")
                        }
                            if (!routeResult.accessibility.dataAvailable) {
                                audioManager.speakSystem("No se pudieron verificar los datos de accesibilidad de OpenStreetMap.")
                            } else if (routeResult.accessibility.steps > 0) {
                                audioManager.speakSystem("Aviso: la alternativa disponible contiene escaleras.")
                            }
                            binding.tvNavigationStatus.setTextColor(ContextCompat.getColor(this@NavigationActivity, android.R.color.holo_green_light))
                            routeCalculationInProgress = false
                        }

                        is OSRMRouteProvider.RouteFetchResult.Failure -> {
                            val errorMessage = routeResponse.userMessage
                            Log.w(TAG, "⚠️ Fallo de OSRM: $errorMessage")
                            binding.tvNavigationStatus.text = "⚠️ SIN RUTA"
                            binding.tvNavigationStatus.setTextColor(ContextCompat.getColor(this@NavigationActivity, android.R.color.holo_red_light))
                            if (isReroute) {
                                guidedNavigationManager.stopNavigation()
                                audioManager.speakSystem("No se pudo recalcular. Detente en un lugar seguro. $errorMessage")
                                isReroute = false
                            } else {
                                audioManager.speakSystem(errorMessage)
                            }
                            routeCalculationInProgress = false
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error calculando ruta", e)
                withContext(Dispatchers.Main) {
                    val errorMsg = "No puedo conectar con el servidor de rutas. Comprueba tu conexión"
                    
                    Toast.makeText(
                        this@NavigationActivity,
                        "⚠️ Error: $errorMsg",
                        Toast.LENGTH_LONG
                    ).show()
                    binding.tvNavigationStatus.text = "⚠️ SIN RUTA"
                    binding.tvNavigationStatus.setTextColor(ContextCompat.getColor(this@NavigationActivity, android.R.color.holo_red_light))
                    
                    audioManager.speakSystem(errorMsg)
                    isReroute = false
                    routeCalculationInProgress = false
                }
            }
        }
    }

    private fun loadSavedRoute(currentLocation: Location) {
        routeCalculationInProgress = true
        lifecycleScope.launch(Dispatchers.IO) {
            val storedRoute = database.routeDao().getRouteById(routeId)
            val points = database.pathPointDao().getPointsByRoute(routeId).first()
            val events = database.mapEventDao().getEventsByRoute(routeId).first()
            val savedCheckpoints = database.checkpointDao().getCheckpointsByRoute(routeId).first()
            val route = SavedRouteBuilder.build(
                routeId = routeId,
                pathPoints = points,
                events = events,
                currentLocation = GeoCoordinate(currentLocation.latitude, currentLocation.longitude),
                savedCheckpoints = savedCheckpoints
            )
            withContext(Dispatchers.Main) {
                routeCalculationInProgress = false
                if (route == null) {
                    binding.tvNavigationStatus.text = "RUTA GUARDADA INCOMPLETA"
                    audioManager.speakSystem("La ruta está incompleta: faltan puntos GPS o el final de un tramo grabado.")
                    return@withContext
                }
                val pointsOnMap = route.geometry.map { GeoPoint(it.latitude, it.longitude) }
                binding.tvDestinationName.text = "→ ${storedRoute?.name ?: "Ruta guardada"}"
                val polyline = org.osmdroid.views.overlay.Polyline().apply { setPoints(pointsOnMap) }
                drawRouteOnMap(polyline)
                isFollowingSavedPath = true
                guidedNavigationManager.startNavigation(route.checkpoints, route.geometry, route.recordedInstructions)
                binding.tvNavigationStatus.text = "RUTA GRABADA LISTA"
                val directionText = if (route.reversed) "en sentido inverso" else "en el sentido grabado"
                audioManager.speakNavigation(
                    "Ruta grabada cargada $directionText. ${route.totalDistanceMeters.toInt()} metros."
                )
            }
        }
    }

    // ============================================
    // NAVIGATION
    // ============================================

    private fun startNavigation() {
        Log.d(TAG, "Starting navigation in mode: $mode")
        
        if (mode != MODE_SAFETY) locationSensorManager.startTracking()
        observeCameraAnalysis()

        if (mode == MODE_SAFETY) {
            toggleCamera(true)
            audioManager.speakSystem("Modo protección activado. La cámara permanecerá encendida.")
        }
        
        if (binding.switchSonar.isChecked) {
            sonarFeedback.start()
        }
        
        // 🎯 Observar estado de GuidedNavigationManager
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                guidedNavigationManager.state.collect { navState: com.blindnav.app.domain.navigation.GuidedNavigationState ->
                    updateNavigationUI(navState)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                guidedNavigationManager.events.collect { event ->
                    when (event) {
                        NavigationEvent.RerouteRequested -> requestReroute()
                    }
                }
            }
        }
        
        // Observe location & direction
        if (mode != MODE_SAFETY) {
            navigationJob = lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    locationSensorManager.orientationFlow.collect { orientation ->
                    lastGpsFixTimestamp = orientation.timestamp
                    if (System.currentTimeMillis() - orientation.timestamp < 10_000L) {
                        gpsSignalWarningSent = false
                    }
                    if (mode == MODE_NAVIGATION && activeRouteGeometry.isEmpty() && !routeCalculationInProgress) {
                        calculateRouteFromCurrentLocation()
                    }
                    
                    // Si estamos grabando, guardar PathPoint periódicamente
                    if (mode == MODE_RECORDING && currentRouteId > 0) {
                        savePathPointPeriodically(orientation)
                    }
                    }
                }
            }
        }
        
        // Crear ruta si estamos en modo grabación
        if (mode == MODE_RECORDING) {
            lifecycleScope.launch {
                try {
                    if (currentRouteId <= 0) currentRouteId = createNewRoute()
                } catch (e: Exception) {
                    audioManager.speakNavigation("No se ha podido iniciar la grabación.")
                    binding.tvNavigationStatus.text = "ERROR AL CREAR RUTA"
                    return@launch
                }
                audioManager.speakNavigation("Grabación iniciada. Apunta el móvil hacia donde caminas. Pulsa Empieza aquí para un paso de cebra, un tramo recto o un giro. Al terminarlo, pulsa Acaba aquí.")
            }
        } else if (mode == MODE_NAVIGATION) {
            audioManager.speakNavigation("Navegación iniciada. Calculando ruta...")
        }

        if (mode != MODE_SAFETY) startGpsSignalMonitor()
        acquireWakeLock()
    }

    private fun requestReroute() {
        if (isFollowingSavedPath) {
            audioManager.speakNavigation("Te has alejado de la ruta grabada. Vuelve hacia la línea marcada.")
            return
        }
        val currentTime = System.currentTimeMillis()
        val timeSinceLastReroute = currentTime - lastRerouteTime

        if (timeSinceLastReroute < REROUTE_COOLDOWN_MS) {
            return
        }

        lastRerouteTime = currentTime
        isReroute = true
        audioManager.speakNavigation("Recalculando ruta")

        lifecycleScope.launch {
            delay(500)
            calculateRouteFromCurrentLocation()
        }
    }

    private fun startGpsSignalMonitor() {
        gpsMonitorJob?.cancel()
        gpsMonitorJob = lifecycleScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val withoutSignalMs = now - lastGpsFixTimestamp
                if (lastGpsFixTimestamp > 0 && withoutSignalMs > 10_000L && !gpsSignalWarningSent) {
                    gpsSignalWarningSent = true
                    audioManager.speakSystem("Buscando señal GPS...")
                }
                delay(2000L)
            }
        }
    }
    
    /**
     * 🎯 Actualiza la UI con el estado de navegación guiada
     */
    private fun updateNavigationUI(navState: com.blindnav.app.domain.navigation.GuidedNavigationState) {
        if (navState.hasArrived) {
            binding.tvNavigationStatus.text = "DESTINO ALCANZADO"
            binding.tvNavigationStatus.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_green_light)
            )
            return
        }
        if (!navState.isActive) return

        val cameraShouldBeEnabled = mode == MODE_SAFETY
        toggleCamera(cameraShouldBeEnabled)
        if (navState.isInCrossingMode) safetyAnalyzer.enableCrossingGuardMode()
        else safetyAnalyzer.disableCrossingGuardMode()

        if (!navState.locationReliable) {
            binding.tvNavigationStatus.text = "GPS IMPRECISO: DETENTE"
            binding.tvNavigationStatus.setTextColor(
                ContextCompat.getColor(this, android.R.color.holo_orange_light)
            )
            return
        }
        
        // Actualizar checkpoint actual
        navState.currentCheckpoint?.let { checkpoint ->
            binding.tvNextCheckpoint.text = "Próximo: ${checkpoint.description}"
        }
        
        // Actualizar instrucción actual si hay dirección
        navState.lastDirection?.let { direction ->
            val distanceText = if (direction.distanceMeters >= 1000) {
                String.format("%.1f km", direction.distanceMeters / 1000)
            } else {
                "${direction.distanceMeters.toInt()}m"
            }
            
            val instructionText = when {
                navState.recordedHeadingError != null -> {
                    val error = navState.recordedHeadingError
                    if (kotlin.math.abs(error) <= 15f) "Rumbo grabado alineado"
                    else "Corrige a la ${if (error > 0) "derecha" else "izquierda"}: ${kotlin.math.abs(error).toInt()}°"
                }
                navState.recordedInstruction != null -> navState.recordedInstruction
                navState.isInCrossingMode -> {
                    val error = navState.crossingHeadingErrorDegrees ?: 0f
                    when {
                        kotlin.math.abs(error) <= 15f -> "CRUZANDO: VAS RECTO"
                        error > 0 -> "CRUZANDO: CORRIGE A LA DERECHA"
                        else -> "CRUZANDO: CORRIGE A LA IZQUIERDA"
                    }
                }
                direction.isAhead -> "Próximo punto delante - $distanceText"
                else -> "En $distanceText: Busca las ${direction.clockHour}"
            }

            if (navState.recordedHeadingError != null) {
                binding.tvClockDirection.text = "Rumbo grabado"
                binding.tvDirectionArrow.rotation = navState.recordedHeadingError
            } else if (navState.isInCrossingMode) {
                val headingError = navState.crossingHeadingErrorDegrees ?: 0f
                binding.tvClockDirection.text = when {
                    kotlin.math.abs(headingError) <= 15f -> "Rumbo correcto"
                    headingError > 0 -> "Corrige a la derecha"
                    else -> "Corrige a la izquierda"
                }
                binding.tvDirectionArrow.rotation = headingError.coerceIn(-90f, 90f)
            } else {
                binding.tvClockDirection.text = getClockText(direction.clockHour)
                binding.tvDirectionArrow.rotation = (direction.clockHour - 12) * 30f
            }
            binding.tvDistanceToTarget.text = distanceText
            
            binding.tvCurrentInstruction.apply {
                text = instructionText
                visibility = View.VISIBLE
            }
            
            // Cambiar color si está en modo cruce
            binding.tvCurrentInstruction.setTextColor(
                if (navState.isInCrossingMode) {
                    ContextCompat.getColor(this, android.R.color.holo_orange_light)
                } else {
                    ContextCompat.getColor(this, android.R.color.holo_green_light)
                }
            )
        }
        
        // Actualizar estado de navegación
        binding.tvNavigationStatus.text = "NAVEGANDO (${navState.currentIndex + 1}/${navState.totalCheckpoints})"
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
            isActive = false
        )
        return database.routeDao().insert(route)
    }

    private var lastPathPointTime = 0L
    private var lastRecordedCoordinate: GeoCoordinate? = null
    
    private fun savePathPointPeriodically(orientation: UserOrientation) {
        if (stoppingRecording) return
        val now = System.currentTimeMillis()
        if (now - orientation.timestamp !in 0..5_000L || orientation.accuracy !in 0f..12f) return
        val coordinate = GeoCoordinate(orientation.latitude, orientation.longitude)
        val movedMeters = lastRecordedCoordinate?.let {
            com.blindnav.app.domain.navigation.RouteGeometry.distanceMeters(it, coordinate)
        } ?: Double.MAX_VALUE
        if (movedMeters >= 3.0 && now - lastPathPointTime > 1_500L) {
            lastPathPointTime = now
            lastRecordedCoordinate = coordinate
            
            lifecycleScope.launch {
                val pathPoint = PathPoint(
                    routeId = currentRouteId,
                    latitude = orientation.latitude,
                    longitude = orientation.longitude,
                    timestamp = now
                )
                try {
                    recordingMutex.withLock { database.pathPointDao().insert(pathPoint) }
                } catch (e: Exception) {
                    Log.e(TAG, "Error guardando trazado", e)
                    binding.tvNavigationStatus.text = "ERROR AL GUARDAR TRAZADO"
                }
            }
        }
    }

    private fun captureAnchor(): UserOrientation? {
        if (currentRouteId <= 0 || recordingWriteInProgress || stoppingRecording || instructionEditor != null || dictationAnchor != null) return null
        if (pendingManeuver != null) {
            audioManager.speakSystem("Marca primero Acaba aquí o cancela el tramo pendiente.")
            return null
        }
        val orientation = locationSensorManager.getCurrentOrientation()
        if (System.currentTimeMillis() - orientation.timestamp !in 0..5_000L ||
            orientation.accuracy !in 0f..12f || !locationSensorManager.isHeadingReliable()) {
            audioManager.speakSystem("Espera un GPS preciso y calibra la brújula. Mantén el móvil apuntando hacia delante.")
            return null
        }
        return orientation
    }

    private fun beginDictation(type: EventType? = null) {
        val anchor = captureAnchor() ?: return
        audioManager.clearGuidance()
        dictationAnchor = anchor
        dictationType = type
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Dicta la instrucción de este punto. Por ejemplo: ahora sigue recto.")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            dictationLauncher.launch(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            dictationAnchor = null
            dictationType = null
            audioManager.speakSystem("Dictado no disponible. Puedes escribir la instrucción.")
            reviewInstruction("", anchor, type)
        }
    }

    private fun reviewInstruction(text: String, anchor: UserOrientation, forcedType: EventType?) {
        val input = EditText(this).apply {
            setText(text)
            hint = "Instrucción que se repetirá en este punto"
            contentDescription = hint
            minLines = 2
            maxLines = 5
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Revisar instrucción")
            .setMessage("Se guardará donde pulsaste dictar. Para rectos y cruces, apunta hacia el tramo que vas a recorrer.")
            .setView(input)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Escuchar") { _, _ -> }
            .setPositiveButton("Guardar", null)
            .create()
        instructionEditor = dialog
        dialog.setOnDismissListener { instructionEditor = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                audioManager.speakNavigation(input.text.toString())
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val sentence = input.text.toString().trim()
                if (sentence.isBlank()) { input.error = "Escribe o dicta una instrucción"; return@setOnClickListener }
                val type = forcedType ?: RecordedInstructionParser.type(sentence)
                persistInstruction(sentence, anchor, type)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun beginRecordedSection(type: EventType, text: String) {
        val anchor = captureAnchor() ?: return
        persistInstruction(text, anchor, type, explicitEnd = true)
    }

    private fun persistInstruction(text: String, anchor: UserOrientation, type: EventType, explicitEnd: Boolean = false) {
        if (recordingWriteInProgress || stoppingRecording) return
        recordingWriteInProgress = true
        lifecycleScope.launch {
            try {
                recordingMutex.withLock {
                    val event = database.withTransaction {
                        val order = (database.mapEventDao().getMaxOrderIndex(currentRouteId) ?: -1) + 1
                        val event = MapEvent(routeId = currentRouteId, type = type,
                            latitude = anchor.latitude, longitude = anchor.longitude,
                            bearing = anchor.bearing, gpsAccuracy = anchor.accuracy,
                            description = text, orderIndex = order, createdAt = anchor.timestamp,
                            targetBearing = if (RecordedInstructionParser.locksHeading(text, type)) anchor.bearing else null,
                            hasExplicitEnd = explicitEnd)
                        database.pathPointDao().insert(PathPoint(routeId = currentRouteId,
                            latitude = anchor.latitude, longitude = anchor.longitude, timestamp = anchor.timestamp))
                        event.copy(id = database.mapEventDao().insert(event))
                    }
                    if (type == EventType.TURN || explicitEnd) {
                        pendingManeuver = event
                    }
                    binding.tvNextCheckpoint.text = "Guardada: $text"
                    updateRecordingButtons()
                    audioManager.speakNavigation(if (explicitEnd)
                        "Inicio guardado. ${if (type == EventType.TURN) "Haz el giro" else "Recorre el tramo"} con el móvil apuntando hacia delante. Pulsa Acaba aquí al terminar."
                        else "Guardada: $text" + if (type == EventType.TURN)
                            " Haz el giro y pulsa Acaba aquí con el móvil hacia delante." else "")
                    feedbackManager.vibrateConfirmation()
                }
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo guardar la instrucción", e)
                audioManager.speakNavigation("No se ha guardado la instrucción. Vuelve a intentarlo.")
            } finally { recordingWriteInProgress = false }
        }
    }

    private fun finishRecordedManeuver() {
        val maneuver = pendingManeuver ?: return
        if (recordingWriteInProgress) return
        val end = locationSensorManager.getCurrentOrientation()
        if (!locationSensorManager.isHeadingReliable() || System.currentTimeMillis() - end.timestamp !in 0..5_000L || end.accuracy !in 0f..12f) {
            audioManager.speakSystem("Espera un GPS y una brújula fiables para guardar el final.")
            return
        }
        val angle = RouteGeometry.signedHeadingError(maneuver.bearing, end.bearing)
        val requestedSide = RecordedInstructionParser.turnSide(maneuver.description)
        if (maneuver.type == EventType.TURN &&
            (kotlin.math.abs(angle) < 15f || (requestedSide != null && angle * requestedSide < 0))) {
            audioManager.speakNavigation("El rumbo medido no coincide con el giro indicado. Apunta el móvil hacia la salida del giro y vuelve a marcar el final.")
            return
        }
        val length = RouteGeometry.distanceMeters(GeoCoordinate(maneuver.latitude, maneuver.longitude),
            GeoCoordinate(end.latitude, end.longitude))
        if (maneuver.type != EventType.TURN && (length < 3.0 || end.timestamp <= maneuver.createdAt)) {
            audioManager.speakNavigation("El GPS aún no distingue el final del inicio. Espera una posición nueva en el final del tramo.")
            return
        }
        recordingWriteInProgress = true
        lifecycleScope.launch {
            try {
                recordingMutex.withLock {
                    database.withTransaction {
                        database.mapEventDao().update(maneuver.copy(
                            // A crossing/straight keeps its START compass heading, even if
                            // the phone rotates while pressing Finish on the far pavement.
                            targetBearing = if (maneuver.type == EventType.TURN) end.bearing else maneuver.targetBearing,
                            turnDegrees = if (maneuver.type == EventType.TURN) angle else null,
                            endLatitude = if (maneuver.hasExplicitEnd) end.latitude else null,
                            endLongitude = if (maneuver.hasExplicitEnd) end.longitude else null,
                            endedAt = if (maneuver.hasExplicitEnd) end.timestamp else null))
                        database.pathPointDao().insert(PathPoint(routeId = currentRouteId,
                            latitude = end.latitude, longitude = end.longitude, timestamp = end.timestamp))
                    }
                    pendingManeuver = null
                    updateRecordingButtons()
                    binding.tvNextCheckpoint.text = "Inicio y final guardados. Puedes marcar el siguiente tramo."
                    audioManager.speakNavigation(if (maneuver.type == EventType.TURN)
                        "Giro guardado: ${kotlin.math.abs(angle).toInt()} grados hacia la ${if (angle >= 0) "derecha" else "izquierda"}."
                        else "Inicio y final guardados. Tramo de unos ${length.toInt()} metros con el rumbo de brújula del inicio.")
                    feedbackManager.vibrateConfirmation()
                }
            } catch (e: Exception) {
                audioManager.speakNavigation("No se pudo guardar el final. Vuelve a pulsar Acaba aquí.")
            } finally { recordingWriteInProgress = false }
        }
    }

    private fun cancelRecordedSection() {
        val pending = pendingManeuver ?: return
        if (recordingWriteInProgress) return
        recordingWriteInProgress = true
        lifecycleScope.launch {
            try {
                recordingMutex.withLock { database.mapEventDao().delete(pending) }
                pendingManeuver = null
                updateRecordingButtons()
                binding.tvNextCheckpoint.text = "Tramo cancelado. Puedes marcar otro inicio."
                audioManager.speakNavigation("Tramo cancelado. La ruta sigue grabándose.")
            } catch (e: Exception) {
                audioManager.speakNavigation("No se pudo cancelar el tramo. Vuelve a intentarlo.")
            } finally { recordingWriteInProgress = false }
        }
    }

    // ============================================
    // LIFECYCLE
    // ============================================
    
    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        myLocationOverlay?.enableMyLocation()
    }
    
    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("recordingRouteId", currentRouteId)
        dictationAnchor?.let {
            outState.putDouble("dictationLatitude", it.latitude)
            outState.putDouble("dictationLongitude", it.longitude)
            outState.putFloat("dictationBearing", it.bearing)
            outState.putFloat("dictationAccuracy", it.accuracy)
            outState.putLong("dictationTimestamp", it.timestamp)
            outState.putString("dictationType", dictationType?.name)
        }
        super.onSaveInstanceState(outState)
    }
    
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BlindNav::NavigationWakeLock"
            )
        }
        wakeLock?.acquire(60 * 60 * 1000L) // Máximo 1 hora
        Log.d(TAG, "🔋 WakeLock adquirido")
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "🔋 WakeLock liberado")
            }
        }
        wakeLock = null
    }

    // ============================================
    // CLEANUP
    // ============================================

    private fun stopAndExit() {
        if (mode == MODE_RECORDING && currentRouteId > 0) {
            if (recordingWriteInProgress || instructionEditor != null || dictationAnchor != null || stoppingRecording) return
            if (pendingManeuver != null) {
                audioManager.speakNavigation("Antes de guardar la ruta, pulsa Acaba aquí o cancela el tramo pendiente.")
                return
            }
            val end = locationSensorManager.getCurrentOrientation()
            if (System.currentTimeMillis() - end.timestamp !in 0..5_000L || end.accuracy !in 0f..12f) {
                audioManager.speakNavigation("Espera un GPS preciso para guardar el final de la ruta.")
                return
            }
            stoppingRecording = true
            lifecycleScope.launch {
                try {
                    recordingMutex.withLock {
                        database.pathPointDao().insert(PathPoint(routeId = currentRouteId,
                            latitude = end.latitude, longitude = end.longitude, timestamp = System.currentTimeMillis()))
                    }
                    Toast.makeText(this@NavigationActivity, "Ruta e instrucciones guardadas", Toast.LENGTH_LONG).show()
                    closeNavigation()
                } catch (e: Exception) {
                    stoppingRecording = false
                    audioManager.speakNavigation("No se pudo guardar el final de la ruta. Vuelve a intentarlo.")
                }
            }
            return
        }
        closeNavigation()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { stopAndExit() }

    private fun closeNavigation() {
        navigationJob?.cancel()
        gpsMonitorJob?.cancel()
        locationSensorManager.stopTracking()
        sonarFeedback.stop()
        if (::cameraSource.isInitialized) cameraSource.stopCamera()
        releaseWakeLock()
        
        if (::guidedNavigationManager.isInitialized) {
            guidedNavigationManager.stopNavigation()
        }
        
        if (mode == MODE_RECORDING && currentRouteId > 0) {
            audioManager.speakSystem("Grabación guardada")
        }
        
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        
        releaseWakeLock()
        if (::cameraSource.isInitialized) cameraSource.release()
        
        if (::audioManager.isInitialized) audioManager.release()
        if (::feedbackManager.isInitialized) feedbackManager.release()
        if (::sonarFeedback.isInitialized) sonarFeedback.release()
        if (::locationSensorManager.isInitialized) locationSensorManager.stopTracking()
        if (::guidedNavigationManager.isInitialized) guidedNavigationManager.release()
    }
}
