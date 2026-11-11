package com.blindnav.app.ui.recording

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blindnav.app.data.db.dao.CheckpointDao
import com.blindnav.app.data.db.dao.MapEventDao
import com.blindnav.app.data.db.dao.PathPointDao
import com.blindnav.app.data.db.dao.RouteDao
import com.blindnav.app.data.db.entity.Checkpoint
import com.blindnav.app.data.db.entity.CheckpointType
import com.blindnav.app.data.db.entity.EventType
import com.blindnav.app.data.db.entity.MapEvent
import com.blindnav.app.data.db.entity.PathPoint
import com.blindnav.app.data.db.entity.Route
import com.blindnav.app.data.sensors.LocationSensorManager
import com.blindnav.app.data.sensors.UserOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log

/**
 * Estado de la UI de grabación de rutas (Waze-style).
 */
data class RecordingUiState(
    /** Indica si hay una grabación de ruta activa */
    val isRecording: Boolean = false,
    
    /** Ruta actualmente en grabación */
    val currentRoute: Route? = null,
    
    /** Número de eventos guardados */
    val eventCount: Int = 0,
    
    /** Número de puntos de trazado guardados */
    val pathPointCount: Int = 0,
    
    /** Último evento reportado */
    val lastEvent: MapEvent? = null,
    
    /** Mensaje de estado para feedback */
    val statusMessage: String = ""
)

/**
 * RouteRecorderViewModel - ViewModel para grabación de rutas estilo Waze
 * 
 * Gestiona:
 * - Iniciar/detener grabación de ruta
 * - Reportar eventos (cruces, obstáculos, giros, notas)
 * - Capturar automáticamente PathPoints cada 5 metros
 * - Guardar en Room Database con orientación de brújula
 */
