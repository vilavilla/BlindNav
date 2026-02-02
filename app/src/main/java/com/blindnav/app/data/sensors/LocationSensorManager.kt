package com.blindnav.app.data.sensors

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
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * UserOrientation - Estado de orientación del usuario
 * 
 * @property latitude Latitud GPS
 * @property longitude Longitud GPS
 * @property bearing Azimut/heading en grados (0-360, donde 0=Norte)
 * @property accuracy Precisión del GPS en metros
 * @property timestamp Timestamp de la medición
 */
data class UserOrientation(
    val latitude: Double,
    val longitude: Double,
    val bearing: Float,
    val accuracy: Float,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Convierte a Location de Android para cálculos de distancia/bearing.
     */
    fun toLocation(): Location {
        return Location("UserOrientation").apply {
            latitude = this@UserOrientation.latitude
            longitude = this@UserOrientation.longitude
            bearing = this@UserOrientation.bearing
            accuracy = this@UserOrientation.accuracy
        }
    }
}

/**
 * LocationSensorManager - Gestor de sensores de ubicación y orientación
 * 
 * Combina:
 * - GPS (FusedLocationProviderClient) para posición precisa
 * - Brújula (Magnetómetro + Acelerómetro) para orientación (heading)
 * 
 * Emite un Flow<UserOrientation> con la posición y hacia dónde mira el usuario.
 */
