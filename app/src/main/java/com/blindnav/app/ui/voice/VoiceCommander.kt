package com.blindnav.app.ui.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.blindnav.app.domain.model.VoiceCommandResult
import com.blindnav.app.domain.model.VoiceRecognizerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * VoiceCommander - Sistema de Reconocimiento de Voz
 * 
 * Permite al usuario dar comandos por voz como:
 * - "Llévame a la farmacia"
 * - "Ir a Mercadona"
 * - "Parar navegación"
 * - "¿Dónde estoy?"
 * - "Ayuda"
 * - "Repetir"
 * 
 * FLUJO:
 * 1. Usuario activa micrófono (botón o doble tap)
 * 2. Sistema escucha y convierte voz a texto
 * 3. Analiza el texto para extraer el comando
 * 4. Emite VoiceCommandResult al observador
 * 
 * COMANDOS SOPORTADOS:
 * ┌────────────────────────────────────────────────────────────┐
 * │  COMANDO                    │  RESULTADO                   │
 * ├────────────────────────────────────────────────────────────┤
 * │  "Llévame a X"              │  NavigateTo(X)               │
 * │  "Ir a X"                   │  NavigateTo(X)               │
 * │  "Navegar a X"              │  NavigateTo(X)               │
 * │  "Parar" / "Detener"        │  StopNavigation              │
 * │  "Repetir"                  │  RepeatInstruction           │
 * │  "¿Dónde estoy?"            │  WhereAmI                    │
 * │  "Ayuda"                    │  Help                        │
 * └────────────────────────────────────────────────────────────┘
 */
class VoiceCommander(private val context: Context) {

    companion object {
        private const val TAG = "VoiceCommander"
        
        // Patrones de comandos de navegación
        private val NAVIGATE_PATTERNS = listOf(
            "llévame a",
            "llevame a",
            "ir a",
            "ve a",
            "navegar a",
            "navega a",
            "quiero ir a",
            "llévame al",
            "llevame al",
            "ir al",
            "ve al",
            "navegar al",
            "navega al",
            "quiero ir al"
        )
        
        // Patrones de parar navegación
        private val STOP_PATTERNS = listOf(
            "parar",
            "para",
            "detener",
            "detén",
            "cancelar",
            "cancela",
            "stop",
            "terminar",
            "termina"
        )
        
        // Patrones de repetir
        private val REPEAT_PATTERNS = listOf(
            "repetir",
            "repite",
            "otra vez",
            "de nuevo",
            "qué dijiste",
            "que dijiste"
        )
        
        // Patrones de ubicación
        private val WHERE_PATTERNS = listOf(
            "dónde estoy",
            "donde estoy",
            "mi ubicación",
            "ubicación actual"
        )
        
        // Patrones de ayuda
        private val HELP_PATTERNS = listOf(
            "ayuda",
            "ayúdame",
            "help",
            "qué puedo decir",
            "que puedo decir",
            "comandos"
        )
    }

    // ============================================
    // ESTADO
    // ============================================
    
    private val _state = MutableStateFlow(VoiceRecognizerState.IDLE)
    val state: StateFlow<VoiceRecognizerState> = _state.asStateFlow()
    
