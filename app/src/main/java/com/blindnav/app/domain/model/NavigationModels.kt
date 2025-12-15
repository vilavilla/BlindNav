package com.blindnav.app.domain.model

import android.location.Location

/**
 * Modelos de datos para el sistema de navegación.
 */

// ============================================
// PRIORIDADES DE AUDIO
// ============================================

/**
 * Prioridades para el sistema de audio.
 * SAFETY siempre interrumpe a los demás.
 */
enum class AudioPriority(val level: Int) {
    /** Alertas de obstáculos - máxima prioridad, interrumpe todo */
    SAFETY(3),
    
    /** Instrucciones de navegación - prioridad media */
    NAVIGATION(2),
    
    /** Confirmaciones del sistema - prioridad baja */
    SYSTEM(1)
}

/**
 * Representa un mensaje de audio en cola.
 */
data class AudioMessage(
    val text: String,
    val priority: AudioPriority,
    val isInterruptible: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

// ============================================
// NAVEGACIÓN GPS
// ============================================

/**
 * Punto de ruta con coordenadas y metadatos.
 */
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val name: String = "",
    val instruction: String = "",
    val distanceToNext: Float = 0f
) {
    fun toLocation(): Location {
        return Location("RoutePoint").apply {
            latitude = this@RoutePoint.latitude
            longitude = this@RoutePoint.longitude
        }
    }
}

/**
 * Representa una ruta completa.
 */
data class NavigationRoute(
    val destination: String,
    val waypoints: List<RoutePoint>,
    val totalDistanceMeters: Float,
    val estimatedTimeMinutes: Int
)

/**
 * Estado actual de la navegación.
 */
data class NavigationState(
    val isNavigating: Boolean = false,
    val currentRoute: NavigationRoute? = null,
    val currentWaypointIndex: Int = 0,
    val currentLocation: Location? = null,
    val currentBearing: Float = 0f,        // Hacia dónde miro (brújula)
    val targetBearing: Float = 0f,         // Hacia dónde debo ir
    val distanceToNextPoint: Float = 0f,
    val lastInstruction: String = "",
    val arrived: Boolean = false
) {
    /**
     * Diferencia angular entre donde miro y donde debo ir.
     * Positivo = girar derecha, Negativo = girar izquierda.
     */
    val bearingDifference: Float
        get() {
            var diff = targetBearing - currentBearing
            // Normalizar a [-180, 180]
            while (diff > 180) diff -= 360
            while (diff < -180) diff += 360
            return diff
        }
    
    /**
     * Indica si el usuario va en la dirección correcta (±20 grados).
     */
    val isOnCourse: Boolean
        get() = kotlin.math.abs(bearingDifference) <= 20f
}

/**
 * Tipo de instrucción de navegación.
 */
enum class NavigationInstruction {
    CONTINUE_STRAIGHT,
    TURN_SLIGHT_LEFT,
    TURN_LEFT,
    TURN_SHARP_LEFT,
    TURN_SLIGHT_RIGHT,
    TURN_RIGHT,
    TURN_SHARP_RIGHT,
    U_TURN,
    ARRIVED,
    RECALCULATING
}

// ============================================
// COMANDOS DE VOZ
// ============================================

/**
 * Resultado del reconocimiento de voz.
 */
sealed class VoiceCommandResult {
    data class NavigateTo(val destination: String) : VoiceCommandResult()
    object StopNavigation : VoiceCommandResult()
    object RepeatInstruction : VoiceCommandResult()
    object WhereAmI : VoiceCommandResult()
    object Help : VoiceCommandResult()
    data class Unknown(val text: String) : VoiceCommandResult()
    data class Error(val message: String) : VoiceCommandResult()
}

/**
 * Estado del reconocedor de voz.
 */
enum class VoiceRecognizerState {
    IDLE,
    LISTENING,
    PROCESSING,
    ERROR
}
