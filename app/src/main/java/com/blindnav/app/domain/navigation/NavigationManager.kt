package com.blindnav.app.domain.navigation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.blindnav.app.domain.model.*
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * NavigationManager - Motor de Navegación GPS con Brújula
 * 
 * FUNCIONALIDADES:
 * 1. Obtiene ubicación GPS en tiempo real
 * 2. Lee la orientación del dispositivo (brújula) para saber hacia dónde mira el usuario
 * 3. Calcula el bearing hacia el siguiente waypoint
 * 4. Emite instrucciones de corrección cuando el usuario se desvía
 * 
 * CÁLCULO DE BEARING:
 * ┌────────────────────────────────────────────────────────────┐
 * │                        NORTE (0°)                          │
 * │                           ▲                                │
 * │                           │                                │
 * │              ┌────────────┼────────────┐                   │
 * │              │            │            │                   │
 * │   OESTE ◄────┼──── USER ──┼──── TARGET │──── ESTE (90°)   │
 * │   (270°)     │            │            │                   │
 * │              └────────────┼────────────┘                   │
 * │                           │                                │
 * │                           ▼                                │
 * │                        SUR (180°)                          │
 * └────────────────────────────────────────────────────────────┘
 * 
 * - Current Bearing: Hacia dónde MIRA el usuario (sensor brújula)
 * - Target Bearing: Hacia dónde DEBE IR (calculado desde GPS)
 * - Si la diferencia > 20°, emitir instrucción de corrección
 */
