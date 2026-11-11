package com.blindnav.app.domain.navigation

import com.blindnav.app.data.db.entity.EventType
import com.blindnav.app.data.db.entity.MapEvent
import com.blindnav.app.data.db.entity.PathPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedRouteBuilderTest {
    @Test
    fun `button crossing has separate start and end with the recorded compass heading`() {
        val event = MapEvent(routeId = 7, type = EventType.CROSSING, latitude = 41.4,
            longitude = 2.17, description = "Paso de cebra", bearing = 5f, gpsAccuracy = 5f,
            orderIndex = 0, createdAt = 1, targetBearing = 5f, hasExplicitEnd = true,
            endLatitude = 41.4003, endLongitude = 2.17, endedAt = 2)
        val route = SavedRouteBuilder.build(7, points, listOf(event))!!
        assertEquals(4, route.checkpoints.size)
        assertEquals(5f, route.recordedInstructions[1]!!.targetBearing!!, 0.01f)
        assertEquals(41.4003, route.checkpoints[2].latitude, 0.000001)
        assertTrue(route.recordedInstructions[2]!!.endsSection)
        assertEquals(null, route.recordedInstructions[2]!!.targetBearing)
        assertEquals("Fin del paso de cebra grabado.", route.recordedInstructions[2]!!.text)
        assertTrue(route.recordedInstructions[2]!!.progressMeters!! > 30.0)
    }

    @Test
    fun `unfinished button section cannot become a navigable route`() {
        val event = MapEvent(routeId = 7, type = EventType.INFO, latitude = 41.4,
            longitude = 2.17, description = "Recto", bearing = 0f, gpsAccuracy = 5f,
            orderIndex = 0, createdAt = 1, targetBearing = 0f, hasExplicitEnd = true)
        assertEquals(null, SavedRouteBuilder.build(7, points, listOf(event)))
    }

    @Test
    fun `button turn keeps both measured exit heading and finish position`() {
        val turn = MapEvent(routeId = 7, type = EventType.TURN, latitude = 41.4,
            longitude = 2.17, description = "Gira a la derecha.", bearing = 0f, gpsAccuracy = 5f,
            orderIndex = 0, createdAt = 1, targetBearing = 90f, turnDegrees = 90f, hasExplicitEnd = true,
            endLatitude = 41.4003, endLongitude = 2.17, endedAt = 2)
        val route = SavedRouteBuilder.build(7, points, listOf(turn))!!
        assertTrue(route.recordedInstructions[1]!!.isTurn)
        assertEquals(90f, route.recordedInstructions[1]!!.targetBearing!!, 0.01f)
        assertEquals("Fin del giro grabado.", route.recordedInstructions[2]!!.text)
        assertEquals(41.4003, route.checkpoints[2].latitude, 0.000001)
    }

    private val points = listOf(
        PathPoint(routeId = 7, latitude = 41.4000, longitude = 2.1700, timestamp = 1),
        PathPoint(routeId = 7, latitude = 41.4003, longitude = 2.1700, timestamp = 2),
        PathPoint(routeId = 7, latitude = 41.4006, longitude = 2.1700, timestamp = 3)
    )

    @Test
    fun `recorded route contains geometry and destination`() {
        val route = SavedRouteBuilder.build(7, points, emptyList())
        assertNotNull(route)
        assertEquals(3, route!!.geometry.size)
        assertEquals("Final de la ruta grabada", route.checkpoints.last().description)
        assertTrue(route.totalDistanceMeters > 60)
    }

    @Test
    fun `route reverses when user starts near recorded end`() {
        val route = SavedRouteBuilder.build(
            7,
            points,
            emptyList(),
            GeoCoordinate(41.4006, 2.1700)
        )!!
        assertTrue(route.reversed)
        assertEquals(41.4006, route.geometry.first().latitude, 0.000001)
    }

    @Test
    fun `recorded crossing becomes a crossing checkpoint in route order`() {
        val event = MapEvent(
            routeId = 7,
            type = EventType.CROSSING,
            latitude = 41.4003,
            longitude = 2.1700,
            description = "Paso conocido",
            bearing = 0f,
            gpsAccuracy = 5f,
            orderIndex = 0
        )
        val route = SavedRouteBuilder.build(7, points, listOf(event))!!
        assertTrue(route.checkpoints.any { it.description == "Paso conocido" })
    }

    @Test
    fun `dictated route keeps original direction exact text and nearby duplicate types`() {
        val first = MapEvent(routeId = 7, type = EventType.TURN, latitude = 41.4003,
            longitude = 2.17, description = "Gira a la derecha junto a la entrada", bearing = 0f,
            gpsAccuracy = 5f, orderIndex = 0, createdAt = 2, targetBearing = 90f, turnDegrees = 90f)
        val second = first.copy(orderIndex = 1, description = "Después gira a la izquierda", targetBearing = 0f)
        val route = SavedRouteBuilder.build(7, points, listOf(second, first), GeoCoordinate(41.4006, 2.17))!!
        assertTrue(!route.reversed)
        assertEquals(listOf(first.description, second.description), route.checkpoints.drop(1).dropLast(1).map { it.description })
        assertEquals(90f, route.recordedInstructions[1]!!.targetBearing!!, 0.01f)
        assertTrue(route.recordedInstructions[1]!!.progressMeters!! in 30.0..35.0)
    }
}
