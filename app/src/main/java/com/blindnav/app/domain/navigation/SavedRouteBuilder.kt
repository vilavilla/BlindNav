package com.blindnav.app.domain.navigation

import com.blindnav.app.data.db.entity.Checkpoint
import com.blindnav.app.data.db.entity.CheckpointType
import com.blindnav.app.data.db.entity.EventType
import com.blindnav.app.data.db.entity.MapEvent
import com.blindnav.app.data.db.entity.PathPoint

data class SavedRouteNavigation(
    val geometry: List<GeoCoordinate>,
    val checkpoints: List<Checkpoint>,
    val totalDistanceMeters: Double,
    val reversed: Boolean,
    val recordedInstructions: Map<Int, RecordedInstruction> = emptyMap()
)

/** Convierte el rastro GPS grabado en una ruta navegable, en ambos sentidos. */
object SavedRouteBuilder {
    private const val MIN_POINT_DISTANCE_METERS = 2.0
    private const val GUIDANCE_SPACING_METERS = 25.0
    private const val EVENT_ROUTE_TOLERANCE_METERS = 35.0

    fun build(
        routeId: Long,
        pathPoints: List<PathPoint>,
        events: List<MapEvent>,
        currentLocation: GeoCoordinate? = null,
        savedCheckpoints: List<Checkpoint> = emptyList()
    ): SavedRouteNavigation? {
        val orderedSamples = pathPoints.sortedBy { it.timestamp }
        val orderedPoints = orderedSamples.map { GeoCoordinate(it.latitude, it.longitude) }
        val filteredTimes = mutableListOf<Long>()
        val filteredPoints = mutableListOf<GeoCoordinate>()
        orderedPoints.forEachIndexed { index, point ->
            if (filteredPoints.isEmpty() ||
                RouteGeometry.distanceMeters(filteredPoints.last(), point) >= MIN_POINT_DISTANCE_METERS
            ) {
                filteredPoints += point
                filteredTimes += orderedSamples[index].timestamp
            }
        }
        var geometry: List<GeoCoordinate> = filteredPoints
        if (geometry.size < 2) return null

        if (events.isNotEmpty()) {
            // Dictated instructions belong to the original walking order, even at a loop
            // or when two different instructions were recorded at the same coordinates.
            val orderedEvents = events.sortedBy { it.orderIndex }
            // An unfinished button-recorded section must be completed before playback.
            if (orderedEvents.any { it.hasExplicitEnd &&
                    (it.endLatitude == null || it.endLongitude == null || it.endedAt == null) }) return null
            fun progressAt(latitude: Double, longitude: Double, timestamp: Long): Double {
                val before = filteredTimes.indexOfLast { it <= timestamp }.coerceAtLeast(0)
                val after = (before + 1).coerceAtMost(geometry.lastIndex)
                val prefix = RouteGeometry.totalDistanceMeters(geometry.take(before + 1))
                return prefix + (RouteGeometry.project(GeoCoordinate(latitude, longitude),
                    geometry.subList(before, after + 1))?.progressMeters ?: 0.0)
            }
            val start = Checkpoint(routeId = routeId, latitude = geometry.first().latitude,
                longitude = geometry.first().longitude, description = "Inicio de la ruta grabada", orderIndex = 0)
            val recorded = mutableListOf(start)
            val instructions = mutableMapOf<Int, RecordedInstruction>()
            orderedEvents.forEach { event ->
                instructions[recorded.size] = RecordedInstruction(event.description, event.targetBearing,
                    isTurn = event.type == EventType.TURN, isCrossing = event.type == EventType.CROSSING,
                    progressMeters = progressAt(event.latitude, event.longitude, event.createdAt))
                recorded += Checkpoint(routeId = routeId, latitude = event.latitude, longitude = event.longitude,
                    description = event.description, orderIndex = recorded.size,
                    type = when (event.type) {
                        EventType.TURN -> CheckpointType.TURN
                        EventType.CROSSING -> CheckpointType.ZEBRA_CROSSING
                        EventType.INFO -> CheckpointType.WAYPOINT
                        else -> CheckpointType.ALERT
                    })
                if (event.hasExplicitEnd) {
                    val endText = when (event.type) {
                        EventType.CROSSING -> "Fin del paso de cebra grabado."
                        EventType.TURN -> "Fin del giro grabado."
                        else -> "Fin del tramo recto grabado."
                    }
                    val length = RouteGeometry.distanceMeters(GeoCoordinate(event.latitude, event.longitude),
                        GeoCoordinate(event.endLatitude!!, event.endLongitude!!))
                    instructions[recorded.size] = RecordedInstruction(endText,
                        progressMeters = progressAt(event.endLatitude, event.endLongitude, event.endedAt!!),
                        // A short crossing must not finish while still at its starting marker.
                        arrivalRadiusMeters = if (event.type == EventType.TURN) null
                            else (length / 3.0).toFloat().coerceIn(1f, 4f),
                        endsSection = true)
                    recorded += Checkpoint(routeId = routeId, latitude = event.endLatitude,
                        longitude = event.endLongitude, description = endText, orderIndex = recorded.size)
                }
            }
            val end = Checkpoint(routeId = routeId, latitude = geometry.last().latitude,
                longitude = geometry.last().longitude, description = "Final de la ruta grabada",
                orderIndex = recorded.size, type = CheckpointType.ENTRANCE)
            return SavedRouteNavigation(geometry, recorded + end,
                RouteGeometry.totalDistanceMeters(geometry), reversed = false,
                recordedInstructions = instructions)
        }

        val reversed = currentLocation?.let {
            RouteGeometry.distanceMeters(it, geometry.last()) + 8.0 <
                RouteGeometry.distanceMeters(it, geometry.first())
        } == true
        if (reversed) geometry = geometry.reversed()

        val checkpointCandidates = mutableListOf<Checkpoint>()
        checkpointCandidates += Checkpoint(
            routeId = routeId,
            latitude = geometry.first().latitude,
            longitude = geometry.first().longitude,
            description = "Inicio de la ruta grabada",
            orderIndex = 0,
            type = CheckpointType.WAYPOINT
        )

        var distanceSinceGuide = 0.0
        geometry.zipWithNext().forEachIndexed { index, (a, b) ->
            distanceSinceGuide += RouteGeometry.distanceMeters(a, b)
            if (distanceSinceGuide >= GUIDANCE_SPACING_METERS && index + 1 < geometry.lastIndex) {
                checkpointCandidates += Checkpoint(
                    routeId = routeId,
                    latitude = b.latitude,
                    longitude = b.longitude,
                    description = "Continúa por la ruta grabada",
                    orderIndex = 0,
                    type = CheckpointType.WAYPOINT
                )
                distanceSinceGuide = 0.0
            }
        }

        events.forEach { event ->
            val coordinate = GeoCoordinate(event.latitude, event.longitude)
            val projection = RouteGeometry.project(coordinate, geometry)
            if (projection != null && projection.distanceToRouteMeters <= EVENT_ROUTE_TOLERANCE_METERS) {
                checkpointCandidates += Checkpoint(
                    routeId = routeId,
                    latitude = projection.latitude,
                    longitude = projection.longitude,
                    description = event.description.ifBlank { event.type.displayName },
                    orderIndex = 0,
                    type = when (event.type) {
                        EventType.CROSSING -> CheckpointType.ZEBRA_CROSSING
                        EventType.OBSTACLE_PERMANENT, EventType.OBSTACLE_TEMPORARY -> CheckpointType.ALERT
                        EventType.TURN -> CheckpointType.TURN
                        EventType.INFO -> CheckpointType.WAYPOINT
                    }
                )
            }
        }

        savedCheckpoints.forEach { checkpoint ->
            val projection = RouteGeometry.project(
                GeoCoordinate(checkpoint.latitude, checkpoint.longitude), geometry
            )
            if (projection != null && projection.distanceToRouteMeters <= EVENT_ROUTE_TOLERANCE_METERS) {
                checkpointCandidates += checkpoint.copy(
                    id = 0,
                    routeId = routeId,
                    latitude = projection.latitude,
                    longitude = projection.longitude,
                    orderIndex = 0
                )
            }
        }

        checkpointCandidates += Checkpoint(
            routeId = routeId,
            latitude = geometry.last().latitude,
            longitude = geometry.last().longitude,
            description = "Final de la ruta grabada",
            orderIndex = 0,
            type = CheckpointType.ENTRANCE
        )

        val checkpoints = checkpointCandidates
            .distinctBy { checkpoint ->
                val progress = RouteGeometry.project(
                    GeoCoordinate(checkpoint.latitude, checkpoint.longitude), geometry
                )?.progressMeters ?: 0.0
                "${(progress / 5.0).toInt()}:${checkpoint.type}"
            }
            .sortedBy { checkpoint ->
                RouteGeometry.project(
                    GeoCoordinate(checkpoint.latitude, checkpoint.longitude), geometry
                )?.progressMeters ?: Double.MAX_VALUE
            }
            .mapIndexed { index, checkpoint -> checkpoint.copy(orderIndex = index) }

        return SavedRouteNavigation(
            geometry = geometry,
            checkpoints = checkpoints,
            totalDistanceMeters = RouteGeometry.totalDistanceMeters(geometry),
            reversed = reversed
        )
    }
}
