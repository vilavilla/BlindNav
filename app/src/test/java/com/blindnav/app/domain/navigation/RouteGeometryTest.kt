package com.blindnav.app.domain.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGeometryTest {
    private val route = listOf(
        GeoCoordinate(41.4000, 2.1700),
        GeoCoordinate(41.4010, 2.1700),
        GeoCoordinate(41.4020, 2.1700)
    )

    @Test
    fun `projection advances after a missed checkpoint`() {
        val checkpoint = RouteGeometry.project(route[1], route)!!
        val user = RouteGeometry.project(GeoCoordinate(41.4013, 2.17002), route)!!
        assertTrue(user.progressMeters > checkpoint.progressMeters + 20)
        assertTrue(user.distanceToRouteMeters < 5)
    }

    @Test
    fun `projection measures cross track error`() {
        val projection = RouteGeometry.project(GeoCoordinate(41.4010, 2.1705), route)
        assertNotNull(projection)
        assertTrue(projection!!.distanceToRouteMeters > 35)
    }

    @Test
    fun `heading error is normalized through north`() {
        assertEquals(20f, RouteGeometry.signedHeadingError(350f, 10f), 0.01f)
        assertEquals(-20f, RouteGeometry.signedHeadingError(10f, 350f), 0.01f)
    }
}
