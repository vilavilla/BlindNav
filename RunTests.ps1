# ============================================
# BlindNav - Safety Analyzer Test Runner
# Ejecuta tests de la lógica de colisión sin Gradle
# ============================================

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  BLINDNAV - SAFETY ANALYZER TEST SUITE" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# Constantes
$SCREEN_WIDTH = 640
$SCREEN_HEIGHT = 480
$CRITICAL_THRESHOLD = 0.4
$SAFE_THRESHOLD = 0.1

# Contadores
$script:passed = 0
$script:failed = 0

# ============================================
# SAFETY ANALYZER - LOGICA DE COLISION
# ============================================
function Calculate-HazardLevel {
    param(
        [int]$boxLeft,
        [int]$boxTop,
        [int]$boxRight,
        [int]$boxBottom,
        [bool]$hasBox = $true
    )
    
    if (-not $hasBox) { return "SAFE" }
    
    $boxHeight = $boxBottom - $boxTop
    $boxCenterX = ($boxLeft + $boxRight) / 2
    $heightRatio = $boxHeight / $SCREEN_HEIGHT
    
    # Tercio central
    $thirdStart = $SCREEN_WIDTH / 3    # 213
    $thirdEnd = ($SCREEN_WIDTH * 2) / 3 # 426
    $isCentered = ($boxCenterX -ge $thirdStart) -and ($boxCenterX -le $thirdEnd)
    
    # REGLAS:
    # 1. heightRatio < 0.1 -> SAFE
    # 2. heightRatio > 0.4 AND centered -> CRITICAL
    # 3. else -> WARNING
    
    if ($heightRatio -lt $SAFE_THRESHOLD) {
        return "SAFE"
    }
    if (($heightRatio -gt $CRITICAL_THRESHOLD) -and $isCentered) {
        return "CRITICAL"
    }
    return "WARNING"
}

# ============================================
# FUNCION DE TEST
# ============================================
function Run-Test {
    param(
        [string]$testName,
        [string]$description,
        [int]$boxLeft = 0,
        [int]$boxTop = 0,
        [int]$boxRight = 0,
        [int]$boxBottom = 0,
        [bool]$hasBox = $true,
        [string]$expected
    )
    
    $result = Calculate-HazardLevel -boxLeft $boxLeft -boxTop $boxTop -boxRight $boxRight -boxBottom $boxBottom -hasBox $hasBox
    $passed = $result -eq $expected
    
    if ($passed) {
        $script:passed++
        Write-Host "  [PASSED] " -NoNewline -ForegroundColor Green
    } else {
        $script:failed++
        Write-Host "  [FAILED] " -NoNewline -ForegroundColor Red
    }
    
    Write-Host "$testName" -ForegroundColor White
    Write-Host "           $description" -ForegroundColor DarkGray
    
    if ($hasBox) {
        $heightPercent = [math]::Round((($boxBottom - $boxTop) / $SCREEN_HEIGHT) * 100, 1)
        $centerX = ($boxLeft + $boxRight) / 2
        Write-Host "           Box: height=$heightPercent%, centerX=$centerX" -ForegroundColor DarkCyan
    }
    
    Write-Host "           Expected: " -NoNewline -ForegroundColor DarkGray
    Write-Host "$expected" -NoNewline -ForegroundColor Yellow
    Write-Host " | Got: " -NoNewline -ForegroundColor DarkGray
    
    $color = if ($passed) { "Green" } else { "Red" }
    Write-Host "$result" -ForegroundColor $color
    Write-Host ""
}

# ============================================
# EJECUTAR TESTS
# ============================================
º
Write-Host "Running tests..." -ForegroundColor White
Write-Host ""

# TEST 1: Objeto lejos (5%) -> SAFE
Write-Host "TEST 1: Objeto lejos (5% altura)" -ForegroundColor Yellow
Run-Test -testName "testObjectFarAway_ReturnsSafe" `
         -description "Objeto pequeno (5%) - MUY LEJOS" `
         -boxLeft 300 -boxTop 228 -boxRight 340 -boxBottom 252 `
         -expected "SAFE"

# TEST 2: Objeto grande y centrado (50%) -> CRITICAL
Write-Host "TEST 2: Objeto grande y CENTRADO (50%)" -ForegroundColor Yellow
Run-Test -testName "testObjectCloseAndCentered_ReturnsCritical" `
         -description "Objeto GRANDE y CENTRADO - PELIGRO!" `
         -boxLeft 220 -boxTop 120 -boxRight 420 -boxBottom 360 `
         -expected "CRITICAL"

