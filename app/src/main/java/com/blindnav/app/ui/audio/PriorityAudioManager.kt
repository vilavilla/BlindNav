package com.blindnav.app.ui.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.blindnav.app.domain.model.AudioMessage
import com.blindnav.app.domain.model.AudioPriority
import com.blindnav.app.domain.model.HazardLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * PriorityAudioManager - Gestor de Audio Inteligente con Prioridades
 * 
 * ARQUITECTURA DE PRIORIDADES:
 * ┌─────────────────────────────────────────────────────────┐
 * │  SAFETY (Prioridad 3) - INTERRUMPE TODO                 │
 * │  • Beeps de obstáculos                                  │
 * │  • Alarmas de peligro crítico                           │
 * │  • Vibración de emergencia                              │
 * ├─────────────────────────────────────────────────────────┤
 * │  NAVIGATION (Prioridad 2) - Espera si hay Safety        │
 * │  • "Gira a la derecha en 10 metros"                     │
 * │  • "Continúa recto"                                     │
 * │  • Instrucciones de ruta                                │
 * ├─────────────────────────────────────────────────────────┤
 * │  SYSTEM (Prioridad 1) - Se encola, puede descartarse    │
 * │  • "Ruta calculada"                                     │
 * │  • "Navegación iniciada"                                │
 * │  • Confirmaciones                                       │
 * └─────────────────────────────────────────────────────────┘
 * 
 * RESOLUCIÓN DE CONFLICTOS:
 * - Si llega SAFETY mientras NAV está hablando → NAV se interrumpe inmediatamente
 * - Si llega NAV mientras SYSTEM habla → SYSTEM se interrumpe
 * - Si llega SAFETY mientras otro SAFETY suena → Se encola (no se pierde)
 * - Mensajes SYSTEM antiguos (>3s) se descartan automáticamente
 */
class PriorityAudioManager(private val context: Context) {

    companion object {
        private const val TAG = "PriorityAudioManager"
        private const val SYSTEM_MESSAGE_TTL_MS = 3000L // Time-to-live para mensajes SYSTEM
        private const val SAFETY_COOLDOWN_MS = 300L     // Cooldown entre beeps de safety
    }

    // ============================================
    // COMPONENTES DE AUDIO
    // ============================================
    
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    
    private val toneGenerator: ToneGenerator by lazy {
        ToneGenerator(AudioManager.STREAM_ALARM, 100)
    }
    
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
    
    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    // ============================================
    // COLAS DE PRIORIDAD
    // ============================================
    
    // Cola para mensajes de cada prioridad
    private val safetyQueue = ConcurrentLinkedQueue<AudioMessage>()
    private val navigationQueue = ConcurrentLinkedQueue<AudioMessage>()
    private val systemQueue = ConcurrentLinkedQueue<AudioMessage>()
    
    // Estado actual
    private var currentPriority: AudioPriority? = null
    private var isSpeaking = false
    private var lastSafetyBeepTime = 0L
    
    // Scope de coroutinas
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var processingJob: Job? = null
    
    // Flow para notificar cambios de estado
    private val _audioState = MutableStateFlow<AudioState>(AudioState.Idle)
    val audioState: StateFlow<AudioState> = _audioState.asStateFlow()

    // ============================================
    // INICIALIZACIÓN
    // ============================================
    
    init {
        initializeTts()
        startQueueProcessor()
    }
    
    private fun initializeTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    val result = engine.setLanguage(Locale("es", "ES"))
                    if (result == TextToSpeech.LANG_MISSING_DATA || 
                        result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        // Fallback a inglés
                        engine.setLanguage(Locale.US)
                    }
                    
                    // Configurar velocidad para claridad
                    engine.setSpeechRate(1.0f)
                    engine.setPitch(1.0f)
                    
