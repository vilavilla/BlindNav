package com.blindnav.app.domain.model

/** Rectángulo independiente de Android para que la lógica sea testeable en la JVM. */
data class ObstacleBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun width(): Int = (right - left).coerceAtLeast(0)
    fun height(): Int = (bottom - top).coerceAtLeast(0)
    fun centerX(): Int = left + width() / 2
    fun centerY(): Int = top + height() / 2
}

/**
 * Representa un obstáculo detectado en el frame de la cámara.
 * 
 * @property boundingBox Rectángulo que delimita el objeto detectado
 * @property label Etiqueta del objeto (opcional, de ML Kit)
 * @property confidence Nivel de confianza de la detección (0.0 - 1.0)
 * @property trackingId ID de seguimiento para objetos entre frames
 */
data class DetectedObstacle(
    val boundingBox: ObstacleBounds,
    val label: String? = null,
    val confidence: Float = 0f,
    val trackingId: Int? = null
) {
    /**
     * Calcula el área del BoundingBox
     */
    val area: Int
        get() = boundingBox.width() * boundingBox.height()

    /**
     * Centro X del BoundingBox
     */
    val centerX: Int
        get() = boundingBox.centerX()

    /**
     * Centro Y del BoundingBox
     */
    val centerY: Int
        get() = boundingBox.centerY()
}
