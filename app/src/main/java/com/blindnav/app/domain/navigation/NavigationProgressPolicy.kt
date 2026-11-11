package com.blindnav.app.domain.navigation

import kotlin.math.max

/** Reglas puras para no bloquearse en un checkpoint que el GPS no clavó exactamente. */
object NavigationProgressPolicy {
    fun isLocationReliable(
        timestamp: Long,
        accuracyMeters: Float,
        now: Long,
        maxAgeMs: Long = 15_000L,
        maxAccuracyMeters: Float = 50f
    ): Boolean = now - timestamp in 0L..maxAgeMs && accuracyMeters in 0f..maxAccuracyMeters

    fun arrivalThresholdMeters(accuracyMeters: Float): Float =
        accuracyMeters.coerceIn(4f, 8f)

    fun shouldAdvanceCheckpoint(
        distanceToCheckpointMeters: Float,
        accuracyMeters: Float,
        userProgressMeters: Double?,
        checkpointProgressMeters: Double,
        distanceToRouteMeters: Double?
    ): Boolean {
        val reached = distanceToCheckpointMeters <= arrivalThresholdMeters(accuracyMeters)
        val passed = userProgressMeters != null && distanceToRouteMeters != null &&
            distanceToRouteMeters <= 30.0 &&
            userProgressMeters >= checkpointProgressMeters + max(6.0, accuracyMeters / 2.0)
        return reached || passed
    }
}
