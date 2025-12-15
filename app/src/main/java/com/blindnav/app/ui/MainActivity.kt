package com.blindnav.app.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import com.blindnav.app.domain.model.NavigationState
import com.blindnav.app.domain.model.SafetyAnalysisResult
import com.blindnav.app.domain.model.VoiceCommandResult
import com.blindnav.app.domain.model.VoiceRecognizerState
import com.blindnav.app.domain.navigation.MockRouteProvider
import com.blindnav.app.domain.navigation.NavigationManager
import com.blindnav.app.ui.audio.PriorityAudioManager
import com.blindnav.app.ui.voice.VoiceCommander
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * MainActivity - Pantalla principal de BlindNav
 * 
 * ARQUITECTURA DE FUSIÓN SAFETY + NAVIGATION:
 * - Job 1 (Safety): Analiza frames de cámara → alertas de obstáculos (PRIORIDAD ALTA)
 * - Job 2 (Navigation): GPS + Brújula → instrucciones de giro (PRIORIDAD MEDIA)
 * - PriorityAudioManager resuelve conflictos automáticamente
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // ============================================
    // PROPIEDADES
    // ============================================
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    
    // Componentes del sistema
    private lateinit var cameraSource: CameraSource
    private lateinit var navigationManager: NavigationManager
    private lateinit var audioManager: PriorityAudioManager
    private lateinit var voiceCommander: VoiceCommander
    
    // Jobs de corrutinas para procesos paralelos
    private var safetyJob: Job? = null
    private var navigationJob: Job? = null
    
    // Animador para flash de peligro
    private var hazardAnimator: ObjectAnimator? = null
    
    // Estado de navegación GPS activa
    private var isGpsNavigating = false

    // ============================================
    // LAUNCHERS DE PERMISOS
    // ============================================
    
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            initializeCamera()
        } else {
            Toast.makeText(this, getString(R.string.camera_permission_required), Toast.LENGTH_LONG).show()
        }
    }
    
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        
        if (fineGranted || coarseGranted) {
            Log.d(TAG, "Permisos de ubicación concedidos")
        } else {
            audioManager.speakSystem("Permisos de ubicación necesarios para navegación GPS")
        }
    }
    
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            voiceCommander.startListening()
        } else {
            audioManager.speakSystem("Permiso de micrófono necesario para comandos de voz")
        }
    }

    // ============================================
    // LIFECYCLE
    // ============================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setupFullscreenMode()
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initializeDependencies()
        setupUI()
        checkPermissions()
        observeState()
        observeNavigation()
        observeVoiceCommands()
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // Cancelar jobs
        safetyJob?.cancel()
        navigationJob?.cancel()
        
        // Liberar recursos
        hazardAnimator?.cancel()
        audioManager.release()
        navigationManager.release()
        voiceCommander.release()
        cameraSource.stopCamera()
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

    private fun initializeDependencies() {
        // Audio Manager (con prioridades)
        audioManager = PriorityAudioManager(this)
        
        // Safety System
        val safetyAnalyzer = SafetyAnalyzer()
        cameraSource = CameraSource(this, safetyAnalyzer)
        val safetyRepository = SafetyRepositoryImpl(cameraSource)
        viewModel = MainViewModel(safetyRepository)
        
        // Navigation System
        navigationManager = NavigationManager(this)
        
        // Voice Commander
        voiceCommander = VoiceCommander(this)
        voiceCommander.initialize()
    }

    private fun setupUI() {
        // Botón principal (Safety ON/OFF)
        binding.btnToggleNavigation.setOnClickListener {
            viewModel.toggleNavigation()
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
        
        // Botón de voz
        binding.btnVoice.setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            startVoiceRecognition()
        }
        
        // Estado inicial
        updateHazardBadge(HazardLevel.SAFE)
        binding.navInfoContainer.visibility = View.GONE
    }

    private fun checkPermissions() {
        // Cámara
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            == PackageManager.PERMISSION_GRANTED) {
            initializeCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        
        // Ubicación
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED) {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    private fun initializeCamera() {
        lifecycleScope.launch {
            cameraSource.startCamera(this@MainActivity, binding.previewView)
            audioManager.speakSystem("BlindNav iniciado. Pulsa el micrófono y di tu destino.")
        }
    }

    // ============================================
    // OBSERVERS - SAFETY SYSTEM
    // ============================================

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Job 1: UI State
                launch {
                    viewModel.uiState.collectLatest { state ->
                        updateUI(state)
                    }
                }
                
                // Job 2: Safety Analysis → Audio SAFETY
                safetyJob = launch {
                    viewModel.safetyAnalysisFlow.collectLatest { result ->
                        updateAnalysisUI(result)
                        
                        // Emitir alertas de seguridad (PRIORIDAD ALTA)
                        if (viewModel.uiState.value.isNavigating) {
                            handleSafetyAlert(result)
                        }
                    }
                }
            }
        }
    }

    private fun handleSafetyAlert(result: SafetyAnalysisResult) {
        when (result.hazardLevel) {
            HazardLevel.CRITICAL -> {
                audioManager.alertSafety(HazardLevel.CRITICAL, "¡Peligro! Obstáculo muy cerca.")
            }
            HazardLevel.WARNING -> {
                audioManager.alertSafety(HazardLevel.WARNING)
            }
            HazardLevel.SAFE -> {
                // No hacer nada
            }
        }
    }

    // ============================================
    // OBSERVERS - NAVIGATION SYSTEM
    // ============================================

    private fun observeNavigation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Job 3: Navigation State → UI
                launch {
                    navigationManager.navigationState.collectLatest { navState ->
                        updateNavigationUI(navState)
                    }
                }
                
                // Job 4: Navigation Instructions → Audio NAVIGATION
                navigationJob = launch {
                    navigationManager.spokenInstructions.collectLatest { instruction ->
                        audioManager.speakNavigation(instruction)
                    }
                }
            }
        }
    }

    private fun updateNavigationUI(navState: NavigationState) {
        if (navState.isNavigating) {
            binding.navInfoContainer.visibility = View.VISIBLE
            
            binding.tvNavDestination.text = "Navegando a: ${navState.currentRoute?.destination ?: "---"}"
            binding.tvNavDistance.text = "Distancia: ${navState.distanceToNextPoint.toInt()} m"
            
            val directionText = when {
                navState.isOnCourse -> "✓ Dirección correcta"
                navState.bearingDifference > 0 -> "→ Gira a la derecha (${navState.bearingDifference.toInt()}°)"
                else -> "← Gira a la izquierda (${(-navState.bearingDifference).toInt()}°)"
            }
            binding.tvNavBearing.text = directionText
            
            isGpsNavigating = true
        } else {
            binding.navInfoContainer.visibility = View.GONE
            isGpsNavigating = false
        }
    }

    // ============================================
    // OBSERVERS - VOICE COMMANDS
    // ============================================

    private fun observeVoiceCommands() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Estado del reconocedor
                launch {
                    voiceCommander.state.collectLatest { state ->
                        updateVoiceUI(state)
                    }
                }
                
                // Comandos reconocidos
                launch {
                    voiceCommander.lastCommand.collectLatest { command ->
                        command?.let { handleVoiceCommand(it) }
                    }
                }
            }
        }
    }

    private fun updateVoiceUI(state: VoiceRecognizerState) {
        when (state) {
            VoiceRecognizerState.LISTENING -> {
                binding.tvVoiceStatus.visibility = View.VISIBLE
                binding.tvVoiceStatus.text = "🎤 Escuchando..."
                binding.btnVoice.setBackgroundColor(Color.parseColor("#E91E63"))
            }
            VoiceRecognizerState.PROCESSING -> {
                binding.tvVoiceStatus.text = "⏳ Procesando..."
            }
            else -> {
                binding.tvVoiceStatus.visibility = View.GONE
                binding.btnVoice.setBackgroundColor(Color.parseColor("#7B1FA2"))
            }
        }
    }

    private fun handleVoiceCommand(command: VoiceCommandResult) {
        Log.d(TAG, "Comando recibido: $command")
        
        when (command) {
            is VoiceCommandResult.NavigateTo -> {
                startGpsNavigation(command.destination)
            }
            is VoiceCommandResult.StopNavigation -> {
                stopGpsNavigation()
            }
            is VoiceCommandResult.RepeatInstruction -> {
                audioManager.speakNavigation("Repitiendo: " + 
                    navigationManager.navigationState.value.lastInstruction.ifEmpty { "Sin instrucciones" })
            }
            is VoiceCommandResult.WhereAmI -> {
                announceCurrentLocation()
            }
            is VoiceCommandResult.Help -> {
                audioManager.speakSystem(voiceCommander.getHelpText())
            }
            is VoiceCommandResult.Unknown -> {
                audioManager.speakSystem("No entendí el comando: ${command.text}")
            }
            is VoiceCommandResult.Error -> {
                audioManager.speakSystem("Error: ${command.message}")
            }
        }
    }

    // ============================================
    // VOICE RECOGNITION
    // ============================================

    private fun startVoiceRecognition() {
        if (voiceCommander.hasAudioPermission()) {
            voiceCommander.startListening()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ============================================
    // GPS NAVIGATION
    // ============================================

    private fun startGpsNavigation(destination: String) {
        val route = MockRouteProvider.findRoute(destination)
        
        if (route != null) {
            audioManager.speakSystem("Calculando ruta a $destination.")
            navigationManager.startNavigation(route)
            
            // Activar también el sistema de Safety si no está activo
            if (!viewModel.uiState.value.isNavigating) {
                viewModel.toggleNavigation()
            }
        } else {
            audioManager.speakSystem("No encontré ruta a $destination. Destinos disponibles: " +
                MockRouteProvider.getAvailableDestinations().joinToString(", "))
        }
    }

    private fun stopGpsNavigation() {
        navigationManager.stopNavigation()
        audioManager.speakSystem("Navegación detenida.")
    }

    private fun announceCurrentLocation() {
        lifecycleScope.launch {
            val location = navigationManager.getLastLocation()
            if (location != null) {
                audioManager.speakSystem(
                    "Tu ubicación actual: latitud ${String.format("%.4f", location.latitude)}, " +
                    "longitud ${String.format("%.4f", location.longitude)}."
                )
            } else {
                audioManager.speakSystem("No puedo obtener tu ubicación actual.")
            }
        }
    }

    // ============================================
    // UI UPDATES
    // ============================================

    private fun updateAnalysisUI(result: SafetyAnalysisResult) {
        binding.boundingBoxOverlay.updateObstacles(
            result.obstacles,
            result.hazardLevel,
            640, 480
        )
        
        updateHazardBadge(result.hazardLevel)
        
        if (result.hazardLevel == HazardLevel.CRITICAL) {
            showCriticalFlash()
        } else {
            hideCriticalFlash()
        }
    }

    private fun updateUI(state: MainUiState) {
        // Texto de estado
        binding.tvStatus.text = when {
            !state.isNavigating -> "LISTO PARA INICIAR"
            state.currentHazardLevel == HazardLevel.CRITICAL -> "¡¡ PELIGRO !!"
            state.currentHazardLevel == HazardLevel.WARNING -> "PRECAUCIÓN"
            isGpsNavigating -> "NAVEGANDO"
            else -> "ESCANEANDO"
        }
        
        binding.tvHazardLevel.text = state.currentHazardLevel.name
        
        val (textColor, statusColor) = when (state.currentHazardLevel) {
            HazardLevel.SAFE -> Pair(Color.parseColor("#4CAF50"), Color.parseColor("#FFFFFF"))
            HazardLevel.WARNING -> Pair(Color.parseColor("#FF9800"), Color.parseColor("#FFE082"))
            HazardLevel.CRITICAL -> Pair(Color.parseColor("#F44336"), Color.parseColor("#FFCDD2"))
        }
        
        binding.tvHazardLevel.setTextColor(textColor)
        binding.tvStatus.setTextColor(statusColor)
        
        val obstacleCount = state.lastAnalysisResult?.obstacles?.size ?: 0
        val latency = state.lastAnalysisResult?.processingTimeMs ?: 0
        binding.tvDebugInfo.text = String.format(
            "%.1f FPS | %d obj | %d ms | GPS: %s",
            state.processingFps,
            obstacleCount,
            latency,
            if (isGpsNavigating) "ON" else "OFF"
        )
        
        if (state.isNavigating) {
            binding.btnToggleNavigation.text = "⏹ DETENER"
            binding.btnToggleNavigation.setBackgroundColor(Color.parseColor("#C62828"))
        } else {
            binding.btnToggleNavigation.text = "▶ INICIAR"
            binding.btnToggleNavigation.setBackgroundColor(Color.parseColor("#1565C0"))
        }
        
        if (!state.isNavigating) {
            binding.boundingBoxOverlay.clear()
            hideCriticalFlash()
        }
    }

    private fun updateHazardBadge(level: HazardLevel) {
        val (bgColor, text) = when (level) {
            HazardLevel.SAFE -> Pair(Color.parseColor("#4CAF50"), "SAFE")
            HazardLevel.WARNING -> Pair(Color.parseColor("#FF9800"), "WARNING")
            HazardLevel.CRITICAL -> Pair(Color.parseColor("#F44336"), "CRITICAL")
        }
        
        binding.tvHazardIndicator.text = text
        
        val background = binding.tvHazardIndicator.background
        if (background is GradientDrawable) {
            background.setColor(bgColor)
        } else {
            val newBackground = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 12f * resources.displayMetrics.density
            }
            binding.tvHazardIndicator.background = newBackground
        }
    }

    private fun showCriticalFlash() {
        binding.hazardOverlay.visibility = View.VISIBLE
        
        if (hazardAnimator == null || !hazardAnimator!!.isRunning) {
            hazardAnimator = ObjectAnimator.ofFloat(binding.hazardOverlay, "alpha", 0f, 0.4f).apply {
                duration = 200
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun hideCriticalFlash() {
        hazardAnimator?.cancel()
        hazardAnimator = null
        binding.hazardOverlay.visibility = View.GONE
        binding.hazardOverlay.alpha = 0f
    }
}
