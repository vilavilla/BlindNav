package com.blindnav.app.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.blindnav.app.data.db.BlindNavDatabase
import com.blindnav.app.data.osm.NominatimGeocoder
import com.blindnav.app.databinding.ActivityHomeBinding
import com.blindnav.app.ui.audio.PriorityAudioManager
import com.blindnav.app.ui.navigation.NavigationActivity
import com.blindnav.app.ui.preview.RoutePreviewActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

/**
 * HomeActivity - Mapa Interactivo con Ubicación en Tiempo Real
 * 
 * Funcionalidades:
 * - Mapa de pantalla completa con osmdroid
 * - MyLocationNewOverlay (punto azul + flecha de dirección)
 * - Búsqueda de POIs con Nominatim
 * - Navegación GPS + Brújula
 */
class HomeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "HomeActivity"
        private const val LOCATION_PERMISSION_REQUEST = 100
        private const val DEFAULT_ZOOM = 18.0 // Zoom más cercano para navegación
    }

    private lateinit var binding: ActivityHomeBinding
    private lateinit var database: BlindNavDatabase
    private lateinit var audioManager: PriorityAudioManager
    
    // Mapa OSM
    private lateinit var mapView: MapView
    private lateinit var myLocationOverlay: MyLocationNewOverlay
    
    // Búsqueda
    private lateinit var searchResultAdapter: SearchResultAdapter
    private var searchJob: Job? = null
    
    // Navegación
    private var destinationMarker: Marker? = null
    private var selectedDestination: NominatimGeocoder.SearchResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configurar osmdroid ANTES de inflar el layout
        configureOsmdroid()
        
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        initializeDependencies()
        setupMap()
        setupUI()
        requestLocationPermission()
        
        // Anunciar pantalla
        audioManager.speakSystem("BlindNav. Mapa listo. Busca un lugar o comienza a grabar tu ruta.")
    }

    /**
     * Configuración crítica de osmdroid
     */
    private fun configureOsmdroid() {
        Configuration.getInstance().load(
            this,
            PreferenceManager.getDefaultSharedPreferences(this)
        )
        
        // User-Agent OBLIGATORIO
        Configuration.getInstance().userAgentValue = "BlindNav/1.0 (Android Accessibility Navigation)"
    }

    private fun initializeDependencies() {
        database = BlindNavDatabase.getInstance(this)
        audioManager = PriorityAudioManager(this)
    }

    /**
     * Configurar MapView con MyLocationNewOverlay
     */
    private fun setupMap() {
        mapView = binding.mapView
        
        mapView.apply {
            // TileSource: MAPNIK (OpenStreetMap estándar) - FORZAR PARA EVITAR MAPAS GRISES
            setTileSource(TileSourceFactory.MAPNIK)
            
            // Habilitar controles táctiles (zoom con dedos)
            setMultiTouchControls(true)
            
            // Zoom inicial nivel calle (18 = muy cercano)
            controller.setZoom(18.0)
            
            // Centro inicial en Barcelona (se actualizará con GPS)
            controller.setCenter(GeoPoint(41.3851, 2.1734))
            
            // Invalidar para forzar renderizado
            invalidate()
        }
        
        // ========== OVERLAY DE MI UBICACIÓN ==========
        setupMyLocationOverlay()
        
        Log.d(TAG, "Mapa OSM configurado: MAPNIK + Zoom 18 + MyLocationNewOverlay")
    }

    /**
     * MyLocationNewOverlay - Muestra TU UBICACIÓN en el mapa
     * - Punto azul (ubicación GPS)
     * - Flecha de dirección (brújula)
     * - Sigue automáticamente al usuario
     */
    private fun setupMyLocationOverlay() {
        // Proveedor de ubicación GPS
        val locationProvider = GpsMyLocationProvider(this)
        
        // Crear overlay de ubicación
        myLocationOverlay = MyLocationNewOverlay(locationProvider, mapView)
        
        myLocationOverlay.apply {
            // Habilitar ubicación
            enableMyLocation()
            
            // Habilitar seguimiento (el mapa se mueve con el usuario)
            enableFollowLocation()
            
            // Mostrar círculo de precisión
            isDrawAccuracyEnabled = true
        }
        
        // Agregar overlay al mapa
        mapView.overlays.add(myLocationOverlay)
        
        // ========== LISTENER DE PRIMERA UBICACIÓN GPS ==========
        // CRÍTICO: Centrar y hacer zoom cuando se obtiene el primer fix GPS
        myLocationOverlay.runOnFirstFix {
            runOnUiThread {
                val myLocation = myLocationOverlay.myLocation
                if (myLocation != null) {
                    // FORZAR ZOOM NIVEL CALLE (18 = muy cercano para navegación peatonal)
                    mapView.controller.setZoom(18.0)
                    
                    // Centrar mapa en mi ubicación con animación suave
                    mapView.controller.animateTo(myLocation)
                    
                    // Feedback al usuario
                    audioManager.speakSystem("Ubicación GPS encontrada")
                    binding.tvGpsStatus.text = "📍 GPS ACTIVO"
                    binding.tvGpsStatus.setTextColor(0xFF4CAF50.toInt()) // Verde
                    
                    Log.d(TAG, "✓ GPS First Fix: ${myLocation.latitude}, ${myLocation.longitude} | Zoom: 18")
                }
            }
        }
        
        Log.d(TAG, "MyLocationOverlay configurado con runOnFirstFix")
    }

    private fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "✓ Permiso de ubicación concedido. Reconfigurando MyLocationOverlay...")
                
                // CRÍTICO: Reconfigurar overlay DESPUÉS de conceder permisos
                // Esto garantiza que runOnFirstFix se ejecute correctamente
                setupMyLocationOverlay()
                
                // Forzar actualización de ubicación
                myLocationOverlay.enableMyLocation()
                myLocationOverlay.enableFollowLocation()
                
                Toast.makeText(this, "GPS activado. Buscando ubicación...", Toast.LENGTH_SHORT).show()
                binding.tvGpsStatus.text = "🔍 Buscando GPS..."
                binding.tvGpsStatus.setTextColor(0xFFFFA500.toInt()) // Naranja
            } else {
                Toast.makeText(this, "⚠️ Se requiere permiso de ubicación para usar BlindNav", Toast.LENGTH_LONG).show()
                binding.tvGpsStatus.text = "❌ GPS Desactivado"
                binding.tvGpsStatus.setTextColor(0xFFF44336.toInt()) // Rojo
            }
        }
    }

    private fun setupUI() {
        // Adapter de resultados de búsqueda
        searchResultAdapter = SearchResultAdapter { result ->
            onSearchResultClicked(result)
        }
        
        binding.rvSearchResults.apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            adapter = searchResultAdapter
        }
        
        // Búsqueda por texto
        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = binding.etSearch.text?.toString()?.trim()
                if (!query.isNullOrEmpty()) {
                    searchPOI(query)
                }
                true
            } else false
        }
        
        // Búsqueda por voz
        binding.fabVoiceSearch.setOnClickListener {
            audioManager.speakSystem("Búsqueda por voz próximamente")
            Toast.makeText(this, "🎤 Búsqueda por voz próximamente", Toast.LENGTH_SHORT).show()
        }
        
        // Botón Limpiar Mapa (largo click en búsqueda por voz)
        binding.fabVoiceSearch.setOnLongClickListener {
            clearMapMarkers()
            true
        }
        
        // Botón Iniciar Navegación
        binding.btnStartNavigation.setOnClickListener {
            startNavigation()
        }
        
        // Botón Grabar Ruta
        binding.btnRecordRoute.setOnClickListener {
            openRecordingMode()
        }
        
        // Botón Mis Rutas
        binding.btnMyRoutes.setOnClickListener {
            // TODO: Abrir lista de rutas guardadas
            Toast.makeText(this, "Mis rutas próximamente", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Buscar POI (Punto de Interés) con Nominatim
     * Soporta búsquedas específicas como "Cítara Fraga" o "Plaza Mayor, Madrid"
     */
    private fun searchPOI(query: String) {
        Log.d(TAG, "========== BÚSQUEDA INICIADA ==========")
        Log.d(TAG, "Query original: \"$query\"")
        Log.d(TAG, "Longitud: ${query.length} caracteres")
        
        audioManager.speakSystem("Buscando $query")
        
        // Cancelar búsqueda anterior
        searchJob?.cancel()
        
        searchJob = lifecycleScope.launch {
            try {
                Log.d(TAG, "Consultando Nominatim API con búsqueda local...")
                
                // ✓ Obtener ubicación GPS del usuario para búsqueda local
                val myLocation = myLocationOverlay.myLocation
                val userLat = myLocation?.latitude
                val userLon = myLocation?.longitude
                
                if (userLat != null && userLon != null) {
                    Log.d(TAG, "✓ GPS disponible: ($userLat, $userLon) - Búsqueda LOCAL")
                } else {
                    Log.w(TAG, "⚠️ GPS no disponible - Búsqueda GLOBAL")
                }
                
                // ✓ Buscar con Nominatim + viewbox local
                val results = NominatimGeocoder.search(
                    query = query, 
                    limit = 10,
                    userLat = userLat,
                    userLon = userLon
                )
                
                Log.d(TAG, "Respuesta recibida: ${results.size} resultados")
                
                if (results.isNotEmpty()) {
                    // ✓ RESULTADOS ENCONTRADOS
                    searchResultAdapter.submitList(results)
                    binding.rvSearchResults.visibility = View.VISIBLE
                    
                    audioManager.speakSystem("${results.size} resultados encontrados")
                    
                    // Log de primeros 3 resultados para debug
                    results.take(3).forEachIndexed { index, result ->
                        Log.d(TAG, "  [$index] ${result.displayName}")
                    }
                    
                } else {
                    // ✗ NO SE ENCONTRÓ NADA
                    binding.rvSearchResults.visibility = View.GONE
                    
                    val errorMessage = "No se encontró '${query}'. Intenta con formato: 'Sitio, Ciudad' (Ej: Cítara, Fraga)"
                    
                    audioManager.speakSystem("No se encontraron resultados. Prueba con el nombre completo y la ciudad.")
                    Toast.makeText(this@HomeActivity, errorMessage, Toast.LENGTH_LONG).show()
                    
                    Log.w(TAG, "❌ Sin resultados para: \"$query\"")
                    Log.w(TAG, "Sugerencia: Prueba agregando ciudad (Ej: '$query, España')")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ ERROR EN BÚSQUEDA", e)
                Log.e(TAG, "Query que falló: \"$query\"")
                Log.e(TAG, "Tipo de error: ${e.javaClass.simpleName}")
                Log.e(TAG, "Mensaje: ${e.message}")
                
                binding.rvSearchResults.visibility = View.GONE
                
                audioManager.speakSystem("Error al buscar. Verifica tu conexión a Internet.")
                Toast.makeText(
                    this@HomeActivity, 
                    "Error: ${e.message ?: "Conexión fallida"}", 
                    Toast.LENGTH_LONG
                ).show()
            }
            
            Log.d(TAG, "========== BÚSQUEDA FINALIZADA ==========")
        }
    }

    /**
     * Cuando el usuario selecciona un resultado de búsqueda
     */
    private fun onSearchResultClicked(result: NominatimGeocoder.SearchResult) {
        Log.d(TAG, "Resultado seleccionado: ${result.displayName}")
        
        selectedDestination = result
        
        // Ocultar resultados
        binding.rvSearchResults.visibility = View.GONE
        
        // Agregar marcador en el mapa
        addDestinationMarker(result.latitude, result.longitude, result.displayName)
        
        // Centrar mapa en el destino
        mapView.controller.animateTo(GeoPoint(result.latitude, result.longitude))
        
        // Mostrar botón de navegación
        binding.btnStartNavigation.visibility = View.VISIBLE
        
        // Anunciar
        val parts = result.displayName.split(",")
        val placeName = parts.firstOrNull() ?: result.displayName
        audioManager.speakNavigation("Destino seleccionado: $placeName. Pulsa el botón verde para iniciar navegación.")
    }

    /**
     * Agregar marcador de destino en el mapa
     */
    private fun addDestinationMarker(lat: Double, lon: Double, name: String) {
        // Remover marcador anterior
        destinationMarker?.let {
            mapView.overlays.remove(it)
        }
        
        val marker = Marker(mapView)
        marker.position = GeoPoint(lat, lon)
        marker.title = name
        marker.snippet = "Destino seleccionado"
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        
        // Personalizar icono (opcional)
        // marker.icon = ContextCompat.getDrawable(this, R.drawable.ic_destination)
        
        mapView.overlays.add(marker)
        destinationMarker = marker
        mapView.invalidate()
        
        Log.d(TAG, "✓ Marcador añadido: [$lat, $lon] - $name")
    }
    
    /**
     * Limpiar todos los marcadores del mapa
     */
    private fun clearMapMarkers() {
        destinationMarker?.let {
            mapView.overlays.remove(it)
            destinationMarker = null
        }
        
        selectedDestination = null
        binding.btnStartNavigation.visibility = View.GONE
        binding.rvSearchResults.visibility = View.GONE
        
        mapView.invalidate()
        
        audioManager.speakSystem("Mapa limpiado")
        Toast.makeText(this, "Marcadores eliminados", Toast.LENGTH_SHORT).show()
        
        Log.d(TAG, "✓ Mapa limpiado")
    }

    /**
     * Iniciar navegación hacia el destino seleccionado
     */
    private fun startNavigation() {
        val destination = selectedDestination
        
        if (destination == null) {
            Toast.makeText(this, "Primero selecciona un destino", Toast.LENGTH_SHORT).show()
            return
        }
        
        audioManager.speakNavigation("Calculando ruta")
        
        // Ir a RoutePreviewActivity para calcular ruta y confirmar
        val intent = Intent(this, RoutePreviewActivity::class.java).apply {
            putExtra(RoutePreviewActivity.EXTRA_DESTINATION_NAME, destination.displayName)
            putExtra(RoutePreviewActivity.EXTRA_DESTINATION_LAT, destination.latitude)
            putExtra(RoutePreviewActivity.EXTRA_DESTINATION_LON, destination.longitude)
        }
        startActivity(intent)
    }

    /**
     * Abrir modo de grabación de rutas
     */
    private fun openRecordingMode() {
        audioManager.speakNavigation("Abriendo modo grabación")
        
        val intent = Intent(this, NavigationActivity::class.java).apply {
            putExtra(NavigationActivity.EXTRA_MODE, NavigationActivity.MODE_RECORDING)
        }
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        
        // Reactivar MyLocation
        if (::myLocationOverlay.isInitialized) {
            myLocationOverlay.enableMyLocation()
        }
    }

    override fun onPause() {
        super.onPause()
        mapView.onPause()
        
        // Desactivar MyLocation para ahorrar batería
        if (::myLocationOverlay.isInitialized) {
            myLocationOverlay.disableMyLocation()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::audioManager.isInitialized) audioManager.release()
    }
}
