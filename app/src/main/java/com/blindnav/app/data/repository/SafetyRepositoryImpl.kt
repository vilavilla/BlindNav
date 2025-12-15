package com.blindnav.app.data.repository

import com.blindnav.app.data.CameraSource
import com.blindnav.app.domain.model.SafetyAnalysisResult
import com.blindnav.app.domain.repository.SafetyRepository
import kotlinx.coroutines.flow.Flow

/**
 * Implementación del repositorio de seguridad.
 * Conecta la capa de datos (CameraSource) con la capa de dominio.
 */
class SafetyRepositoryImpl(
    private val cameraSource: CameraSource
) : SafetyRepository {

    override val safetyAnalysisFlow: Flow<SafetyAnalysisResult>
        get() = cameraSource.analysisResultFlow

    override suspend fun startAnalysis() {
        // La cámara ya debería estar iniciada por el ViewModel
        cameraSource.resumeAnalysis()
    }

    override suspend fun stopAnalysis() {
        cameraSource.pauseAnalysis()
    }

    override fun isAnalyzing(): Boolean {
        return cameraSource.isAnalyzing()
    }
}
