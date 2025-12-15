package com.blindnav.app.domain.model

/**
 * Niveles de peligro para la navegación.
 * 
 * Basado en la proximidad y tamaño del obstáculo detectado.
 * El nivel se calcula heurísticamente según el porcentaje
 * de pantalla que ocupa el BoundingBox del objeto.
 */
enum class HazardLevel {
    /**
     * Ruta completamente despejada.
     * BoundingBox < 10% de la pantalla.
     */
    SAFE,

    /**
     * Obstáculo detectado a distancia media.
     * BoundingBox entre 10% y 40% de la pantalla.
     */
    WARNING,

    /**
     * ¡PELIGRO INMINENTE!
     * BoundingBox > 40% de la pantalla Y centrado horizontalmente.
     * Requiere acción inmediata del usuario.
     */
    CRITICAL
}
