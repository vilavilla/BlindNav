package com.blindnav.app.data.osm

import android.content.Context
import android.location.Location
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.blindnav.app.data.db.entity.Checkpoint
import com.blindnav.app.data.db.entity.CheckpointType
import com.blindnav.app.domain.navigation.GeoCoordinate
import com.blindnav.app.domain.navigation.RouteGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.bonuspack.routing.Road
import org.osmdroid.bonuspack.routing.RoadNode
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLHandshakeException
import kotlin.math.abs

class OSRMRouteProvider(context: Context) {

    companion object {
        private const val TAG = "OSRMRouteProvider"
        private const val USER_AGENT = "BlindNavApp/1.0 (blindnav.app@gmail.com)"
        // Instancia pública procesada con el perfil foot. En OSRM el texto del perfil
        // de la URL no selecciona el grafo; el grafo se fija al arrancar el servidor.
        private const val OSRM_BASE_URL = "https://routing.openstreetmap.de/routed-foot/route/v1/driving/"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val MAX_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 1200L
        private const val CONNECTION_ERROR_MESSAGE = "No puedo conectar con el servidor de rutas. Comprueba tu conexión"
        private const val CHECKPOINT_MERGE_THRESHOLD_METERS = 20.0
    }

    sealed class RouteFetchResult {
        data class Success(val routeResult: RouteResult) : RouteFetchResult()
        data class Failure(val userMessage: String) : RouteFetchResult()
    }

    enum class RouteSource {
        PEDESTRIAN_OSRM
    }

    data class RouteResult(
        val checkpoints: List<Checkpoint>,
        val totalDistance: Double,
        val totalDuration: Double,
        val road: Road,
        val polyline: Polyline,
        val instructions: List<TurnInstruction>,
        val source: RouteSource,
        val accessibility: AccessibilitySummary = AccessibilitySummary(dataAvailable = false)
    )

    data class TurnInstruction(
        val distance: Double,
        val duration: Double,
        val instruction: String,
        val maneuverType: Int,
        val latitude: Double,
        val longitude: Double
    )

    private data class HttpResult(
        val statusCode: Int,
        val responseBody: String,
        val errorBody: String
    )

    private val appContext = context.applicationContext
    private val accessibilityProvider = OpenStreetMapAccessibilityProvider()

    private val translationMap: List<Pair<Regex, String>> = listOf(
        Regex("\\bturn left\\b", RegexOption.IGNORE_CASE) to "Gira a la izquierda",
        Regex("\\bturn right\\b", RegexOption.IGNORE_CASE) to "Gira a la derecha",
        Regex("\\bcontinue straight\\b", RegexOption.IGNORE_CASE) to "Continúa recto",
        Regex("\\bcontinue\\b", RegexOption.IGNORE_CASE) to "Continúa",
        Regex("\\bkeep left\\b", RegexOption.IGNORE_CASE) to "Mantente a la izquierda",
        Regex("\\bkeep right\\b", RegexOption.IGNORE_CASE) to "Mantente a la derecha",
        Regex("\\broundabout\\b", RegexOption.IGNORE_CASE) to "rotonda",
        Regex("\\bsvolta a sinistra\\b", RegexOption.IGNORE_CASE) to "Gira a la izquierda",
        Regex("\\bsvolta a destra\\b", RegexOption.IGNORE_CASE) to "Gira a la derecha",
        Regex("\\bcontinua dritto\\b", RegexOption.IGNORE_CASE) to "Continúa recto",
        Regex("\\bmantieni la sinistra\\b", RegexOption.IGNORE_CASE) to "Mantente a la izquierda",
        Regex("\\bmantieni la destra\\b", RegexOption.IGNORE_CASE) to "Mantente a la derecha"
    )

