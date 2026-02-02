package com.blindnav.app.ui.feedback

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SonarFeedback - Módulo de feedback auditivo tipo "sensor de aparcamiento"
 * 
 * Emite beeps de frecuencia variable según la distancia:
 * - > 3m: Sin beep (zona segura)
 * - 1m - 3m: Beep lento (precaución)
 * - 0.5m - 1m: Beep rápido (alerta)
 * - < 0.5m: Tono continuo (peligro inminente)
 * 
 * NOTA: La "distancia" se estima a partir del tamaño del bounding box.
 * Ratio de altura 0.1 ≈ 3m, 0.4 ≈ 1m, 0.6 ≈ 0.5m (aproximación heurística)
 */
class SonarFeedback {
    
    companion object {
        // Umbrales de ratio de altura → distancia estimada
        private const val THRESHOLD_FAR = 0.1f      // ~3m
        private const val THRESHOLD_MEDIUM = 0.25f   // ~1m
        private const val THRESHOLD_CLOSE = 0.4f     // ~0.5m
        
        // Intervalos de beep en milisegundos
        private const val BEEP_INTERVAL_SLOW = 800L
        private const val BEEP_INTERVAL_FAST = 300L
        private const val BEEP_DURATION = 100
    }
    
    // ToneGenerator para emitir beeps
    private var toneGenerator: ToneGenerator? = null
    
    // Handler para programar beeps repetidos
    private val handler = Handler(Looper.getMainLooper())
    private var beepRunnable: Runnable? = null
    
    // Estado del sonar (activado/desactivado)
    private val _isEnabled = MutableStateFlow(true)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()
    
    // Estado actual de proximidad
    private var currentProximityLevel = ProximityLevel.SAFE
    
    /**
     * Niveles de proximidad
     */
    enum class ProximityLevel {
        SAFE,       // > 3m - Sin beep
        CAUTION,    // 1m - 3m - Beep lento
        ALERT,      // 0.5m - 1m - Beep rápido
        DANGER      // < 0.5m - Tono continuo
    }
    
    init {
        initToneGenerator()
    }
    
    private fun initToneGenerator() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Activa o desactiva el sonar.
     */
    fun toggle() {
        _isEnabled.value = !_isEnabled.value
        if (!_isEnabled.value) {
            stopBeeping()
        }
    }
    
    /**
     * Establece el estado de habilitación del sonar.
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        if (!enabled) {
            stopBeeping()
        }
    }
    
    /**
     * Inicia el sonar.
     */
    fun start() {
        setEnabled(true)
    }
    
    /**
     * Detiene el sonar.
     */
    fun stop() {
        setEnabled(false)
    }
    
    /**
     * Actualiza la proximidad basándose en distancia estimada en metros.
     */
    fun updateProximity(distanceMeters: Float) {
        // Convertir distancia a ratio estimado
        val heightRatio = when {
            distanceMeters > 3.0f -> 0.05f
            distanceMeters > 1.0f -> 0.2f
            distanceMeters > 0.5f -> 0.35f
            else -> 0.5f
        }
        processProximity(heightRatio)
    }
    
    /**
     * Procesa el ratio de altura del obstáculo más cercano.
     * 
     * @param heightRatio Altura del obstáculo / altura de la imagen (0.0 - 1.0)
     */
    fun processProximity(heightRatio: Float) {
        if (!_isEnabled.value) return
        
        val newLevel = when {
            heightRatio < THRESHOLD_FAR -> ProximityLevel.SAFE
            heightRatio < THRESHOLD_MEDIUM -> ProximityLevel.CAUTION
            heightRatio < THRESHOLD_CLOSE -> ProximityLevel.ALERT
            else -> ProximityLevel.DANGER
        }
        
        // Solo actualizar si cambió el nivel
        if (newLevel != currentProximityLevel) {
            currentProximityLevel = newLevel
            updateBeepPattern()
        }
    }
    
    /**
     * Actualiza el patrón de beeps según el nivel de proximidad.
     */
    private fun updateBeepPattern() {
        stopBeeping()
        
        when (currentProximityLevel) {
            ProximityLevel.SAFE -> {
                // Sin beeps
            }
            ProximityLevel.CAUTION -> {
                startRepeatingBeep(BEEP_INTERVAL_SLOW, ToneGenerator.TONE_PROP_BEEP)
            }
            ProximityLevel.ALERT -> {
                startRepeatingBeep(BEEP_INTERVAL_FAST, ToneGenerator.TONE_PROP_BEEP2)
            }
            ProximityLevel.DANGER -> {
                startContinuousTone()
            }
        }
    }
    
    /**
     * Inicia beeps repetidos con el intervalo especificado.
     */
    private fun startRepeatingBeep(intervalMs: Long, toneType: Int) {
        beepRunnable = object : Runnable {
            override fun run() {
                if (_isEnabled.value && currentProximityLevel != ProximityLevel.SAFE) {
                    toneGenerator?.startTone(toneType, BEEP_DURATION)
                    handler.postDelayed(this, intervalMs)
                }
            }
        }
        beepRunnable?.run()
    }
    
    /**
     * Inicia un tono continuo para peligro inminente.
     */
    private fun startContinuousTone() {
        toneGenerator?.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 2000)
    }
    
    /**
     * Detiene todos los beeps.
     */
    private fun stopBeeping() {
        beepRunnable?.let { handler.removeCallbacks(it) }
        beepRunnable = null
        toneGenerator?.stopTone()
    }
    
    /**
     * Libera recursos.
     */
    fun release() {
        stopBeeping()
        toneGenerator?.release()
        toneGenerator = null
    }
}
