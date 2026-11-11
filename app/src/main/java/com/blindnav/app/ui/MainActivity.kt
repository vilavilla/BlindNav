package com.blindnav.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.blindnav.app.R
import com.blindnav.app.data.CameraSource
import com.blindnav.app.data.repository.SafetyRepositoryImpl
import com.blindnav.app.databinding.ActivityMainBinding
import com.blindnav.app.domain.SafetyAnalyzer
import com.blindnav.app.domain.model.HazardLevel
import com.blindnav.app.domain.model.SafetyAnalysisResult
import com.blindnav.app.domain.model.VoiceCommandResult
import com.blindnav.app.domain.model.VoiceRecognizerState
import com.blindnav.app.ui.audio.PriorityAudioManager
import com.blindnav.app.ui.feedback.SonarFeedback
import com.blindnav.app.ui.voice.VoiceCommander
import com.blindnav.app.data.db.BlindNavDatabase
import com.blindnav.app.data.db.entity.EventType
import com.blindnav.app.data.sensors.LocationSensorManager
import com.blindnav.app.domain.navigation.GuidedNavigationManager
import com.blindnav.app.ui.recording.RouteRecorderViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * MainActivity - Pantalla principal de BlindNav
 * 
 * PRIORIDAD: Arreglar pantalla negra con gestión correcta de cámara.
 * 
 * CAPAS DE UI:
 * 1. viewFinder (PreviewView) - Cámara en vivo
 * 2. boundingBoxOverlay - Detección de obstáculos
 * 3. hudCard - Información GPS/Brújula
 * 4. controlsContainer - Botones flotantes
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
    }

    // ============================================
    // BINDING Y COMPONENTES
    // ============================================
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    
    // CameraX
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    
    // Componentes del sistema
    private lateinit var cameraSource: CameraSource
    // Navigation: usa GuidedNavigationManager (motor unificado)
    private lateinit var audioManager: PriorityAudioManager
    private lateinit var voiceCommander: VoiceCommander
    private lateinit var sonarFeedback: SonarFeedback
    
    // Tactical Navigation
    private lateinit var locationSensorManager: LocationSensorManager
    private lateinit var guidedNavigationManager: GuidedNavigationManager
    private lateinit var routeRecorderViewModel: RouteRecorderViewModel
    private lateinit var database: BlindNavDatabase
    
    // Jobs
    private var safetyJob: Job? = null
    private var navigationJob: Job? = null
    private var sensorUpdateJob: Job? = null
    
    // Estado
    private var isRecordingRoute = false
    private var isSonarEnabled = false

    // ============================================
    // PERMISSION LAUNCHER (AGRESIVO)
    // ============================================
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        
        if (allGranted) {
            Log.d(TAG, "✅ Todos los permisos concedidos")
            onAllPermissionsGranted()
        } else {
            val denied = permissions.filter { !it.value }.keys
            Log.w(TAG, "❌ Permisos denegados: $denied")
            
            // Mostrar diálogo explicativo
            showPermissionRationale(denied)
        }
    }

    // ============================================
    // LIFECYCLE
    // ============================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "🚀 onCreate - Iniciando BlindNav")
        
        // Pantalla completa
        setupFullscreenMode()
        
        // Inflar layout
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Executor para cámara
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        // Inicializar componentes básicos (sin cámara)
        initializeBasicComponents()
        
        // Setup UI listeners
        setupUIListeners()
        // Los collectors se registran una sola vez desde onCreate.
        startSensorUpdates()
        
        // PRIORIDAD: Verificar permisos ANTES de todo
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "📱 onResume")
        
        // Reiniciar sensores si tenemos permisos
        if (hasLocationPermission()) {
            locationSensorManager.startTracking()
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "⏸ onPause")
        if (::locationSensorManager.isInitialized) locationSensorManager.stopTracking()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "💀 onDestroy")
        
        // Cancelar jobs
        safetyJob?.cancel()
        navigationJob?.cancel()
        sensorUpdateJob?.cancel()
        
        // Liberar recursos
        cameraExecutor.shutdown()
        if (::audioManager.isInitialized) audioManager.release()
        if (::voiceCommander.isInitialized) voiceCommander.release()
        if (::sonarFeedback.isInitialized) sonarFeedback.release()
        if (::cameraSource.isInitialized) cameraSource.stopCamera()
    }

    // ============================================
    // PERMISOS (AGRESIVO)
    // ============================================

    private fun checkAndRequestPermissions() {
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        
        if (missingPermissions.isEmpty()) {
            Log.d(TAG, "✅ Todos los permisos ya concedidos")
            onAllPermissionsGranted()
        } else {
            Log.d(TAG, "🔐 Solicitando permisos: $missingPermissions")
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun showPermissionRationale(deniedPermissions: Set<String>) {
        val message = buildString {
            append("BlindNav necesita los siguientes permisos para funcionar:\n\n")
            
            if (Manifest.permission.CAMERA in deniedPermissions) {
                append("📷 CÁMARA: Detectar obstáculos\n")
            }
            if (Manifest.permission.ACCESS_FINE_LOCATION in deniedPermissions ||
                Manifest.permission.ACCESS_COARSE_LOCATION in deniedPermissions) {
                append("📍 UBICACIÓN: Navegación GPS\n")
            }
            if (Manifest.permission.RECORD_AUDIO in deniedPermissions) {
                append("🎤 MICRÓFONO: Comandos de voz\n")
            }
            
            append("\n¿Quieres concederlos ahora?")
        }
        
        AlertDialog.Builder(this)
            .setTitle("Permisos necesarios")
            .setMessage(message)
            .setPositiveButton("Reintentar") { _, _ ->
                permissionLauncher.launch(REQUIRED_PERMISSIONS)
            }
            .setNegativeButton("Salir") { _, _ ->
                Toast.makeText(this, "La app no puede funcionar sin permisos", Toast.LENGTH_LONG).show()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun onAllPermissionsGranted() {
        Log.d(TAG, "🎉 Permisos OK - Iniciando cámara y sensores")
        
        // Anunciar inicio
        audioManager.speakSystem("BlindNav iniciado. Cámara y sensores activos.")
        
        // Iniciar cámara
        startCamera()
        
        // Iniciar sensores de ubicación; el collector ya se creó en onCreate.
        locationSensorManager.startTracking()
        
        // Observar estado
        observeState()
        observeVoiceCommands()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == 
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == 
            PackageManager.PERMISSION_GRANTED
    }

    // ============================================
    // INICIALIZACIÓN
    // ============================================

    private fun setupFullscreenMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    private fun initializeBasicComponents() {
        Log.d(TAG, "⚙️ Inicializando componentes básicos")
        
        // Audio Manager
        audioManager = PriorityAudioManager(this)
        
        // Sonar Feedback
        sonarFeedback = SonarFeedback()
        
        // Safety System
        val safetyAnalyzer = SafetyAnalyzer()
        cameraSource = CameraSource(this, safetyAnalyzer)
        val safetyRepository = SafetyRepositoryImpl(cameraSource)
        viewModel = MainViewModel(safetyRepository)
        
        // Navigation System: GuidedNavigationManager es el motor unificado (NavigationManager eliminado)
        
        // Voice Commander
        voiceCommander = VoiceCommander(this)
        voiceCommander.initialize()
        
        // Database & Location
        database = BlindNavDatabase.getInstance(this)
        locationSensorManager = LocationSensorManager(this)
        
        // Guided Navigation
        guidedNavigationManager = GuidedNavigationManager(
            context = this,
            locationSensorManager = locationSensorManager,
            audioManager = audioManager
        )
        
        // Route Recorder
        routeRecorderViewModel = RouteRecorderViewModel(
            routeDao = database.routeDao(),
            checkpointDao = database.checkpointDao(),
            pathPointDao = database.pathPointDao(),
            mapEventDao = database.mapEventDao(),
            locationSensorManager = locationSensorManager
        )
    }

    // ============================================
    // CÁMARA (CameraX con PreviewView)
    // ============================================

    private fun startCamera() {
        Log.d(TAG, "📷 Iniciando cámara CameraX")
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error al obtener cameraProvider", e)
                audioManager.speakSystem("Error al iniciar cámara")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        
        Log.d(TAG, "🔗 Vinculando casos de uso de cámara")
        
        // Preview use case - VINCULAR AL viewFinder
        val preview = Preview.Builder()
            .build()
            .also { 
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
                Log.d(TAG, "✅ Preview vinculado a viewFinder")
            }
        
        // Seleccionar cámara trasera
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        
        try {
            // Desvincular casos previos
            cameraProvider.unbindAll()
            
            // Vincular al lifecycle
            cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview
            )
            
            Log.d(TAG, "✅ Cámara vinculada correctamente")
            
            // También iniciar el análisis de safety
            startSafetyAnalysis()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error al vincular cámara", e)
        }
    }

    private fun startSafetyAnalysis() {
        Log.d(TAG, "🔍 Iniciando análisis de seguridad")
        
        safetyJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.safetyAnalysisFlow.collectLatest { result ->
                    updateAnalysisUI(result)
                    
                    // Actualizar sonar si está activo
                    if (isSonarEnabled && result.hazardLevel != HazardLevel.SAFE) {
                        val distance = estimateDistance(result)
                        sonarFeedback.updateProximity(distance)
                    }
                }
            }
        }
    }

    private fun estimateDistance(result: SafetyAnalysisResult): Float {
        return when (result.hazardLevel) {
            HazardLevel.CRITICAL -> 0.5f
            HazardLevel.WARNING -> 2.0f
            HazardLevel.SAFE -> 10.0f
        }
    }

    // ============================================
    // SENSORES (GPS + Brújula)
    // ============================================

    private fun startSensorUpdates() {
        Log.d(TAG, "🧭 Iniciando actualizaciones de sensores")
        
        sensorUpdateJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                locationSensorManager.orientationFlow.collectLatest { orientation ->
                    updateHUD(orientation)
                }
            }
        }
    }

    private fun updateHUD(orientation: com.blindnav.app.data.sensors.UserOrientation) {
        // Actualizar GPS
        binding.tvGpsCoordinates.text = String.format(
            "%.5f, %.5f", 
            orientation.latitude, 
            orientation.longitude
        )
        binding.tvGpsAccuracy.text = "±${orientation.accuracy.toInt()}m"
        
        // Actualizar brújula
        val cardinalDirection = getCardinalDirection(orientation.bearing)
        binding.tvCompassDirection.text = "$cardinalDirection (${orientation.bearing.toInt()}°)"
    }

    private fun getCardinalDirection(bearing: Float): String {
        return when {
            bearing >= 337.5 || bearing < 22.5 -> "N"
            bearing >= 22.5 && bearing < 67.5 -> "NE"
            bearing >= 67.5 && bearing < 112.5 -> "E"
            bearing >= 112.5 && bearing < 157.5 -> "SE"
            bearing >= 157.5 && bearing < 202.5 -> "S"
            bearing >= 202.5 && bearing < 247.5 -> "SO"
            bearing >= 247.5 && bearing < 292.5 -> "O"
            else -> "NO"
        }
    }

    // ============================================
    // UI LISTENERS
    // ============================================

    private fun setupUIListeners() {
        // Switch Sonar
        binding.switchSonar.setOnCheckedChangeListener { _, isChecked ->
            isSonarEnabled = isChecked
            if (isChecked) {
                sonarFeedback.start()
                audioManager.speakSystem("Sonar activado")
            } else {
                sonarFeedback.stop()
                audioManager.speakSystem("Sonar desactivado")
            }
        }
        
        // Botón Grabar Ruta
        binding.btnRecordRoute.setOnClickListener {
            if (!isRecordingRoute) {
                startRouteRecording()
            } else {
                stopRouteRecording()
            }
        }
        
        // FAB Marcar Evento
        binding.fabMarkEvent.setOnClickListener {
            if (isRecordingRoute) {
                showEventTypeDialog()
            } else {
                audioManager.speakSystem("Primero inicia la grabación de ruta")
            }
        }
        
        // Botones del panel de grabación
        binding.btnReportCrossing.setOnClickListener {
            reportEvent(EventType.CROSSING)
        }
        
        binding.btnReportObstacle.setOnClickListener {
            reportEvent(EventType.OBSTACLE_TEMPORARY)
        }
        
        binding.btnReportTurn.setOnClickListener {
            reportEvent(EventType.TURN)
        }
        
        binding.btnReportInfo.setOnClickListener {
            reportEvent(EventType.INFO)
        }
        
        // Botón Parar Grabación
        binding.btnStopRecording.setOnClickListener {
            stopRouteRecording()
        }
    }

    // ============================================
    // GRABACIÓN DE RUTA
    // ============================================

    private fun startRouteRecording() {
        val routeName = "Ruta ${java.text.SimpleDateFormat("dd-MMM HH:mm", 
            java.util.Locale.getDefault()).format(java.util.Date())}"
        
        if (routeRecorderViewModel.startRecording(routeName)) {
            isRecordingRoute = true
            
            // Mostrar panel de grabación
            binding.recordingPanel.visibility = View.VISIBLE
            binding.btnRecordRoute.text = "⏹ PARAR"
            binding.btnRecordRoute.setBackgroundColor(getColor(android.R.color.holo_red_dark))
            
            audioManager.speakNavigation("Grabación iniciada. Usa los botones para marcar eventos.")
        } else {
            audioManager.speakSystem("Error al iniciar grabación")
        }
    }

    private fun stopRouteRecording() {
        routeRecorderViewModel.stopRecording()
        isRecordingRoute = false
        
        // Ocultar panel
        binding.recordingPanel.visibility = View.GONE
        binding.btnRecordRoute.text = "🎬 GRABAR"
        binding.btnRecordRoute.setBackgroundColor(getColor(android.R.color.holo_red_dark))
        
        audioManager.speakNavigation("Grabación finalizada")
    }

    private fun showEventTypeDialog() {
        val eventTypes = EventType.entries.toTypedArray()
        val names = eventTypes.map { "${it.emoji} ${it.displayName}" }.toTypedArray()
        
        AlertDialog.Builder(this)
            .setTitle("Tipo de evento")
            .setItems(names) { _, which ->
                reportEvent(eventTypes[which])
            }
            .show()
    }

    private fun reportEvent(type: EventType, description: String = "") {
        if (routeRecorderViewModel.reportEvent(type, description)) {
            audioManager.speakNavigation("${type.displayName} marcado")
        } else {
            audioManager.speakSystem("Error. Esperando GPS.")
        }
    }

    // ============================================
    // UI UPDATES
    // ============================================

    private fun updateAnalysisUI(result: SafetyAnalysisResult) {
        // Actualizar overlay de detección
        binding.boundingBoxOverlay.updateObstacles(
            result.obstacles,
            result.hazardLevel,
            640, 480
        )
        
        // Actualizar indicador de estado
        when (result.hazardLevel) {
            HazardLevel.CRITICAL -> {
                binding.tvSystemStatus.text = "🔴 ¡PELIGRO!"
                binding.tvSystemStatus.setTextColor(getColor(android.R.color.holo_red_light))
                binding.tvHazardIndicator.visibility = View.VISIBLE
                binding.tvHazardIndicator.text = "⚠️ ¡OBSTÁCULO CERCANO!"
            }
            HazardLevel.WARNING -> {
                binding.tvSystemStatus.text = "🟡 PRECAUCIÓN"
                binding.tvSystemStatus.setTextColor(getColor(android.R.color.holo_orange_light))
                binding.tvHazardIndicator.visibility = View.GONE
            }
            HazardLevel.SAFE -> {
                binding.tvSystemStatus.text = "🟢 SEGURO"
                binding.tvSystemStatus.setTextColor(getColor(android.R.color.holo_green_light))
                binding.tvHazardIndicator.visibility = View.GONE
            }
        }
    }

    // ============================================
    // OBSERVADORES
    // ============================================

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    // Log para debug
                    Log.d(TAG, "Estado: navigating=${state.isNavigating}, hazard=${state.currentHazardLevel}")
                }
            }
        }
    }

    private fun observeVoiceCommands() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                voiceCommander.lastCommand.collectLatest { result ->
                    result?.let { handleVoiceCommand(it) }
                }
            }
        }
        
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                voiceCommander.state.collectLatest { state ->
                    updateVoiceIndicator(state)
                }
            }
        }
    }

    private fun handleVoiceCommand(result: VoiceCommandResult) {
        Log.d(TAG, "🎤 Comando de voz: $result")
        
        when (result) {
            is VoiceCommandResult.NavigateTo -> {
                audioManager.speakSystem("Navegando a ${result.destination}")
            }
            is VoiceCommandResult.StopNavigation -> {
                if (isRecordingRoute) stopRouteRecording()
            }
            is VoiceCommandResult.RepeatInstruction -> {
                audioManager.speakSystem("Repitiendo instrucción")
            }
            is VoiceCommandResult.WhereAmI -> {
                // Use simple response - can't directly access SharedFlow value
                audioManager.speakSystem("Consulta tu ubicación en el HUD superior")
            }
            is VoiceCommandResult.Help -> {
                audioManager.speakSystem("Comandos disponibles: grabar, cruce, obstáculo, giro, sonar")
            }
            is VoiceCommandResult.Unknown -> {
                // Procesar texto libre
                val text = result.text.lowercase()
                when {
                    text.contains("grabar") -> {
                        if (!isRecordingRoute) startRouteRecording() else stopRouteRecording()
                    }
                    text.contains("cruce") || text.contains("cebra") -> {
                        reportEvent(EventType.CROSSING)
                    }
                    text.contains("obstáculo") -> {
                        reportEvent(EventType.OBSTACLE_TEMPORARY)
                    }
                    text.contains("giro") -> {
                        reportEvent(EventType.TURN)
                    }
                    text.contains("sonar") -> {
                        binding.switchSonar.isChecked = !binding.switchSonar.isChecked
                    }
                }
            }
            is VoiceCommandResult.Error -> {
                Log.w(TAG, "Error de voz: ${result.message}")
            }
        }
    }

    private fun updateVoiceIndicator(state: VoiceRecognizerState) {
        when (state) {
            VoiceRecognizerState.LISTENING -> {
                binding.voiceIndicator.visibility = View.VISIBLE
                binding.tvVoiceStatus.text = "🎤 Escuchando..."
            }
            VoiceRecognizerState.PROCESSING -> {
                binding.tvVoiceStatus.text = "⏳ Procesando..."
            }
            else -> {
                binding.voiceIndicator.visibility = View.GONE
            }
        }
    }
}
