package com.blindnav.app.data.repository

import com.blindnav.app.data.db.entity.Checkpoint
import com.blindnav.app.data.osm.OSRMRouteProvider
import org.osmdroid.util.GeoPoint

class RealRouteRepository(
    private val osrmRouteProvider: OSRMRouteProvider
) {
    suspend fun getRoute(
        start: GeoPoint,
        end: GeoPoint,
        routeId: Long = 0L,
        savedCheckpoints: List<Checkpoint> = emptyList()
    ): OSRMRouteProvider.RouteFetchResult {
        return osrmRouteProvider.getRoute(
            start = start,
            end = end,
            routeId = routeId,
            savedCheckpoints = savedCheckpoints
        )
    }

    suspend fun calculateRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        routeId: Long = 0L,
        savedCheckpoints: List<Checkpoint> = emptyList()
    ): OSRMRouteProvider.RouteResult? {
        return osrmRouteProvider.calculateRoute(
            startLat = startLat,
            startLon = startLon,
            endLat = endLat,
            endLon = endLon,
            routeId = routeId,
            savedCheckpoints = savedCheckpoints
        )
    }
}