    suspend fun getRoute(
        start: GeoPoint,
        end: GeoPoint,
        routeId: Long = 0L,
        savedCheckpoints: List<Checkpoint> = emptyList()
    ): RouteFetchResult = withContext(Dispatchers.IO) {
        if (!hasInternetConnection()) {
            return@withContext RouteFetchResult.Failure(
                "Sin conexión. No se mostrará una ruta aproximada porque no sería segura."
            )
        }

        val routeUrl = buildOsrmUrl(start = start, end = end)
        val rawJson = requestRouteJsonWithRetry(routeUrl)
            ?: return@withContext RouteFetchResult.Failure(CONNECTION_ERROR_MESSAGE)

        try {
            val parsed = parseRoute(rawJson, routeId, savedCheckpoints)
            RouteFetchResult.Success(parsed)
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando JSON OSRM: ${e.javaClass.simpleName}: ${e.message}", e)
            RouteFetchResult.Failure("No se ha encontrado una ruta peatonal verificable para este trayecto.")
        }
    }

    suspend fun calculateRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        routeId: Long = 0L,
        savedCheckpoints: List<Checkpoint> = emptyList()
    ): RouteResult? = withContext(Dispatchers.IO) {
        when (
            val result = getRoute(
                start = GeoPoint(startLat, startLon),
                end = GeoPoint(endLat, endLon),
                routeId = routeId,
                savedCheckpoints = savedCheckpoints
            )
        ) {
            is RouteFetchResult.Success -> result.routeResult
            is RouteFetchResult.Failure -> {
                Log.e(TAG, result.userMessage)
                null
            }
        }
    }

    private fun buildOsrmUrl(start: GeoPoint, end: GeoPoint): String {
        val startLon = String.format(Locale.US, "%.6f", start.longitude)
        val startLat = String.format(Locale.US, "%.6f", start.latitude)
        val endLon = String.format(Locale.US, "%.6f", end.longitude)
        val endLat = String.format(Locale.US, "%.6f", end.latitude)

        return OSRM_BASE_URL +
            "$startLon,$startLat;$endLon,$endLat" +
            "?steps=true&geometries=geojson&overview=full&alternatives=3"
    }

    private suspend fun requestRouteJsonWithRetry(url: String): String? {
        repeat(MAX_ATTEMPTS) { attempt ->
            val currentAttempt = attempt + 1
            try {
                val httpResult = executeGet(url)
                if (httpResult.statusCode == HttpURLConnection.HTTP_OK) {
                    return httpResult.responseBody
                }

                Log.e(
                    TAG,
                    "OSRM HTTP ${httpResult.statusCode}. Error body: ${httpResult.errorBody.ifBlank { "<vacío>" }}"
                )
            } catch (e: UnknownHostException) {
                Log.e(TAG, "OSRM error de red (${e.javaClass.simpleName}): ${e.message}", e)
            } catch (e: SocketTimeoutException) {
                Log.e(TAG, "OSRM timeout (${e.javaClass.simpleName}): ${e.message}", e)
            } catch (e: SSLHandshakeException) {
                Log.e(TAG, "OSRM SSL (${e.javaClass.simpleName}): ${e.message}", e)
            } catch (e: IOException) {
                Log.e(TAG, "OSRM IO (${e.javaClass.simpleName}): ${e.message}", e)
            } catch (e: Exception) {
                Log.e(TAG, "OSRM error inesperado (${e.javaClass.simpleName}): ${e.message}", e)
            }

            if (currentAttempt < MAX_ATTEMPTS) {
                delay(RETRY_DELAY_MS)
            }
        }

        return null
    }

    private fun executeGet(url: String): HttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doInput = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val statusCode = connection.responseCode
            val responseBody = connection.inputStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResult(statusCode, responseBody, errorBody)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun parseRoute(
        responseJson: String,
        routeId: Long,
        savedCheckpoints: List<Checkpoint>
    ): RouteResult {
        val root = JSONObject(responseJson)
        val code = root.optString("code", "")
        if (code != "Ok") {
            val message = root.optString("message", "respuesta inválida")
            throw IllegalStateException("OSRM code=$code message=$message")
        }

        val routes = root.optJSONArray("routes") ?: JSONArray()
        if (routes.length() == 0) {
            throw IllegalStateException("OSRM sin rutas en la respuesta")
        }

        val routeCandidates = (0 until routes.length()).map { routes.getJSONObject(it) }
        routeCandidates.forEach { ensureWalkingProfile(it) }

        val allGeometry = routeCandidates.flatMap { candidate ->
            candidate.optJSONObject("geometry")?.let(::parseGeometryPoints).orEmpty()
        }
        val mappedFeatures = accessibilityProvider.getFeatures(allGeometry)
        val route = routeCandidates.minByOrNull { candidate ->
            scoreRoute(candidate, mappedFeatures)
        } ?: throw IllegalStateException("OSRM sin candidatos válidos")
        val totalDistanceMeters = route.optDouble("distance", 0.0)
        val totalDurationSeconds = route.optDouble("duration", 0.0)

        val geometry = route.optJSONObject("geometry")
            ?: throw IllegalStateException("OSRM sin geometry")
        val routeGeometry = parseGeometryPoints(geometry)
        if (routeGeometry.isEmpty()) {
            throw IllegalStateException("OSRM geometry vacía")
        }

        val stepData = parseSteps(route)

        val road = Road().apply {
            mStatus = Road.STATUS_OK
            mLength = totalDistanceMeters / 1000.0
            mDuration = totalDurationSeconds
            mRouteHigh = ArrayList(routeGeometry)
            mNodes = ArrayList(stepData.nodes)
        }

        val polyline = Polyline().apply {
            setPoints(routeGeometry)
            outlinePaint.color = 0xFF0099FF.toInt()
            outlinePaint.strokeWidth = 15f
        }

        val calculatedCheckpoints = buildCheckpointsFromNodes(road.mNodes, routeId)
        val matchedFeatures = mappedFeatures?.filter { feature ->
            RouteGeometry.project(
                feature.coordinate,
                routeGeometry.map { GeoCoordinate(it.latitude, it.longitude) }
            )?.distanceToRouteMeters?.let { it <= CHECKPOINT_MERGE_THRESHOLD_METERS + 4.0 } == true
        }.orEmpty()
        val accessibilityCheckpoints = matchedFeatures.map { feature ->
            when (feature.kind) {
                AccessibilityFeature.Kind.CROSSING -> Checkpoint(
                    routeId = routeId,
                    latitude = feature.coordinate.latitude,
                    longitude = feature.coordinate.longitude,
                    description = buildCrossingDescription(feature),
                    orderIndex = 0,
                    type = CheckpointType.ZEBRA_CROSSING
                )
                AccessibilityFeature.Kind.STEPS -> Checkpoint(
                    routeId = routeId,
                    latitude = feature.coordinate.latitude,
                    longitude = feature.coordinate.longitude,
                    description = "Precaución: escaleras en la ruta",
                    orderIndex = 0,
                    type = CheckpointType.STAIRS
                )
            }
        }
        val mergedCheckpoints = mergeCheckpointsWithRoute(
            calculatedCheckpoints + accessibilityCheckpoints,
            savedCheckpoints,
            routeGeometry
        )
        val accessibilitySummary = AccessibilitySummary(
            dataAvailable = mappedFeatures != null,
            crossings = matchedFeatures.count { it.kind == AccessibilityFeature.Kind.CROSSING },
            accessibleCrossings = matchedFeatures.count {
                it.kind == AccessibilityFeature.Kind.CROSSING &&
                    (it.tactilePaving || it.audibleSignals || it.loweredKerb)
            },
            steps = matchedFeatures.count { it.kind == AccessibilityFeature.Kind.STEPS }
        )

        return RouteResult(
            checkpoints = mergedCheckpoints,
            totalDistance = totalDistanceMeters,
            totalDuration = totalDurationSeconds,
            road = road,
            polyline = polyline,
            instructions = stepData.instructions,
            source = RouteSource.PEDESTRIAN_OSRM,
            accessibility = accessibilitySummary
        )
    }

    private data class StepParseResult(
        val nodes: List<RoadNode>,
        val instructions: List<TurnInstruction>
    )

    private fun ensureWalkingProfile(route: JSONObject) {
        val modes = mutableListOf<String>()
        val legs = route.optJSONArray("legs") ?: JSONArray()
        for (legIndex in 0 until legs.length()) {
            val steps = legs.optJSONObject(legIndex)?.optJSONArray("steps") ?: continue
            for (stepIndex in 0 until steps.length()) {
                steps.optJSONObject(stepIndex)?.optString("mode")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(modes::add)
            }
        }
        if (modes.isEmpty() || modes.any { it != "walking" }) {
            throw IllegalStateException("El servidor no ha confirmado un perfil peatonal")
        }
    }

    private fun scoreRoute(
        route: JSONObject,
        features: List<AccessibilityFeature>?
    ): Double {
        val points = route.optJSONObject("geometry")?.let(::parseGeometryPoints).orEmpty()
        val geometry = points.map { GeoCoordinate(it.latitude, it.longitude) }
        val nearFeatures = features.orEmpty().filter { feature ->
            RouteGeometry.project(feature.coordinate, geometry)
                ?.distanceToRouteMeters?.let { it <= CHECKPOINT_MERGE_THRESHOLD_METERS + 4.0 } == true
        }
        val stairsPenalty = nearFeatures.count { it.kind == AccessibilityFeature.Kind.STEPS } * 1_500.0
        val crossingPenalty = nearFeatures.count {
            it.kind == AccessibilityFeature.Kind.CROSSING &&
                !it.tactilePaving && !it.audibleSignals && !it.loweredKerb
        } * 20.0
        val accessibilityReward = nearFeatures.count {
            it.kind == AccessibilityFeature.Kind.CROSSING &&
                (it.tactilePaving || it.audibleSignals || it.loweredKerb)
        } * 12.0
        return route.optDouble("distance", Double.MAX_VALUE) + stairsPenalty + crossingPenalty - accessibilityReward
    }

    private fun buildCrossingDescription(feature: AccessibilityFeature): String {
        val details = buildList {
            if (feature.crossingType == "traffic_signals") add("con semáforo")
            if (feature.tactilePaving) add("con pavimento táctil")
            if (feature.audibleSignals) add("con señal acústica")
            if (feature.loweredKerb) add("con bordillo rebajado")
        }
        return if (details.isEmpty()) "Paso de peatones" else "Paso de peatones ${details.joinToString(", ")}"
    }

    private fun parseGeometryPoints(geometry: JSONObject): List<GeoPoint> {
        val coordinates = geometry.optJSONArray("coordinates") ?: JSONArray()
        val points = ArrayList<GeoPoint>(coordinates.length())

        for (i in 0 until coordinates.length()) {
            val point = coordinates.optJSONArray(i) ?: continue
            if (point.length() < 2) continue

            val lon = point.optDouble(0)
            val lat = point.optDouble(1)
            points.add(GeoPoint(lat, lon))
        }

        return points
    }

    private fun parseSteps(route: JSONObject): StepParseResult {
        val nodes = mutableListOf<RoadNode>()
        val instructions = mutableListOf<TurnInstruction>()

        val legs = route.optJSONArray("legs") ?: JSONArray()
        for (legIndex in 0 until legs.length()) {
            val leg = legs.optJSONObject(legIndex) ?: continue
            val steps = leg.optJSONArray("steps") ?: JSONArray()

            for (stepIndex in 0 until steps.length()) {
                val step = steps.optJSONObject(stepIndex) ?: continue
                val maneuver = step.optJSONObject("maneuver") ?: JSONObject()
                val locationArray = maneuver.optJSONArray("location") ?: JSONArray()
                if (locationArray.length() < 2) continue

                val lon = locationArray.optDouble(0)
                val lat = locationArray.optDouble(1)
                val distanceMeters = step.optDouble("distance", 0.0)
                val durationSeconds = step.optDouble("duration", 0.0)

                val instruction = buildSpanishInstruction(step, maneuver)
                val maneuverType = mapManeuverToType(maneuver)

                val node = RoadNode().apply {
                    mLocation = GeoPoint(lat, lon)
                    mLength = distanceMeters / 1000.0
                    mDuration = durationSeconds
                    mManeuverType = maneuverType
                    mInstructions = instruction
                }
                nodes.add(node)

                instructions.add(
                    TurnInstruction(
                        distance = distanceMeters,
                        duration = durationSeconds,
                        instruction = instruction,
                        maneuverType = maneuverType,
                        latitude = lat,
                        longitude = lon
                    )
                )
            }
        }

        return StepParseResult(nodes = nodes, instructions = instructions)
    }

    private fun buildSpanishInstruction(step: JSONObject, maneuver: JSONObject): String {
        val rawInstruction = step.optString("instruction", "")
        if (rawInstruction.isNotBlank()) {
            return translateInstructionToSpanish(rawInstruction)
        }

        val maneuverType = maneuver.optString("type", "continue")
        val modifier = maneuver.optString("modifier", "")
        val roadName = step.optString("name", "")

        val baseInstruction = when (maneuverType.lowercase(Locale.getDefault())) {
            "depart" -> "Comienza"
            "arrive" -> "Has llegado a tu destino"
            "turn" -> when (modifier.lowercase(Locale.getDefault())) {
                "left" -> "Gira a la izquierda"
                "right" -> "Gira a la derecha"
                "sharp left" -> "Giro pronunciado a la izquierda"
                "sharp right" -> "Giro pronunciado a la derecha"
                "slight left" -> "Leve giro a la izquierda"
                "slight right" -> "Leve giro a la derecha"
                else -> "Gira"
            }
            "new name", "continue" -> "Continúa recto"
            "roundabout", "rotary" -> "Entra en la rotonda"
            "merge" -> "Incorpórate"
            "fork" -> when (modifier.lowercase(Locale.getDefault())) {
                "left" -> "Mantente a la izquierda"
                "right" -> "Mantente a la derecha"
                else -> "Sigue la bifurcación"
            }
            "end of road" -> "Al final de la vía, gira"
            else -> "Continúa"
        }

        val normalized = if (roadName.isNotBlank() && roadName != "null") {
            "$baseInstruction por $roadName"
        } else {
            baseInstruction
        }

        return translateInstructionToSpanish(normalized)
    }

    private fun mapManeuverToType(maneuver: JSONObject): Int {
        val type = maneuver.optString("type", "").lowercase(Locale.getDefault())
        val modifier = maneuver.optString("modifier", "").lowercase(Locale.getDefault())

        return when {
            type == "turn" && modifier.contains("left") -> 1
            type == "turn" && modifier.contains("right") -> 2
            type == "uturn" || modifier == "uturn" -> 3
            type == "roundabout" || type == "rotary" -> 7
            type == "fork" || type == "merge" || type == "end of road" -> 4
            else -> 0
        }
    }

    private fun buildCheckpointsFromNodes(nodes: List<RoadNode>, routeId: Long): List<Checkpoint> {
        if (nodes.isEmpty()) return emptyList()

        val checkpoints = mutableListOf<Checkpoint>()
        var previousBearing: Float? = null

        nodes.forEachIndexed { index, node ->
            val isSignificant = node.mLength > 0.015 ||
                node.mManeuverType != 0 ||
                index == 0 ||
                index == nodes.lastIndex

            if (!isSignificant) return@forEachIndexed

            val currentBearing = if (index < nodes.lastIndex) {
                val nextNode = nodes[index + 1]
                calculateBearing(
                    node.mLocation.latitude,
                    node.mLocation.longitude,
                    nextNode.mLocation.latitude,
                    nextNode.mLocation.longitude
                )
            } else {
                null
            }

            val bearingChange = if (previousBearing != null && currentBearing != null) {
                calculateBearingDifference(previousBearing ?: 0f, currentBearing)
            } else {
                0f
            }

            val type = determineCheckpointType(
                maneuverType = node.mManeuverType,
                instruction = node.mInstructions ?: "",
                bearingChange = bearingChange
            )

            checkpoints.add(
                Checkpoint(
                    routeId = routeId,
                    latitude = node.mLocation.latitude,
                    longitude = node.mLocation.longitude,
                    description = node.mInstructions ?: "Continúa recto",
                    orderIndex = checkpoints.size,
                    type = type
                )
            )

            previousBearing = currentBearing
        }

        return checkpoints
    }

    private fun translateInstructionToSpanish(rawInstruction: String?): String {
        val raw = rawInstruction?.trim().orEmpty()
        if (raw.isBlank()) return "Continúa recto"

        var translated = raw
        translationMap.forEach { (pattern, replacement) ->
            translated = translated.replace(pattern, replacement)
        }

        val normalized = translated.trim()
        if (normalized.isBlank()) return "Continúa recto"

        return normalized.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
    }

    private fun determineCheckpointType(
        maneuverType: Int,
        instruction: String,
        bearingChange: Float
    ): CheckpointType {
        val text = instruction.lowercase(Locale.getDefault())

        if (text.contains("cross") || text.contains("zebra") || text.contains("cruce") || text.contains("cebra")) {
            return CheckpointType.ZEBRA_CROSSING
        }

        if (text.contains("roundabout") || text.contains("rotonda")) {
            return CheckpointType.ALERT
        }

        if (text.contains("turn") || text.contains("gira") || text.contains("gire")) {
            return CheckpointType.TURN
        }

        if (abs(bearingChange) > 45f) {
            return CheckpointType.CORNER
        }

        return when (maneuverType) {
            1, 2, 3 -> CheckpointType.TURN
            4 -> CheckpointType.CORNER
            7 -> CheckpointType.ALERT
            else -> CheckpointType.WAYPOINT
        }
    }

    private fun mergeCheckpointsWithRoute(
        calculatedCheckpoints: List<Checkpoint>,
        savedCheckpoints: List<Checkpoint>,
        routeGeometry: List<GeoPoint>
    ): List<Checkpoint> {
        val geometry = routeGeometry.map { GeoCoordinate(it.latitude, it.longitude) }
        val routeId = calculatedCheckpoints.firstOrNull()?.routeId
            ?: savedCheckpoints.firstOrNull()?.routeId ?: 0L
        val all = calculatedCheckpoints + savedCheckpoints.filter { checkpoint ->
            RouteGeometry.project(
                GeoCoordinate(checkpoint.latitude, checkpoint.longitude),
                geometry
            )?.distanceToRouteMeters?.let { it <= CHECKPOINT_MERGE_THRESHOLD_METERS } == true
        }.map { it.copy(routeId = routeId) }

        return all
            .distinctBy { checkpoint ->
                val projection = RouteGeometry.project(
                    GeoCoordinate(checkpoint.latitude, checkpoint.longitude), geometry
                )
                val bucket = ((projection?.progressMeters ?: 0.0) / 6.0).toInt()
                "$bucket:${checkpoint.type}"
            }
            .sortedBy { checkpoint ->
                RouteGeometry.project(
                    GeoCoordinate(checkpoint.latitude, checkpoint.longitude), geometry
                )?.progressMeters ?: Double.MAX_VALUE
            }
            .mapIndexed { index, checkpoint -> checkpoint.copy(orderIndex = index) }
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)

        val y = kotlin.math.sin(dLon) * kotlin.math.cos(lat2Rad)
        val x = kotlin.math.cos(lat1Rad) * kotlin.math.sin(lat2Rad) -
            kotlin.math.sin(lat1Rad) * kotlin.math.cos(lat2Rad) * kotlin.math.cos(dLon)

        val bearing = Math.toDegrees(kotlin.math.atan2(y, x))
        return ((bearing + 360) % 360).toFloat()
    }

    private fun calculateBearingDifference(bearing1: Float, bearing2: Float): Float {
        var diff = bearing2 - bearing1
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360
        return diff
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0].toDouble()
    }

    private fun hasInternetConnection(): Boolean {
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
