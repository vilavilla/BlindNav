/**
 * BlindNav - Test Runner Standalone
 * 
 * Este script valida la lógica de colisión heurística sin necesidad de Gradle.
 * Ejecutar con: kotlinc -script SafetyTestRunner.kts
 * O simplemente revisar la salida aquí.
 */

// ============================================
// ENUMS Y DATA CLASSES
// ============================================

enum class HazardLevel {
    SAFE,      // Objeto lejos (<10% altura)
    WARNING,   // Objeto a distancia media (10-40%)
    CRITICAL   // ¡PELIGRO! (>40% altura Y centrado)
}

data class BoundingBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
}

// ============================================
// SAFETY ANALYZER (CORE LOGIC)
// ============================================

class SafetyAnalyzer(
    private val criticalThreshold: Float = 0.4f,
    private val safeThreshold: Float = 0.1f
) {
    /**
     * LÓGICA DE COLISIÓN HEURÍSTICA
     * 
     * Reglas:
     * 1. Si boxHeight/screenHeight < 0.1 → SAFE
     * 2. Si boxHeight/screenHeight > 0.4 Y centrado → CRITICAL  
     * 3. Resto → WARNING
     */
    fun calculateHazardLevel(box: BoundingBox?, screenWidth: Int, screenHeight: Int): HazardLevel {
        if (box == null) return HazardLevel.SAFE
        
        val heightRatio = box.height.toFloat() / screenHeight.toFloat()
        
        // Tercio central de la pantalla
        val thirdWidth = screenWidth / 3
        val isCentered = box.centerX in thirdWidth..(thirdWidth * 2)
        
        return when {
            heightRatio < safeThreshold -> HazardLevel.SAFE
            heightRatio > criticalThreshold && isCentered -> HazardLevel.CRITICAL
            else -> HazardLevel.WARNING
        }
    }
}

// ============================================
// TEST RUNNER
// ============================================

class TestRunner {
    private val analyzer = SafetyAnalyzer()
    private val screenWidth = 640
    private val screenHeight = 480
    private var passed = 0
    private var failed = 0

    fun runAllTests() {
        println("=" .repeat(60))
        println("🧪 BLINDNAV - SAFETY ANALYZER TEST SUITE")
        println("=" .repeat(60))
        println()

        testObjectFarAway_ReturnsSafe()
        testObjectCloseAndCentered_ReturnsCritical()
        testObjectCloseButSide_ReturnsWarning()
        testObjectMediumSize_ReturnsWarning()
        testObjectExactly40Percent_ReturnsWarning()
        testNoObstacles_ReturnsSafe()
        testApproachingObject_EscalatesToCritical()

        println()
        println("=" .repeat(60))
        println("📊 RESULTADOS: $passed PASSED, $failed FAILED")
        println("=" .repeat(60))
        
        if (failed == 0) {
            println("✅ ¡TODOS LOS TESTS PASARON!")
            println("   La lógica de detección de peligros está VALIDADA.")
        } else {
            println("❌ Hay tests fallidos. Revisar la lógica.")
        }
    }

    private fun assert(condition: Boolean, testName: String, message: String) {
        if (condition) {
            println("  ✅ PASSED: $testName")
            passed++
        } else {
            println("  ❌ FAILED: $testName - $message")
            failed++
        }
    }

    // TEST 1: Objeto lejos -> SAFE
    private fun testObjectFarAway_ReturnsSafe() {
        println("\n📍 TEST 1: Objeto lejos (5% altura)")
        // Box de 24px de alto (5% de 480)
        val box = BoundingBox(300, 228, 340, 252)
        val result = analyzer.calculateHazardLevel(box, screenWidth, screenHeight)
        assert(result == HazardLevel.SAFE, "testObjectFarAway", 
            "Esperado SAFE, obtenido $result")
    }

    // TEST 2: Objeto grande y centrado -> CRITICAL
    private fun testObjectCloseAndCentered_ReturnsCritical() {
        println("\n📍 TEST 2: Objeto grande (50%) y CENTRADO")
        // Box de 240px de alto (50% de 480), centerX = 320 (centro)
        val box = BoundingBox(220, 120, 420, 360)
        val result = analyzer.calculateHazardLevel(box, screenWidth, screenHeight)
        assert(result == HazardLevel.CRITICAL, "testObjectCloseAndCentered",
            "Esperado CRITICAL, obtenido $result")
    }

