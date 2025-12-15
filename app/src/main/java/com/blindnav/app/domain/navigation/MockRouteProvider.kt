package com.blindnav.app.domain.navigation

import com.blindnav.app.domain.model.NavigationRoute
import com.blindnav.app.domain.model.RoutePoint

/**
 * MockRouteProvider - Proveedor de rutas simuladas para desarrollo
 * 
 * En producción, esto se reemplazaría por una API real como:
 * - Google Directions API
 * - OpenRouteService
 * - Mapbox Directions API
 * 
 * Por ahora, simula rutas a destinos conocidos con waypoints fijos.
 */
object MockRouteProvider {

    // Coordenadas de ejemplo (Barcelona)
    private val ORIGIN_LAT = 41.3851
    private val ORIGIN_LON = 2.1734

    /**
     * Destinos conocidos con rutas simuladas.
     */
    private val knownDestinations = mapOf(
        "farmacia" to createFarmaciaRoute(),
        "mercadona" to createMercadonaRoute(),
        "supermercado" to createMercadonaRoute(),
        "parada de bus" to createBusStopRoute(),
        "bus" to createBusStopRoute(),
        "parque" to createParkRoute(),
        "banco" to createBankRoute(),
        "hospital" to createHospitalRoute(),
        "casa" to createHomeRoute()
    )

    /**
     * Busca una ruta para el destino dado.
     * Hace matching fuzzy con los destinos conocidos.
     */
    fun findRoute(destination: String): NavigationRoute? {
        val normalizedDestination = destination.lowercase().trim()
        
        // Buscar coincidencia exacta primero
        knownDestinations[normalizedDestination]?.let { return it }
        
        // Buscar coincidencia parcial
        for ((key, route) in knownDestinations) {
            if (normalizedDestination.contains(key) || key.contains(normalizedDestination)) {
                return route.copy(destination = destination)
            }
        }
        
        // Si no hay coincidencia, generar ruta genérica
        return createGenericRoute(destination)
    }

    /**
     * Lista los destinos disponibles.
     */
    fun getAvailableDestinations(): List<String> {
        return listOf(
            "Farmacia",
            "Mercadona / Supermercado",
            "Parada de bus",
            "Parque",
            "Banco",
            "Hospital",
            "Casa"
        )
    }

    // ============================================
    // RUTAS SIMULADAS
    // ============================================

    private fun createFarmaciaRoute(): NavigationRoute {
        return NavigationRoute(
            destination = "Farmacia",
            waypoints = listOf(
                RoutePoint(
                    latitude = ORIGIN_LAT + 0.0001,
                    longitude = ORIGIN_LON + 0.0001,
                    name = "Primer tramo",
                    instruction = "Sal del edificio y gira a la derecha.",
                    distanceToNext = 50f
                ),
                RoutePoint(
                    latitude = ORIGIN_LAT + 0.0003,
                    longitude = ORIGIN_LON + 0.0002,
                    name = "Cruce principal",
                    instruction = "En el cruce, continúa recto.",
                    distanceToNext = 80f
                ),
                RoutePoint(
                    latitude = ORIGIN_LAT + 0.0005,
                    longitude = ORIGIN_LON + 0.0003,
                    name = "Farmacia",
                    instruction = "La farmacia está a tu derecha.",
                    distanceToNext = 0f
                )
            ),
            totalDistanceMeters = 130f,
            estimatedTimeMinutes = 2
        )
    }

    private fun createMercadonaRoute(): NavigationRoute {
        return NavigationRoute(
            destination = "Mercadona",
            waypoints = listOf(
                RoutePoint(
                    latitude = ORIGIN_LAT + 0.0002,
                    longitude = ORIGIN_LON - 0.0001,
                    name = "Salida",
                    instruction = "Sal del edificio y gira a la izquierda.",
                    distanceToNext = 100f
                ),
                RoutePoint(
                    latitude = ORIGIN_LAT + 0.0005,
                    longitude = ORIGIN_LON - 0.0003,
                    name = "Avenida principal",
                    instruction = "Sigue por la avenida principal.",
                    distanceToNext = 150f
                ),
                RoutePoint(
                    latitude = ORIGIN_LAT + 0.0008,
                    longitude = ORIGIN_LON - 0.0005,
                    name = "Rotonda",
                    instruction = "En la rotonda, toma la segunda salida.",
                    distanceToNext = 100f
                ),
                RoutePoint(
                    latitude = ORIGIN_LAT + 0.001,
                    longitude = ORIGIN_LON - 0.0006,
                    name = "Mercadona",
                    instruction = "El Mercadona está a tu izquierda.",
                    distanceToNext = 0f
                )
            ),
            totalDistanceMeters = 350f,
            estimatedTimeMinutes = 5
        )
    }

