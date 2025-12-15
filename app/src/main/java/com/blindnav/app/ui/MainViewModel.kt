package com.blindnav.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blindnav.app.domain.model.HazardLevel
import com.blindnav.app.domain.model.SafetyAnalysisResult
import com.blindnav.app.domain.repository.SafetyRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Estado de la UI principal.
 */
data class MainUiState(
    val isNavigating: Boolean = false,
    val currentHazardLevel: HazardLevel = HazardLevel.SAFE,
    val lastAnalysisResult: SafetyAnalysisResult? = null,
    val statusMessage: String = "Listo para iniciar",
    val processingFps: Float = 0f
)

/**
 * ViewModel principal de la aplicación.
 * Gestiona el estado de navegación y conecta la UI con el dominio.
 */
class MainViewModel(
    private val safetyRepository: SafetyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    
    // Flow de análisis de seguridad
    val safetyAnalysisFlow: Flow<SafetyAnalysisResult> = safetyRepository.safetyAnalysisFlow

    // Buffer para calcular FPS
    private val processingTimes = mutableListOf<Long>()
    private val maxTimeSamples = 10

    init {
        observeSafetyAnalysis()
    }

    /**
     * Observa el flujo de análisis y actualiza el estado de UI.
     */
    private fun observeSafetyAnalysis() {
        viewModelScope.launch {
            safetyRepository.safetyAnalysisFlow.collect { result ->
                updateUiState(result)
            }
        }
    }

    /**
     * Actualiza el estado de UI con el resultado del análisis.
     */
    private fun updateUiState(result: SafetyAnalysisResult) {
        // Calcular FPS promedio
        processingTimes.add(result.processingTimeMs)
        if (processingTimes.size > maxTimeSamples) {
            processingTimes.removeAt(0)
        }
        val avgProcessingTime = processingTimes.average()
        val fps = if (avgProcessingTime > 0) (1000.0 / avgProcessingTime).toFloat() else 0f

        // Generar mensaje de estado
        val statusMessage = when (result.hazardLevel) {
            HazardLevel.SAFE -> "Ruta despejada"
            HazardLevel.WARNING -> "Precaución: ${result.obstacles.size} obstáculo(s)"
            HazardLevel.CRITICAL -> "¡PELIGRO! Obstáculo cercano"
        }

        _uiState.update { current ->
            current.copy(
                currentHazardLevel = result.hazardLevel,
                lastAnalysisResult = result,
                statusMessage = statusMessage,
                processingFps = fps
            )
        }
    }

    /**
     * Inicia la navegación.
     */
    fun startNavigation() {
        viewModelScope.launch {
            _uiState.update { it.copy(isNavigating = true, statusMessage = "Escaneando entorno...") }
            safetyRepository.startAnalysis()
        }
    }

    /**
     * Detiene la navegación.
     */
    fun stopNavigation() {
        viewModelScope.launch {
            safetyRepository.stopAnalysis()
            _uiState.update { 
                it.copy(
                    isNavigating = false, 
                    statusMessage = "Navegación detenida",
                    currentHazardLevel = HazardLevel.SAFE
                ) 
            }
        }
    }

    /**
     * Alterna el estado de navegación.
     */
    fun toggleNavigation() {
        if (_uiState.value.isNavigating) {
            stopNavigation()
        } else {
            startNavigation()
        }
    }
}