class LocationSensorManager(
    private val context: Context
) : SensorEventListener {
    
    companion object {
        private const val TAG = "LocationSensorManager"
        
        // Intervalo de actualización GPS (milisegundos)
        private const val GPS_UPDATE_INTERVAL = 2000L
        private const val GPS_FASTEST_INTERVAL = 1000L
        
        // ✓ MEJORADO: Filtro low-pass MUY agresivo para eliminar jitter
        // smoothAzimuth = (azimuth * 0.05) + (lastAzimuth * 0.95)
        private const val COMPASS_ALPHA = 0.05f // Era 0.15f - Ahora MUCHO más estable
    }
    
    // ============================================
    // FLUJOS DE DATOS
    // ============================================
    
    private val _orientationFlow = MutableSharedFlow<UserOrientation>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val orientationFlow: Flow<UserOrientation> = _orientationFlow.asSharedFlow()
    
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()
    
    // ============================================
    // COMPONENTES DE SENSORES
    // ============================================
    
    // GPS
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    
    private var locationCallback: LocationCallback? = null
    
    // ✓ BRÚJULA MEJORADA: Rotation Vector (fusión de sensores por hardware)
    // ABANDONA: Magnetometer + Accelerometer (antiguos, inestables)
    // USA: TYPE_ROTATION_VECTOR (fusión por hardware de acelerómetro + giróscopos + magnetómetro)
    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    private val rotationVectorSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    
    // Fallback si el dispositivo no tiene Rotation Vector
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    private val magnetometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    
    // Últimas lecturas de sensores
    private var lastAccelerometer = FloatArray(3)
    private var lastMagnetometer = FloatArray(3)
    private var hasAccelerometer = false
    private var hasMagnetometer = false
    
    // ✓ Rotation Vector (modo prioritario)
    private var usingRotationVector = false
    private var lastRotationVector = FloatArray(4)
    
    // Matrices de rotación
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    
    // Estado actual
    private var currentLocation: Location? = null
    private var currentBearing: Float = 0f
    private var smoothedBearing: Float = 0f
    
    // ============================================
    // CONTROL DE CICLO DE VIDA
    // ============================================
    
    /**
     * Inicia el tracking de ubicación y orientación.
     */
    fun startTracking() {
        if (_isTracking.value) return
        
        startLocationUpdates()
        startCompassUpdates()
        _isTracking.value = true
    }
    
    /**
     * Detiene el tracking y libera recursos.
     */
    fun stopTracking() {
        stopLocationUpdates()
        stopCompassUpdates()
        _isTracking.value = false
    }
    
    /**
     * Libera todos los recursos.
     */
    fun release() {
        stopTracking()
    }
    
    // ============================================
    // GPS (FusedLocationProviderClient)
    // ============================================
    
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            GPS_UPDATE_INTERVAL
        ).apply {
            setMinUpdateIntervalMillis(GPS_FASTEST_INTERVAL)
            setWaitForAccurateLocation(false)
        }.build()
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    currentLocation = location
                    emitOrientation()
                }
            }
        }
        
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
    
    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
    }
    
    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    // ============================================
    // BRÚJULA (Rotation Vector o Fallback)
    // ============================================
    
    private fun startCompassUpdates() {
        // ✓ PRIORIDAD 1: Rotation Vector (MUCHO más estable)
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(
                this,
                rotationVectorSensor,
                SensorManager.SENSOR_DELAY_UI
            )
            usingRotationVector = true
            Log.d(TAG, "✓ BRÚJULA: Usando Rotation Vector (fusión hardware)")
        } 
        // Fallback: Método antiguo (menos estable)
        else {
            accelerometer?.let {
                sensorManager.registerListener(
                    this,
                    it,
                    SensorManager.SENSOR_DELAY_UI
                )
            }
            
            magnetometer?.let {
                sensorManager.registerListener(
                    this,
                    it,
                    SensorManager.SENSOR_DELAY_UI
                )
            }
            usingRotationVector = false
            Log.d(TAG, "⚠️ BRÚJULA: Fallback a Magnetometer+Accelerometer (menos preciso)")
        }
    }
    
    private fun stopCompassUpdates() {
        sensorManager.unregisterListener(this)
        hasAccelerometer = false
        hasMagnetometer = false
    }
    
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            // ✓ PRIORIDAD: Rotation Vector (hardware fusion)
            Sensor.TYPE_ROTATION_VECTOR -> {
                System.arraycopy(event.values, 0, lastRotationVector, 0, event.values.size)
                updateCompassFromRotationVector()
            }
            
            // Fallback: Método antiguo
            Sensor.TYPE_ACCELEROMETER -> {
                if (!usingRotationVector) {
                    lowPassFilter(event.values, lastAccelerometer)
                    hasAccelerometer = true
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                if (!usingRotationVector) {
                    lowPassFilter(event.values, lastMagnetometer)
                    hasMagnetometer = true
                }
            }
        }
        
        // Solo usar método antiguo si no hay Rotation Vector
        if (!usingRotationVector && hasAccelerometer && hasMagnetometer) {
            updateCompassBearingLegacy()
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Avisos de calibración si accuracy es bajo
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            Log.w(TAG, "⚠️ Sensor ${sensor?.name} poco preciso - Requiere calibración")
        }
    }
    
    /**
     * ✓ NUEVO: Calcular azimut desde Rotation Vector (MÉTODO PREFERIDO)
     * 
     * Rotation Vector es la fusión de:
     * - Acelerómetro (gravedad)
     * - Giróscopos (rotación angular)
     * - Magnetómetro (campo magnético)
     * 
     * Resultado: MUCHO más estable que combinar sensores manualmente
     */
    private fun updateCompassFromRotationVector() {
        // Convertir rotation vector a matriz de rotación
        SensorManager.getRotationMatrixFromVector(rotationMatrix, lastRotationVector)
        
        // Extraer orientación (azimut, pitch, roll)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        
        // orientationAngles[0] = azimut en radianes (-π a π)
        var azimuthDegrees = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        
        // Normalizar a 0-360
        if (azimuthDegrees < 0) {
            azimuthDegrees += 360f
        }
        
        // ✓ Suavizar con filtro AGRESIVO (alpha=0.05)
        smoothedBearing = smoothBearing(smoothedBearing, azimuthDegrees)
        currentBearing = smoothedBearing
        
        emitOrientation()
    }
    
    /**
     * [LEGACY] Calcular azimut usando método antiguo (Magnetometer + Accelerometer)
     * Solo se usa si el dispositivo no tiene Rotation Vector
     */
    private fun updateCompassBearingLegacy() {
        val success = SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            lastAccelerometer,
            lastMagnetometer
        )
        
        if (success) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            
            // orientationAngles[0] = azimut en radianes (-π a π)
            var azimuthDegrees = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            
            // Normalizar a 0-360
            if (azimuthDegrees < 0) {
                azimuthDegrees += 360f
            }
            
            // Suavizar con exponential moving average
            smoothedBearing = smoothBearing(smoothedBearing, azimuthDegrees)
            currentBearing = smoothedBearing
            
            emitOrientation()
        }
    }
    
    /**
     * Suaviza el bearing para evitar saltos bruscos.
     * ✓ Usa alpha=0.05 para eliminar jitter (muy agresivo)
     * Formula: smoothed = current + 0.05 * (target - current)
     * 
     * Tiene en cuenta el cruce del 0/360.
     */
    private fun smoothBearing(current: Float, target: Float): Float {
        var delta = target - current
        
        // Ajustar para el cruce 0/360
        if (delta > 180) delta -= 360
        if (delta < -180) delta += 360
        
        var result = current + COMPASS_ALPHA * delta
        
        // Normalizar
        if (result < 0) result += 360
        if (result >= 360) result -= 360
        
        return result
    }
    
    /**
     * Filtro low-pass para suavizar lecturas de sensores.
     */
    private fun lowPassFilter(input: FloatArray, output: FloatArray) {
        for (i in input.indices) {
            output[i] = output[i] + COMPASS_ALPHA * (input[i] - output[i])
        }
    }
    
    // ============================================
    // EMISIÓN DE ORIENTACIÓN
    // ============================================
    
    private fun emitOrientation() {
        val location = currentLocation ?: return
        
        val orientation = UserOrientation(
            latitude = location.latitude,
            longitude = location.longitude,
            bearing = currentBearing,
            accuracy = location.accuracy
        )
        
        _orientationFlow.tryEmit(orientation)
    }
    
    /**
     * Obtiene la última ubicación conocida de forma síncrona.
     */
    fun getLastKnownLocation(): Location? = currentLocation
    
    /**
     * Obtiene el último bearing conocido.
     */
    fun getLastKnownBearing(): Float = currentBearing
    
    /**
     * Obtiene la orientación actual de forma síncrona.
     */
    fun getCurrentOrientation(): UserOrientation {
        val location = currentLocation
        return UserOrientation(
            latitude = location?.latitude ?: 0.0,
            longitude = location?.longitude ?: 0.0,
            bearing = currentBearing,
            accuracy = location?.accuracy ?: 0f
        )
    }
}
