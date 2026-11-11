package com.blindnav.app.domain.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationProgressPolicyTest {
    @Test
    fun `does not consume a turn twenty meters before its location`() {
        assertFalse(NavigationProgressPolicy.shouldAdvanceCheckpoint(20f, 15f, 80.0, 100.0, 3.0))
    }
    @Test
    fun `advances checkpoint after user passes it while remaining on route`() {
        assertTrue(
            NavigationProgressPolicy.shouldAdvanceCheckpoint(
                distanceToCheckpointMeters = 32f,
                accuracyMeters = 8f,
                userProgressMeters = 125.0,
                checkpointProgressMeters = 100.0,
                distanceToRouteMeters = 3.0
            )
        )
    }

    @Test
    fun `does not skip checkpoint when user is far from route`() {
        assertFalse(
            NavigationProgressPolicy.shouldAdvanceCheckpoint(
                distanceToCheckpointMeters = 60f,
                accuracyMeters = 8f,
                userProgressMeters = 125.0,
                checkpointProgressMeters = 100.0,
                distanceToRouteMeters = 55.0
            )
        )
    }

    @Test
    fun `rejects stale or very inaccurate gps`() {
        assertFalse(NavigationProgressPolicy.isLocationReliable(1_000L, 5f, 20_000L))
        assertFalse(NavigationProgressPolicy.isLocationReliable(19_000L, 80f, 20_000L))
        assertTrue(NavigationProgressPolicy.isLocationReliable(19_000L, 5f, 20_000L))
    }
}
