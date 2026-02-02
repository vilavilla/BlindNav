package com.blindnav.app.data.osm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * NominatimGeocoder - Búsqueda de direcciones usando Nominatim (OpenStreetMap)
 * 
 * API pública y gratuita: https://nominatim.openstreetmap.org
 * NO requiere API Key
 * 
 * Uso:
 * ```kotlin
 * val result = NominatimGeocoder.search("Farmacia Barcelona")
 * if (result != null) {
 *     val (lat, lon, displayName) = result
 *     // Usar coordenadas
 * }
 * ```
 */
object NominatimGeocoder {

    private const val TAG = "NominatimGeocoder"
    private const val BASE_URL = "https://nominatim.openstreetmap.org/search"
    private const val USER_AGENT = "BlindNav/1.0 (Android Accessibility App)"

    /**
     * Resultado de búsqueda Nominatim
     */
    data class SearchResult(
        val latitude: Double,
        val longitude: Double,
        val displayName: String,
        val type: String,
        val importance: Double
    )

    /**
     * Buscar una dirección o lugar
     * 
     * ✓ MEJORADO: Búsqueda LOCAL inteligente
     * - Si se proporcionan userLat/userLon, crea un viewbox de ±0.1° (~10km)
     * - Añade bounded=1 para PRIORIZAR resultados locales
     * - Evita resultados de todo el mundo - Prioriza la ciudad del usuario
     * 
     * @param query Texto de búsqueda (ej: "Farmacia", "Cítara")
     * @param limit Número máximo de resultados (por defecto 5)
     * @param userLat Latitud del usuario (opcional - para búsqueda local)
     * @param userLon Longitud del usuario (opcional - para búsqueda local)
     * @return Lista de resultados ordenados por relevancia local
     */
    suspend fun search(
        query: String, 
        limit: Int = 5,
        userLat: Double? = null,
        userLon: Double? = null
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "========== NOMINATIM SEARCH ==========")
            Log.d(TAG, "Query: \"$query\"")
            
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            
            // ✓ Construir URL con búsqueda LOCAL si tenemos GPS
            val urlString = buildString {
                append("$BASE_URL?q=$encodedQuery&format=json&limit=$limit&addressdetails=1")
                
                // ✓ Añadir VIEWBOX para búsqueda local (±0.1° = ~10km)
                if (userLat != null && userLon != null) {
                    val minLat = userLat - 0.1
                    val maxLat = userLat + 0.1
                    val minLon = userLon - 0.1
                    val maxLon = userLon + 0.1
                    
                    // viewbox formato: left,top,right,bottom (lon,lat,lon,lat)
                    append("&viewbox=$minLon,$maxLat,$maxLon,$minLat")
                    
                    // ✓ bounded=1 FUERZA resultados dentro del viewbox
                    append("&bounded=1")
                    
                    Log.d(TAG, "✓ Búsqueda LOCAL: Viewbox centrado en ($userLat, $userLon) ± 10km")
                } else {
                    Log.d(TAG, "⚠️ Búsqueda GLOBAL (sin GPS) - Resultados pueden ser lejanos")
                }
            }
            
            Log.d(TAG, "URL: $urlString")
            
            val url = URL(urlString)
            val connection = url.openConnection() as HttpURLConnection
            
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = 10000
                readTimeout = 10000
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Error HTTP: $responseCode")
                return@withContext emptyList()
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            parseResults(response, userLat, userLon)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error buscando en Nominatim", e)
            emptyList()
        }
    }

    /**
     * Parsear respuesta JSON de Nominatim
     * 
     * ✓ MEJORADO: Ordena por distancia al usuario si se proporciona ubicación
     */
    private fun parseResults(
        jsonResponse: String,
        userLat: Double? = null,
        userLon: Double? = null
    ): List<SearchResult> {
        return try {
            val jsonArray = org.json.JSONArray(jsonResponse)
            val results = mutableListOf<SearchResult>()

            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.getJSONObject(i)
                
                results.add(
                    SearchResult(
                        latitude = item.getString("lat").toDouble(),
                        longitude = item.getString("lon").toDouble(),
                        displayName = item.getString("display_name"),
                        type = item.optString("type", "unknown"),
                        importance = item.optDouble("importance", 0.0)
                    )
                )
            }

            // ✓ Ordenar por DISTANCIA AL USUARIO (si disponible) o por importancia
            if (userLat != null && userLon != null) {
                results.sortedBy { result ->
                    // Calcular distancia euclidiana simple (suficiente para <100km)
                    val dLat = result.latitude - userLat
                    val dLon = result.longitude - userLon
                    Math.sqrt(dLat * dLat + dLon * dLon)
                }.also {
                    Log.d(TAG, "✓ Resultados ordenados por DISTANCIA al usuario")
                }
            } else {
                results.sortedByDescending { it.importance }.also {
                    Log.d(TAG, "✓ Resultados ordenados por IMPORTANCIA (sin GPS)")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parseando JSON de Nominatim", e)
            emptyList()
        }.also { results ->
            // ✓ Log detallado de resultados
            Log.d(TAG, "Encontrados ${results.size} resultados:")
            results.take(3).forEachIndexed { index, result ->
                Log.d(TAG, "  [${index + 1}] ${result.displayName}")
            }
        }
    }

    /**
     * Búsqueda inversa: De coordenadas a dirección
     * 
     * @param lat Latitud
     * @param lon Longitud
     * @return Dirección legible o null
     */
    suspend fun reverseGeocode(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lon")
            val connection = url.openConnection() as HttpURLConnection
            
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
            }

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()

            val json = JSONObject(response)
            json.optString("display_name")

        } catch (e: Exception) {
            Log.e(TAG, "Error en reverse geocode", e)
            null
        }
    }
}
