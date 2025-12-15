package com.blindnav.app.domain

import android.graphics.Rect
import com.blindnav.app.domain.model.DetectedObstacle
import com.blindnav.app.domain.model.HazardLevel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * SafetyTest - Tests Unitarios del Motor de Seguridad
 * 
 * Valida la lógica de colisión heurística sin necesidad de
 * ejecutar en un dispositivo Android real.
 * 
 * CASOS DE PRUEBA:
 * 1. Objeto pequeño (lejos) -> SAFE
 * 2. Objeto mediano -> WARNING  
 * 3. Objeto grande y centrado -> CRITICAL
 * 4. Objeto grande pero lateral -> WARNING (no tan peligroso)
 * 5. Simulación de acercamiento frame a frame
 */
class SafetyTest {

    private lateinit var safetyAnalyzer: SafetyAnalyzer
    
    // Dimensiones de imagen de referencia (640x480)
    private val imageWidth = 640
    private val imageHeight = 480

    @Before
    fun setUp() {
        // Inicializar el analizador con umbrales por defecto
        // criticalHeightThreshold = 0.4 (40%)
        // safeHeightThreshold = 0.1 (10%)
        // centerTolerance = 0.3 (30%)
        safetyAnalyzer = SafetyAnalyzer()
    }

    // ============================================
    // CASO 1: OBJETO PEQUEÑO (LEJOS) -> SAFE
    // ============================================

