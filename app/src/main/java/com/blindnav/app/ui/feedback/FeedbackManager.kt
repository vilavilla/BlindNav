package com.blindnav.app.ui.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.blindnav.app.domain.model.HazardLevel
import kotlinx.coroutines.*
import java.util.*

/**
 * FeedbackManager - Sistema de Retroalimentación Multimodal
 * 
 * Gestiona las alertas de audio, vibración y TTS para comunicar
 * el nivel de peligro al usuario invidente.
 * 
 * Principios de diseño:
 * - Baja latencia: Usa ToneGenerator en lugar de MediaPlayer
 * - No bloquea UI: Todas las operaciones son asíncronas
 * - Prioridad a CRITICAL: Interrumpe cualquier feedback anterior
 * 
 * @param context Contexto de Android para acceso a servicios
 */
class FeedbackManager(private val context: Context) {

    // ============================================
    // COMPONENTES DE AUDIO
    // ============================================
    
    /**
     * ToneGenerator de baja latencia para beeps inmediatos.
     * Usa el stream de ALARM para máxima audibilidad.
     */
    private var toneGenerator: ToneGenerator? = null
    
    /**
     * Text-to-Speech para mensajes verbales.
     * Se usa para instrucciones de navegación, no para alertas críticas.
     */
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false

    // ============================================
    // COMPONENTES DE VIBRACIÓN
    // ============================================
    
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    // ============================================
    // CONTROL DE ESTADO
    // ============================================
    
    private var currentAlertJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Último nivel de alerta para evitar repeticiones innecesarias
    private var lastHazardLevel: HazardLevel = HazardLevel.SAFE
    private var lastAlertTime: Long = 0L
    
    // Configuración de tiempos mínimos entre alertas (evitar spam)
    private val minAlertIntervalMs = 300L // 300ms entre alertas
    private val criticalRepeatIntervalMs = 500L // Repetir CRITICAL cada 500ms

    init {
        initializeToneGenerator()
        initializeTextToSpeech()
    }

    /**
     * Inicializa el generador de tonos de baja latencia.
     */
    private fun initializeToneGenerator() {
        try {
            toneGenerator = ToneGenerator(
                AudioManager.STREAM_ALARM,
                ToneGenerator.MAX_VOLUME
            )
        } catch (e: Exception) {
            // Fallback si no se puede crear el ToneGenerator
            e.printStackTrace()
        }
    }

