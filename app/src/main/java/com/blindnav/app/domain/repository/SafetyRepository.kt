package com.blindnav.app.domain.repository

import com.blindnav.app.domain.model.SafetyAnalysisResult
import kotlinx.coroutines.flow.Flow

/**
 * Repositorio para análisis de seguridad.
 * Define el contrato para la capa de datos.
 */
interface SafetyRepository {
    
    /**
     * Flujo continuo de resultados de análisis de seguridad.
     * Emite un nuevo resultado cada vez que se procesa un frame.
     */
    val safetyAnalysisFlow: Flow<SafetyAnalysisResult>
    
    /**
     * Inicia el análisis de frames de la cámara.
     */
    suspend fun startAnalysis()
    
    /**
     * Detiene el análisis de frames.
     */
    suspend fun stopAnalysis()
    
    /**
     * Indica si el análisis está activo.
     */
    fun isAnalyzing(): Boolean
}
