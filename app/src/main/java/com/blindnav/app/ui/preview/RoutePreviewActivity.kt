package com.blindnav.app.ui.preview

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.blindnav.app.data.db.BlindNavDatabase
import com.blindnav.app.data.db.entity.EventType
import com.blindnav.app.data.osm.OSRMRouteProvider
import com.blindnav.app.databinding.ActivityRoutePreviewBinding
import com.blindnav.app.ui.audio.PriorityAudioManager
import com.blindnav.app.ui.navigation.NavigationActivity
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
                    
                    if (route != null) {
                        // Distancia - usar número de eventos como aproximación
                        val estimatedDistance = events.size * 50 // ~50m por evento
                        val distanceText = if (estimatedDistance >= 1000) {
                            String.format("%.1f km", estimatedDistance / 1000f)
                        } else {
                            "${estimatedDistance}m"
                        }
                        binding.tvDistance.text = distanceText
                        
                        // Cruces - use `type` field
                        val crossings = events.count { it.type == EventType.CROSSING }
                        binding.tvCrossings.text = crossings.toString()
                        
                        // Tiempo estimado (asumiendo 1m/s de velocidad)
                        val timeMinutes = (estimatedDistance / 60)
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
                        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                            if (location != null) {
                                lifecycleScope.launch {
                                    calculateRouteWithOSRM(
                                        location.latitude,
                                        location.longitude,
                                        destinationLat,
                                        destinationLon
                                    )
                                }
                            } else {
                                showMockData()
                            }
                        }.addOnFailureListener {
                            Log.e(TAG, "Error obteniendo ubicación", it)
                            showMockData()
                        }
                    } else {
                        showMockData()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error calculando ruta", e)
                    showMockData()
                }
            }
        }
    }

    /**
     * Calcular ruta real usando OSRM
     */
    private suspend fun calculateRouteWithOSRM(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double
    ) {
        try {
            // ✓ Crear instancia de OSRMRouteProvider con Context
            val routeProvider = OSRMRouteProvider(this)
            
            val routeResult = routeProvider.calculateRoute(
                startLat, startLon,
                endLat, endLon
            )
            
            if (routeResult != null) {
                // Actualizar UI con datos reales
                val distanceKm = routeResult.totalDistance / 1000
                val distanceText = if (distanceKm >= 1.0) {
                    String.format("%.1f km", distanceKm)
                } else {
                    "${routeResult.totalDistance.toInt()}m"
                }
                binding.tvDistance.text = distanceText
                
                // Calcular cruces (cambios de dirección significativos)
                val crossings = routeResult.checkpoints.count { 
                    it.description.contains("gira", ignoreCase = true) ||
                    it.description.contains("turn", ignoreCase = true)
                }
                binding.tvCrossings.text = crossings.toString()
                
                // Tiempo estimado
                val timeMinutes = (routeResult.totalDuration / 60).toInt()
                binding.tvTime.text = if (timeMinutes > 0) "$timeMinutes min" else "< 1 min"
                
                audioManager.speakNavigation(
                    "Ruta calculada. $distanceText. $crossings giros. $timeMinutes minutos. " +
                    "Pulsa el botón grande para iniciar navegación."
                )
                
                Log.d(TAG, "Ruta OSRM: ${routeResult.checkpoints.size} puntos, $distanceText")
            } else {
                showMockData()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error calculando con OSRM", e)
            showMockData()
        }
    }

    /**
     * Mostrar datos mock si OSRM falla
     */
    private fun showMockData() {
        binding.tvDistance.text = "~350m"
        binding.tvCrossings.text = "?"
        binding.tvTime.text = "~5 min"
        
        audioManager.speakNavigation(
            "Destino: $destinationName. " +
            "Pulsa el botón grande para iniciar navegación."
        )
    }

    private fun startNavigation() {
        audioManager.speakNavigation("Iniciando navegación")
        
        val intent = Intent(this, NavigationActivity::class.java).apply {
            putExtra(NavigationActivity.EXTRA_MODE, NavigationActivity.MODE_NAVIGATION)
            
            if (routeId != -1L) {
                putExtra(NavigationActivity.EXTRA_ROUTE_ID, routeId)
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