    /**
     * Inicializa el motor de Text-to-Speech.
     */
    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale("es", "ES")
                isTtsReady = true
            }
        }
    }

    /**
     * FUNCIÓN PRINCIPAL DE ALERTA
     * 
     * Dispara feedback multimodal basado en el nivel de peligro.
     * 
     * @param level Nivel de peligro detectado
     */
    fun alert(level: HazardLevel) {
        val currentTime = System.currentTimeMillis()
        
        // Throttling: Evitar alertas demasiado frecuentes (excepto CRITICAL)
        if (level != HazardLevel.CRITICAL && 
            level == lastHazardLevel &&
            currentTime - lastAlertTime < minAlertIntervalMs) {
            return
        }
        
        // Cancelar alerta anterior si cambia el nivel
        if (level != lastHazardLevel) {
            currentAlertJob?.cancel()
        }
        
        lastHazardLevel = level
        lastAlertTime = currentTime

        currentAlertJob = scope.launch {
            when (level) {
                HazardLevel.CRITICAL -> alertCritical()
                HazardLevel.WARNING -> alertWarning()
                HazardLevel.SAFE -> alertSafe()
            }
        }
    }

    /**
     * ALERTA CRÍTICA - ¡PELIGRO INMINENTE!
     * 
     * - Detiene cualquier TTS en curso
     * - Vibración continua fuerte
     * - Tono de alta frecuencia repetido
     */
    private suspend fun alertCritical() {
        // 1. Detener TTS inmediatamente
        stopTextToSpeech()
        
        // 2. Vibración continua de emergencia
        vibrateCritical()
        
        // 3. Tono de alarma de alta frecuencia
        playCriticalTone()
    }

    /**
     * ALERTA DE PRECAUCIÓN
     * 
     * - Vibración corta
     * - Beep simple de advertencia
     */
    private suspend fun alertWarning() {
        // Vibración corta de advertencia
        vibrateWarning()
        
        // Beep de advertencia
        playWarningTone()
    }

    /**
     * ESTADO SEGURO
     * 
     * - Cancela vibraciones activas
     * - Opcionalmente, un beep suave de confirmación
     */
    private fun alertSafe() {
        // Cancelar vibración
        vibrator.cancel()
        
        // Beep suave opcional de "todo despejado"
        // (comentado para no molestar al usuario)
        // playSafeTone()
    }

    // ============================================
    // MÉTODOS DE VIBRACIÓN
    // ============================================

    /**
     * Vibración CRÍTICA - Patrón de alarma pulsante fuerte
     * 
     * Patrón tipo "alarma de emergencia":
     * - Pulsos largos y fuertes
     * - Repetición rápida
     * - Máxima intensidad
     */
    private fun vibrateCritical() {
        if (!vibrator.hasVibrator()) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Patrón de alarma: FUERTE-pausa-FUERTE-pausa-FUERTE
            // [delay, vibrate, sleep, vibrate, sleep, vibrate...]
            val pattern = longArrayOf(
                0,    // Delay inicial
                300,  // Vibración LARGA
                100,  // Pausa corta
                300,  // Vibración LARGA
                100,  // Pausa corta
                500   // Vibración MUY LARGA
            )
            val amplitudes = intArrayOf(
                0,    // Delay (sin vibración)
                255,  // Máxima intensidad
                0,    // Pausa
                255,  // Máxima intensidad
                0,    // Pausa
                255   // Máxima intensidad
            )
            
            val effect = VibrationEffect.createWaveform(pattern, amplitudes, -1)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 300, 100, 300, 100, 500), -1)
        }
    }

    /**
     * Vibración WARNING - Pulso corto de advertencia (100ms)
     */
    private fun vibrateWarning() {
        if (!vibrator.hasVibrator()) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Vibración corta de 100ms con intensidad media
            val effect = VibrationEffect.createOneShot(
                100, // 100ms - corta
                VibrationEffect.DEFAULT_AMPLITUDE
            )
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(100) // 100ms
        }
    }

    /**
     * Vibración de confirmación - Pulso corto y suave
     */
    fun vibrateConfirmation() {
        if (!vibrator.hasVibrator()) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(50, 100)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }

    /**
     * Vibración de peligro - Pulso fuerte y largo
     */
    fun vibrateDanger() {
        vibrateCritical()
    }

    /**
     * Vibración de éxito - Doble pulso celebratorio
     */
    fun vibrateSuccess() {
        if (!vibrator.hasVibrator()) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pattern = longArrayOf(0, 100, 100, 100)
            val amplitudes = intArrayOf(0, 150, 0, 200)
            val effect = VibrationEffect.createWaveform(pattern, amplitudes, -1)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 100, 100, 100), -1)
        }
    }

    // ============================================
    // MÉTODOS DE AUDIO (TONOS)
    // ============================================

    /**
     * Tono CRÍTICO - Beep agudo de alarma
     * 
     * Usa TONE_CDMA_EMERGENCY_RINGBACK que es muy penetrante
     * y diseñado para llamar la atención inmediatamente.
     */
    private fun playCriticalTone() {
        toneGenerator?.apply {
            // Tono de emergencia - muy agudo y llamativo
            // Duración 500ms para que sea claramente audible
            startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 500)
        }
    }

    /**
     * Tono WARNING - Beep corto de advertencia
     */
    private fun playWarningTone() {
        toneGenerator?.apply {
            // Beep simple y corto (150ms)
            startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        }
    }

    /**
     * Tono suave de confirmación (ruta despejada).
     */
    @Suppress("unused")
    private fun playSafeTone() {
        toneGenerator?.apply {
            startTone(ToneGenerator.TONE_PROP_ACK, 100)
        }
    }

    // ============================================
    // MÉTODOS DE TEXT-TO-SPEECH
    // ============================================

    /**
     * Habla un mensaje usando TTS.
     * 
     * @param message Mensaje a verbalizar
     * @param interrupt Si true, interrumpe cualquier mensaje en curso
     */
    fun speak(message: String, interrupt: Boolean = false) {
        if (!isTtsReady) return
        
        val queueMode = if (interrupt) {
            TextToSpeech.QUEUE_FLUSH
        } else {
            TextToSpeech.QUEUE_ADD
        }
        
        textToSpeech?.speak(message, queueMode, null, "blindnav_${System.currentTimeMillis()}")
    }

    /**
     * Detiene inmediatamente cualquier TTS en curso.
     * Crítico para alertas de emergencia.
     */
    fun stopTextToSpeech() {
        textToSpeech?.stop()
    }

    // ============================================
    // LIMPIEZA DE RECURSOS
    // ============================================

    /**
     * Libera todos los recursos.
     * DEBE llamarse en onDestroy() de la Activity.
     */
    fun release() {
        currentAlertJob?.cancel()
        scope.cancel()
        
        toneGenerator?.release()
        toneGenerator = null
        
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        
        vibrator.cancel()
    }
}
