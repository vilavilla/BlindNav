package com.blindnav.app.domain

import android.graphics.Rect
import com.blindnav.app.domain.model.DetectedObstacle
import com.blindnav.app.domain.model.HazardLevel
import com.blindnav.app.ui.feedback.FeedbackManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations

/**
 * SafetyIntegrationTest - Tests de Integración
 * 
 * Verifica que el SafetyAnalyzer se integra correctamente
 * con el FeedbackManager para disparar alertas.
 * 
 * Usa Mockito para simular el FeedbackManager.
 */
class SafetyIntegrationTest {

    private lateinit var safetyAnalyzer: SafetyAnalyzer

    @Mock
    private lateinit var mockFeedbackManager: FeedbackManager

    private val imageWidth = 640
    private val imageHeight = 480

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        safetyAnalyzer = SafetyAnalyzer()
    }

    @Test
    fun `when small object detected, FeedbackManager alert should NOT be called with CRITICAL`() {
        // GIVEN: Objeto pequeño
        val smallObstacle = DetectedObstacle(
            boundingBox = Rect(300, 200, 340, 224),
            label = "obstacle",
            confidence = 0.9f
        )
        
        // WHEN: Analizamos
        val result = safetyAnalyzer.analyzeObstacles(
            obstacles = listOf(smallObstacle),
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
        
        // THEN: El nivel NO es CRITICAL
        assertNotEquals(HazardLevel.CRITICAL, result.hazardLevel)
        
        // Simular llamada al FeedbackManager
        mockFeedbackManager.alert(result.hazardLevel)
        
        // Verificar que NO se llamó con CRITICAL
        verify(mockFeedbackManager, never()).alert(HazardLevel.CRITICAL)
        verify(mockFeedbackManager, times(1)).alert(HazardLevel.SAFE)
    }

    @Test
    fun `when large centered object detected, FeedbackManager alert MUST be called with CRITICAL`() {
        // GIVEN: Objeto grande y centrado (>40% altura)
        val largeObstacle = DetectedObstacle(
            boundingBox = Rect(220, 84, 420, 324), // 50% altura, centrado
            label = "obstacle",
            confidence = 0.9f
        )
        
        // WHEN: Analizamos
        val result = safetyAnalyzer.analyzeObstacles(
            obstacles = listOf(largeObstacle),
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
        
        // THEN: El nivel ES CRITICAL
        assertEquals(HazardLevel.CRITICAL, result.hazardLevel)
        assertTrue(result.requiresAlert)
        
        // Simular llamada al FeedbackManager
        mockFeedbackManager.alert(result.hazardLevel)
        
        // Verificar que SE LLAMÓ con CRITICAL
        verify(mockFeedbackManager, times(1)).alert(HazardLevel.CRITICAL)
    }

    @Test
    fun `approaching object scenario triggers escalating alerts`() {
        // Simular secuencia de frames con objeto acercándose
        val frames = listOf(
            // Frame 1: Lejos (5%)
            DetectedObstacle(Rect(310, 230, 330, 254), "obj", 0.9f),
            // Frame 2: Medio (25%)
            DetectedObstacle(Rect(270, 180, 370, 300), "obj", 0.9f),
            // Frame 3: Cerca (45%)
            DetectedObstacle(Rect(220, 84, 420, 300), "obj", 0.9f)
        )
        
        val expectedLevels = listOf(
            HazardLevel.SAFE,
            HazardLevel.WARNING,
            HazardLevel.CRITICAL
        )
        
        // Procesar cada frame y simular alertas
        frames.forEachIndexed { index, obstacle ->
            val result = safetyAnalyzer.analyzeObstacles(
                obstacles = listOf(obstacle),
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )
            
            assertEquals(
                "Frame ${index + 1} debería tener nivel ${expectedLevels[index]}",
                expectedLevels[index],
                result.hazardLevel
            )
            
            // Simular que el FeedbackManager recibe la alerta
            mockFeedbackManager.alert(result.hazardLevel)
        }
        
        // Verificar secuencia de llamadas
        val inOrder = inOrder(mockFeedbackManager)
        inOrder.verify(mockFeedbackManager).alert(HazardLevel.SAFE)
        inOrder.verify(mockFeedbackManager).alert(HazardLevel.WARNING)
        inOrder.verify(mockFeedbackManager).alert(HazardLevel.CRITICAL)
    }

    @Test
    fun `SafetyAnalyzer performance test - must process in under 50ms`() {
        // GIVEN: Múltiples obstáculos
        val obstacles = (1..10).map { i ->
            DetectedObstacle(
                boundingBox = Rect(i * 50, i * 30, i * 50 + 40, i * 30 + 40),
                label = "obstacle_$i",
                confidence = 0.8f
            )
        }
        
        // WHEN: Medimos tiempo de procesamiento
        val startTime = System.nanoTime()
        
        repeat(100) {
            safetyAnalyzer.analyzeObstacles(
                obstacles = obstacles,
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )
        }
        
        val endTime = System.nanoTime()
        val avgTimeMs = (endTime - startTime) / 1_000_000.0 / 100
        
        // THEN: El procesamiento debe ser rápido (< 50ms por frame)
        println("Tiempo promedio de análisis: ${avgTimeMs}ms")
        assertTrue(
            "El análisis debe completarse en menos de 50ms (fue: ${avgTimeMs}ms)",
            avgTimeMs < 50
        )
    }
}