    // TEST 3: Objeto grande pero lateral -> WARNING
    private fun testObjectCloseButSide_ReturnsWarning() {
        println("\n📍 TEST 3: Objeto grande (50%) pero a la IZQUIERDA")
        // Box de 240px de alto, pero centerX = 100 (izquierda)
        val box = BoundingBox(0, 120, 200, 360)
        val result = analyzer.calculateHazardLevel(box, screenWidth, screenHeight)
        assert(result == HazardLevel.WARNING, "testObjectCloseButSide",
            "Esperado WARNING, obtenido $result")
    }

    // TEST 4: Objeto mediano -> WARNING
    private fun testObjectMediumSize_ReturnsWarning() {
        println("\n📍 TEST 4: Objeto mediano (25% altura)")
        // Box de 120px de alto (25% de 480)
        val box = BoundingBox(270, 180, 370, 300)
        val result = analyzer.calculateHazardLevel(box, screenWidth, screenHeight)
        assert(result == HazardLevel.WARNING, "testObjectMediumSize",
            "Esperado WARNING, obtenido $result")
    }

    // TEST 5: Exactamente 40% -> WARNING (umbral es >40%, no >=40%)
    private fun testObjectExactly40Percent_ReturnsWarning() {
        println("\n📍 TEST 5: Objeto exactamente al 40% (caso límite)")
        // Box de 192px de alto (exactamente 40% de 480)
        val box = BoundingBox(220, 144, 420, 336)
        val result = analyzer.calculateHazardLevel(box, screenWidth, screenHeight)
        assert(result == HazardLevel.WARNING, "testObjectExactly40Percent",
            "Esperado WARNING (umbral es >40%), obtenido $result")
    }

    // TEST 6: Sin obstáculos -> SAFE
    private fun testNoObstacles_ReturnsSafe() {
        println("\n📍 TEST 6: Sin obstáculos detectados")
        val result = analyzer.calculateHazardLevel(null, screenWidth, screenHeight)
        assert(result == HazardLevel.SAFE, "testNoObstacles",
            "Esperado SAFE, obtenido $result")
    }

    // TEST 7: Simulación de acercamiento
    private fun testApproachingObject_EscalatesToCritical() {
        println("\n📍 TEST 7: Simulación de objeto acercándose")
        
        // Frame 1: 3% altura -> SAFE
        val f1 = BoundingBox(310, 230, 330, 244)
        val l1 = analyzer.calculateHazardLevel(f1, screenWidth, screenHeight)
        println("     Frame 1 (3%):  $l1")
        assert(l1 == HazardLevel.SAFE, "Frame1", "")
        
        // Frame 2: 8% altura -> SAFE
        val f2 = BoundingBox(300, 210, 340, 248)
        val l2 = analyzer.calculateHazardLevel(f2, screenWidth, screenHeight)
        println("     Frame 2 (8%):  $l2")
        assert(l2 == HazardLevel.SAFE, "Frame2", "")
        
        // Frame 3: 20% altura -> WARNING
        val f3 = BoundingBox(270, 180, 370, 276)
        val l3 = analyzer.calculateHazardLevel(f3, screenWidth, screenHeight)
        println("     Frame 3 (20%): $l3")
        assert(l3 == HazardLevel.WARNING, "Frame3", "")
        
        // Frame 4: 35% altura -> WARNING
        val f4 = BoundingBox(220, 120, 420, 288)
        val l4 = analyzer.calculateHazardLevel(f4, screenWidth, screenHeight)
        println("     Frame 4 (35%): $l4")
        assert(l4 == HazardLevel.WARNING, "Frame4", "")
        
        // Frame 5: 45% altura, centrado -> CRITICAL!
        val f5 = BoundingBox(220, 84, 420, 300)
        val l5 = analyzer.calculateHazardLevel(f5, screenWidth, screenHeight)
        println("     Frame 5 (45%): $l5 🚨")
        assert(l5 == HazardLevel.CRITICAL, "Frame5", "")
    }
}

// ============================================
// MAIN - EJECUTAR TESTS
// ============================================

fun main() {
    val runner = TestRunner()
    runner.runAllTests()
}

// Ejecutar
main()