    private fun createBusStopRoute(): NavigationRoute {
        return NavigationRoute(
            destination = "Parada de bus",
            waypoints = listOf(
                RoutePoint(
                    latitude = ORIGIN_LAT - 0.0001,
                    longitude = ORIGIN_LON + 0.0002,
                    name = "Calle lateral",
                    instruction = "Gira a la derecha en la calle lateral.",
                    distanceToNext = 40f
                ),
                RoutePoint(
                    latitude = ORIGIN_LAT - 0.0002,
                    longitude = ORIGIN_LON + 0.0004,
                    name = "Parada de bus",
                    instruction = "La parada de bus está aquí.",
                    distanceToNext = 0f
                )
            ),
            totalDistanceMeters = 40f,
            estimatedTimeMinutes = 1
        )
    }

    private fun createParkRoute(): NavigationRoute {
        return NavigationRoute(
            destination = "Parque",
            waypoints = listOf(
                RoutePoint(
                    latitude = ORIGIN_LAT + 0.0003,
                    longitude = ORIGIN_LON + 0.0005,
                    name = "Camino al parque",
                    instruction = "Sigue recto por el camino peatonal.",
                    distanceToNext = 200f
                ),
                RoutePoint(
                    latitude = ORIGIN_LAT + 0.0006,
                    longitude = ORIGIN_LON + 0.001,
                    name = "Entrada del parque",
                    instruction = "Has llegado a la entrada del parque.",
                    distanceToNext = 0f
                )
            ),
            totalDistanceMeters = 200f,
            estimatedTimeMinutes = 3
        )
    }

    private fun createBankRoute(): NavigationRoute {
        return NavigationRoute(
            destination = "Banco",
            waypoints = listOf(
                RoutePoint(
                    latitude = ORIGIN_LAT - 0.0002,
                    longitude = ORIGIN_LON - 0.0002,
                    name = "Calle comercial",
                    instruction = "Baja por la calle comercial.",
                    distanceToNext = 120f
                ),
                RoutePoint(
                    latitude = ORIGIN_LAT - 0.0004,
                    longitude = ORIGIN_LON - 0.0003,
                    name = "Banco",
                    instruction = "El banco está a tu derecha.",
                    distanceToNext = 0f
                )
            ),
            totalDistanceMeters = 120f,
            estimatedTimeMinutes = 2
        )
    }

    private fun createHospitalRoute(): NavigationRoute {
        return NavigationRoute(
            destination = "Hospital",
            waypoints = listOf(
                RoutePoint(
                    latitude = ORIGIN_LAT + 0.002,
                    longitude = ORIGIN_LON + 0.001,
                    name = "Avenida del hospital",
                    instruction = "Sigue por la avenida principal hacia el norte.",
                    distanceToNext = 500f
                ),
                RoutePoint(
                    latitude = ORIGIN_LAT + 0.004,
                    longitude = ORIGIN_LON + 0.002,
                    name = "Entrada de urgencias",
                    instruction = "La entrada de urgencias está frente a ti.",
                    distanceToNext = 0f
                )
            ),
            totalDistanceMeters = 500f,
            estimatedTimeMinutes = 7
        )
    }

    private fun createHomeRoute(): NavigationRoute {
        return NavigationRoute(
            destination = "Casa",
            waypoints = listOf(
                RoutePoint(
                    latitude = ORIGIN_LAT,
                    longitude = ORIGIN_LON,
                    name = "Tu casa",
                    instruction = "Estás en casa.",
                    distanceToNext = 0f
                )
            ),
            totalDistanceMeters = 0f,
            estimatedTimeMinutes = 0
        )
    }

    private fun createGenericRoute(destination: String): NavigationRoute {
        // Genera una ruta genérica de 100m en línea recta
        return NavigationRoute(
            destination = destination,
            waypoints = listOf(
                RoutePoint(
                    latitude = ORIGIN_LAT + 0.0005,
                    longitude = ORIGIN_LON,
                    name = "Punto intermedio",
                    instruction = "Continúa recto.",
                    distanceToNext = 50f
                ),
                RoutePoint(
                    latitude = ORIGIN_LAT + 0.001,
                    longitude = ORIGIN_LON,
                    name = destination,
                    instruction = "Has llegado a $destination.",
                    distanceToNext = 0f
                )
            ),
            totalDistanceMeters = 100f,
            estimatedTimeMinutes = 2
        )
    }
}