    private val _lastCommand = MutableStateFlow<VoiceCommandResult?>(null)
    val lastCommand: StateFlow<VoiceCommandResult?> = _lastCommand.asStateFlow()
    
    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    // ============================================
    // SPEECH RECOGNIZER
    // ============================================
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    
    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Listo para escuchar")
            _state.value = VoiceRecognizerState.LISTENING
        }
        
        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Usuario comenzó a hablar")
        }
        
        override fun onRmsChanged(rmsdB: Float) {
            // Nivel de audio - podríamos usarlo para feedback visual
        }
        
        override fun onBufferReceived(buffer: ByteArray?) {}
        
        override fun onEndOfSpeech() {
            Log.d(TAG, "Usuario terminó de hablar")
            _state.value = VoiceRecognizerState.PROCESSING
        }
        
        override fun onError(error: Int) {
            val errorMessage = getErrorText(error)
            Log.e(TAG, "Error de reconocimiento: $errorMessage")
            
            _state.value = VoiceRecognizerState.ERROR
            _lastCommand.value = VoiceCommandResult.Error(errorMessage)
            
            isListening = false
            
            // Volver a IDLE después de un momento
            _state.value = VoiceRecognizerState.IDLE
        }
        
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            
            if (!matches.isNullOrEmpty()) {
                val recognizedText = matches[0]
                Log.d(TAG, "Texto reconocido: $recognizedText")
                
                _recognizedText.value = recognizedText
                
                val command = parseCommand(recognizedText)
                _lastCommand.value = command
                
                Log.d(TAG, "Comando interpretado: $command")
            } else {
                _lastCommand.value = VoiceCommandResult.Error("No se entendió el comando")
            }
            
            _state.value = VoiceRecognizerState.IDLE
            isListening = false
        }
        
        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            partial?.firstOrNull()?.let {
                _recognizedText.value = it
            }
        }
        
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ============================================
    // API PÚBLICA
    // ============================================
    
    /**
     * Inicializa el reconocedor de voz.
     */
    fun initialize() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Reconocimiento de voz no disponible en este dispositivo")
            return
        }
        
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }
        
        Log.d(TAG, "VoiceCommander inicializado")
    }
    
    /**
     * Inicia la escucha de voz.
     */
    fun startListening() {
        if (!hasAudioPermission()) {
            _lastCommand.value = VoiceCommandResult.Error("Permiso de micrófono requerido")
            return
        }
        
        if (isListening) {
            Log.d(TAG, "Ya está escuchando")
            return
        }
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("es", "ES").toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        
        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            _recognizedText.value = ""
            Log.d(TAG, "Escuchando...")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando reconocimiento: ${e.message}")
            _lastCommand.value = VoiceCommandResult.Error("Error al iniciar micrófono")
        }
    }
    
    /**
     * Detiene la escucha de voz.
     */
    fun stopListening() {
        speechRecognizer?.stopListening()
        isListening = false
        _state.value = VoiceRecognizerState.IDLE
        Log.d(TAG, "Escucha detenida")
    }
    
    /**
     * Cancela la escucha actual.
     */
    fun cancel() {
        speechRecognizer?.cancel()
        isListening = false
        _state.value = VoiceRecognizerState.IDLE
    }
    
    /**
     * Comprueba si hay permiso de audio.
     */
    fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    // ============================================
    // PARSING DE COMANDOS
    // ============================================
    
    private fun parseCommand(text: String): VoiceCommandResult {
        val normalizedText = text.lowercase().trim()
        
        // Buscar patrón de navegación
        for (pattern in NAVIGATE_PATTERNS) {
            if (normalizedText.startsWith(pattern)) {
                val destination = normalizedText.removePrefix(pattern).trim()
                if (destination.isNotEmpty()) {
                    return VoiceCommandResult.NavigateTo(destination)
                }
            }
        }
        
        // Buscar patrón de parar
        for (pattern in STOP_PATTERNS) {
            if (normalizedText.contains(pattern)) {
                return VoiceCommandResult.StopNavigation
            }
        }
        
        // Buscar patrón de repetir
        for (pattern in REPEAT_PATTERNS) {
            if (normalizedText.contains(pattern)) {
                return VoiceCommandResult.RepeatInstruction
            }
        }
        
        // Buscar patrón de ubicación
        for (pattern in WHERE_PATTERNS) {
            if (normalizedText.contains(pattern)) {
                return VoiceCommandResult.WhereAmI
            }
        }
        
        // Buscar patrón de ayuda
        for (pattern in HELP_PATTERNS) {
            if (normalizedText.contains(pattern)) {
                return VoiceCommandResult.Help
            }
        }
        
        // Si el texto parece un nombre de lugar, asumir navegación
        if (normalizedText.isNotEmpty() && !normalizedText.contains(" ") || 
            normalizedText.matches(Regex("^[a-záéíóúñ]+\$"))) {
            return VoiceCommandResult.NavigateTo(normalizedText)
        }
        
        return VoiceCommandResult.Unknown(text)
    }
    
    private fun getErrorText(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Error de audio"
            SpeechRecognizer.ERROR_CLIENT -> "Error del cliente"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permisos insuficientes"
            SpeechRecognizer.ERROR_NETWORK -> "Error de red"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tiempo de espera de red agotado"
            SpeechRecognizer.ERROR_NO_MATCH -> "No se encontró coincidencia"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconocedor ocupado"
            SpeechRecognizer.ERROR_SERVER -> "Error del servidor"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No se detectó voz"
            else -> "Error desconocido ($errorCode)"
        }
    }

    // ============================================
    // MENSAJES DE AYUDA
    // ============================================
    
    /**
     * Devuelve el texto de ayuda con los comandos disponibles.
     */
    fun getHelpText(): String {
        return """
            Puedes decir:
            "Llévame a la farmacia" para iniciar navegación.
            "Parar" para detener la navegación.
            "Repetir" para escuchar la última instrucción.
            "¿Dónde estoy?" para conocer tu ubicación.
            "Ayuda" para escuchar esta información.
            
            Destinos disponibles: farmacia, Mercadona, parada de bus, parque, banco, hospital.
        """.trimIndent()
    }

    // ============================================
    // LIMPIEZA
    // ============================================
    
    fun release() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        Log.d(TAG, "VoiceCommander liberado")
    }
}
