package com.blindnav.app.data.osm

import android.util.Log
import com.blindnav.app.domain.navigation.GeoCoordinate
import com.blindnav.app.domain.navigation.RouteGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class AccessibilityFeature(
    val coordinate: GeoCoordinate,
    val kind: Kind,
    val crossingType: String? = null,
    val tactilePaving: Boolean = false,
    val audibleSignals: Boolean = false,
    val loweredKerb: Boolean = false
) {
    enum class Kind { CROSSING, STEPS }
}

data class AccessibilitySummary(
    val dataAvailable: Boolean,
    val crossings: Int = 0,
    val accessibleCrossings: Int = 0,
    val steps: Int = 0
)

/** Lee de OpenStreetMap elementos que un enrutador genérico no explica al peatón. */
class OpenStreetMapAccessibilityProvider {
    companion object {
        private const val TAG = "OsmAccessibility"
        private const val ENDPOINT = "https://overpass-api.de/api/interpreter"
        private const val USER_AGENT = "BlindNavApp/1.0 (accessibility routing)"
        private const val MAX_BBOX_SPAN_DEGREES = 0.18
        private const val ROUTE_MATCH_METERS = 24.0
    }

    suspend fun getFeatures(points: List<GeoPoint>): List<AccessibilityFeature>? =
        withContext(Dispatchers.IO) {
            if (points.isEmpty()) return@withContext emptyList()
            val south = points.minOf { it.latitude } - 0.001
            val north = points.maxOf { it.latitude } + 0.001
            val west = points.minOf { it.longitude } - 0.001
            val east = points.maxOf { it.longitude } + 0.001

            // Evita consultas enormes al servicio comunitario.
            if (north - south > MAX_BBOX_SPAN_DEGREES || east - west > MAX_BBOX_SPAN_DEGREES) {
                Log.w(TAG, "Ruta demasiado extensa para enriquecerla con Overpass")
                return@withContext null
            }

            val bbox = listOf(south, west, north, east)
                .joinToString(",") { String.format(Locale.US, "%.6f", it) }
            val query = "[out:json][timeout:15];(" +
                "node[highway=crossing]($bbox);" +
                "way[highway=steps]($bbox);" +
                ");out center tags;"
            val url = "$ENDPOINT?data=${URLEncoder.encode(query, Charsets.UTF_8.name())}"

            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000
                    readTimeout = 18_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }
                val body = try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
                parse(body).filter { feature ->
                    RouteGeometry.project(
                        feature.coordinate,
                        points.map { GeoCoordinate(it.latitude, it.longitude) }
                    )?.distanceToRouteMeters?.let { it <= ROUTE_MATCH_METERS } == true
                }
            } catch (error: Exception) {
                Log.w(TAG, "No se pudieron consultar datos peatonales: ${error.message}")
                null
            }
        }

    private fun parse(json: String): List<AccessibilityFeature> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        return buildList {
            for (index in 0 until elements.length()) {
                val element = elements.optJSONObject(index) ?: continue
                val tags = element.optJSONObject("tags") ?: JSONObject()
                val center = element.optJSONObject("center")
                val latitude = if (element.has("lat")) element.optDouble("lat") else center?.optDouble("lat")
                val longitude = if (element.has("lon")) element.optDouble("lon") else center?.optDouble("lon")
                if (latitude == null || longitude == null || latitude.isNaN() || longitude.isNaN()) continue

                when (tags.optString("highway")) {
                    "crossing" -> add(
                        AccessibilityFeature(
                            coordinate = GeoCoordinate(latitude, longitude),
                            kind = AccessibilityFeature.Kind.CROSSING,
                            crossingType = tags.optString("crossing").ifBlank { null },
                            tactilePaving = tags.optString("tactile_paving") == "yes",
                            audibleSignals = tags.optString("traffic_signals:sound") == "yes",
                            loweredKerb = tags.optString("kerb") in setOf("lowered", "flush")
                        )
                    )
                    "steps" -> add(
                        AccessibilityFeature(
                            coordinate = GeoCoordinate(latitude, longitude),
                            kind = AccessibilityFeature.Kind.STEPS
                        )
                    )
                }
            }
        }
    }
}
