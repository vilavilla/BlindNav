package com.blindnav.app.domain.navigation

import android.location.Location
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * CompassNavigationHelper - Guiado con Brújula usando Reloj Analógico
 * 
 * Convierte diferencias de bearing en direcciones de reloj:
 * - 12:00 = Recto
 * - 1:00-2:00 = Ligeramente a la derecha
 * - 3:00 = 90° derecha
 * - 6:00 = Dar la vuelta
 * - 9:00 = 90° izquierda
 * - 10:00-11:00 = Ligeramente a la izquierda
 */
object CompassNavigationHelper {

    /**
     * Feedback de navegación basado en brújula
     */
    data class CompassFeedback(
        val clockHour: Int,        // Hora del reloj (1-12)
        val instruction: String,   // Instrucción verbal
        val shouldSpeak: Boolean,  // ¿Debe anunciarse?
        val tone: ToneFeedback,    // Tipo de tono
        val bearingDiff: Float     // Diferencia en grados (debug)
    )

    enum class ToneFeedback {
        SILENT,         // Vas recto (±10°)
        SOFT_BEEP,      // Ajuste pequeño (10-30°)
        MEDIUM_BEEP,    // Giro moderado (30-60°)
        URGENT_BEEP     // Giro grande o dar la vuelta (>60°)
    }

    /**
     * Calcular bearing desde ubicación actual hacia un destino
     * 
     * @param currentLat Latitud actual
     * @param currentLon Longitud actual
     * @param targetLat Latitud del destino
     * @param targetLon Longitud del destino
     * @return Bearing en grados (0-360)
     */
    fun calculateBearing(
        currentLat: Double,
        currentLon: Double,
        targetLat: Double,
        targetLon: Double
    ): Float {
        val lat1 = Math.toRadians(currentLat)
        val lon1 = Math.toRadians(currentLon)
        val lat2 = Math.toRadians(targetLat)
        val lon2 = Math.toRadians(targetLon)

        val dLon = lon2 - lon1

        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val bearing = Math.toDegrees(atan2(y, x))

        // Normalizar a 0-360
        return ((bearing + 360) % 360).toFloat()
    }

    /**
     * Generar feedback de navegación comparando brújula con bearing objetivo
     * 
     * @param currentBearing Bearing actual de la brújula (hacia dónde miro)
     * @param targetBearing Bearing necesario (hacia dónde debo ir)
     * @return CompassFeedback con instrucciones
     */
    fun generateFeedback(currentBearing: Float, targetBearing: Float): CompassFeedback {
        // Calcular diferencia angular (puede ser negativa)
        var diff = targetBearing - currentBearing
        
        // Normalizar a -180 a +180
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        
        // Determinar hora del reloj (basado en ángulo)
        val clockHour = bearingToClockHour(diff)
        
        // Generar instrucción y feedback
        return when {
            abs(diff) < 10 -> {
                // Vas recto
                CompassFeedback(
                    clockHour = 12,
                    instruction = "Recto",
                    shouldSpeak = false,
                    tone = ToneFeedback.SILENT,
                    bearingDiff = diff
                )
            }
            
            abs(diff) < 30 -> {
                // Ajuste pequeño
                val direction = if (diff > 0) "derecha" else "izquierda"
                val hour = if (diff > 0) "la 1" else "las 11"
                
                CompassFeedback(
                    clockHour = clockHour,
                    instruction = "Ajusta ligeramente a la $direction, hacia $hour",
                    shouldSpeak = true,
                    tone = ToneFeedback.SOFT_BEEP,
                    bearingDiff = diff
                )
            }
            
            abs(diff) < 60 -> {
                // Giro moderado
                val direction = if (diff > 0) "derecha" else "izquierda"
                val hour = if (diff > 0) "las 2" else "las 10"
                
                CompassFeedback(
                    clockHour = clockHour,
                    instruction = "Gira a la $direction, hacia $hour",
                    shouldSpeak = true,
                    tone = ToneFeedback.MEDIUM_BEEP,
                    bearingDiff = diff
                )
            }
            
            abs(diff) < 135 -> {
                // Giro grande
                val direction = if (diff > 0) "derecha" else "izquierda"
                val hour = if (diff > 0) "las 3" else "las 9"
                
                CompassFeedback(
                    clockHour = clockHour,
                    instruction = "Gira 90 grados a la $direction, hacia $hour",
                    shouldSpeak = true,
                    tone = ToneFeedback.URGENT_BEEP,
                    bearingDiff = diff
                )
            }
            
            else -> {
                // Dar la vuelta (>135°)
                CompassFeedback(
                    clockHour = 6,
                    instruction = "Da la vuelta, hacia las 6",
                    shouldSpeak = true,
                    tone = ToneFeedback.URGENT_BEEP,
                    bearingDiff = diff
                )
            }
        }
    }

    /**
     * Convertir diferencia de bearing a hora del reloj (1-12)
     * 
     * @param bearingDiff Diferencia angular (-180 a +180)
     * @return Hora del reloj (1-12)
     */
    private fun bearingToClockHour(bearingDiff: Float): Int {
        // Mapear -180 a +180 → 1-12
        val normalized = ((bearingDiff + 180) % 360).let { if (it < 0) it + 360 else it }
        val hour = ((normalized / 30) + 6).toInt() % 12
        return if (hour == 0) 12 else hour
    }

    /**
     * Calcular distancia entre dos puntos GPS (en metros)
     */
    fun calculateDistance(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    /**
     * Feedback simplificado para UI
     */
    fun getSimpleDirection(bearingDiff: Float): String {
        return when {
            abs(bearingDiff) < 10 -> "↑ RECTO"
            bearingDiff in 10f..45f -> "↗ LIGERAMENTE DERECHA"
            bearingDiff in 45f..135f -> "→ DERECHA"
            bearingDiff > 135f -> "↓ DAR LA VUELTA"
            bearingDiff in -45f..-10f -> "↖ LIGERAMENTE IZQUIERDA"
            bearingDiff in -135f..-45f -> "← IZQUIERDA"
            else -> "↓ DAR LA VUELTA"
        }
    }
}