    @Test
    fun `when object is small and far, hazard level should be SAFE`() {
        // GIVEN: Un objeto que ocupa solo el 5% de la altura (muy lejos)
        // altura = 480 * 0.05 = 24 píxeles
        val smallObstacle = createObstacle(
            left = 300,
            top = 200,
            right = 340,      // ancho = 40px
            bottom = 224      // alto = 24px (5% de 480)
        )
        
        // WHEN: Analizamos el obstáculo
        val result = safetyAnalyzer.calculateHazardLevel(
            obstacles = listOf(smallObstacle),
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
        
        // THEN: El nivel debe ser SAFE
        assertEquals(
            "Un objeto pequeño (5% altura) debería ser SAFE",
            HazardLevel.SAFE,
            result
        )
    }

    @Test
    fun `when no obstacles detected, hazard level should be SAFE`() {
        // GIVEN: Sin obstáculos
        val obstacles = emptyList<DetectedObstacle>()
        
        // WHEN: Analizamos
        val result = safetyAnalyzer.calculateHazardLevel(
            obstacles = obstacles,
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
        
        // THEN: SAFE
        assertEquals(HazardLevel.SAFE, result)
    }

    // ============================================
    // CASO 2: OBJETO MEDIANO -> WARNING
    // ============================================

    @Test
    fun `when object is medium sized, hazard level should be WARNING`() {
        // GIVEN: Un objeto que ocupa el 25% de la altura (distancia media)
        // altura = 480 * 0.25 = 120 píxeles
        val mediumObstacle = createObstacle(
            left = 270,
            top = 180,
            right = 370,      // ancho = 100px
            bottom = 300      // alto = 120px (25% de 480)
        )
        
        // WHEN: Analizamos
        val result = safetyAnalyzer.calculateHazardLevel(
            obstacles = listOf(mediumObstacle),
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
        
        // THEN: WARNING (entre 10% y 40%)
        assertEquals(
            "Un objeto mediano (25% altura) debería ser WARNING",
            HazardLevel.WARNING,
            result
        )
    }

    // ============================================
    // CASO 3: OBJETO GRANDE Y CENTRADO -> CRITICAL
    // ============================================

    @Test
    fun `when object is large and centered, hazard level should be CRITICAL`() {
        // GIVEN: Un objeto que ocupa el 50% de la altura Y está centrado
        // altura = 480 * 0.5 = 240 píxeles
        // Centro de imagen X = 320
        val largeObstacle = createObstacle(
            left = 220,       // Centro X del objeto = (220+420)/2 = 320
            top = 120,
            right = 420,      // ancho = 200px
            bottom = 360      // alto = 240px (50% de 480)
        )
        
        // Verificar que está centrado
        val objectCenterX = (220 + 420) / 2  // = 320
        val imageCenterX = imageWidth / 2     // = 320
        assertEquals("El objeto debe estar centrado", imageCenterX, objectCenterX)
        
        // WHEN: Analizamos
        val result = safetyAnalyzer.calculateHazardLevel(
            obstacles = listOf(largeObstacle),
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
        
        // THEN: CRITICAL (>40% altura Y centrado)
        assertEquals(
            "Un objeto grande (50% altura) y centrado debería ser CRITICAL",
            HazardLevel.CRITICAL,
            result
        )
    }

    @Test
    fun `when object occupies exactly 40 percent height and is centered, hazard level should be WARNING`() {
        // GIVEN: Un objeto que ocupa EXACTAMENTE el 40% (límite)
        // altura = 480 * 0.4 = 192 píxeles
        val borderlineObstacle = createObstacle(
            left = 220,
            top = 144,
            right = 420,
            bottom = 336      // alto = 192px (exactamente 40%)
        )
        
        // WHEN: Analizamos
        val result = safetyAnalyzer.calculateHazardLevel(
            obstacles = listOf(borderlineObstacle),
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
        
        // THEN: WARNING (el umbral es > 0.4, no >= 0.4)
        assertEquals(
            "Un objeto al límite (40% altura) debería ser WARNING, no CRITICAL",
            HazardLevel.WARNING,
            result
        )
    }

    // ============================================
    // CASO 4: OBJETO GRANDE PERO LATERAL -> WARNING
    // ============================================

    @Test
    fun `when object is large but not centered, hazard level should be WARNING`() {
        // GIVEN: Un objeto grande pero a la izquierda de la pantalla
        // altura = 480 * 0.5 = 240 píxeles
        // Centro del objeto muy a la izquierda
        val largeButOffsetObstacle = createObstacle(
            left = 0,         // Centro X = (0+200)/2 = 100
            top = 120,
            right = 200,
            bottom = 360      // alto = 240px (50%)
        )
        
        // Verificar que NO está centrado
        val objectCenterX = (0 + 200) / 2     // = 100
        val imageCenterX = imageWidth / 2      // = 320
        val offset = kotlin.math.abs(objectCenterX - imageCenterX)  // = 220
        val maxAllowedOffset = imageWidth * 0.3  // = 192
        assertTrue("El objeto debe estar fuera del centro", offset > maxAllowedOffset)
        
        // WHEN: Analizamos
        val result = safetyAnalyzer.calculateHazardLevel(
            obstacles = listOf(largeButOffsetObstacle),
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
        
        // THEN: WARNING (grande pero no centrado = menos peligroso)
        assertEquals(
            "Un objeto grande pero lateral debería ser WARNING, no CRITICAL",
            HazardLevel.WARNING,
            result
        )
    }

    // ============================================
    // CASO 5: SIMULACIÓN DE ACERCAMIENTO
    // ============================================

    @Test
    fun `when object grows frame by frame, hazard level should escalate`() {
        // Simular un objeto que se acerca en 5 frames
        
        // Frame 1: Objeto muy lejos (3% altura) -> SAFE
        val frame1Obstacle = createObstacle(
            left = 310, top = 230,
            right = 330, bottom = 244  // altura = 14px (~3%)
        )
        val level1 = safetyAnalyzer.calculateHazardLevel(
            listOf(frame1Obstacle), imageWidth, imageHeight
        )
        assertEquals("Frame 1: Objeto muy lejos", HazardLevel.SAFE, level1)
        
        // Frame 2: Objeto lejos (8% altura) -> SAFE todavía
        val frame2Obstacle = createObstacle(
            left = 300, top = 210,
            right = 340, bottom = 248  // altura = 38px (~8%)
        )
        val level2 = safetyAnalyzer.calculateHazardLevel(
            listOf(frame2Obstacle), imageWidth, imageHeight
        )
        assertEquals("Frame 2: Objeto lejos pero visible", HazardLevel.SAFE, level2)
        
        // Frame 3: Objeto a distancia media (20% altura) -> WARNING
        val frame3Obstacle = createObstacle(
            left = 270, top = 180,
            right = 370, bottom = 276  // altura = 96px (20%)
        )
        val level3 = safetyAnalyzer.calculateHazardLevel(
            listOf(frame3Obstacle), imageWidth, imageHeight
        )
        assertEquals("Frame 3: Objeto acercándose", HazardLevel.WARNING, level3)
        
        // Frame 4: Objeto cerca (35% altura) -> WARNING todavía
        val frame4Obstacle = createObstacle(
            left = 220, top = 120,
            right = 420, bottom = 288  // altura = 168px (35%)
        )
        val level4 = safetyAnalyzer.calculateHazardLevel(
            listOf(frame4Obstacle), imageWidth, imageHeight
        )
        assertEquals("Frame 4: Objeto muy cerca", HazardLevel.WARNING, level4)
        
        // Frame 5: Objeto MUY cerca (45% altura, centrado) -> CRITICAL!
        val frame5Obstacle = createObstacle(
            left = 170, top = 84,
            right = 470, bottom = 300  // altura = 216px (45%)
        )
        val level5 = safetyAnalyzer.calculateHazardLevel(
            listOf(frame5Obstacle), imageWidth, imageHeight
        )
        assertEquals("Frame 5: ¡PELIGRO CRÍTICO!", HazardLevel.CRITICAL, level5)
        
        // Verificar que la escalada fue: SAFE -> SAFE -> WARNING -> WARNING -> CRITICAL
        println("Simulación de acercamiento completada:")
        println("  Frame 1 (3%):  $level1")
        println("  Frame 2 (8%):  $level2")
        println("  Frame 3 (20%): $level3")
        println("  Frame 4 (35%): $level4")
        println("  Frame 5 (45%): $level5")
    }

    // ============================================
    // CASOS ADICIONALES
    // ============================================

    @Test
    fun `when multiple obstacles exist, largest one determines hazard level`() {
        // GIVEN: Múltiples obstáculos de diferentes tamaños
        val smallObstacle = createObstacle(
            left = 500, top = 400,
            right = 540, bottom = 420  // pequeño
        )
        val largeObstacle = createObstacle(
            left = 220, top = 84,
            right = 420, bottom = 324  // grande (50% altura) y centrado
        )
        val mediumObstacle = createObstacle(
            left = 50, top = 200,
            right = 150, bottom = 300  // mediano
        )
        
        // WHEN: Analizamos todos juntos
        val result = safetyAnalyzer.calculateHazardLevel(
            obstacles = listOf(smallObstacle, largeObstacle, mediumObstacle),
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
        
        // THEN: CRITICAL (determinado por el más grande)
        assertEquals(
            "El obstáculo más grande debe determinar el nivel",
            HazardLevel.CRITICAL,
            result
        )
    }

    @Test
    fun `analyzeObstacles returns SafetyAnalysisResult with correct data`() {
        // GIVEN: Un obstáculo crítico
        val obstacle = createObstacle(
            left = 220, top = 84,
            right = 420, bottom = 324
        )
        
        // WHEN: Usamos la función completa de análisis
        val result = safetyAnalyzer.analyzeObstacles(
            obstacles = listOf(obstacle),
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
        
        // THEN: El resultado contiene todos los datos correctos
        assertEquals(HazardLevel.CRITICAL, result.hazardLevel)
        assertEquals(1, result.obstacles.size)
        assertTrue(result.requiresAlert)
        assertNotNull(result.primaryObstacle)
        assertTrue(result.processingTimeMs >= 0)
    }

    // ============================================
    // HELPER METHODS
    // ============================================

    /**
     * Crea un obstáculo simulado para testing.
     */
    private fun createObstacle(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): DetectedObstacle {
        return DetectedObstacle(
            boundingBox = Rect(left, top, right, bottom),
            label = "test_obstacle",
            confidence = 0.95f,
            trackingId = null
        )
    }
}
