package com.blindnav.app.domain.crossing

import android.util.Log
import com.blindnav.app.domain.SafetyAnalyzer
import com.blindnav.app.domain.source.FrameData
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 🚦 CrossingGuard - Asistente Inteligente de Cruce Peatonal
 * 
 * Imita el comportamiento de un instructor de movilidad:
 * 1. Detecta cuando el usuario está a punto de cruzar (< 10m del nodo de giro)
 * 2. Activa modo CROSSING_GUARD en SafetyAnalyzer (hipersensible a vehículos)
 * 3. Instruye al usuario: "Levanta el móvil y mira a los lados"
 * 4. Alerta inmediatamente si detecta vehículos: "¡COCHE! ESPERA"
 * 
 * MEJORAS FUTURAS:
 * - Detección de paso de cebra (líneas blancas horizontales)
 * - Feedback háptico si el usuario se desvía del centro
 * - Confirmación de que la calle está despejada
 */
class CrossingGuard(
    private val safetyAnalyzer: SafetyAnalyzer,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) : CrossingAnalyzer {
    
    companion object {
        private const val TAG = "CrossingGuard"
        
        // Intervalo de análisis durante cruce (ms)
        private const val ANALYSIS_INTERVAL_MS = 500L
    }
    
    // Estado actual del cruce
    private val _state = MutableStateFlow(CrossingAnalyzer.CrossingState.IDLE)
    val state: StateFlow<CrossingAnalyzer.CrossingState> = _state.asStateFlow()
    
    // Resultado de detección de tráfico
    private val _trafficStatus = MutableStateFlow<SafetyAnalyzer.TrafficDetectionResult?>(null)
    val trafficStatus: StateFlow<SafetyAnalyzer.TrafficDetectionResult?> = _trafficStatus.asStateFlow()
    
    // Job de análisis continuo
    private var analysisJob: Job? = null
    
    /**
     * 🚦 Activa modo de asistencia de cruce
     * 
     * - Cambia SafetyAnalyzer a modo CROSSING_GUARD
     * - Inicia análisis continuo de tráfico
     * - Emite alertas en tiempo real
     */
    override fun startCrossingAssist() {
        if (_state.value != CrossingAnalyzer.CrossingState.IDLE) {
            Log.w(TAG, "Crossing assist ya está activo")
            return
        }
        
        Log.d(TAG, "🚦 Activando CROSSING GUARD")
        _state.value = CrossingAnalyzer.CrossingState.SEARCHING
        
        // Activar modo hipersensible en SafetyAnalyzer
        safetyAnalyzer.enableCrossingGuardMode()
        
        // Iniciar análisis continuo
        startContinuousAnalysis()
    }
    
    /**
     * Desactiva modo de asistencia de cruce
     */
    override fun stopCrossingAssist() {
        Log.d(TAG, "🚦 Desactivando CROSSING GUARD")
        
        analysisJob?.cancel()
        analysisJob = null
        
        safetyAnalyzer.disableCrossingGuardMode()
        
        _state.value = CrossingAnalyzer.CrossingState.COMPLETED
        _trafficStatus.value = null
        
        // Volver a IDLE después de un delay
        scope.launch {
            delay(1000)
            _state.value = CrossingAnalyzer.CrossingState.IDLE
        }
    }
    
    /**
     * Analiza un frame para detectar cruce peatonal
     * 
     * NOTA: Esta función es llamada externamente por NavigationActivity
     * con cada frame de la cámara
     */
    override fun analyzeCrossing(frame: FrameData): CrossingAnalyzer.CrossingFeedback? {
        if (!isActive()) {
            return CrossingAnalyzer.CrossingFeedback.NoCrossingDetected
        }
        
        // La cámara genérica no demuestra que exista un paso de cebra. Los cruces
        // se obtienen del mapa y la alineación se calcula con brújula/GPS.
        return CrossingAnalyzer.CrossingFeedback.NoCrossingDetected
    }
    
    override fun isActive(): Boolean {
        return _state.value in listOf(
            CrossingAnalyzer.CrossingState.SEARCHING,
            CrossingAnalyzer.CrossingState.ACTIVE
        )
    }
    
    /**
     * 🔄 Análisis continuo de tráfico durante el cruce
     * 
     * Se ejecuta cada 500ms mientras el cruce está activo
     */
    private fun startContinuousAnalysis() {
        analysisJob = scope.launch {
            while (isActive) {
                // El análisis real se hace cuando NavigationActivity
                // llama a analyzeTraffic() con el frame actual
                delay(ANALYSIS_INTERVAL_MS)
            }
        }
    }
    
    /**
     * 🚗 Analiza frame para detectar tráfico
     * 
     * Llamado por NavigationActivity con cada frame de la cámara
     * 
     * @param inputImage Frame de la cámara
     * @param imageWidth Ancho de la imagen
     * @param imageHeight Alto de la imagen
     * @return Resultado de detección de tráfico
     */
    suspend fun analyzeTraffic(
        inputImage: InputImage,
        imageWidth: Int,
        imageHeight: Int
    ): SafetyAnalyzer.TrafficDetectionResult {
        val result = safetyAnalyzer.checkForTraffic(inputImage, imageWidth, imageHeight)
        
        // Actualizar estado
        _trafficStatus.value = result
        
        // Cambiar estado según detección
        if (result.isCritical) {
            _state.value = CrossingAnalyzer.CrossingState.ACTIVE
            Log.w(TAG, "🚨 ${result.message}")
        } else if (result.hasVehicle) {
            Log.d(TAG, "⚠️ ${result.message}")
        } else {
            // Solo cambiar a ACTIVE si no había vehículos antes
            if (_state.value == CrossingAnalyzer.CrossingState.SEARCHING) {
                _state.value = CrossingAnalyzer.CrossingState.ACTIVE
            }
        }
        
        return result
    }
    
    /**
     * 📍 Verifica si el usuario está cerca de un nodo de giro
     * 
     * @param distanceToNextNode Distancia en metros al próximo nodo
     * @return true si está a < 10m y debería activar crossing assist
     */
    fun shouldActivateCrossing(distanceToNextNode: Double): Boolean {
        return distanceToNextNode < 10.0 && _state.value == CrossingAnalyzer.CrossingState.IDLE
    }
    
    /**
     * Limpia recursos
     */
    fun release() {
        stopCrossingAssist()
    }
}
