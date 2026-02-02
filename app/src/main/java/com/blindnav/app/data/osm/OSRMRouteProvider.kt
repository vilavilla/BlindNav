package com.blindnav.app.data.osm

import android.content.Context
import android.util.Log
import com.blindnav.app.data.db.entity.Checkpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.bonuspack.routing.OSRMRoadManager
import org.osmdroid.bonuspack.routing.Road
import org.osmdroid.bonuspack.routing.RoadManager
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Polyline
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * OSRMRouteProvider - Navegación Turn-by-Turn con OSRMBonusPack RoadManager
 * 
 * MEJORAS CRÍTICAS:
 * - Usa OSRMRoadManager con modo PEATÓN (MEAN_BY_FOOT)
 * - Extrae road.mNodes para instrucciones reales: "Gira a la izquierda en 50m"
 * - Genera Polyline exacta que sigue las calles/aceras
 * - Convierte mNodes en Checkpoints para navegación audio
 */
class OSRMRouteProvider(private val context: Context) {

    companion object {
        private const val TAG = "OSRMRouteProvider"
        private const val USER_AGENT = "BlindNav/1.0 (Android Navigation App)"
        
        // Deprecated: Solo para compatibilidad con código viejo
        @Deprecated("Usar RoadManager en lugar de llamadas HTTP manuales")
        private const val BASE_URL = "http://router.project-osrm.org/route/v1/foot"
    }
    
    // RoadManager configurado para PEATÓN
    private val roadManager: OSRMRoadManager by lazy {
        OSRMRoadManager(context, USER_AGENT).apply {
            // ✓ CRÍTICO: Configurar modo PEATÓN para rutas por aceras/calles
            setMean(OSRMRoadManager.MEAN_BY_FOOT)
        }
    }

    /**
     * Resultado de cálculo de ruta con Turn-by-Turn
     */
    data class RouteResult(
        val checkpoints: List<Checkpoint>,
        val totalDistance: Double, // metros
        val totalDuration: Double, // segundos
        val road: Road, // Objeto Road de OSRMBonusPack con mNodes
        val polyline: Polyline, // Polyline lista para dibujar en MapView
        val instructions: List<TurnInstruction> // Instrucciones de navegación
    )
    
    /**
     * Instrucción de navegación Turn-by-Turn
     */
    data class TurnInstruction(
        val distance: Double, // metros hasta esta instrucción
        val duration: Double, // segundos
        val instruction: String, // "Gira a la izquierda", "Continúa recto"
        val maneuverType: Int, // Tipo de maniobra (ver RoadNode constants)
        val latitude: Double,
        val longitude: Double
    )

