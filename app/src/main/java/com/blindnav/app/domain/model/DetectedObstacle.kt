package com.blindnav.app.domain.model

import android.graphics.Rect

/**
 * Representa un obstáculo detectado en el frame de la cámara.
 * 
 * @property boundingBox Rectángulo que delimita el objeto detectado
 * @property label Etiqueta del objeto (opcional, de ML Kit)
 * @property confidence Nivel de confianza de la detección (0.0 - 1.0)
 * @property trackingId ID de seguimiento para objetos entre frames
 */
data class DetectedObstacle(
    val boundingBox: Rect,
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
