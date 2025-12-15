package com.blindnav.app.domain

import com.blindnav.app.domain.model.HazardLevel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * SafetyAnalyzerPureTest - Tests Unitarios PUROS (sin Android Framework)
 * 
 * Valida la lógica de colisión heurística usando solo JUnit.
 * Estos tests pueden ejecutarse directamente con `./gradlew test`
 * 
 * REGLAS DE NEGOCIO:
 * - boxHeight/screenHeight < 0.1  →  SAFE (objeto lejos)
 * - boxHeight/screenHeight > 0.4 Y centrado  →  CRITICAL (¡peligro!)
 * - Resto  →  WARNING
 */
class SafetyAnalyzerPureTest {

    private lateinit var analyzer: SafetyAnalyzerPure
    
    // Dimensiones de referencia (640x480)
    private val screenWidth = 640
    private val screenHeight = 480

    @Before
    fun setUp() {
        analyzer = SafetyAnalyzerPure(
            criticalHeightThreshold = 0.4f,
            safeHeightThreshold = 0.1f,
            centerToleranceRatio = 1f / 3f  // Tercio central
        )
    }

    // ============================================
    // TEST 1: OBJETO LEJOS -> SAFE
    // ============================================
    
    @Test
    fun testObjectFarAway_ReturnsSafe() {
        // GIVEN: Objeto pequeño (5% de altura) - MUY LEJOS
        // boxHeight = 480 * 0.05 = 24px
        val box = BoundingBox(
            left = 300,
            top = 228,
            right = 340,
            bottom = 252   // height = 24px (5%)
        )
        
        // WHEN
        val result = analyzer.calculateHazardLevel(box, screenWidth, screenHeight)
        
        // THEN
        assertEquals("Objeto pequeño (5%) debe ser SAFE", HazardLevel.SAFE, result)
        println("✅ TEST 1 PASSED: Objeto lejos (5% altura) -> SAFE")
    }

    @Test
    fun testObjectAt9Percent_ReturnsSafe() {
        // GIVEN: Objeto al 9% (justo bajo el umbral del 10%)
        // boxHeight = 480 * 0.09 = 43px
        val box = BoundingBox(
            left = 300,
            top = 218,
            right = 340,
            bottom = 261   // height = 43px (~9%)
        )
        
        // WHEN
        val result = analyzer.calculateHazardLevel(box, screenWidth, screenHeight)
        
        // THEN
        assertEquals("Objeto al 9% debe ser SAFE", HazardLevel.SAFE, result)
        println("✅ TEST 1b PASSED: Objeto al 9% altura -> SAFE")
    }

    // ============================================
    // TEST 2: OBJETO GRANDE Y CENTRADO -> CRITICAL
    // ============================================
    
    @Test
    fun testObjectCloseAndCentered_ReturnsCritical() {
        // GIVEN: Objeto GRANDE (50% altura) Y CENTRADO
        // boxHeight = 480 * 0.5 = 240px
        // Centro de pantalla X = 320
        // Tercio central: de 213 a 427
        val box = BoundingBox(
            left = 220,      // centerX = (220+420)/2 = 320 (CENTRADO!)
            top = 120,
            right = 420,
            bottom = 360     // height = 240px (50%)
        )
        
        // Verificar que está en el tercio central
        val boxCenterX = (box.left + box.right) / 2  // = 320
        val thirdStart = screenWidth / 3             // = 213
        val thirdEnd = screenWidth * 2 / 3           // = 426
        assertTrue("Box debe estar centrado", boxCenterX in thirdStart..thirdEnd)
        
        // WHEN
        val result = analyzer.calculateHazardLevel(box, screenWidth, screenHeight)
        
        // THEN
        assertEquals("Objeto grande (50%) y centrado debe ser CRITICAL", HazardLevel.CRITICAL, result)
        println("✅ TEST 2 PASSED: Objeto grande y centrado -> CRITICAL")
    }

    @Test
    fun testObjectAt45PercentCentered_ReturnsCritical() {
        // GIVEN: Objeto al 45% (justo sobre el umbral del 40%) y centrado
        // boxHeight = 480 * 0.45 = 216px
        val box = BoundingBox(
            left = 250,      // centerX = 350 (centrado)
            top = 132,
            right = 450,
            bottom = 348     // height = 216px (45%)
        )
        
        // WHEN
        val result = analyzer.calculateHazardLevel(box, screenWidth, screenHeight)
        
        // THEN
        assertEquals("Objeto al 45% centrado debe ser CRITICAL", HazardLevel.CRITICAL, result)
        println("✅ TEST 2b PASSED: Objeto 45% centrado -> CRITICAL")
    }

