package com.blindnav.app.domain.usecase

import com.blindnav.app.domain.model.SafetyAnalysisResult
import com.blindnav.app.domain.repository.SafetyRepository
import kotlinx.coroutines.flow.Flow

/**
 * Caso de uso para observar el análisis de seguridad en tiempo real.
 * Sigue el principio de Single Responsibility de Clean Architecture.
 */
class ObserveSafetyAnalysisUseCase(
    private val safetyRepository: SafetyRepository
) {
    /**
     * Obtiene el flujo de resultados de análisis de seguridad.
     * 
     * @return Flow que emite SafetyAnalysisResult cada vez que se procesa un frame
     */
    operator fun invoke(): Flow<SafetyAnalysisResult> {
        return safetyRepository.safetyAnalysisFlow
    }
}
