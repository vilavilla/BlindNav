# BlindNav 🦯

**Aplicación Android de navegación y detección de obstáculos para personas con discapacidad visual.**

> El usuario puede decir *"Llévame a la farmacia"* y el sistema lo guía paso a paso mientras escanea el entorno en busca de obstáculos.

---

## 📋 Índice

1. [Descripción General](#-descripción-general)
2. [Arquitectura del Sistema](#-arquitectura-del-sistema)
3. [Flujo de Datos](#-flujo-de-datos)
4. [Componentes Principales](#-componentes-principales)
5. [Sistema de Prioridad de Audio](#-sistema-de-prioridad-de-audio)
6. [Cómo Funciona la Navegación](#-cómo-funciona-la-navegación)
7. [Detección de Obstáculos (Safety)](#-detección-de-obstáculos-safety)
8. [Comandos de Voz](#-comandos-de-voz)
9. [Permisos Requeridos](#-permisos-requeridos)
10. [Estructura del Proyecto](#-estructura-del-proyecto)
11. [Cómo Ejecutar](#-cómo-ejecutar)

---

## 🎯 Descripción General

BlindNav es una aplicación Android diseñada para ayudar a personas ciegas o con baja visión a:

1. **Navegar** hacia destinos usando GPS y brújula
2. **Detectar obstáculos** en tiempo real usando la cámara y ML Kit
3. **Recibir feedback auditivo** con prioridad inteligente (seguridad > navegación)

### Características Clave

- ✅ **Offline-first**: Detección de objetos sin conexión a internet
- ✅ **Dual-task paralelo**: Safety y Navigation corren simultáneamente
- ✅ **Audio inteligente**: Safety SIEMPRE interrumpe a Navigation
- ✅ **Alto contraste**: UI diseñada para baja visión
- ✅ **Comandos de voz**: "Llévame a X", "Ir a X", "Parar"

---

## 🏗️ Arquitectura del Sistema

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

| Componente | Tecnología |
|------------|------------|
| Lenguaje | Kotlin 1.9.20 |
| Arquitectura | MVVM + Clean Architecture |
| Visión | CameraX 1.3.0 |
| ML Offline | ML Kit Object Detection 17.0.0 |
| GPS | Google Play Services Location 21.0.1 |
| Brújula | SensorManager (TYPE_ROTATION_VECTOR) |
| Voz | SpeechRecognizer + TextToSpeech |
| Async | Coroutines + Flow |
| Testing | JUnit 4 + Mockito 5 |

---

## 🔄 Flujo de Datos

### 1. Flujo de Safety (Detección de Obstáculos)

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

## 🔮 Futuras Mejoras

- [ ] Integrar Google Directions API para rutas reales
- [ ] Añadir sensor de profundidad (ARCore Depth API)
- [ ] Modo offline completo con mapas descargados
- [ ] Detección de semáforos y señales
- [ ] Aprendizaje de rutas frecuentes
- [ ] Soporte multi-idioma
- [ ] Integración con TalkBack

---

## 📄 Licencia

MIT License - Proyecto académico FIB-UPC

---

<p align="center">
  <b>BlindNav</b> - Navegación accesible para todos 🦯<br>
  <i>Desarrollado para el proyecto GAFAS - FIB UPC</i>
</p>