    // ============================================
    // TEST 3: OBJETO GRANDE PERO LATERAL -> WARNING
    // ============================================
    
    @Test
    fun testObjectCloseButSide_ReturnsWarning() {
        // GIVEN: Objeto GRANDE (50% altura) pero a la IZQUIERDA
        // boxHeight = 240px (50%)
        // Centro del objeto X = 100 (fuera del tercio central 213-426)
        val box = BoundingBox(
            left = 0,        // centerX = (0+200)/2 = 100 (IZQUIERDA!)
            top = 120,
            right = 200,
            bottom = 360     // height = 240px (50%)
        )
        
        // Verificar que NO está en el tercio central
        val boxCenterX = (box.left + box.right) / 2  // = 100
        val thirdStart = screenWidth / 3             // = 213
        assertTrue("Box debe estar fuera del centro", boxCenterX < thirdStart)
        
        // WHEN
        val result = analyzer.calculateHazardLevel(box, screenWidth, screenHeight)
        
        // THEN
        assertEquals("Objeto grande pero lateral debe ser WARNING", HazardLevel.WARNING, result)
        println("✅ TEST 3 PASSED: Objeto grande pero lateral -> WARNING")
    }

    @Test
    fun testObjectLargeOnRightSide_ReturnsWarning() {
        // GIVEN: Objeto grande pero a la DERECHA
        val box = BoundingBox(
            left = 500,      // centerX = (500+640)/2 = 570 (DERECHA!)
            top = 120,
            right = 640,
            bottom = 360     // height = 240px (50%)
        )
        
        // Verificar que NO está en el tercio central
        val boxCenterX = (box.left + box.right) / 2  // = 570
        val thirdEnd = screenWidth * 2 / 3           // = 426
        assertTrue("Box debe estar a la derecha", boxCenterX > thirdEnd)
        
        // WHEN
        val result = analyzer.calculateHazardLevel(box, screenWidth, screenHeight)
        
        // THEN
        assertEquals("Objeto grande a la derecha debe ser WARNING", HazardLevel.WARNING, result)
        println("✅ TEST 3b PASSED: Objeto grande a la derecha -> WARNING")
    }

    // ============================================
    // TEST 4: OBJETO MEDIANO -> WARNING
    // ============================================
    
    @Test
    fun testObjectMediumSize_ReturnsWarning() {
        // GIVEN: Objeto mediano (25% altura)
        // boxHeight = 480 * 0.25 = 120px
        val box = BoundingBox(
            left = 270,
            top = 180,
            right = 370,
            bottom = 300     // height = 120px (25%)
        )
        
        // WHEN
        val result = analyzer.calculateHazardLevel(box, screenWidth, screenHeight)
        
        // THEN
        assertEquals("Objeto mediano (25%) debe ser WARNING", HazardLevel.WARNING, result)
        println("✅ TEST 4 PASSED: Objeto mediano -> WARNING")
    }

    // ============================================
    // TEST 5: CASO LÍMITE - Exactamente 40%
    // ============================================
    
    @Test
    fun testObjectAtExactly40Percent_ReturnsWarning() {
        // GIVEN: Objeto EXACTAMENTE al 40% (límite)
        // boxHeight = 480 * 0.4 = 192px
        val box = BoundingBox(
            left = 220,
            top = 144,
            right = 420,
            bottom = 336     // height = 192px (exactamente 40%)
        )
        
        // WHEN
        val result = analyzer.calculateHazardLevel(box, screenWidth, screenHeight)
        
        // THEN: El umbral es > 0.4, no >= 0.4, así que debe ser WARNING
        assertEquals("Objeto exactamente al 40% debe ser WARNING (umbral es >40%)", 
            HazardLevel.WARNING, result)
        println("✅ TEST 5 PASSED: Exactamente 40% -> WARNING (no CRITICAL)")
    }

    // ============================================
    // TEST 6: SIN OBSTÁCULOS -> SAFE
    // ============================================
    
    @Test
    fun testNoObstacles_ReturnsSafe() {
        // GIVEN: Sin obstáculos (null)
        
        // WHEN
        val result = analyzer.calculateHazardLevel(null, screenWidth, screenHeight)
        
        // THEN
        assertEquals("Sin obstáculos debe ser SAFE", HazardLevel.SAFE, result)
        println("✅ TEST 6 PASSED: Sin obstáculos -> SAFE")
    }