class NavigationManager(private val context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "NavigationManager"
        private const val LOCATION_UPDATE_INTERVAL = 2000L // ms
        private const val FASTEST_LOCATION_INTERVAL = 1000L // ms
        private const val BEARING_THRESHOLD = 20f // grados antes de emitir corrección
        private const val WAYPOINT_REACHED_DISTANCE = 15f // metros para considerar llegado
        private const val INSTRUCTION_COOLDOWN = 5000L // ms entre instrucciones
    }

    // ============================================
    // SERVICIOS DE UBICACIÓN Y SENSORES
    // ============================================
    
    private val fusedLocationClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }
    
    private val sensorManager: SensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    
    private val accelerometer: Sensor? by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }
    
    private val magnetometer: Sensor? by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    }

    // ============================================
    // ESTADO DE NAVEGACIÓN
    // ============================================
    
    private val _navigationState = MutableStateFlow(NavigationState())
    val navigationState: StateFlow<NavigationState> = _navigationState.asStateFlow()
    
    private val _instructions = MutableSharedFlow<NavigationInstruction>(replay = 0)
    val instructions: SharedFlow<NavigationInstruction> = _instructions.asSharedFlow()
    
    private val _spokenInstructions = MutableSharedFlow<String>(replay = 0)
    val spokenInstructions: SharedFlow<String> = _spokenInstructions.asSharedFlow()
    
    // Variables internas
    private var currentRoute: NavigationRoute? = null
    private var currentWaypointIndex = 0
    private var lastInstructionTime = 0L
    
    // Datos de sensores para calcular orientación
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var currentBearing = 0f
    
    // Coroutine scope
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Location callback
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                processLocationUpdate(location)
            }
        }
    }

    // ============================================
    // API PÚBLICA
    // ============================================
    
    /**
     * Inicia la navegación hacia un destino.
     */
    fun startNavigation(route: NavigationRoute) {
        Log.d(TAG, "Iniciando navegación hacia: ${route.destination}")
        
        currentRoute = route
        currentWaypointIndex = 0
        
        _navigationState.value = NavigationState(
            isNavigating = true,
            currentRoute = route,
            currentWaypointIndex = 0
        )
        
        startLocationUpdates()
        startCompassUpdates()
        
        // Emitir mensaje inicial
        scope.launch {
            _spokenInstructions.emit("Navegación iniciada hacia ${route.destination}. " +
                "Distancia total: ${route.totalDistanceMeters.toInt()} metros.")
        }
    }
    
    /**
     * Detiene la navegación actual.
     */
    fun stopNavigation() {
        Log.d(TAG, "Deteniendo navegación")
        
        stopLocationUpdates()
        stopCompassUpdates()
        
        currentRoute = null
        currentWaypointIndex = 0
        
        _navigationState.value = NavigationState(isNavigating = false)
        
        scope.launch {
            _spokenInstructions.emit("Navegación detenida.")
        }
    }
    
    /**
     * Obtiene la última ubicación conocida.
     */
    suspend fun getLastLocation(): Location? {
        return if (hasLocationPermission()) {
            try {
                fusedLocationClient.lastLocation.await()
            } catch (e: SecurityException) {
                Log.e(TAG, "Error de permisos: ${e.message}")
                null
            }
        } else {
            null
        }
    }

    // ============================================
    // PROCESAMIENTO DE UBICACIÓN
    // ============================================
    
    private fun processLocationUpdate(location: Location) {
        val route = currentRoute ?: return
        
        if (currentWaypointIndex >= route.waypoints.size) {
            // Llegamos al destino
            handleArrival()
            return
        }
        
        val targetWaypoint = route.waypoints[currentWaypointIndex]
        val targetLocation = targetWaypoint.toLocation()
        
        // Calcular distancia al siguiente waypoint
        val distanceToNext = location.distanceTo(targetLocation)
        
        // Calcular bearing hacia el destino
        val targetBearing = location.bearingTo(targetLocation)
        val normalizedTargetBearing = if (targetBearing < 0) targetBearing + 360 else targetBearing
        
        // Actualizar estado
        _navigationState.value = _navigationState.value.copy(
            currentLocation = location,
            currentBearing = currentBearing,
            targetBearing = normalizedTargetBearing,
            distanceToNextPoint = distanceToNext
        )
        
        // Comprobar si llegamos al waypoint
        if (distanceToNext < WAYPOINT_REACHED_DISTANCE) {
            handleWaypointReached()
            return
        }
        
        // Emitir instrucciones de corrección si es necesario
        checkAndEmitInstruction()
    }
    
    private fun handleWaypointReached() {
        val route = currentRoute ?: return
        
        currentWaypointIndex++
        
        if (currentWaypointIndex >= route.waypoints.size) {
            handleArrival()
        } else {
            val nextWaypoint = route.waypoints[currentWaypointIndex]
            
            _navigationState.value = _navigationState.value.copy(
                currentWaypointIndex = currentWaypointIndex
            )
            
            scope.launch {
                if (nextWaypoint.instruction.isNotEmpty()) {
                    _spokenInstructions.emit(nextWaypoint.instruction)
                } else {
                    _spokenInstructions.emit("Continúa hacia ${nextWaypoint.name}")
                }
            }
        }
    }
    
    private fun handleArrival() {
        scope.launch {
            _instructions.emit(NavigationInstruction.ARRIVED)
            _spokenInstructions.emit("Has llegado a tu destino.")
        }
        
        _navigationState.value = _navigationState.value.copy(
            arrived = true,
            isNavigating = false
        )
        
        stopNavigation()
    }
    
    private fun checkAndEmitInstruction() {
        val now = System.currentTimeMillis()
        
        // Cooldown entre instrucciones
        if (now - lastInstructionTime < INSTRUCTION_COOLDOWN) {
            return
        }
        
        val state = _navigationState.value
        val bearingDiff = state.bearingDifference
        
        val instruction = when {
            kotlin.math.abs(bearingDiff) <= BEARING_THRESHOLD -> {
                // Vamos bien, solo confirmar si hace mucho que no hablamos
                if (now - lastInstructionTime > 15000) {
                    NavigationInstruction.CONTINUE_STRAIGHT
                } else {
                    null
                }
            }
            bearingDiff > 90 -> NavigationInstruction.TURN_SHARP_RIGHT
            bearingDiff > 45 -> NavigationInstruction.TURN_RIGHT
            bearingDiff > 20 -> NavigationInstruction.TURN_SLIGHT_RIGHT
            bearingDiff < -90 -> NavigationInstruction.TURN_SHARP_LEFT
            bearingDiff < -45 -> NavigationInstruction.TURN_LEFT
            bearingDiff < -20 -> NavigationInstruction.TURN_SLIGHT_LEFT
            else -> null
        }
        
        instruction?.let {
            lastInstructionTime = now
            
            scope.launch {
                _instructions.emit(it)
                _spokenInstructions.emit(instructionToSpokenText(it, state.distanceToNextPoint))
            }
        }
    }
    
    private fun instructionToSpokenText(instruction: NavigationInstruction, distance: Float): String {
        val distanceText = when {
            distance < 10 -> "muy cerca"
            distance < 50 -> "a ${distance.toInt()} metros"
            distance < 100 -> "a unos ${(distance / 10).toInt() * 10} metros"
            else -> "a ${(distance / 100).toInt() * 100} metros"
        }
        
        return when (instruction) {
            NavigationInstruction.CONTINUE_STRAIGHT -> "Continúa recto. Siguiente punto $distanceText."
            NavigationInstruction.TURN_SLIGHT_LEFT -> "Gira levemente a la izquierda."
            NavigationInstruction.TURN_LEFT -> "Gira a la izquierda."
            NavigationInstruction.TURN_SHARP_LEFT -> "Gira bruscamente a la izquierda."
            NavigationInstruction.TURN_SLIGHT_RIGHT -> "Gira levemente a la derecha."
            NavigationInstruction.TURN_RIGHT -> "Gira a la derecha."
            NavigationInstruction.TURN_SHARP_RIGHT -> "Gira bruscamente a la derecha."
            NavigationInstruction.U_TURN -> "Da la vuelta."
            NavigationInstruction.ARRIVED -> "Has llegado a tu destino."
            NavigationInstruction.RECALCULATING -> "Recalculando ruta."
        }
    }

    // ============================================
    // SERVICIOS DE UBICACIÓN
    // ============================================
    
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            Log.e(TAG, "No hay permisos de ubicación")
            return
        }
        
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            LOCATION_UPDATE_INTERVAL
        ).apply {
            setMinUpdateIntervalMillis(FASTEST_LOCATION_INTERVAL)
            setWaitForAccurateLocation(false)
        }.build()
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "Location updates iniciados")
        } catch (e: SecurityException) {
            Log.e(TAG, "Error iniciando location updates: ${e.message}")
        }
    }
    
    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
        Log.d(TAG, "Location updates detenidos")
    }
    
    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ============================================
    // BRÚJULA (SENSOR DE ORIENTACIÓN)
    // ============================================
    
    private fun startCompassUpdates() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        Log.d(TAG, "Compass updates iniciados")
    }
    
    private fun stopCompassUpdates() {
        sensorManager.unregisterListener(this)
        Log.d(TAG, "Compass updates detenidos")
    }
    
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, gravity, 0, 3)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, geomagnetic, 0, 3)
            }
        }
        
        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)
        
        if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            
            // Convertir radianes a grados y normalizar a [0, 360)
            var azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            if (azimuth < 0) azimuth += 360
            
            currentBearing = azimuth
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No necesitamos manejar esto por ahora
    }

    // ============================================
    // UTILIDADES
    // ============================================
    
    /**
     * Extensión para convertir Task a suspend function.
     */
    private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T? {
        return suspendCancellableCoroutine { continuation ->
            addOnSuccessListener { result ->
                continuation.resume(result) {}
            }
            addOnFailureListener { exception ->
                continuation.resume(null) {}
            }
        }
    }

    // ============================================
    // LIMPIEZA
    // ============================================
    
    fun release() {
        stopNavigation()
        scope.cancel()
        Log.d(TAG, "NavigationManager liberado")
    }
}