    /**
     * Calcular ruta Turn-by-Turn entre dos puntos
     * 
     * USA OSRMRoadManager configurado para PEATÓN (MEAN_BY_FOOT)
     * Extrae mNodes para instrucciones reales de giro
     * 
     * @param startLat Latitud de inicio
     * @param startLon Longitud de inicio
     * @param endLat Latitud de destino
     * @param endLon Longitud de destino
     * @param routeId ID de la ruta para los checkpoints
     * @return RouteResult con Turn-by-Turn instructions
     */
    suspend fun calculateRoute(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        routeId: Long = 0L
    ): RouteResult? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "========== OSRM TURN-BY-TURN ===========")
            Log.d(TAG, "Inicio: ($startLat, $startLon)")
            Log.d(TAG, "Destino: ($endLat, $endLon)")
            Log.d(TAG, "Modo: PEATÓN (aceras/calles)")

            // ✓ Crear waypoints (GeoPoint)
            val startPoint = GeoPoint(startLat, startLon)
            val endPoint = GeoPoint(endLat, endLon)
            val waypoints = arrayListOf(startPoint, endPoint)

            // ✓ Calcular ruta con RoadManager (ejecuta en background)
            val road = roadManager.getRoad(waypoints)
            
            if (road.mStatus != Road.STATUS_OK) {
                Log.e(TAG, "❌ Error OSRM: ${road.mStatus}")
                return@withContext null
            }

            Log.d(TAG, "✓ Ruta calculada: ${road.mLength}km, ${road.mDuration/60}min")
            Log.d(TAG, "✓ Nodos de navegación: ${road.mNodes.size}")
            Log.d(TAG, "✓ Puntos de ruta: ${road.mRouteHigh.size}")

            // ✓ Extraer instrucciones Turn-by-Turn de mNodes
            val instructions = extractTurnInstructions(road)
            
            // ✓ Convertir mNodes a Checkpoints para navegación
            val checkpoints = convertNodesToCheckpoints(road, routeId)
            
            // ✓ Crear Polyline para dibujar en mapa
            val polyline = RoadManager.buildRoadOverlay(road).apply {
                // Personalizar visualización
                outlinePaint.color = 0xFF2196F3.toInt() // Azul brillante
                outlinePaint.strokeWidth = 12f // Línea gruesa visible
            }

            RouteResult(
                checkpoints = checkpoints,
                totalDistance = road.mLength * 1000, // km -> m
                totalDuration = road.mDuration, // segundos
                road = road,
                polyline = polyline,
                instructions = instructions
            )

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error calculando ruta OSRM", e)
            null
        }
    }

    /**
     * Extraer instrucciones Turn-by-Turn de road.mNodes
     * 
     * Los mNodes contienen:
     * - mManeuverType: Tipo de giro (ver RoadNode.MANEUVER_*)
     * - mInstructions: Texto legible "Gira a la izquierda"
     * - mLength: Distancia hasta el siguiente nodo
     * - mDuration: Tiempo estimado
     */
    private fun extractTurnInstructions(road: Road): List<TurnInstruction> {
        val instructions = mutableListOf<TurnInstruction>()
        
        road.mNodes.forEachIndexed { index, node ->
            // Filtrar nodos significativos (no incluir "continúa recto" cada 5m)
            if (node.mLength > 20.0 || node.mManeuverType != 0) {
                val instruction = TurnInstruction(
                    distance = node.mLength * 1000, // km -> m
                    duration = node.mDuration,
                    instruction = node.mInstructions ?: "Continúa",
                    maneuverType = node.mManeuverType,
                    latitude = node.mLocation.latitude,
                    longitude = node.mLocation.longitude
                )
                instructions.add(instruction)
                
                Log.d(TAG, "  [${index}] ${instruction.instruction} - ${instruction.distance.toInt()}m")
            }
        }
        
        return instructions
    }
    
    /**
     * Convertir road.mNodes a Checkpoints para sistema de navegación
     */
    private fun convertNodesToCheckpoints(road: Road, routeId: Long): List<Checkpoint> {
        val checkpoints = mutableListOf<Checkpoint>()
        
        road.mNodes.forEachIndexed { index, node ->
            // Solo crear checkpoint para nodos significativos
            if (node.mLength > 15.0 || node.mManeuverType != 0 || index == 0 || index == road.mNodes.size - 1) {
                val checkpoint = Checkpoint(
                    routeId = routeId,
                    orderIndex = checkpoints.size,
                    latitude = node.mLocation.latitude,
                    longitude = node.mLocation.longitude,
                    description = node.mInstructions ?: "Punto de navegación"
                )
                checkpoints.add(checkpoint)
            }
        }
        
        Log.d(TAG, "✓ Generados ${checkpoints.size} checkpoints desde mNodes")
        return checkpoints
    }

    /**
     * [DEPRECATED] Parsear respuesta OSRM manual - Usar RoadManager en su lugar
     */
    @Deprecated("Usar calculateRoute() con RoadManager")
    private fun parseRoute(jsonResponse: String, routeId: Long): RouteResult? {
        return try {
            val json = JSONObject(jsonResponse)

            if (json.getString("code") != "Ok") {
                Log.e(TAG, "OSRM error: ${json.optString("message")}")
                return null
            }

            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) {
                Log.e(TAG, "No se encontró ninguna ruta")
                return null
            }

            val route = routes.getJSONObject(0)
            val legs = route.getJSONArray("legs")
            val checkpoints = mutableListOf<Checkpoint>()

            var orderIndex = 0
            var accumulatedDistance = 0.0

            // Agregar punto de inicio como primer checkpoint
            if (legs.length() > 0) {
                val firstLeg = legs.getJSONObject(0)
                val firstStep = firstLeg.getJSONArray("steps").getJSONObject(0)
                val startCoords = firstStep.getJSONObject("maneuver").getJSONArray("location")

                checkpoints.add(
                    Checkpoint(
                        routeId = routeId,
                        orderIndex = orderIndex++,
                        latitude = startCoords.getDouble(1), // lat
                        longitude = startCoords.getDouble(0), // lon
                        description = "Inicio de la ruta"
                    )
                )
            }

            // Procesar cada leg (segmento) de la ruta
            for (i in 0 until legs.length()) {
                val leg = legs.getJSONObject(i)
                val steps = leg.getJSONArray("steps")

                // Cada step es un punto de navegación importante
                for (j in 0 until steps.length()) {
                    val step = steps.getJSONObject(j)
                    val maneuver = step.getJSONObject("maneuver")
                    val location = maneuver.getJSONArray("location")
                    val distance = step.getDouble("distance")
                    val instruction = maneuver.optString("instruction", "Continúa")

                    // Solo agregar si es un punto significativo (giro, cambio de calle)
                    if (distance > 10.0) { // Mínimo 10 metros entre checkpoints
                        checkpoints.add(
                            Checkpoint(
                                routeId = routeId,
                                orderIndex = orderIndex++,
                                latitude = location.getDouble(1),
                                longitude = location.getDouble(0),
                                description = instruction
                            )
                        )
                        accumulatedDistance += distance.toDouble()
                    }
                }
            }

            // Agregar punto final
            if (legs.length() > 0) {
                val lastLeg = legs.getJSONObject(legs.length() - 1)
                val lastSteps = lastLeg.getJSONArray("steps")
                val lastStep = lastSteps.getJSONObject(lastSteps.length() - 1)
                val endLocation = lastStep.getJSONObject("maneuver").getJSONArray("location")

                checkpoints.add(
                    Checkpoint(
                        routeId = routeId,
                        orderIndex = orderIndex,
                        latitude = endLocation.getDouble(1),
                        longitude = endLocation.getDouble(0),
                        description = "Destino alcanzado"
                    )
                )
            }

            Log.d(TAG, "Ruta calculada: ${checkpoints.size} checkpoints, ${accumulatedDistance}m")

            RouteResult(
                checkpoints = checkpoints,
                totalDistance = route.getDouble("distance"),
                totalDuration = route.getDouble("duration"),
                road = Road(), // Deprecated - usar nueva implementación
                polyline = Polyline(), // Deprecated
                instructions = emptyList() // Deprecated
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error parseando ruta OSRM", e)
            null
        }
    }

    /**
     * [DEPRECATED] Calcular ruta con múltiples waypoints
     * Usa RoadManager.getRoad() en su lugar
     */
    @Deprecated("Usar calculateRoute() con RoadManager")
    suspend fun calculateRouteWithWaypoints(
        waypoints: List<Pair<Double, Double>>, // (lat, lon)
        routeId: Long = 0L
    ): RouteResult? = withContext(Dispatchers.IO) {
        try {
            if (waypoints.size < 2) {
                Log.e(TAG, "Se necesitan al menos 2 waypoints")
                return@withContext null
            }

            // Construir coordenadas: lon1,lat1;lon2,lat2;...
            val coords = waypoints.joinToString(";") { "${it.second},${it.first}" }
            val urlString = "$BASE_URL/$coords?overview=full&geometries=polyline&steps=true"

            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            parseRoute(response, routeId)

        } catch (e: Exception) {
            Log.e(TAG, "Error calculando ruta con waypoints", e)
            null
        }
    }
}