    // ============================================
    // TEST 7: SIMULACIÓN DE ACERCAMIENTO
    // ============================================
    
    @Test
    fun testApproachingObject_EscalatesFromSafeToCritical() {
        println("\n🚶 Simulando objeto que se acerca frame a frame:")
        
        // Frame 1: Muy lejos (3%)
        val frame1 = BoundingBox(310, 230, 330, 244)  // h=14px (~3%)
        val level1 = analyzer.calculateHazardLevel(frame1, screenWidth, screenHeight)
        assertEquals(HazardLevel.SAFE, level1)
        println("  Frame 1 (3%):  $level1 ✓")
        
        // Frame 2: Lejos (8%)
        val frame2 = BoundingBox(300, 210, 340, 248)  // h=38px (~8%)
        val level2 = analyzer.calculateHazardLevel(frame2, screenWidth, screenHeight)
        assertEquals(HazardLevel.SAFE, level2)
        println("  Frame 2 (8%):  $level2 ✓")
        
        // Frame 3: Medio (20%)
        val frame3 = BoundingBox(270, 180, 370, 276)  // h=96px (20%)
        val level3 = analyzer.calculateHazardLevel(frame3, screenWidth, screenHeight)
        assertEquals(HazardLevel.WARNING, level3)
        println("  Frame 3 (20%): $level3 ✓")
        
        // Frame 4: Cerca (35%)
        val frame4 = BoundingBox(220, 120, 420, 288)  // h=168px (35%)
        val level4 = analyzer.calculateHazardLevel(frame4, screenWidth, screenHeight)
        assertEquals(HazardLevel.WARNING, level4)
        println("  Frame 4 (35%): $level4 ✓")
        
        // Frame 5: MUY CERCA (45% y centrado) -> CRITICAL!
        val frame5 = BoundingBox(220, 84, 420, 300)   // h=216px (45%)
        val level5 = analyzer.calculateHazardLevel(frame5, screenWidth, screenHeight)
        assertEquals(HazardLevel.CRITICAL, level5)
        println("  Frame 5 (45%): $level5 🚨")
        
        println("✅ TEST 7 PASSED: Escalada SAFE -> WARNING -> CRITICAL")
    }
}

// ============================================
// CLASES AUXILIARES PARA TESTS PUROS
// ============================================

/**
 * BoundingBox simple sin dependencia de Android.
 */
data class BoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * SafetyAnalyzer simplificado para tests puros (sin ML Kit).
 * Implementa la MISMA lógica matemática que el real.
 */
class SafetyAnalyzerPure(
    private val criticalHeightThreshold: Float = 0.4f,
    private val safeHeightThreshold: Float = 0.1f,
    private val centerToleranceRatio: Float = 1f / 3f
) {
    /**
     * LÓGICA DE COLISIÓN HEURÍSTICA
     * 
     * Reglas:
     * 1. Si boxHeight/screenHeight < 0.1 → SAFE
     * 2. Si boxHeight/screenHeight > 0.4 Y centrado → CRITICAL
     * 3. Resto → WARNING
     * 
     * "Centrado" = el centro del box está en el tercio central del ancho
     */
    fun calculateHazardLevel(
        box: BoundingBox?,
        screenWidth: Int,
        screenHeight: Int
    ): HazardLevel {
        // Sin obstáculos = seguro
        if (box == null) return HazardLevel.SAFE
        
        // Calcular ratio de altura
        val heightRatio = box.height.toFloat() / screenHeight.toFloat()
        
        // Calcular si está en el tercio central
        val thirdWidth = screenWidth / 3
        val centerStart = thirdWidth           // 213 para 640px
        val centerEnd = thirdWidth * 2         // 426 para 640px
        val isCentered = box.centerX in centerStart..centerEnd
        
        return when {
            // SAFE: Objeto pequeño (<10%) - está lejos
            heightRatio < safeHeightThreshold -> HazardLevel.SAFE
            
            // CRITICAL: Objeto grande (>40%) Y centrado - ¡PELIGRO!
            heightRatio > criticalHeightThreshold && isCentered -> HazardLevel.CRITICAL
            
            // WARNING: Todo lo demás
            else -> HazardLevel.WARNING
        }
    }
}
