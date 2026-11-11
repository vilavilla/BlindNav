package com.blindnav.app.domain

import com.blindnav.app.domain.model.DetectedObstacle
import com.blindnav.app.domain.model.HazardLevel
import com.blindnav.app.domain.model.ObstacleBounds
import com.blindnav.app.domain.model.SafetyAnalysisResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SafetyAnalyzer - Motor de Seguridad Principal
 * 
 * Analiza frames de la cámara para detectar obstáculos y calcular
 * el nivel de peligro basado en heurísticas de proximidad.
 * 
 * 🔴 MODO CROSSING_GUARD (NUEVO):
 * - Hipersensible a vehículos: Car, Bus, Truck, Motorcycle
 * - Umbral reducido: 20% altura = CRITICAL (vs 40% normal)
 * - Alerta inmediata si vehículo detectado cerca
 * 
 * LÓGICA DE COLISIÓN HEURÍSTICA:
 * - Se basa en el tamaño relativo del BoundingBox respecto a la imagen
 * - Un objeto que ocupa más del 40% vertical y está centrado = PELIGRO CRÍTICO
 * - Un objeto pequeño (<10%) = SEGURO (lejos)
 * - Intermedio = WARNING
 * 
 * @property criticalHeightThreshold Umbral de altura para nivel CRITICAL (default 40%)
 * @property safeHeightThreshold Umbral de altura para nivel SAFE (default 10%)
 * @property centerTolerance Tolerancia para considerar un objeto "centrado" (default 30%)
 */
