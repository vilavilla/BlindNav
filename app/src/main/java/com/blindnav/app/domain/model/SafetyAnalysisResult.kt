package com.blindnav.app.domain.model

/**
 * Resultado del análisis de seguridad de un frame.
 * 
 * @property hazardLevel Nivel de peligro calculado
 * @property obstacles Lista de obstáculos detectados en el frame
 * @property processingTimeMs Tiempo de procesamiento del frame en milisegundos
 * @property frameTimestamp Timestamp del frame analizado
 */
data class SafetyAnalysisResult(
    val hazardLevel: HazardLevel,
    val obstacles: List<DetectedObstacle> = emptyList(),
    val processingTimeMs: Long = 0L,
    val frameTimestamp: Long = System.currentTimeMillis()
) {
    /**
     * Indica si hay peligro inminente que requiere alerta
     */
    val requiresAlert: Boolean
        get() = hazardLevel == HazardLevel.CRITICAL || hazardLevel == HazardLevel.WARNING

    /**
     * Obtiene el obstáculo más peligroso (el más grande/cercano)
     */
    val primaryObstacle: DetectedObstacle?
        get() = obstacles.maxByOrNull { it.area }
}
