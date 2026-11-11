package com.blindnav.app.ui.preview

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.blindnav.app.data.db.BlindNavDatabase
import com.blindnav.app.data.db.entity.Checkpoint
import com.blindnav.app.data.db.entity.EventType
import com.blindnav.app.data.db.entity.Route
import com.blindnav.app.data.db.entity.PathPoint
import com.blindnav.app.data.osm.OSRMRouteProvider
import com.blindnav.app.databinding.ActivityRoutePreviewBinding
import com.blindnav.app.ui.audio.PriorityAudioManager
import com.blindnav.app.ui.navigation.NavigationActivity
import com.blindnav.app.domain.navigation.SavedRouteBuilder
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

/**
 * RoutePreviewActivity - Pantalla de confirmación antes de navegar
 * 
 * Muestra resumen de la ruta calculada con OSRM y permite iniciar navegación.
 */
class RoutePreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RoutePreview"
        
        // Extras para ruta guardada
        const val EXTRA_ROUTE_ID = "route_id"
        const val EXTRA_ROUTE_NAME = "route_name"
        
        // Extras para destino nuevo (geocodificado)
        const val EXTRA_DESTINATION_NAME = "dest_name"
        const val EXTRA_DESTINATION_LAT = "dest_lat"
        const val EXTRA_DESTINATION_LON = "dest_lon"
    }

    private lateinit var binding: ActivityRoutePreviewBinding
    private lateinit var audioManager: PriorityAudioManager
    private lateinit var database: BlindNavDatabase
    
    private var routeId: Long = -1
    private var generatedRouteId: Long = -1
    private var destinationName: String = ""
    private var destinationLat: Double = 0.0
    private var destinationLon: Double = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityRoutePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        audioManager = PriorityAudioManager(this)
        database = BlindNavDatabase.getInstance(this)
        
        parseIntent()
        setupUI()
        loadRouteData()
    }

    private fun parseIntent() {
        routeId = intent.getLongExtra(EXTRA_ROUTE_ID, -1)
        
        if (routeId != -1L) {
            // Ruta guardada
            destinationName = intent.getStringExtra(EXTRA_ROUTE_NAME) ?: "Ruta"
        } else {
            // Destino nuevo
            destinationName = intent.getStringExtra(EXTRA_DESTINATION_NAME) ?: ""
            destinationLat = intent.getDoubleExtra(EXTRA_DESTINATION_LAT, 0.0)
            destinationLon = intent.getDoubleExtra(EXTRA_DESTINATION_LON, 0.0)
        }
        
        Log.d(TAG, "Route ID: $routeId, Destination: $destinationName")
    }

    private fun setupUI() {
        // Botón atrás
        binding.btnBack.setOnClickListener {
            finish()
        }
        
        // Botón iniciar navegación
        binding.btnStartNavigation.setOnClickListener {
            startNavigation()
        }
        binding.btnStartNavigation.isEnabled = false
        
        // Nombre del destino
        binding.tvDestination.text = destinationName
    }

    private fun loadRouteData() {
        if (routeId != -1L) {
            // Cargar datos de ruta guardada
            lifecycleScope.launch {
                try {
                    val route = database.routeDao().getRouteById(routeId)
                    val events = database.mapEventDao().getEventsByRoute(routeId).first()
                    val points = database.pathPointDao().getPointsByRoute(routeId).first()
                    val checkpoints = database.checkpointDao().getCheckpointsByRoute(routeId).first()
                    
                    if (route != null) {
                        val navigableRoute = SavedRouteBuilder.build(
                            routeId = routeId,
                            pathPoints = points,
                            events = events,
                            savedCheckpoints = checkpoints
                        )
                        if (navigableRoute == null) {
                            showRouteUnavailable("Esta grabación no contiene suficientes puntos GPS.")
                            return@launch
                        }
                        val estimatedDistance = navigableRoute.totalDistanceMeters.toInt()
                        val distanceText = if (estimatedDistance >= 1000) {
                            String.format("%.1f km", estimatedDistance / 1000f)
                        } else {
                            "${estimatedDistance}m"
                        }
                        binding.tvDistance.text = distanceText
                        
                        // Cruces - use `type` field
                        val crossings = navigableRoute.checkpoints.count {
                            it.type == com.blindnav.app.data.db.entity.CheckpointType.CROSSING ||
                                it.type == com.blindnav.app.data.db.entity.CheckpointType.ZEBRA_CROSSING
                        }
                        binding.tvCrossings.text = crossings.toString()
                        
                        // Tiempo estimado (asumiendo 1m/s de velocidad)
                        val timeMinutes = (estimatedDistance / 66)
                        binding.tvTime.text = if (timeMinutes > 0) "$timeMinutes min" else "< 1 min"
                        
                        // Obstáculos - use `type` field
                        val obstacles = events.count { 
                            it.type == EventType.OBSTACLE_PERMANENT || 
                            it.type == EventType.OBSTACLE_TEMPORARY 
                        }
                        if (obstacles > 0) {
                            binding.warningsCard.visibility = android.view.View.VISIBLE
                            binding.tvWarnings.text = "$obstacles obstáculos reportados en esta ruta"
                        }
                        
                        // Anunciar
                        audioManager.speakNavigation(
                            "$destinationName. $distanceText. $crossings cruces. " +
                            "Pulsa el botón grande para iniciar navegación."
                        )
                        binding.btnStartNavigation.isEnabled = true
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading route", e)
                }
            }
        } else {
            // Destino nuevo - calcular ruta real con OSRM
            binding.tvDistance.text = "Calculando..."
            binding.tvCrossings.text = "?"
            binding.tvTime.text = "Calculando..."
            
            audioManager.speakNavigation("Calculando ruta hacia $destinationName")
            
            // Obtener ubicación actual y calcular ruta
            lifecycleScope.launch {
                try {
                    if (ActivityCompat.checkSelfPermission(
                            this@RoutePreviewActivity,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this@RoutePreviewActivity)
                        fusedLocationClient.getCurrentLocation(
                            Priority.PRIORITY_HIGH_ACCURACY,
                            null
                        ).addOnSuccessListener { location ->
                            if (location != null) {
                                calculateRouteWithOSRM(
                                    location.latitude,
                                    location.longitude,
                                    destinationLat,
                                    destinationLon
                                )
                            } else {
                                showRouteUnavailable("No se pudo obtener tu ubicación actual para calcular la ruta")
                            }
                        }.addOnFailureListener {
                            Log.e(TAG, "Error obteniendo ubicación", it)
                            showRouteUnavailable("No se pudo obtener tu ubicación actual para calcular la ruta")
                        }
                    } else {
                        showRouteUnavailable("Se requiere permiso de ubicación para calcular la ruta")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error calculando ruta", e)
                    showRouteUnavailable("No se pudo iniciar el cálculo de ruta")
                }
            }
        }
    }

    /**
     * Calcular ruta real usando OSRM
     */
    private fun calculateRouteWithOSRM(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ) {
        binding.tvDistance.text = "Calculando..."
        binding.tvCrossings.text = "..."
        binding.tvTime.text = "Calculando..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // ✓ Crear instancia de OSRMRouteProvider con Context
                val routeProvider = OSRMRouteProvider(this@RoutePreviewActivity)
                
                val routeResponse = routeProvider.getRoute(
                    start = GeoPoint(startLat, startLon),
                    end = GeoPoint(endLat, endLon)
                )

                val persistedRouteId = if (routeId == -1L && routeResponse is OSRMRouteProvider.RouteFetchResult.Success) {
                    persistCalculatedRoute(routeResponse.routeResult)
                } else {
                    null
                }
                
                // ✓ Actualizar UI en Main Thread
                withContext(Dispatchers.Main) {
                    when (routeResponse) {
                        is OSRMRouteProvider.RouteFetchResult.Success -> {
                            val routeResult = routeResponse.routeResult
                            if (persistedRouteId != null && persistedRouteId > 0) {
                                generatedRouteId = persistedRouteId
                            }

                            val distanceKm = routeResult.totalDistance / 1000
                            val distanceText = if (distanceKm >= 1.0) {
                                String.format("%.1f km", distanceKm)
                            } else {
                                "${routeResult.totalDistance.toInt()}m"
                            }
                            binding.tvDistance.text = distanceText

                            val crossings = routeResult.checkpoints.count {
                                it.type == com.blindnav.app.data.db.entity.CheckpointType.CROSSING ||
                                    it.type == com.blindnav.app.data.db.entity.CheckpointType.ZEBRA_CROSSING
                            }
                            binding.tvCrossings.text = crossings.toString()

                            val timeMinutes = (routeResult.totalDuration / 60).toInt()
                            binding.tvTime.text = if (timeMinutes > 0) "$timeMinutes min" else "< 1 min"

                            when {
                                routeResult.accessibility.steps > 0 -> {
                                    binding.warningsCard.visibility = android.view.View.VISIBLE
                                    binding.tvWarnings.text =
                                        "La mejor alternativa disponible contiene ${routeResult.accessibility.steps} tramo(s) de escaleras"
                                }
                                !routeResult.accessibility.dataAvailable -> {
                                    binding.warningsCard.visibility = android.view.View.VISIBLE
                                    binding.tvWarnings.text = "No se pudieron verificar cruces, bordillos y escaleras"
                                }
                                else -> binding.warningsCard.visibility = android.view.View.GONE
                            }

                            val verification = if (routeResult.accessibility.dataAvailable) {
                                "$crossings pasos de peatones registrados"
                            } else {
                                "datos de accesibilidad sin verificar"
                            }
                            audioManager.speakNavigation(
                                "Ruta peatonal calculada. $distanceText. $verification. $timeMinutes minutos. " +
                                    "Pulsa el botón grande para iniciar navegación."
                            )
                            binding.btnStartNavigation.isEnabled = true

                            Log.d(TAG, "Ruta OSRM: ${routeResult.checkpoints.size} puntos, $distanceText")
                        }

                        is OSRMRouteProvider.RouteFetchResult.Failure -> {
                            showRouteUnavailable(routeResponse.userMessage)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error calculando con OSRM", e)
                withContext(Dispatchers.Main) {
                    showRouteUnavailable("No puedo conectar con el servidor de rutas. Comprueba tu conexión")
                }
            }
        }
    }

    private suspend fun persistCalculatedRoute(routeResult: OSRMRouteProvider.RouteResult): Long? {
        return try {
            val sourceText = "PEDESTRIAN_OSRM"
            val routeName = "Ruta a $destinationName"
            val routeDescription = "AUTO_DEST:$destinationLat,$destinationLon|source=$sourceText"

            val newRouteId = database.routeDao().insert(
                Route(
                    name = routeName,
                    description = routeDescription,
                    createdAt = System.currentTimeMillis(),
                    isActive = false
                )
            )

            val checkpointsToPersist: List<Checkpoint> = routeResult.checkpoints.mapIndexed { index, checkpoint ->
                checkpoint.copy(
                    id = 0,
                    routeId = newRouteId,
                    orderIndex = index,
                    createdAt = System.currentTimeMillis()
                )
            }

            if (checkpointsToPersist.isNotEmpty()) {
                database.checkpointDao().insertAll(checkpointsToPersist)
            }

            val recordedAt = System.currentTimeMillis()
            val pathPoints = routeResult.road.mRouteHigh.mapIndexed { index, point ->
                PathPoint(
                    routeId = newRouteId,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    timestamp = recordedAt + index
                )
            }
            if (pathPoints.isNotEmpty()) database.pathPointDao().insertAll(pathPoints)

            Log.d(TAG, "Ruta guardada localmente: id=$newRouteId, checkpoints=${checkpointsToPersist.size}")
            newRouteId
        } catch (e: Exception) {
            Log.e(TAG, "Error guardando ruta local", e)
            null
        }
    }

    private fun showRouteUnavailable(message: String) {
        binding.tvDistance.text = "Sin ruta"
        binding.tvCrossings.text = "-"
        binding.tvTime.text = "-"
        binding.btnStartNavigation.isEnabled = false

        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        audioManager.speakSystem(message)
    }

    private fun startNavigation() {
        audioManager.speakNavigation("Iniciando navegación")
        
        val intent = Intent(this, NavigationActivity::class.java).apply {
            putExtra(NavigationActivity.EXTRA_MODE, NavigationActivity.MODE_NAVIGATION)
            
            if (routeId != -1L) {
                putExtra(NavigationActivity.EXTRA_ROUTE_ID, routeId)
            } else if (generatedRouteId != -1L) {
                putExtra(NavigationActivity.EXTRA_ROUTE_ID, generatedRouteId)
                putExtra(NavigationActivity.EXTRA_DESTINATION_NAME, destinationName)
                putExtra(NavigationActivity.EXTRA_DESTINATION_LAT, destinationLat)
                putExtra(NavigationActivity.EXTRA_DESTINATION_LON, destinationLon)
            } else {
                putExtra(NavigationActivity.EXTRA_DESTINATION_NAME, destinationName)
                putExtra(NavigationActivity.EXTRA_DESTINATION_LAT, destinationLat)
                putExtra(NavigationActivity.EXTRA_DESTINATION_LON, destinationLon)
            }
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::audioManager.isInitialized) audioManager.release()
    }
}
