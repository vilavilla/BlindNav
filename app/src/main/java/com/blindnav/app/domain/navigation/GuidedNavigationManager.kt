package com.blindnav.app.domain.navigation

import android.location.Location
import android.os.Vibrator
import android.os.VibratorManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import com.blindnav.app.data.db.entity.Checkpoint
import com.blindnav.app.data.sensors.LocationSensorManager
import com.blindnav.app.data.sensors.UserOrientation
import com.blindnav.app.ui.audio.PriorityAudioManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Estado de navegación guiada.
 */
data class GuidedNavigationState(
    /** Si la navegación guiada está activa */
    val isActive: Boolean = false,
    
    /** Checkpoint actual (siguiente objetivo) */
    val currentCheckpoint: Checkpoint? = null,
    
    /** Índice del checkpoint actual */
    val currentIndex: Int = 0,
    
    /** Total de checkpoints en la ruta */
    val totalCheckpoints: Int = 0,
    
    /** Última dirección calculada */
    val lastDirection: TacticalNavigationEngine.TacticalDirection? = null,
    
    /** Si llegamos al destino final */
    val hasArrived: Boolean = false
)

/**
 * GuidedNavigationManager - Gestor de navegación guiada
 * 
 * Coordina:
 * - LocationSensorManager para obtener orientación del usuario
 * - TacticalNavigationEngine para calcular direcciones
 * - PriorityAudioManager para emitir instrucciones de voz
 * - Vibración para confirmación cuando está delante (12 en punto)
 * 
 * Emite instrucciones cada 3 segundos cuando hay ruta activa.
 */
class GuidedNavigationManager(
    private val context: Context,
    private val locationSensorManager: LocationSensorManager,
    private val audioManager: PriorityAudioManager
) {
    companion object {
        private const val INSTRUCTION_INTERVAL_MS = 3000L
        private const val ARRIVAL_THRESHOLD_METERS = 5f
        private const val VIBRATION_DURATION_MS = 100L
    }
    
    private val tacticalEngine = TacticalNavigationEngine()
    private val vibrator: Vibrator = getVibrator(context)
    
    private val _state = MutableStateFlow(GuidedNavigationState())
    val state: StateFlow<GuidedNavigationState> = _state.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var navigationJob: Job? = null
    
    // Lista de checkpoints de la ruta activa
    private var checkpoints: List<Checkpoint> = emptyList()
    private var currentCheckpointIndex = 0
    
    /**
     * Inicia la navegación guiada con una lista de checkpoints.
     */
    fun startNavigation(checkpointList: List<Checkpoint>) {
        if (checkpointList.isEmpty()) return
        
        checkpoints = checkpointList.sortedBy { it.orderIndex }
        currentCheckpointIndex = 0
        
        locationSensorManager.startTracking()
        
        _state.update {
            it.copy(
                isActive = true,
                currentCheckpoint = checkpoints.firstOrNull(),
                currentIndex = 0,
                totalCheckpoints = checkpoints.size,
                hasArrived = false
            )
        }
        
        startNavigationLoop()
        
        // Anunciar inicio
        audioManager.speakNavigation(
            "Navegación iniciada. ${checkpoints.size} puntos de control."
        )
    }
    
    /**
     * Detiene la navegación guiada.
     */
    fun stopNavigation() {
        navigationJob?.cancel()
        locationSensorManager.stopTracking()
        
        _state.update {
            GuidedNavigationState()
        }
        
        audioManager.speakNavigation("Navegación detenida.")
    }
    
    /**
     * Inicia el bucle de navegación que emite instrucciones cada 3 segundos.
     */
    private fun startNavigationLoop() {
        navigationJob?.cancel()
        
        navigationJob = scope.launch {
            while (isActive) {
                updateNavigation()
                delay(INSTRUCTION_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Actualiza la navegación: calcula dirección y emite instrucción.
     */
    private suspend fun updateNavigation() {
        val checkpoint = checkpoints.getOrNull(currentCheckpointIndex) ?: return
        val userOrientation = getCurrentOrientation() ?: return
        
        val userLocation = userOrientation.toLocation()
        val targetLocation = Location("Checkpoint").apply {
            latitude = checkpoint.latitude
            longitude = checkpoint.longitude
        }
        
        // Calcular dirección táctica
        val direction = tacticalEngine.calculateDirection(
            userHeading = userOrientation.bearing,
            userLocation = userLocation,
            targetLocation = targetLocation
        )
        
        _state.update {
            it.copy(lastDirection = direction)
        }
        
        // Verificar si llegamos al checkpoint
        if (direction.distanceMeters <= ARRIVAL_THRESHOLD_METERS) {
            handleCheckpointArrival(checkpoint)
            return
        }
        
        // Emitir instrucción de audio
        val instruction = direction.toSpokenText(checkpoint.description)
        audioManager.speakNavigation(instruction)
        
        // Vibrar si está delante (12 en punto)
        if (direction.isAhead) {
            vibrateConfirmation()
        }
    }
    
    /**
     * Maneja la llegada a un checkpoint.
     */
    private fun handleCheckpointArrival(checkpoint: Checkpoint) {
        audioManager.speakNavigation("Llegaste a: ${checkpoint.description}")
        vibrateArrival()
        
        // Avanzar al siguiente checkpoint
        currentCheckpointIndex++
        
        if (currentCheckpointIndex >= checkpoints.size) {
            // Llegamos al final
            handleFinalArrival()
        } else {
            val nextCheckpoint = checkpoints[currentCheckpointIndex]
            _state.update {
                it.copy(
                    currentCheckpoint = nextCheckpoint,
                    currentIndex = currentCheckpointIndex
                )
            }
            
            audioManager.speakNavigation(
                "Siguiente: ${nextCheckpoint.description}"
            )
        }
    }
    
    /**
     * Maneja la llegada al destino final.
     */
    private fun handleFinalArrival() {
        audioManager.speakNavigation("¡Has llegado a tu destino!")
        vibrateSuccess()
        
        _state.update {
            it.copy(
                isActive = false,
                hasArrived = true
            )
        }
        
        navigationJob?.cancel()
        locationSensorManager.stopTracking()
    }
    
    /**
     * Obtiene la orientación actual del usuario.
     */
    private suspend fun getCurrentOrientation(): UserOrientation? {
        return locationSensorManager.orientationFlow.firstOrNull()
    }
    
    // ============================================
    // FEEDBACK HÁPTICO
    // ============================================
    
    private fun vibrateConfirmation() {
        vibrate(VIBRATION_DURATION_MS)
    }
    
    private fun vibrateArrival() {
        vibrate(200L)
    }
    
    private fun vibrateSuccess() {
        // Patrón de éxito: 3 vibraciones cortas
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 100, 100, 100, 100, 100),
                    -1
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 100, 100, 100, 100, 100), -1)
        }
    }
    
    private fun vibrate(durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    durationMs,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }
    
    private fun getVibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
    
    /**
     * Libera recursos.
     */
    fun release() {
        navigationJob?.cancel()
        scope.cancel()
    }
}
