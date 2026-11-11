package com.blindnav.app.domain.navigation

import kotlin.math.abs
import kotlin.math.roundToInt

/** Text stays separate from sensor guidance so the recorded sentence is never rewritten. */
data class RecordedInstruction(
    val text: String,
    val targetBearing: Float? = null,
    val isTurn: Boolean = false,
    val isCrossing: Boolean = false,
    val progressMeters: Double? = null,
    val arrivalRadiusMeters: Float? = null,
    val endsSection: Boolean = false
)

data class RecordedPlaybackUpdate(
    val index: Int,
    val message: String? = null,
    val headingError: Float? = null,
    val arrived: Boolean = false,
    val reliable: Boolean = true
)

/** Sequential playback: no skipped instructions, no automatic reversal, no guessed turn angle. */
class RecordedRoutePlayer(
    private val locations: List<GeoCoordinate>,
    private val instructions: Map<Int, RecordedInstruction>,
    private val geometry: List<GeoCoordinate> = emptyList()
) {
    var index = 0
        private set
    private var nearFixes = 0
    private var lastFixTimestamp = -1L
    private var activeHeading: Float? = null
    private var turning = false
    private var alignedSince: Long? = null
    private var lastVoiceTime = 0L
    private var warningSent = false
    private var lastText = "Acércate al inicio de la ruta grabada."
    private var progress = 0.0

    fun repeat(): String = lastText

    fun update(position: GeoCoordinate, heading: Float, accuracy: Float, timestamp: Long, now: Long,
               headingReliable: Boolean = true, voiceBusy: Boolean = false): RecordedPlaybackUpdate {
        if (index >= locations.size) return RecordedPlaybackUpdate(index, arrived = true)
        if (!NavigationProgressPolicy.isLocationReliable(timestamp, accuracy, now, maxAccuracyMeters = 12f) ||
            !headingReliable) {
            nearFixes = 0
            alignedSince = null
            val message = if (!warningSent) {
                warningSent = true
                "Guía en pausa. Espera una ubicación y brújula fiables."
            } else null
            return RecordedPlaybackUpdate(index, message, reliable = false)
        }
        warningSent = false
        val distance = RouteGeometry.distanceMeters(position, locations[index])
        val radius = minOf(accuracy.coerceIn(4f, 8f), instructions[index]?.arrivalRadiusMeters ?: 8f)
        if (timestamp != lastFixTimestamp) {
            val elapsed = if (lastFixTimestamp < 0) 0.0 else ((timestamp - lastFixTimestamp) / 1000.0).coerceIn(0.0, 5.0)
            if (index > 0 && geometry.isNotEmpty()) {
                val projection = RouteGeometry.project(position, geometry,
                    (progress - 8.0).coerceAtLeast(0.0), progress + elapsed * 2.5 + 8.0)
                if (projection != null && projection.distanceToRouteMeters <= 12.0) {
                    progress = maxOf(progress, projection.progressMeters)
                }
            }
            lastFixTimestamp = timestamp
            val expectedProgress = instructions[index]?.progressMeters
                ?: if (index == locations.lastIndex && geometry.isNotEmpty()) RouteGeometry.totalDistanceMeters(geometry) else null
            val onThisVisit = expectedProgress == null || abs(expectedProgress - progress) <= radius + 4.0
            nearFixes = if (distance <= radius && onThisVisit) nearFixes + 1 else 0
        }

        if (voiceBusy) return RecordedPlaybackUpdate(index,
            headingError = activeHeading?.let { RouteGeometry.signedHeadingError(heading, it) })

        if (turning) {
            val error = RouteGeometry.signedHeadingError(heading, activeHeading!!)
            if (abs(error) <= 15f) {
                if (alignedSince == null) alignedSince = now
                if (now - alignedSince!! >= 1500L) {
                    turning = false
                    index++
                    nearFixes = 0
                    return say("Giro completado. Mantén este rumbo.", now, error)
                }
            } else alignedSince = null
            return correction(error, now, turn = true)
        }

        if (nearFixes >= 2) {
            val cue = instructions[index]
            nearFixes = 0
            // A new instruction ends the previous heading lock, including notes and obstacles.
            activeHeading = cue?.targetBearing
            if (cue?.isTurn == true && activeHeading != null) {
                turning = true
                alignedSince = null
            } else index++
            val text = cue?.text ?: if (index >= locations.size) "Has llegado al final de la ruta grabada."
                else "Inicio de la ruta grabada."
            return say(text, now).copy(arrived = index >= locations.size)
        }

        val target = activeHeading
        if (target != null) {
            if (instructions[index]?.endsSection == true &&
                abs(RouteGeometry.signedHeadingError(heading, target)) <= 15f && now - lastVoiceTime >= 12_000L) {
                return say("Mantén el rumbo. Final marcado a unos ${distance.roundToInt()} metros.", now,
                    RouteGeometry.signedHeadingError(heading, target))
            }
            return correction(RouteGeometry.signedHeadingError(heading, target), now)
        }
        if (now - lastVoiceTime >= 12_000L) {
            // Describe where the marker is, without telling the user to turn there yet.
            return say("${if (index == 0) "Inicio de ruta" else "Siguiente instrucción"} a unos ${distance.roundToInt()} metros.", now)
        }
        return RecordedPlaybackUpdate(index)
    }

    private fun correction(error: Float, now: Long, turn: Boolean = false): RecordedPlaybackUpdate {
        if (abs(error) > 15f && now - lastVoiceTime >= 5_000L) {
            val side = if (error > 0) "derecha" else "izquierda"
            val text = if (turn) "Gira a la $side unos ${abs(error).roundToInt()} grados más."
                else "Corrige a la $side unos ${abs(error).roundToInt()} grados para recuperar el rumbo grabado."
            return say(text, now, error)
        }
        return RecordedPlaybackUpdate(index, headingError = error)
    }

    private fun say(text: String, now: Long, error: Float? = null): RecordedPlaybackUpdate {
        lastVoiceTime = now
        lastText = text
        return RecordedPlaybackUpdate(index, text, error)
    }
}
