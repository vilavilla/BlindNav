package com.blindnav.app.domain.navigation

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoCoordinate(val latitude: Double, val longitude: Double)

data class RouteProjection(
    val distanceToRouteMeters: Double,
    val progressMeters: Double,
    val segmentIndex: Int,
    val segmentFraction: Double,
    val latitude: Double,
    val longitude: Double
)

/** Cálculos geométricos puros usados por GPS, checkpoints y rutas grabadas. */
object RouteGeometry {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun distanceMeters(a: GeoCoordinate, b: GeoCoordinate): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt(max(0.0, 1 - h)))
    }

    fun totalDistanceMeters(points: List<GeoCoordinate>): Double =
        points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }

    fun bearingDegrees(a: GeoCoordinate, b: GeoCoordinate): Float {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    fun signedHeadingError(currentBearing: Float, targetBearing: Float): Float {
        var error = targetBearing - currentBearing
        while (error > 180f) error -= 360f
        while (error < -180f) error += 360f
        return error
    }

    fun project(point: GeoCoordinate, route: List<GeoCoordinate>,
                minProgressMeters: Double = 0.0, maxProgressMeters: Double = Double.MAX_VALUE): RouteProjection? {
        if (route.isEmpty()) return null
        if (route.size == 1) {
            return RouteProjection(
                distanceToRouteMeters = distanceMeters(point, route.first()),
                progressMeters = 0.0,
                segmentIndex = 0,
                segmentFraction = 0.0,
                latitude = route.first().latitude,
                longitude = route.first().longitude
            )
        }

        val referenceLatRad = Math.toRadians(point.latitude)
        var cumulative = 0.0
        var best: RouteProjection? = null

        route.zipWithNext().forEachIndexed { index, (a, b) ->
            val segmentDistance = distanceMeters(a, b)
            if (cumulative + segmentDistance < minProgressMeters || cumulative > maxProgressMeters) {
                cumulative += segmentDistance
                return@forEachIndexed
            }
            val ax = lonMeters(a.longitude, referenceLatRad)
            val ay = latMeters(a.latitude)
            val bx = lonMeters(b.longitude, referenceLatRad)
            val by = latMeters(b.latitude)
            val px = lonMeters(point.longitude, referenceLatRad)
            val py = latMeters(point.latitude)
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val minFraction = if (segmentDistance > 0) ((minProgressMeters - cumulative) / segmentDistance).coerceIn(0.0, 1.0) else 0.0
            val maxFraction = if (segmentDistance > 0) ((maxProgressMeters - cumulative) / segmentDistance).coerceIn(0.0, 1.0) else 1.0
            val fraction = if (lengthSquared <= 1e-6) 0.0 else
                (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(minFraction, maxFraction)
            val nearestX = ax + dx * fraction
            val nearestY = ay + dy * fraction
            val distance = sqrt((px - nearestX) * (px - nearestX) + (py - nearestY) * (py - nearestY))

            if (best == null || distance < best!!.distanceToRouteMeters) {
                best = RouteProjection(
                    distanceToRouteMeters = distance,
                    progressMeters = cumulative + segmentDistance * fraction,
                    segmentIndex = index,
                    segmentFraction = fraction,
                    latitude = a.latitude + (b.latitude - a.latitude) * fraction,
                    longitude = a.longitude + (b.longitude - a.longitude) * fraction
                )
            }
            cumulative += segmentDistance
        }
        return best
    }

    fun bearingAtProgress(route: List<GeoCoordinate>, progressMeters: Double): Float? {
        if (route.size < 2) return null
        var cumulative = 0.0
        route.zipWithNext().forEach { (a, b) ->
            val segment = distanceMeters(a, b)
            if (progressMeters <= cumulative + segment || b == route.last()) {
                return bearingDegrees(a, b)
            }
            cumulative += segment
        }
        return bearingDegrees(route[route.lastIndex - 1], route.last())
    }

    private fun latMeters(latitude: Double): Double = Math.toRadians(latitude) * EARTH_RADIUS_METERS
    private fun lonMeters(longitude: Double, latitudeRadians: Double): Double =
        Math.toRadians(longitude) * EARTH_RADIUS_METERS * cos(latitudeRadians)
}