                    // Listener para saber cuándo termina de hablar
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            isSpeaking = true
                            _audioState.value = AudioState.Speaking(currentPriority ?: AudioPriority.SYSTEM)
                        }
                        
                        override fun onDone(utteranceId: String?) {
                            isSpeaking = false
                            currentPriority = null
                            _audioState.value = AudioState.Idle
                        }
                        
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            isSpeaking = false
                            currentPriority = null
                            _audioState.value = AudioState.Idle
                        }
                    })
                    
                    isTtsReady = true
                    Log.d(TAG, "TTS inicializado correctamente")
                }
            } else {
                Log.e(TAG, "Error inicializando TTS: $status")
            }
        }
    }

    // ============================================
    // API PÚBLICA - ENCOLAR MENSAJES
    // ============================================
    
    /**
     * Encola un mensaje con la prioridad especificada.
     */
    fun speak(text: String, priority: AudioPriority) {
        val message = AudioMessage(text, priority)
        
        when (priority) {
            AudioPriority.SAFETY -> {
                safetyQueue.offer(message)
                // Safety es urgente - procesar inmediatamente
                processImmediately()
            }
            AudioPriority.NAVIGATION -> {
                navigationQueue.offer(message)
            }
            AudioPriority.SYSTEM -> {
                // Limpiar mensajes SYSTEM antiguos
                cleanOldSystemMessages()
                systemQueue.offer(message)
            }
        }
        
        Log.d(TAG, "Mensaje encolado: [$priority] $text")
    }
    
    /**
     * Emite alerta de seguridad (beep + vibración + voz opcional).
     */
    fun alertSafety(hazardLevel: HazardLevel, spokenMessage: String? = null) {
        val now = System.currentTimeMillis()
        
        // Cooldown para evitar spam de beeps
        if (now - lastSafetyBeepTime < SAFETY_COOLDOWN_MS && hazardLevel != HazardLevel.CRITICAL) {
            return
        }
        lastSafetyBeepTime = now
        
        // Interrumpir cualquier cosa que esté sonando
        if (currentPriority != AudioPriority.SAFETY) {
            interruptCurrentAudio()
        }
        
        currentPriority = AudioPriority.SAFETY
        _audioState.value = AudioState.Speaking(AudioPriority.SAFETY)
        
        when (hazardLevel) {
            HazardLevel.SAFE -> {
                // No hacer nada en SAFE
            }
            HazardLevel.WARNING -> {
                // Beep corto + vibración suave
                scope.launch(Dispatchers.IO) {
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                    vibratePattern(longArrayOf(0, 100))
                }
            }
            HazardLevel.CRITICAL -> {
                // Alarma urgente + vibración fuerte + voz
                scope.launch(Dispatchers.IO) {
                    // Patrón de alarma: beep-beep-beeeep
                    toneGenerator.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 100)
                    delay(150)
                    toneGenerator.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 100)
                    delay(150)
                    toneGenerator.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 300)
                    
                    // Vibración de emergencia
                    vibratePattern(longArrayOf(0, 200, 100, 200, 100, 400))
                }
                
                // Mensaje de voz si se proporciona
                spokenMessage?.let {
                    speak(it, AudioPriority.SAFETY)
                }
            }
        }
    }
    
    /**
     * Emite instrucción de navegación.
     */
    fun speakNavigation(instruction: String) {
        speak(instruction, AudioPriority.NAVIGATION)
    }
    
    /**
     * Emite confirmación del sistema.
     */
    fun speakSystem(message: String) {
        speak(message, AudioPriority.SYSTEM)
    }

    // ============================================
    // PROCESADOR DE COLAS
    // ============================================
    
    private fun startQueueProcessor() {
        processingJob = scope.launch {
            while (isActive) {
                processNextMessage()
                delay(50) // Polling interval
            }
        }
    }
    
    private fun processNextMessage() {
        // Si ya estamos hablando con prioridad alta, esperar
        if (isSpeaking && currentPriority == AudioPriority.SAFETY) {
            return
        }
        
        // Procesar en orden de prioridad
        val message = safetyQueue.poll()
            ?: navigationQueue.poll()
            ?: systemQueue.poll()
        
        message?.let { msg ->
            // Si hay un mensaje de mayor prioridad, interrumpir
            if (isSpeaking && currentPriority != null) {
                if (msg.priority.level > currentPriority!!.level) {
                    interruptCurrentAudio()
                } else if (msg.priority.level < currentPriority!!.level) {
                    // Re-encolar si es de menor prioridad
                    requeue(msg)
                    return
                }
            }
            
            speakInternal(msg)
        }
    }
    
    private fun processImmediately() {
        // Forzar procesamiento inmediato para mensajes SAFETY
        scope.launch {
            processNextMessage()
        }
    }
    
    private fun speakInternal(message: AudioMessage) {
        if (!isTtsReady) {
            Log.w(TAG, "TTS no está listo, descartando mensaje: ${message.text}")
            return
        }
        
        currentPriority = message.priority
        isSpeaking = true
        _audioState.value = AudioState.Speaking(message.priority)
        
        val utteranceId = "msg_${System.currentTimeMillis()}"
        
        tts?.speak(
            message.text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            utteranceId
        )
        
        Log.d(TAG, "Hablando [${message.priority}]: ${message.text}")
    }
    
    private fun interruptCurrentAudio() {
        tts?.stop()
        isSpeaking = false
        Log.d(TAG, "Audio interrumpido (prioridad anterior: $currentPriority)")
    }
    
    private fun requeue(message: AudioMessage) {
        when (message.priority) {
            AudioPriority.SAFETY -> safetyQueue.offer(message)
            AudioPriority.NAVIGATION -> navigationQueue.offer(message)
            AudioPriority.SYSTEM -> systemQueue.offer(message)
        }
    }
    
    private fun cleanOldSystemMessages() {
        val now = System.currentTimeMillis()
        systemQueue.removeIf { now - it.timestamp > SYSTEM_MESSAGE_TTL_MS }
    }

    // ============================================
    // VIBRACIÓN
    // ============================================
    
    private fun vibratePattern(pattern: LongArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitudes = IntArray(pattern.size) { if (it % 2 == 0) 0 else 255 }
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    // ============================================
    // LIMPIEZA
    // ============================================
    
    fun release() {
        processingJob?.cancel()
        scope.cancel()
        
        tts?.stop()
        tts?.shutdown()
        tts = null
        
        try {
            toneGenerator.release()
        } catch (e: Exception) {
            // Ignorar
        }
        
        safetyQueue.clear()
        navigationQueue.clear()
        systemQueue.clear()
        
        Log.d(TAG, "PriorityAudioManager liberado")
    }

    // ============================================
    // ESTADOS
    // ============================================
    
    sealed class AudioState {
        object Idle : AudioState()
        data class Speaking(val priority: AudioPriority) : AudioState()
    }
}
