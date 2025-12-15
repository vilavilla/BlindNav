package com.blindnav.app.domain

import android.graphics.Rect
import com.blindnav.app.domain.model.DetectedObstacle
import com.blindnav.app.domain.model.HazardLevel
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
    
    // Detector de objetos ML Kit configurado para modo STREAM (tiempo real)
    private val objectDetector: ObjectDetector by lazy {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE) // Optimizado para video
            .enableMultipleObjects() // Detectar múltiples obstáculos
            .enableClassification() // Clasificar tipo de objeto
            .build()
        
        ObjectDetection.getClient(options)
    }

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
                    boundingBox = obj.boundingBox,
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
                processingTimeMs = processingTime
            )
        } catch (e: Exception) {
            // En caso de error, retornar estado seguro pero registrar
            SafetyAnalysisResult(
                hazardLevel = HazardLevel.SAFE,
                obstacles = emptyList(),
                processingTimeMs = System.currentTimeMillis() - startTime
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
            processingTimeMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * LÓGICA DE COLISIÓN HEURÍSTICA
     * 
     * Calcula el nivel de peligro basándose en:
     * 1. Tamaño vertical del objeto más grande respecto a la pantalla
     * 2. Posición horizontal del objeto (centrado = más peligroso)
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

        // Encontrar el obstáculo más grande (más cercano)
        val largestObstacle = obstacles.maxByOrNull { it.boundingBox.height() }
            ?: return HazardLevel.SAFE

        val boxHeight = largestObstacle.boundingBox.height().toFloat()
        val boxCenterX = largestObstacle.centerX.toFloat()
        
        // Calcular ratio de altura
        val heightRatio = boxHeight / imageHeight.toFloat()
        
        // Calcular si está centrado horizontalmente
        val imageCenterX = imageWidth / 2f
        val horizontalOffset = kotlin.math.abs(boxCenterX - imageCenterX)
        val maxOffset = imageWidth * centerTolerance
        val isCentered = horizontalOffset <= maxOffset

        return when {
            // CRITICAL: Objeto grande (>40% altura) Y centrado
            heightRatio > criticalHeightThreshold && isCentered -> HazardLevel.CRITICAL
            
            // WARNING: Objeto de tamaño medio (10-40%) o grande pero no centrado
            heightRatio > safeHeightThreshold -> HazardLevel.WARNING
            
            // SAFE: Objeto pequeño (<10%) - está lejos
            else -> HazardLevel.SAFE
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
                    continuation.resume(objects)
                }
                .addOnFailureListener { exception ->
                    continuation.resumeWithException(exception)
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
        objectDetector.close()
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
                boundingBox = Rect(left, top, right, bottom),
                label = label,
                confidence = 0.9f,
                trackingId = null
            )
        }
    }
}