class SafetyAnalyzer(
    private val criticalHeightThreshold: Float = 0.4f,
    private val safeHeightThreshold: Float = 0.1f,
    private val centerTolerance: Float = 0.3f
) {
    
    /**
     * Modo de análisis de seguridad
     */
    enum class AnalysisMode {
        /** Modo normal de detección de obstáculos */
        NORMAL,
        
        /** 🔴 Modo CROSSING GUARD - Hipersensible a vehículos */
        CROSSING_GUARD
    }
    
    // Estado actual del analizador
    private var currentMode: AnalysisMode = AnalysisMode.NORMAL
    
    // Etiquetas de vehículos para detección prioritaria
    private val vehicleLabels = setOf(
        "car", "bus", "truck", "motorcycle", "bicycle",
        "vehicle", "automobile", "van"
    )
    
    // Analizar cada imagen de forma independiente evita que el detector dependa
    // de marcas de tiempo perfectas. Algunas cámaras y el emulador pueden entregar
    // dos frames con la misma marca y ML Kit rechaza entonces todo el flujo.
    private val detectorDelegate = lazy {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects() // Detectar múltiples obstáculos
            .enableClassification() // Clasificar tipo de objeto
            .build()
        
        ObjectDetection.getClient(options)
    }
    private val objectDetector: ObjectDetector get() = detectorDelegate.value
    
    /**
     * 🔴 Activa modo CROSSING GUARD - Hipersensible a vehículos
     */
    fun enableCrossingGuardMode() {
        currentMode = AnalysisMode.CROSSING_GUARD
    }
    
    /**
     * Desactiva modo CROSSING GUARD y vuelve a modo normal
     */
    fun disableCrossingGuardMode() {
        currentMode = AnalysisMode.NORMAL
    }
    
    /**
     * 🔴 DETECCIÓN DE TRÁFICO HIPERSENSIBLE
     * 
     * Detecta vehículos cercanos con umbral reducido (20% vs 40%)
     * Usado durante cruces de calle para máxima seguridad
     * 
     * @return true si hay vehículo peligroso cerca
     */
    suspend fun checkForTraffic(
        inputImage: InputImage,
        imageWidth: Int,
        imageHeight: Int
    ): TrafficDetectionResult = withContext(Dispatchers.Default) {
        try {
            val detectedObjects = detectObjects(inputImage)
            
            // Filtrar solo vehículos
            val vehicles = detectedObjects.filter { obj ->
                obj.labels.any { label ->
                    vehicleLabels.contains(label.text.lowercase())
                }
            }
            
            if (vehicles.isEmpty()) {
                return@withContext TrafficDetectionResult(
                    hasVehicle = false,
                    isCritical = false,
                    message = "No se identifican vehículos. No confirma que se pueda cruzar."
                )
            }
            
            // Analizar vehículo más grande (más cercano)
            val largestVehicle = vehicles.maxByOrNull { it.boundingBox.height() }!!
            val heightRatio = largestVehicle.boundingBox.height().toFloat() / imageHeight
            
            // 🔴 UMBRAL REDUCIDO para cruces: 20% en lugar de 40%
            val isCritical = heightRatio > 0.2f
            
            val vehicleLabel = largestVehicle.labels.firstOrNull()?.text ?: "vehículo"
            val message = if (isCritical) {
                "¡$vehicleLabel CERCA! ESPERA"
            } else {
                "$vehicleLabel detectado a distancia"
            }
            
            TrafficDetectionResult(
                hasVehicle = true,
                isCritical = isCritical,
                message = message,
                vehicleType = vehicleLabel,
                distance = if (isCritical) "CERCA" else "LEJOS"
            )
        } catch (e: Exception) {
            TrafficDetectionResult(
                hasVehicle = false,
                isCritical = true,
                message = "Detección no disponible. No cruces.",
                isOperational = false
            )
        }
    }
    
    /**
     * Resultado de detección de tráfico
     */
    data class TrafficDetectionResult(
        val hasVehicle: Boolean,
        val isCritical: Boolean,
        val message: String,
        val vehicleType: String? = null,
        val distance: String? = null,
        val isOperational: Boolean = true
    )

    /**
     * Analiza un frame de imagen y retorna el resultado de seguridad.
     * 
     * @param inputImage Imagen del frame de la cámara
     * @param imageWidth Ancho de la imagen en píxeles
     * @param imageHeight Alto de la imagen en píxeles
     * @return SafetyAnalysisResult con el nivel de peligro y obstáculos detectados
     */
    suspend fun analyze(
        inputImage: InputImage,
        imageWidth: Int,
        imageHeight: Int
    ): SafetyAnalysisResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        
        try {
            // Ejecutar detección de objetos de ML Kit
            val detectedObjects = detectObjects(inputImage)
            
            // Convertir a nuestro modelo de dominio
            val obstacles = detectedObjects.map { obj ->
                DetectedObstacle(
                    boundingBox = ObstacleBounds(
                        left = obj.boundingBox.left,
                        top = obj.boundingBox.top,
                        right = obj.boundingBox.right,
                        bottom = obj.boundingBox.bottom
                    ),
                    label = obj.labels.firstOrNull()?.text,
                    confidence = obj.labels.firstOrNull()?.confidence ?: 0f,
                    trackingId = obj.trackingId
                )
            }
            
            // Calcular nivel de peligro usando heurística
            val hazardLevel = calculateHazardLevel(obstacles, imageWidth, imageHeight)
            
            val processingTime = System.currentTimeMillis() - startTime
            
            SafetyAnalysisResult(
                hazardLevel = hazardLevel,
                obstacles = obstacles,
                processingTimeMs = processingTime,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )
        } catch (e: Exception) {
            // Un fallo del detector no puede presentarse como un entorno seguro.
            SafetyAnalysisResult(
                hazardLevel = HazardLevel.WARNING,
                obstacles = emptyList(),
                processingTimeMs = System.currentTimeMillis() - startTime,
                isOperational = false,
                errorMessage = e.message ?: "Detector no disponible",
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )
        }
    }

    /**
     * Versión simplificada para testing sin ML Kit.
     * Calcula el nivel de peligro directamente desde obstáculos simulados.
     * 
     * @param obstacles Lista de obstáculos (puede ser simulada para tests)
     * @param imageWidth Ancho de referencia de la imagen
     * @param imageHeight Alto de referencia de la imagen
     * @return HazardLevel calculado
     */
    fun analyzeObstacles(
        obstacles: List<DetectedObstacle>,
        imageWidth: Int,
        imageHeight: Int
    ): SafetyAnalysisResult {
        val startTime = System.currentTimeMillis()
        val hazardLevel = calculateHazardLevel(obstacles, imageWidth, imageHeight)
        
        return SafetyAnalysisResult(
            hazardLevel = hazardLevel,
            obstacles = obstacles,
            processingTimeMs = System.currentTimeMillis() - startTime,
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
    }

    /**
     * LÓGICA DE COLISIÓN HEURÍSTICA
     * 
     * Calcula el nivel de peligro basándose en:
     * 1. Tamaño vertical del objeto más grande respecto a la pantalla
     * 2. Posición horizontal del objeto (centrado = más peligroso)
     * 3. 🔴 Modo CROSSING_GUARD: Umbral reducido si detecta vehículos
     * 
     * @param obstacles Lista de obstáculos detectados
     * @param imageWidth Ancho de la imagen
     * @param imageHeight Alto de la imagen
     * @return HazardLevel calculado
     */
    fun calculateHazardLevel(
        obstacles: List<DetectedObstacle>,
        imageWidth: Int,
        imageHeight: Int
    ): HazardLevel {
        if (obstacles.isEmpty()) {
            return HazardLevel.SAFE
        }

        // 🔴 En modo CROSSING_GUARD, priorizar vehículos
        if (imageWidth <= 0 || imageHeight <= 0) return HazardLevel.WARNING
        val relevantObstacles = obstacles

        // Encontrar el obstáculo más grande (más cercano)
        return relevantObstacles.maxOf { largestObstacle ->

        val boxHeight = largestObstacle.boundingBox.height().toFloat()
        val boxCenterX = largestObstacle.centerX.toFloat()
        
        // Calcular ratio de altura
        val heightRatio = boxHeight / imageHeight.toFloat()
        
        // Calcular si está centrado horizontalmente
        val imageCenterX = imageWidth / 2f
        val horizontalOffset = kotlin.math.abs(boxCenterX - imageCenterX)
        val maxOffset = imageWidth * centerTolerance
        val isCentered = horizontalOffset <= maxOffset
        
        // 🔴 Ajustar umbrales si estamos en modo CROSSING_GUARD
        val criticalThreshold = if (currentMode == AnalysisMode.CROSSING_GUARD) {
            0.2f // Umbral reducido para cruces
        } else {
            criticalHeightThreshold
        }

        when {
            // CRITICAL: Objeto grande Y centrado
            heightRatio > criticalThreshold && isCentered -> HazardLevel.CRITICAL
            
            // WARNING: Objeto de tamaño medio o grande pero no centrado
            heightRatio > safeHeightThreshold -> HazardLevel.WARNING
            
            // SAFE: Objeto pequeño - está lejos
            else -> HazardLevel.SAFE
        }
        }
    }

    /**
     * Ejecuta la detección de objetos usando ML Kit de forma suspendible.
     */
    private suspend fun detectObjects(
        inputImage: InputImage
    ): List<com.google.mlkit.vision.objects.DetectedObject> = 
        suspendCancellableCoroutine { continuation ->
            objectDetector.process(inputImage)
                .addOnSuccessListener { objects ->
                    if (continuation.isActive) continuation.resume(objects)
                }
                .addOnFailureListener { exception ->
                    if (continuation.isActive) continuation.resumeWithException(exception)
                }
                .addOnCanceledListener {
                    continuation.cancel()
                }
        }

    /**
     * Libera recursos del detector.
     * Llamar cuando se destruya la Activity/Fragment.
     */
    fun close() {
        if (detectorDelegate.isInitialized()) objectDetector.close()
    }

    companion object {
        /**
         * Crea un obstáculo simulado para testing.
         * 
         * @param left Posición izquierda del BoundingBox
         * @param top Posición superior del BoundingBox
         * @param right Posición derecha del BoundingBox
         * @param bottom Posición inferior del BoundingBox
         * @param label Etiqueta opcional del objeto
         * @return DetectedObstacle simulado
         */
        fun createTestObstacle(
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            label: String? = "obstacle"
        ): DetectedObstacle {
            return DetectedObstacle(
                boundingBox = ObstacleBounds(left, top, right, bottom),
                label = label,
                confidence = 0.9f,
                trackingId = null
            )
        }
    }
}