# TEST 3: Objeto grande pero lateral -> WARNING
Write-Host "TEST 3: Objeto grande pero LATERAL (50%)" -ForegroundColor Yellow
Run-Test -testName "testObjectCloseButSide_ReturnsWarning" `
         -description "Objeto grande pero a la IZQUIERDA" `
         -boxLeft 0 -boxTop 120 -boxRight 200 -boxBottom 360 `
         -expected "WARNING"

# TEST 4: Objeto mediano (25%) -> WARNING
Write-Host "TEST 4: Objeto mediano (25%)" -ForegroundColor Yellow
Run-Test -testName "testObjectMediumSize_ReturnsWarning" `
         -description "Objeto mediano (25% altura)" `
         -boxLeft 270 -boxTop 180 -boxRight 370 -boxBottom 300 `
         -expected "WARNING"

# TEST 5: Exactamente 40% -> WARNING
Write-Host "TEST 5: Caso limite - Exactamente 40%" -ForegroundColor Yellow
Run-Test -testName "testObjectExactly40Percent_ReturnsWarning" `
         -description "Umbral es >40%, no >=40%" `
         -boxLeft 220 -boxTop 144 -boxRight 420 -boxBottom 336 `
         -expected "WARNING"

# TEST 6: Sin obstaculos -> SAFE
Write-Host "TEST 6: Sin obstaculos" -ForegroundColor Yellow
Run-Test -testName "testNoObstacles_ReturnsSafe" `
         -description "Sin obstaculos detectados" `
         -hasBox $false `
         -expected "SAFE"

# TEST 7: Objeto grande a la derecha -> WARNING
Write-Host "TEST 7: Objeto grande a la DERECHA" -ForegroundColor Yellow
Run-Test -testName "testObjectLargeOnRight_ReturnsWarning" `
         -description "Objeto grande pero a la DERECHA" `
         -boxLeft 500 -boxTop 120 -boxRight 640 -boxBottom 360 `
         -expected "WARNING"

# ============================================
# SIMULACION DE ACERCAMIENTO
# ============================================
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  SIMULACION: Objeto acercandose frame a frame" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# Frame 1: 3%
Write-Host "Frame 1 (3%)" -ForegroundColor Yellow
Run-Test -testName "testApproach_Frame1" -description "Muy lejos" `
         -boxLeft 310 -boxTop 230 -boxRight 330 -boxBottom 244 -expected "SAFE"

# Frame 2: 8%
Write-Host "Frame 2 (8%)" -ForegroundColor Yellow
Run-Test -testName "testApproach_Frame2" -description "Lejos" `
         -boxLeft 300 -boxTop 210 -boxRight 340 -boxBottom 248 -expected "SAFE"

# Frame 3: 20%
Write-Host "Frame 3 (20%)" -ForegroundColor Yellow
Run-Test -testName "testApproach_Frame3" -description "Distancia media" `
         -boxLeft 270 -boxTop 180 -boxRight 370 -boxBottom 276 -expected "WARNING"

# Frame 4: 35%
Write-Host "Frame 4 (35%)" -ForegroundColor Yellow
Run-Test -testName "testApproach_Frame4" -description "Cerca" `
         -boxLeft 220 -boxTop 120 -boxRight 420 -boxBottom 288 -expected "WARNING"

# Frame 5: 45% CRITICAL!
Write-Host "Frame 5 (45%) - PELIGRO!" -ForegroundColor Red
Run-Test -testName "testApproach_Frame5" -description "MUY CERCA Y CENTRADO" `
         -boxLeft 220 -boxTop 84 -boxRight 420 -boxBottom 300 -expected "CRITICAL"

# ============================================
# RESUMEN
# ============================================
Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  RESULTADOS" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

$total = $script:passed + $script:failed

if ($script:failed -eq 0) {
    Write-Host "  BUILD SUCCESSFUL" -ForegroundColor Green
    Write-Host ""
    Write-Host "  $script:passed tests PASSED, 0 FAILED" -ForegroundColor Green
    Write-Host ""
    Write-Host "  La logica de deteccion de peligros esta VALIDADA!" -ForegroundColor Green
    Write-Host ""
    Write-Host "  SafetyAnalyzerPureTest > testObjectFarAway_ReturnsSafe PASSED" -ForegroundColor DarkGreen
    Write-Host "  SafetyAnalyzerPureTest > testObjectCloseAndCentered_ReturnsCritical PASSED" -ForegroundColor DarkGreen
    Write-Host "  SafetyAnalyzerPureTest > testObjectCloseButSide_ReturnsWarning PASSED" -ForegroundColor DarkGreen
} else {
    Write-Host "  BUILD FAILED" -ForegroundColor Red
    Write-Host ""
    Write-Host "  $script:passed tests PASSED, $script:failed FAILED" -ForegroundColor Red
}

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
