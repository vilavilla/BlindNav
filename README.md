# BlindNav 🦯

**Aplicación Android de navegación Turn-by-Turn y detección de obstáculos para personas con discapacidad visual.**

> Los usuarios dicen *"Llévame a la farmacia"* y el sistema les guía paso a paso por las calles reales mientras escanea el entorno en busca de obstáculos.

### 🆕 **NUEVAS FUNCIONALIDADES (Febrero 2026)**

- ✅ **OpenStreetMap** sin API keys (Nominatim + OSRM + osmdroid)
- ✅ **Navegación Turn-by-Turn** por calles y aceras reales (OSRMBonusPack)
- ✅ **Brújula de alta precisión** con Rotation Vector Sensor (eliminado jitter)
- ✅ **Búsqueda local inteligente** con viewbox GPS (resultados cercanos prioritarios)
- ✅ **MyLocationNewOverlay** - Ubicación en tiempo real con flecha direccional

---

## 📋 Table of Contents

1. [Overview](#-overview)
2. [How It Works - Complete Workflow](#-how-it-works---complete-workflow)
3. [System Architecture](#-system-architecture)
4. [Data Flow](#-data-flow)
5. [Core Components](#-core-components)
6. [Priority Audio System](#-priority-audio-system)
7. [Navigation System](#-navigation-system)
8. [Obstacle Detection (Safety)](#-obstacle-detection-safety)
9. [Voice Commands](#-voice-commands)
10. [Required Permissions](#-required-permissions)
11. [Project Structure](#-project-structure)
12. [How to Run](#-how-to-run)

---

## 🎯 Overview

BlindNav is an Android application designed to help blind or low-vision people to:

1. **Navigate** to destinations using GPS and compass
2. **Detect obstacles** in real-time using camera and ML Kit
3. **Receive audio feedback** with intelligent priority (safety > navigation)

### Key Features

- ✅ **OpenStreetMap completo**: Sin Google Maps, sin API keys, sin costos
- ✅ **Turn-by-Turn navigation**: "Gira a la izquierda en 50m", "Cruza la calle"
- ✅ **Rutas por calles reales**: OSRMBonusPack con modo PEATÓN (aceras)
- ✅ **Brújula ultra-estable**: Rotation Vector Sensor (fusión hardware)
- ✅ **Búsqueda local**: ViewBox ±10km prioriza resultados cercanos
- ✅ **Ubicación en tiempo real**: MyLocationNewOverlay con flecha direccional
- ✅ **Detección de obstáculos offline**: ML Kit sin internet
- ✅ **Audio inteligente**: Seguridad SIEMPRE interrumpe navegación
- ✅ **Alto contraste**: UI diseñada para baja visión
- ✅ **Comandos de voz**: "Llévame a X", "Ve a X", "Para"

---

## �️ Stack Tecnológico

### **Mapas y Navegación (OpenStreetMap)**

| Componente | Tecnología | Propósito |
|------------|-----------|----------|
| **Mapa visual** | osmdroid 6.1.18 | Tiles de OpenStreetMap sin API keys |
| **Ubicación en tiempo real** | MyLocationNewOverlay | Punto azul + flecha de dirección |
| **Geocoding** | Nominatim API | "Cítara, Fraga" → coordenadas GPS |
| **Búsqueda local** | ViewBox + bounded=1 | Resultados en radio ±10km |
| **Routing** | OSRM + OSRMBonusPack | Rutas peatonales por calles |
| **Turn-by-Turn** | RoadManager.mNodes | Instrucciones: "Gira a la izquierda" |
| **Polyline** | RoadManager.buildRoadOverlay | Visualización de ruta sobre mapa |

### **Sensores de Navegación**

| Sensor | Implementación | Mejora |
|--------|---------------|--------|
| **GPS** | FusedLocationProviderClient | Alta precisión |
| **Brújula** | TYPE_ROTATION_VECTOR | Fusión hardware (accel+gyro+mag) |
| **Filtro Low-Pass** | Alpha = 0.05 | Elimina jitter (temblor) |

### **Detección de Obstáculos**

- **ML Kit Object Detection** (offline)
- **CameraX** para captura de frames
- **Heurísticas de seguridad** basadas en tamaño/posición

### **Sistema de Audio Prioritario**

- **TextToSpeech** para instrucciones
- **Prioridades**: SAFETY > NAVIGATION > SYSTEM
- **Interrupciones inteligentes**

---

## �🔍 How It Works - Complete Workflow

### Real-World Usage Scenario

Let's walk through a complete example of how a blind user would use BlindNav to navigate to a pharmacy:

#### **Phase 1: Initialization (App Launch)**

```
User opens app
    ↓
System performs startup sequence:
    ├─ Initialize Camera (rear-facing, 30 FPS)
    ├─ Load ML Kit model (offline object detection)
    ├─ Initialize GPS client
    ├─ Activate compass sensors
    ├─ Initialize Text-to-Speech engine
    └─ Start voice recognition listener
    ↓
TTS announces: "BlindNav ready. Say 'Take me to' followed by a destination."
```

#### **Phase 2: Voice Command & Route Planning**

```
User says: "Llévame a la farmacia"
    ↓
VoiceCommander captures and processes audio:
    ├─ Speech-to-text conversion
    ├─ Pattern matching: "llévame a [destination]"
    └─ Extract destination: "farmacia"
    ↓
Nominatim Search (con ViewBox local):
    ├─ GPS usuario: (41.3851°, 2.1734°)
    ├─ ViewBox: ±0.1° (~10km radio)
    ├─ Query: "farmacia&viewbox=2.07,41.48,2.27,41.28&bounded=1"
    └─ Resultado: Farmacia Municipal (41.3860°, 2.1745°) - 120m
    ↓
OSRMRouteProvider calculates Turn-by-Turn route:
    ├─ Start: (41.3851°, 2.1734°)
    ├─ End: (41.3860°, 2.1745°)
    ├─ RoadManager mode: MEAN_BY_FOOT (pedestrian)
    └─ Road.mNodes extracted:
        • Node 0: "Sal del edificio" (0m)
        • Node 1: "Gira a la derecha en Calle Mayor" (15m)
        • Node 2: "Continúa recto por Calle Mayor" (80m)
        • Node 3: "Cruza el paso de peatones" (95m)
        • Node 4: "Has llegado a Farmacia Municipal" (120m)
    ↓
Polyline azul dibujada en el mapa siguiendo las aceras
    ↓
TTS confirms: "Ruta calculada. 120 metros a farmacia. Iniciando navegación."
```

#### **Phase 3: Active Navigation (Dual System)**

Now the app runs **two parallel systems** simultaneously:

**🟢 SYSTEM A: Navigation Loop (runs every 2 seconds)**

```
GPS Update (Location: 41.3851°, 2.1734°)
    ↓
NavigationManager calculations:
    ├─ Distance to next waypoint: 48 meters
    ├─ Compass heading: 85° (pointing East)
    ├─ Required bearing: 45° (Northeast to waypoint)
    ├─ Angular difference: 45° - 85° = -40°
    └─ Generate instruction: "Turn 40 degrees left"
    ↓
Check: Is Safety currently speaking?
    ├─ NO → Speak instruction (Priority: NAVIGATION)
    └─ YES → Queue for later
    ↓
TTS (if allowed): "Turn 40 degrees left, then continue 48 meters"
    ↓
[Wait 2 seconds] → Next GPS update
```

**🔴 SYSTEM B: Safety Loop (runs every 100ms)**

```
Camera captures frame (1920x1080 pixels)
    ↓
ML Kit Object Detection (processes in ~30-50ms):
    ├─ Detects: Person
    ├─ Bounding box: (x:480, y:200, width:960, height:880)
    ├─ Confidence: 89%
    └─ Label: "person"
    ↓
SafetyAnalyzer calculations:
    ├─ Box height ratio: 880/1080 = 0.81 (81% of frame)
    ├─ Distance estimate: 0.81 > 0.7 → **VERY CLOSE** → 0.5 meters
    ├─ Box center X: 480 + 960/2 = 960 pixels
    ├─ Frame center X: 1920/2 = 960 pixels
    ├─ Horizontal offset: |960 - 960| = 0 → **DEAD CENTER**
    └─ Risk calculation:
        • Large object (81% height) = +0.5 risk
        • Very close (<2m) = +0.3 risk
        • Centered (collision path) = +0.2 risk
        • TOTAL RISK: 1.0 → **CRITICAL DANGER**
    ↓
Immediate Safety Response:
    ├─ [1] INTERRUPT any ongoing TTS (stop navigation voice)
    ├─ [2] Play alert tone (200ms beep)
    ├─ [3] Vibrate phone (500ms, max intensity)
    └─ [4] Speak (Priority: SAFETY - cannot be interrupted)
    ↓
TTS: "CAUTION! Person directly ahead at half a meter. Stop walking."
    ↓
[Wait 100ms] → Next camera frame
```

#### **Phase 4: Collision Avoidance & Recovery**

```
User hears safety warning and stops
    ↓
Next camera frame (100ms later):
    ├─ Object detection: Same person detected
    ├─ Bounding box: (x:500, y:220, width:880, height:820)
    ├─ Height ratio: 820/1080 = 0.76 (still large)
    ├─ Distance: ~0.6 meters (user stopped, didn't get closer)
    ├─ Risk: Still CRITICAL
    └─ Action: Safety stays silent (already warned, avoid repetition spam)
    ↓
User moves around the person (shifts body right)
    ↓
Camera frame updates (100ms later):
    ├─ Object detection: Person now at left side
    ├─ Bounding box: (x:100, y:300, width:400, height:600)
    ├─ Height ratio: 600/1080 = 0.56 (medium)
    ├─ Center offset: |250 - 960| = 710 pixels (NOT centered)
    ├─ Risk: MEDIUM (0.5) → Not immediate danger
    └─ Action: No announcement (user successfully avoided)
    ↓
Next frame (100ms later):
    ├─ Object detection: Person now behind/out of frame
    ├─ Risk: SAFE
    └─ Action: Resume normal navigation
    ↓
Navigation system (which has been waiting) now speaks:
TTS: "Continue straight 42 meters" (updated distance from GPS)
```

#### **Phase 5: Arrival**

```
GPS Update: Distance to destination = 5 meters
    ↓
NavigationManager detects proximity threshold
    ↓
TTS: "You are approaching the pharmacy. 5 meters ahead."
    ↓
GPS Update: Distance = 2 meters
    ↓
TTS: "Destination reached. Pharmacy entrance on your right."
    ↓
System stops navigation
    ↓
Safety system continues running (always active for obstacle detection)
```

### Key Technical Details

**Why Two Separate Loops?**
- **Navigation**: GPS updates are slow (2 seconds) but need accurate position
- **Safety**: Camera must be fast (100ms = 10 FPS) to catch moving obstacles
- Running them independently prevents GPS lag from slowing down safety detection

**Priority System in Action:**
```
Timeline (example):
00:00.000 - NAV speaks: "Turn left in 30—"
00:00.800 - SAFETY detects danger (interrupts)
00:00.850 - NAV speech STOPPED mid-sentence
00:00.900 - SAFETY speaks: "CAUTION! Obstacle ahead!"
00:03.500 - SAFETY finishes speaking
00:03.600 - NAV resumes: "Turn left in 30 meters"
```

**Distance Estimation Logic:**
```kotlin
// No LiDAR sensor, so we estimate by object size in frame
Object height = 81% of frame height
    ↓
Real-world logic:
    • If person fills 80% of vertical space → They must be VERY close
    • If person is only 10% of frame → They are far away
    ↓
Mapping:
    • >70% height → 0.5m (critical)
    • 50-70% → 1.5m (warning)
    • 30-50% → 3.0m (caution)
    • 10-30% → 5.0m (safe)
    • <10% → 10m+ (irrelevant)
```

---

## 🏗️ System Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        MainActivity                              │
│  ┌─────────────────┐              ┌─────────────────┐           │
│  │   safetyJob     │              │  navigationJob  │           │
│  │   (Coroutine)   │              │   (Coroutine)   │           │
│  └────────┬────────┘              └────────┬────────┘           │
│           │                                │                     │
│           ▼                                ▼                     │
│  ┌─────────────────┐              ┌─────────────────┐           │
│  │  CameraSource   │              │NavigationManager│           │
│  │  (CameraX)      │              │  (GPS+Compass)  │           │
│  └────────┬────────┘              └────────┬────────┘           │
│           │                                │                     │
│           ▼                                ▼                     │
│  ┌─────────────────┐              ┌─────────────────┐           │
│  │ SafetyAnalyzer  │              │MockRouteProvider│           │
│  │   (ML Kit)      │              │  (Fake Routes)  │           │
│  └────────┬────────┘              └────────┬────────┘           │
│           │                                │                     │
│           └────────────┬───────────────────┘                     │
│                        ▼                                         │
│              ┌─────────────────────┐                             │
│              │ PriorityAudioManager│                             │
│              │   (TTS + Tonos)     │                             │
│              └─────────────────────┘                             │
└─────────────────────────────────────────────────────────────────┘
```

**Patrón**: MVVM + Clean Architecture
- **UI Layer**: MainActivity, BoundingBoxOverlay
- **Domain Layer**: SafetyAnalyzer, NavigationManager, VoiceCommander
- **Data Layer**: CameraSource, MockRouteProvider

### Tecnologías Utilizadas

| Component | Technology |
|-----------|------------|
| Language | Kotlin 1.9.20 |
| Architecture | MVVM + Clean Architecture |
| Vision | CameraX 1.3.0 |
| Offline ML | ML Kit Object Detection 17.0.0 |
| GPS | Google Play Services Location 21.0.1 |
| Compass | SensorManager (TYPE_ROTATION_VECTOR) |
| Voice | SpeechRecognizer + TextToSpeech |
| Async | Coroutines + Flow |
| Testing | JUnit 4 + Mockito 5 |

---

## 🔄 Data Flow

### 1. Safety Flow (Obstacle Detection)

```
Cámara → CameraSource → ML Kit → SafetyAnalyzer → PriorityAudioManager
   │                                    │                    │
   │  Frame cada 100ms                  │ Análisis de        │ "¡Cuidado!
   │  (ImageProxy)                      │ colisiones         │  Obstáculo
   │                                    │                    │  a 2 metros"
   ▼                                    ▼                    ▼
PreviewView                      SafetyAnalysisResult    TTS + Vibración
```

### 2. Flujo de Navigation (Guiado GPS)

```
"Llévame a la farmacia"
        │
        ▼
┌───────────────┐     ┌──────────────────┐     ┌─────────────────┐
│VoiceCommander │────▶│ MockRouteProvider│────▶│NavigationManager│
│(SpeechRecog.) │     │  (Ruta falsa)    │     │  (GPS+Brújula)  │
└───────────────┘     └──────────────────┘     └────────┬────────┘
                                                        │
                                                        ▼
                                               ┌─────────────────┐
                                               │NavigationState  │
                                               │ - distancia     │
                                               │ - ángulo giro   │
                                               │ - instrucción   │
                                               └────────┬────────┘
                                                        │
                                                        ▼
                                               ┌─────────────────┐
                                               │PriorityAudio    │
                                               │"Gira 45° dcha"  │
                                               └─────────────────┘
```

---

## 🧩 Componentes Principales

### 1. CameraSource (`data/camera/CameraSource.kt`)

Captura frames de la cámara usando **CameraX**.

```kotlin
// Configuración clave
imageAnalysis.setAnalyzer(executor) { imageProxy ->
    // Envía frame al SafetyAnalyzer cada ~100ms
    _frames.tryEmit(imageProxy)
}

// Estrategia: solo procesar el último frame (evita lag)
.setBackpressureStrategy(STRATEGY_KEEP_ONLY_LATEST)
```

**Responsabilidades:**
- Inicializar cámara trasera
- Emitir frames via `SharedFlow`
- Vincular preview a la UI

---

### 2. SafetyAnalyzer (`domain/safety/SafetyAnalyzer.kt`)

Analiza frames para detectar obstáculos usando **ML Kit Object Detection**.

```kotlin
fun analyze(frame: ImageProxy): SafetyAnalysisResult {
    // 1. Detectar objetos con ML Kit (offline)
    val objects = objectDetector.process(frame)
    
    // 2. Estimar distancia por tamaño del bounding box
    val distance = estimateDistance(boundingBox)
    
    // 3. Calcular riesgo de colisión
    val risk = calculateCollisionRisk(object, distance)
    
    // 4. Retornar resultado
    return SafetyAnalysisResult(
        hasImmediateDanger = risk > 0.7,
        nearestObstacle = "persona",
        distanceMeters = 1.5f,
        riskLevel = HIGH
    )
}
```

**Heurísticas de Colisión:**

```
┌─────────────────────────────────────┐
│            Frame de Cámara           │
│                                      │
│    ┌─────┐         ┌─────┐          │
│    │ LOW │         │ LOW │          │  < 10% altura = SAFE
│    └─────┘         └─────┘          │
│         ┌───────────────┐            │
│         │    MEDIUM     │            │  10-40% altura = WARNING
│         │               │            │
│         │  ┌─────────┐  │            │
│         │  │  HIGH   │  │            │  > 40% + centrado = CRITICAL
│         │  │(centro) │  │            │
│         │  └─────────┘  │            │
│         └───────────────┘            │
│                                      │
└─────────────────────────────────────┘

Centro = Dirección de caminata = Mayor riesgo
```

| Condición | Riesgo |
|-----------|--------|
| Objeto en centro + cerca (< 2m) | 🔴 CRITICAL |
| Objeto en centro + lejos (2-5m) | 🟡 WARNING |
| Objeto lateral | 🟢 LOW |
| Sin objetos | ✅ SAFE |

---

### 3. NavigationManager (`domain/navigation/NavigationManager.kt`)

Gestiona la navegación GPS y genera instrucciones de giro.

```kotlin
class NavigationManager(context: Context) {
    private val fusedLocationClient: FusedLocationProviderClient
    private val sensorManager: SensorManager  // Para brújula
    
    // Estado reactivo
    val navigationState: StateFlow<NavigationState>
    
    fun startNavigation(route: NavigationRoute) {
        // 1. Solicitar updates de GPS cada 2 segundos
        fusedLocationClient.requestLocationUpdates(request, callback)
        
        // 2. Escuchar brújula (rotation vector sensor)
        sensorManager.registerListener(this, rotationSensor)
    }
    
    private fun updateNavigation(location: Location) {
        // 1. Calcular bearing hacia siguiente waypoint
        val targetBearing = calculateBearing(location, nextWaypoint)
        
        // 2. Calcular diferencia con heading actual (brújula)
        val turnAngle = targetBearing - currentHeading
        
        // 3. Generar instrucción
        val instruction = when {
            turnAngle > 30 -> "Gira ${turnAngle}° a la derecha"
            turnAngle < -30 -> "Gira ${-turnAngle}° a la izquierda"
            else -> "Continúa recto ${distance} metros"
        }
        
        // 4. Emitir nuevo estado
        _navigationState.value = NavigationState(instruction, distance, ...)
    }
}
```

**Cálculo del Bearing:**
```
                    N (0°)
                     │
                     │ bearing = 45°
            W ───────┼───────▶ E
           270°      │        90°
                     │
                    S (180°)

bearing = atan2(sin(Δlon) × cos(lat2), 
                cos(lat1)×sin(lat2) - sin(lat1)×cos(lat2)×cos(Δlon))
```

---

### 4. PriorityAudioManager (`ui/audio/PriorityAudioManager.kt`)

**El corazón del sistema de audio.** Gestiona qué se dice y cuándo.

```kotlin
enum class AudioPriority(val level: Int) {
    SAFETY(1),      // 🔴 Máxima - SIEMPRE interrumpe
    NAVIGATION(2),  // 🟡 Media - Solo si no hay Safety
    SYSTEM(3)       // 🟢 Baja - Notificaciones
}

class PriorityAudioManager(context: Context) {
    private val tts: TextToSpeech
    private val toneGenerator: ToneGenerator
    private var currentPriority: AudioPriority = SYSTEM
    
    fun speak(message: String, priority: AudioPriority) {
        // Solo hablar si prioridad >= actual
        if (priority.level <= currentPriority.level) {
            if (priority == SAFETY) {
                tts.stop()  // Interrumpe inmediatamente
            }
            currentPriority = priority
            tts.speak(message, QUEUE_FLUSH, null, null)
        }
    }
    
    fun interruptForSafety() {
        tts.stop()
        currentPriority = SAFETY
        // Tono de alerta
        toneGenerator.startTone(TONE_CDMA_ALERT_CALL_GUARD, 200)
    }
}
```

---

### 5. VoiceCommander (`ui/voice/VoiceCommander.kt`)

Procesa comandos de voz del usuario.

```kotlin
class VoiceCommander(context: Context) {
    private val speechRecognizer: SpeechRecognizer
    
    // Comandos soportados
    sealed class VoiceCommand {
        data class Navigate(val destination: String) : VoiceCommand()
        object Stop : VoiceCommand()
        object WhereAmI : VoiceCommand()
    }
    
    fun parseCommand(text: String): VoiceCommand? {
        val lower = text.lowercase()
        return when {
            lower.contains("llévame a") -> Navigate(dest)
            lower.contains("ir a") -> Navigate(dest)
            lower.contains("para") -> Stop
            lower.contains("dónde estoy") -> WhereAmI
            else -> null
        }
    }
}
```

---

### 6. MockRouteProvider (`domain/navigation/MockRouteProvider.kt`)

Proporciona rutas simuladas para testing (sin API de routing real).

```kotlin
object MockRouteProvider {
    private val routes = mapOf(
        "farmacia" to listOf(
            RoutePoint(41.3851, 2.1734, "Inicio"),
            RoutePoint(41.3855, 2.1740, "Girar derecha"),
            RoutePoint(41.3860, 2.1745, "Farmacia")
        ),
        "supermercado" to listOf(...),
        "parada" to listOf(...),
        "parque" to listOf(...)
    )
}
```

**Destinos Disponibles (Mock):**
- 🏥 Farmacia
- 🛒 Supermercado
- 🚌 Parada de bus
- 🌳 Parque
- 🏠 Casa

---

## 🔊 Sistema de Prioridad de Audio

### El Problema

¿Qué pasa cuando Navigation dice *"Gira a la derecha"* y Safety detecta un obstáculo?

### La Solución: Interrupciones por Prioridad

```
┌─────────────────────────────────────────────────────────────┐
│ Timeline                                                     │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│ NAV:  "Gira 45 grados a la dere--"                          │
│                    │                                         │
│                    │ ← INTERRUPCIÓN                         │
│                    ▼                                         │
│ SAFETY: "¡CUIDADO! Obstáculo a 1.5 metros al frente"        │
│                                                              │
│ [2 segundos después]                                         │
│                                                              │
│ NAV:  "Gira 45 grados a la derecha"  (reinicia)             │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Código de Interrupción (MainActivity)

```kotlin
// Job 1: Safety (siempre activo, máxima prioridad)
safetyJob = lifecycleScope.launch {
    cameraSource.frames.collect { frame ->
        val result = safetyAnalyzer.analyze(frame)
        
        if (result.hasImmediateDanger) {
            // 1. INTERRUMPIR todo audio actual
            audioManager.interruptForSafety()
            
            // 2. Vibrar dispositivo
            vibrator.vibrate(VibrationEffect.createOneShot(500, 255))
            
            // 3. Anunciar peligro
            audioManager.speak(
                "¡Cuidado! ${result.nearestObstacle} a ${result.distance} metros",
                AudioPriority.SAFETY
            )
        }
    }
}

// Job 2: Navigation (prioridad media, puede ser interrumpido)
navigationJob = lifecycleScope.launch {
    navigationManager.navigationState.collect { state ->
        // Solo habla si Safety no está hablando
        audioManager.speak(state.instruction, AudioPriority.NAVIGATION)
    }
}
```

### Matriz de Prioridades

| Hablando | Nuevo Mensaje | Acción |
|----------|---------------|--------|
| NAV | SAFETY | ⚡ **Interrumpe inmediatamente** |
| NAV | NAV | Encola (espera) |
| SAFETY | NAV | ❌ Ignora |
| SAFETY | SAFETY | ⚡ Interrumpe (nuevo peligro) |
| Nada | Cualquiera | ✅ Reproduce |

---

## 🧭 Cómo Funciona la Navegación

### Paso 1: Usuario da comando de voz

```
Usuario: "Llévame a la farmacia"
         │
         ▼
VoiceCommander.parseCommand()
         │
         ▼
VoiceCommand.Navigate("farmacia")
```

### Paso 2: Se calcula la ruta

```kotlin
val route = MockRouteProvider.calculateRoute("farmacia")
// route = [Point1, Point2, Point3, ..., Destino]
```

### Paso 3: NavigationManager inicia tracking

```kotlin
navigationManager.startNavigation(route)
// - Activa GPS (cada 2 segundos)
// - Activa brújula (tiempo real)
```

### Paso 4: Generación de instrucciones

```
┌─────────────────────────────────────────┐
│ GPS dice: Estás en (41.385, 2.173)      │
│ Brújula dice: Miras hacia 90° (Este)    │
│ Siguiente waypoint: (41.386, 2.175)     │
│                                          │
│ Cálculo:                                 │
│   bearing_objetivo = 45° (Noreste)       │
│   bearing_actual = 90° (Este)            │
│   diferencia = 45° - 90° = -45°          │
│                                          │
│ Instrucción: "Gira 45 grados a la        │
│              izquierda"                  │
└─────────────────────────────────────────┘
```

### Paso 5: Audio con prioridad NAV

```kotlin
audioManager.speak(
    "Gira 45 grados a la izquierda, luego continúa 50 metros",
    AudioPriority.NAVIGATION
)
```

---

## 🛡️ Detección de Obstáculos (Safety)

### Pipeline de Procesamiento

```
Frame (1920x1080) ──▶ ML Kit Object Detection ──▶ Lista de Objetos
                              │
                              ▼
                     ┌─────────────────┐
                     │ Objeto Detectado │
                     │ - label: "person"│
                     │ - bbox: Rect     │
                     │ - confidence: 87%│
                     └────────┬────────┘
                              │
                              ▼
                     ┌─────────────────┐
                     │ Estimación de   │
                     │ Distancia       │
                     │ (por tamaño bbox)│
                     └────────┬────────┘
                              │
                              ▼
                     ┌─────────────────┐
                     │ Análisis de     │
                     │ Riesgo          │
                     │ - posición      │
                     │ - velocidad     │
                     │ - trayectoria   │
                     └────────┬────────┘
                              │
                              ▼
                     SafetyAnalysisResult
```

### Estimación de Distancia

Sin sensores de profundidad (LiDAR), estimamos distancia por **tamaño del bounding box**:

```kotlin
fun estimateDistance(bbox: Rect, frameHeight: Int): Float {
    val heightRatio = bbox.height().toFloat() / frameHeight
    
    // Heurística: objeto más grande = más cerca
    return when {
        heightRatio > 0.7 -> 0.5f   // Muy cerca (< 1m)
        heightRatio > 0.5 -> 1.5f   // Cerca (1-2m)
        heightRatio > 0.3 -> 3.0f   // Medio (2-4m)
        heightRatio > 0.1 -> 5.0f   // Lejos (4-6m)
        else -> 10.0f               // Muy lejos
    }
}
```

---

## 🎤 Comandos de Voz

### Activación

1. Pulsar botón 🎤 en pantalla
2. Esperar tono de confirmación
3. Decir comando

### Lista de Comandos

| Español | Inglés | Acción |
|---------|--------|--------|
| "Llévame a [destino]" | "Take me to [dest]" | Inicia navegación |
| "Ir a [destino]" | "Go to [dest]" | Inicia navegación |
| "Para" | "Stop" | Detiene navegación |
| "¿Dónde estoy?" | "Where am I?" | Ubicación actual |
| "Repetir" | "Repeat" | Repite última instrucción |

---

## 📋 Permisos Requeridos

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.INTERNET" />
```

| Permiso | Uso |
|---------|-----|
| CAMERA | Detección de obstáculos |
| FINE_LOCATION | GPS preciso para navegación |
| COARSE_LOCATION | GPS aproximado (fallback) |
| RECORD_AUDIO | Comandos de voz |
| VIBRATE | Alertas hápticas |
| INTERNET | (Futuro) Rutas reales |

---

## 📁 Estructura del Proyecto

```
BlindNav/
├── app/
│   ├── src/main/
│   │   ├── java/com/blindnav/app/
│   │   │   │
│   │   │   ├── domain/                    # Lógica de negocio
│   │   │   │   ├── model/
│   │   │   │   │   ├── DetectedObject.kt
│   │   │   │   │   ├── SafetyAnalysisResult.kt
│   │   │   │   │   └── NavigationModels.kt    # AudioPriority, NavState
│   │   │   │   │
│   │   │   │   ├── safety/
│   │   │   │   │   └── SafetyAnalyzer.kt      # ML Kit + Heurísticas
│   │   │   │   │
│   │   │   │   └── navigation/
│   │   │   │       ├── NavigationManager.kt   # GPS + Brújula
│   │   │   │       └── MockRouteProvider.kt   # Rutas fake
│   │   │   │
│   │   │   ├── data/                      # Fuentes de datos
│   │   │   │   └── camera/
│   │   │   │       └── CameraSource.kt        # CameraX pipeline
│   │   │   │
│   │   │   └── ui/                        # Presentación
│   │   │       ├── MainActivity.kt            # Orquestador principal
│   │   │       ├── audio/
│   │   │       │   ├── FeedbackManager.kt     # Tonos + Vibración
│   │   │       │   └── PriorityAudioManager.kt # TTS con prioridades
│   │   │       ├── overlay/
│   │   │       │   └── BoundingBoxOverlay.kt  # Debug visual
│   │   │       └── voice/
│   │   │           └── VoiceCommander.kt      # SpeechRecognizer
│   │   │
│   │   └── res/
│   │       ├── layout/
│   │       │   └── activity_main.xml          # UI alto contraste
│   │       └── values/
│   │           ├── colors.xml
│   │           ├── strings.xml
│   │           └── themes.xml
│   │
│   └── build.gradle.kts                   # Dependencias
│
├── RunTests.ps1                           # Script de tests
└── README.md                              # Este archivo
```

---

## 🚀 Cómo Ejecutar

### Requisitos

- Android Studio Hedgehog (2023.1.1) o superior
- JDK 17
- Dispositivo Android físico (SDK 26+) con:
  - Cámara trasera
  - GPS
  - Micrófono

### Pasos

```powershell
# 1. Clonar/Abrir proyecto
cd BlindNav

# 2. Sincronizar Gradle
./gradlew build

# 3. Ejecutar en dispositivo
# (Android Studio > Run > Seleccionar dispositivo físico)
```

### Testing

```powershell
# Ejecutar tests unitarios
.\RunTests.ps1
```

### Casos de prueba cubiertos:

1. ✅ Objeto pequeño (lejos) → `SAFE`
2. ✅ Objeto mediano → `WARNING`
3. ✅ Objeto grande + centrado → `CRITICAL`
4. ✅ Objeto grande + lateral → `WARNING` (no tan peligroso)
5. ✅ Simulación de acercamiento frame a frame
6. ✅ Múltiples obstáculos (el más grande determina nivel)
7. ✅ Test de rendimiento (< 50ms por frame)

---

## �️ Componentes de OpenStreetMap

### NominatimGeocoder.kt

```kotlin
// Búsqueda con ViewBox local
suspend fun search(
    query: String,
    limit: Int = 5,
    userLat: Double? = null,  // Para búsqueda local
    userLon: Double? = null
): List<SearchResult>

// Ejemplo de uso:
val results = NominatimGeocoder.search(
    query = "Cítara",
    userLat = 41.52,
    userLon = 0.35
)
// Devuelve solo resultados en radio ±10km
```

### OSRMRouteProvider.kt

```kotlin
class OSRMRouteProvider(context: Context) {
    private val roadManager = OSRMRoadManager(context, USER_AGENT).apply {
        setMean(OSRMRoadManager.MEAN_BY_FOOT) // Modo PEATÓN
    }
    
    suspend fun calculateRoute(
        startLat: Double, startLon: Double,
        endLat: Double, endLon: Double,
        routeId: Long
    ): RouteResult? {
        val road = roadManager.getRoad(waypoints)
        
        // Extraer instrucciones Turn-by-Turn
        val instructions = road.mNodes.map { node ->
            TurnInstruction(
                distance = node.mLength * 1000,
                instruction = node.mInstructions, // "Gira a la izquierda"
                maneuverType = node.mManeuverType,
                latitude = node.mLocation.latitude,
                longitude = node.mLocation.longitude
            )
        }
        
        // Polyline para visualización
        val polyline = RoadManager.buildRoadOverlay(road)
        polyline.outlinePaint.color = 0xFF2196F3.toInt() // Azul
        polyline.outlinePaint.strokeWidth = 12f
        
        return RouteResult(checkpoints, road, polyline, instructions)
    }
}
```

### LocationSensorManager.kt

```kotlin
// Brújula con Rotation Vector (hardware fusion)
private val rotationVectorSensor: Sensor? =
    sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

private const val COMPASS_ALPHA = 0.05f // Filtro muy agresivo

override fun onSensorChanged(event: SensorEvent) {
    when (event.sensor.type) {
        Sensor.TYPE_ROTATION_VECTOR -> {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            
            var azimuth = Math.toDegrees(orientationAngles[0].toDouble())
            // Suavizar con low-pass filter
            smoothedBearing = smoothBearing(smoothedBearing, azimuth)
        }
    }
}
```

### HomeActivity.kt - Configuración del Mapa

```kotlin
private fun setupMap() {
    mapView.apply {
        setTileSource(TileSourceFactory.MAPNIK) // Tiles de OSM
        setMultiTouchControls(true)
        controller.setZoom(18.0) // Nivel calle
    }
    
    // MyLocationOverlay
    myLocationOverlay = MyLocationNewOverlay(locationProvider, mapView)
    myLocationOverlay.enableMyLocation()
    myLocationOverlay.enableFollowLocation()
    
    // Centrar en primera ubicación GPS
    myLocationOverlay.runOnFirstFix {
        runOnUiThread {
            mapView.controller.setZoom(18.0)
            mapView.controller.animateTo(myLocationOverlay.myLocation)
        }
    }
}
```

---

## 📊 Comparativa: Antes vs Ahora

| Aspecto | ❌ ANTES (Google Maps) | ✅ AHORA (OpenStreetMap) |
|---------|----------------------|------------------------|
| **API Keys** | Requerido (facturación) | Sin API keys |
| **Costos** | $7/1000 requests | Gratis ilimitado |
| **Geocoding** | Google Places API | Nominatim (OSM) |
| **Routing** | Directions API | OSRM + OSRMBonusPack |
| **Rutas** | Líneas genéricas | Turn-by-Turn por calles |
| **Instrucciones** | "Ve al Norte" | "Gira a la izquierda en 50m" |
| **Búsqueda** | Global (mundo) | Local con viewbox ±10km |
| **Brújula** | Magnetometer + Accel | Rotation Vector (fusion) |
| **Estabilidad** | Jitter visible | Ultra-estable (alpha 0.05) |
| **Mapa offline** | No | Posible con tiles cache |
| **Libertad** | Limitada (ToS) | Open source completo |

---

## �🔮 Futuras Mejoras

- [x] ~~Integrar rutas reales~~ → **✅ HECHO con OSRM Turn-by-Turn**
- [x] ~~Búsqueda de lugares~~ → **✅ HECHO con Nominatim local**
- [x] ~~Brújula estable~~ → **✅ HECHO con Rotation Vector**
- [ ] Modo offline completo con tiles de OSM descargados
- [ ] Añadir sensor de profundidad (ARCore Depth API)
- [ ] Detección de semáforos y señales con YOLO
- [ ] Aprendizaje de rutas frecuentes
- [ ] Soporte multi-idioma (inglés, catalán, español)
- [ ] Integración completa con TalkBack
- [ ] Audio espacial 3D para obstáculos laterales
- [ ] Notificaciones hápticas direccionales

---

## 📄 Licencia

MIT License - Proyecto académico FIB-UPC

---

<p align="center">
  <b>BlindNav</b> - Navegación accesible para todos 🦯<br>
  <i>Desarrollado para el proyecto GAFAS - FIB UPC</i>
</p>