class RouteRecorderViewModel(
    private val routeDao: RouteDao,
    private val checkpointDao: CheckpointDao,
    private val pathPointDao: PathPointDao,
    private val mapEventDao: MapEventDao,
    private val locationSensorManager: LocationSensorManager
) : ViewModel() {
    
    companion object {
        private const val TAG = "RouteRecorderViewModel"
        
        /** Distancia mínima entre PathPoints en metros */
        private const val PATH_POINT_MIN_DISTANCE_METERS = 5f
    }
    
    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()
    
    // Ruta activa para grabación
    private var activeRouteId: Long? = null
    
    // Job para auto-tracking de PathPoints
    private var pathTrackingJob: Job? = null
    
    // Última ubicación guardada para calcular distancia
    private var lastPathPointLocation: Location? = null
    
    // ============================================
    // CONTROL DE GRABACIÓN
    // ============================================
    
    /**
     * Inicia una nueva grabación de ruta.
     * 
     * @param routeName Nombre de la ruta
     * @return true si se inició correctamente
     */
    fun startRecording(routeName: String): Boolean {
        if (_uiState.value.isRecording) {
            Log.w(TAG, "Ya hay una grabación activa")
            return false
        }
        
        if (routeName.isBlank()) {
            Log.e(TAG, "Nombre de ruta vacío")
            return false
        }
        
        viewModelScope.launch {
            try {
                // 🛡️ BLINDADO: Operaciones de DB en Dispatchers.IO
                val routeId = withContext(Dispatchers.IO) {
                    val route = Route(name = routeName, isActive = true)
                    routeDao.insert(route)
                }
                
                activeRouteId = routeId
                
                val insertedRoute = withContext(Dispatchers.IO) {
                    routeDao.getRouteById(routeId)
                }
                
                // Iniciar tracking de ubicación
                locationSensorManager.startTracking()
                
                // Iniciar auto-tracking de PathPoints
                startPathTracking()
                
                _uiState.update {
                    it.copy(
                        isRecording = true,
                        currentRoute = insertedRoute,
                        eventCount = 0,
                        pathPointCount = 0,
                        statusMessage = "Grabando ruta: $routeName"
                    )
                }
                
                Log.d(TAG, "✓ Grabación iniciada: ID=$routeId, nombre=$routeName")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error iniciando grabación", e)
                _uiState.update {
                    it.copy(statusMessage = "Error: ${e.message}")
                }
            }
        }
        return true
    }
    
    /**
     * Detiene la grabación de ruta actual.
     */
    fun stopRecording() {
        if (!_uiState.value.isRecording) {
            Log.w(TAG, "No hay grabación activa para detener")
            return
        }
        
        viewModelScope.launch {
            try {
                // Detener auto-tracking
                stopPathTracking()
                
                // 🛡️ BLINDADO: Operaciones de DB en Dispatchers.IO
                activeRouteId?.let { routeId ->
                    withContext(Dispatchers.IO) {
                        val eventCount = mapEventDao.getEventCount(routeId)
                        if (eventCount == 0) {
                            // Eliminar ruta vacía
                            routeDao.getRouteById(routeId)?.let { route ->
                                routeDao.delete(route)
                                Log.d(TAG, "✓ Ruta vacía eliminada: ID=$routeId")
                            }
                        } else {
                            Log.d(TAG, "✓ Ruta guardada: ID=$routeId, eventos=$eventCount")
                        }
                    }
                }
                
                locationSensorManager.stopTracking()
                activeRouteId = null
                lastPathPointLocation = null
                
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        currentRoute = null,
                        statusMessage = "Grabación finalizada"
                    )
                }
                
                Log.d(TAG, "✓ Grabación detenida correctamente")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error deteniendo grabación", e)
                // Asegurarse de limpiar el estado incluso si hay error
                locationSensorManager.stopTracking()
                activeRouteId = null
                lastPathPointLocation = null
                
                _uiState.update {
                    it.copy(
                        isRecording = false,
                        currentRoute = null,
                        statusMessage = "Error al detener: ${e.message}"
                    )
                }
            }
        }
    }
    
    // ============================================
    // WAZE-STYLE EVENT REPORTING
    // ============================================
    
    /**
     * Reporta un evento en la ubicación actual (estilo Waze).
     * 
     * Captura la ubicación Y LA ORIENTACIÓN (Azimuth) actual.
     * 
     * @param type Tipo de evento (CROSSING, OBSTACLE_*, TURN, INFO)
     * @param description Descripción del evento
     * @return true si se guardó correctamente
     */
    fun reportEvent(type: EventType, description: String = ""): Boolean {
        val routeId = activeRouteId
        if (routeId == null) {
            Log.w(TAG, "No hay ruta activa para reportar evento")
            return false
        }
        
        // 🛡️ VALIDACIÓN: Verificar que tenemos ubicación
        val orientation = getCurrentOrientation()
        if (orientation == null) {
            Log.e(TAG, "❌ No se puede reportar evento: GPS sin ubicación")
            _uiState.update {
                it.copy(statusMessage = "Error: GPS sin señal")
            }
            return false
        }
        
        viewModelScope.launch {
            try {
                // 🛡️ BLINDADO: Operaciones de DB en Dispatchers.IO
                withContext(Dispatchers.IO) {
                    // Obtener el siguiente índice de orden
                    val maxIndex = mapEventDao.getMaxOrderIndex(routeId) ?: -1
                    val newIndex = maxIndex + 1
                    
                    val event = MapEvent(
                        routeId = routeId,
                        type = type,
                        latitude = orientation.latitude,
                        longitude = orientation.longitude,
                        description = description.ifEmpty { type.displayName },
                        bearing = orientation.bearing,
                        gpsAccuracy = orientation.accuracy,
                        orderIndex = newIndex
                    )
                    
                    mapEventDao.insert(event)
                    
                    val newCount = mapEventDao.getEventCount(routeId)
                    
                    _uiState.update {
                        it.copy(
                            eventCount = newCount,
                            lastEvent = event,
                            statusMessage = "${type.emoji} ${type.displayName} reportado"
                        )
                    }
                    
                    Log.d(TAG, "✓ Evento reportado: $type en (${orientation.latitude}, ${orientation.longitude})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error reportando evento", e)
                _uiState.update {
                    it.copy(statusMessage = "Error: ${e.message}")
                }
            }
        }
        return true
    }
    
    /**
     * Reporta un paso de cebra.
     */
    fun reportCrossing(description: String = ""): Boolean =
        reportEvent(EventType.CROSSING, description)
    
    /**
     * Reporta un obstáculo temporal.
     */
    fun reportObstacle(description: String = "", permanent: Boolean = false): Boolean =
        reportEvent(
            if (permanent) EventType.OBSTACLE_PERMANENT else EventType.OBSTACLE_TEMPORARY,
            description
        )
    
    /**
     * Reporta un giro.
     */
    fun reportTurn(description: String = ""): Boolean =
        reportEvent(EventType.TURN, description)
    
    /**
     * Reporta una nota de voz / información.
     */
    fun reportInfo(description: String): Boolean =
        reportEvent(EventType.INFO, description)
    
    // ============================================
    // AUTO PATH TRACKING (cada 5 metros)
    // ============================================
    
    /**
     * Inicia el tracking automático de PathPoints.
     */
    private fun startPathTracking() {
        pathTrackingJob?.cancel()
        
        pathTrackingJob = viewModelScope.launch {
            locationSensorManager.orientationFlow.collect { orientation ->
                checkAndSavePathPoint(orientation)
            }
        }
    }
    
    /**
     * Detiene el tracking automático de PathPoints.
     */
    private fun stopPathTracking() {
        pathTrackingJob?.cancel()
        pathTrackingJob = null
    }
    
    /**
     * Verifica si el usuario se ha movido 5+ metros y guarda PathPoint.
     */
    private suspend fun checkAndSavePathPoint(orientation: UserOrientation) {
        val routeId = activeRouteId ?: return
        
        try {
            val currentLocation = orientation.toLocation()
            val lastLocation = lastPathPointLocation
            
            // Verificar si hay que guardar (primera vez o distancia >= 5m)
            val shouldSave = lastLocation == null || 
                lastLocation.distanceTo(currentLocation) >= PATH_POINT_MIN_DISTANCE_METERS
            
            if (shouldSave) {
                // 🛡️ BLINDADO: Operación de DB en Dispatchers.IO
                withContext(Dispatchers.IO) {
                    val pathPoint = PathPoint(
                        routeId = routeId,
                        latitude = orientation.latitude,
                        longitude = orientation.longitude
                    )
                    
                    pathPointDao.insert(pathPoint)
                    lastPathPointLocation = currentLocation
                    
                    val newCount = pathPointDao.getPointCount(routeId)
                    _uiState.update {
                        it.copy(pathPointCount = newCount)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error guardando PathPoint", e)
            // No propagar el error para no interrumpir el tracking
        }
    }
    
    // ============================================
    // HELPERS
    // ============================================
    
    /**
     * Obtiene la orientación actual del usuario.
     */
    private fun getCurrentOrientation(): UserOrientation? {
        val location = locationSensorManager.getLastKnownLocation() ?: return null
        val bearing = locationSensorManager.getLastKnownBearing()
        
        return UserOrientation(
            latitude = location.latitude,
            longitude = location.longitude,
            bearing = bearing,
            accuracy = location.accuracy
        )
    }
    
    /**
     * Obtiene todas las rutas guardadas.
     */
    fun getAllRoutes(): Flow<List<Route>> = routeDao.getAllRoutes()
    
    /**
     * Obtiene los eventos de una ruta.
     */
    fun getEvents(routeId: Long): Flow<List<MapEvent>> =
        mapEventDao.getEventsByRoute(routeId)
    
    /**
     * Obtiene los PathPoints de una ruta.
     */
    fun getPathPoints(routeId: Long): Flow<List<PathPoint>> =
        pathPointDao.getPointsByRoute(routeId)
    
    /**
     * Activa una ruta para navegación guiada.
     */
    fun activateRoute(routeId: Long) {
        viewModelScope.launch {
            routeDao.setActiveRoute(routeId)
        }
    }
    
    /**
     * Elimina una ruta y sus datos asociados.
     */
    fun deleteRoute(route: Route) {
        viewModelScope.launch {
            routeDao.delete(route)
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        if (_uiState.value.isRecording) {
            stopPathTracking()
            locationSensorManager.stopTracking()
        }
    }
}
