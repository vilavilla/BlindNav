package com.blindnav.app.domain.navigation

import android.location.Location
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * TacticalNavigationEngine - Motor de navegación táctica
 * 
 * Calcula direcciones usando el sistema de reloj (1-12) para
 * indicar al usuario hacia dónde debe ir de forma intuitiva.
 * 
 * SISTEMA DE RELOJ:
 * - 12 = Delante (± 15°)
 * - 3 = Derecha (90°)
 * - 6 = Detrás (180°)
 * - 9 = Izquierda (270°)
 */
class TacticalNavigationEngine {
    
    companion object {
        // Cada hora del reloj = 30 grados (360/12)
        private const val DEGREES_PER_HOUR = 30f
        
        // Umbral para considerar "12 en punto" (directamente delante)
        private const val AHEAD_THRESHOLD = 15f
    }
    
    /**
     * Resultado de navegación táctica.
     */
    data class TacticalDirection(
        /** Hora del reloj (1-12) */
        val clockHour: Int,
        
        /** Distancia al objetivo en metros */
        val distanceMeters: Float,
        
        /** Bearing absoluto hacia el objetivo (0-360) */
        val absoluteBearing: Float,
        
        /** Diferencia relativa de bearing (positivo = derecha, negativo = izquierda) */
        val relativeBearing: Float,
        
        /** Indica si el objetivo está directamente delante (12 en punto) */
        val isAhead: Boolean
    ) {
        /**
         * Genera el texto de audio para TTS.
         */
        fun toSpokenText(checkpointName: String = "Punto"): String {
            val distanceText = when {
                distanceMeters < 10 -> "${distanceMeters.roundToInt()} metros"
                distanceMeters < 100 -> "${(distanceMeters / 5).roundToInt() * 5} metros"
                else -> "${(distanceMeters / 10).roundToInt() * 10} metros"
            }
            
            return if (isAhead) {
                "$checkpointName delante, $distanceText"
            } else {
                "$checkpointName a las $clockHour, $distanceText"
            }
        }
    }
    
    /**
     * Calcula la dirección táctica hacia un objetivo.
     * 
     * @param userHeading Orientación actual del usuario (0-360, donde 0=Norte)
     * @param userLocation Ubicación actual del usuario
     * @param targetLocation Ubicación del objetivo
     * @return TacticalDirection con la hora del reloj y distancia
     */
    fun calculateDirection(
        userHeading: Float,
        userLocation: Location,
        targetLocation: Location
    ): TacticalDirection {
        // 1. Calcular bearing absoluto hacia el objetivo (norte geográfico)
        val absoluteBearing = userLocation.bearingTo(targetLocation)
        
        // 2. Calcular bearing relativo (respecto a donde mira el usuario)
        val relativeBearing = normalizeAngle(absoluteBearing - userHeading)
        
        // 3. Convertir a hora del reloj
        val clockHour = bearingToClockHour(relativeBearing)
        
        // 4. Calcular distancia
        val distanceMeters = userLocation.distanceTo(targetLocation)
        
        // 5. Determinar si está delante
        val isAhead = abs(relativeBearing) <= AHEAD_THRESHOLD
        
        return TacticalDirection(
            clockHour = clockHour,
            distanceMeters = distanceMeters,
            absoluteBearing = normalizeAngle360(absoluteBearing),
            relativeBearing = relativeBearing,
            isAhead = isAhead
        )
    }
    
    /**
     * Calcula la hora del reloj desde el heading del usuario.
     * 
     * @param userHeading Orientación del usuario (0-360)
     * @param targetBearing Bearing absoluto hacia el objetivo (0-360)
     * @return Hora del reloj (1-12)
     */
    fun calculateClockDirection(userHeading: Float, targetBearing: Float): Int {
        val relativeBearing = normalizeAngle(targetBearing - userHeading)
        return bearingToClockHour(relativeBearing)
    }
    
    /**
     * Convierte un bearing relativo a hora del reloj.
     * 
     * @param relativeBearing Ángulo relativo (-180 a 180)
     * @return Hora del reloj (1-12)
     */
    private fun bearingToClockHour(relativeBearing: Float): Int {
        // Normalizar a 0-360 para el cálculo
        var angle = relativeBearing
        if (angle < 0) angle += 360
        
        // Dividir por 30 grados y redondear
        var hour = ((angle + DEGREES_PER_HOUR / 2) / DEGREES_PER_HOUR).roundToInt()
        
        // Ajustar: 0 -> 12
        if (hour == 0) hour = 12
        if (hour > 12) hour -= 12
        
        return hour
    }
    
    /**
     * Normaliza un ángulo a -180 a 180 grados.
     */
    private fun normalizeAngle(angle: Float): Float {
        var normalized = angle % 360
        if (normalized > 180) normalized -= 360
        if (normalized < -180) normalized += 360
        return normalized
    }
    
    /**
     * Normaliza un ángulo a 0-360 grados.
     */
    private fun normalizeAngle360(angle: Float): Float {
        var normalized = angle % 360
        if (normalized < 0) normalized += 360
        return normalized
    }
    
    /**
     * Calcula si el usuario está en la dirección correcta.
     * 
     * @param userHeading Orientación del usuario
     * @param targetBearing Bearing hacia el objetivo
     * @param toleranceDegrees Tolerancia en grados (default 20°)
     * @return true si el usuario está orientado hacia el objetivo
     */
    fun isOnCourse(
        userHeading: Float,
        targetBearing: Float,
        toleranceDegrees: Float = 20f
    ): Boolean {
        val diff = abs(normalizeAngle(targetBearing - userHeading))
        return diff <= toleranceDegrees
    }
    
    /**
     * Genera instrucción de corrección de rumbo.
     */
    fun getCorrectionInstruction(relativeBearing: Float): String {
        return when {
            relativeBearing > 90 -> "Gira a la derecha"
            relativeBearing > 20 -> "Gira ligeramente a la derecha"
            relativeBearing < -90 -> "Gira a la izquierda"
            relativeBearing < -20 -> "Gira ligeramente a la izquierda"
            else -> "Continúa recto"
        }
    }
}
