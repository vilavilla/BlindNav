package com.blindnav.app.domain.crossing

import com.blindnav.app.domain.source.FrameData

/**
 * CrossingAnalyzer - Analizador de cruces peatonales
 * 
 * Interfaz preparada para futura detección de pasos de cebra
 * y asistencia háptica para mantener al usuario centrado.
 * 
 * FUNCIONALIDAD FUTURA:
 * - Detectar líneas paralelas (paso de cebra)
 * - Calcular desviación del centro
 * - Emitir feedback háptico izq/der
 */
interface CrossingAnalyzer {
    
    /**
     * Feedback de cruce peatonal.
     */
    sealed class CrossingFeedback {
        /** Usuario centrado en el cruce */
        object OnCenter : CrossingFeedback()
        
        /** Usuario desviándose hacia la izquierda */
        data class DeviatingLeft(val degrees: Float) : CrossingFeedback()
        
        /** Usuario desviándose hacia la derecha */
        data class DeviatingRight(val degrees: Float) : CrossingFeedback()
        
        /** No se detecta cruce peatonal */
        object NoCrossingDetected : CrossingFeedback()
    }
    
    /**
     * Estado del detector de cruces.
     */
    enum class CrossingState {
        /** Detector inactivo */
        IDLE,
        
        /** Buscando cruce peatonal */
        SEARCHING,
        
        /** Cruce detectado, guiando al usuario */
        ACTIVE,
        
        /** Cruce completado */
        COMPLETED
    }
    
    /**
     * Analiza un frame para detectar cruce peatonal.
     * 
     * @param frame Datos del frame a analizar
     * @return Feedback de cruce o null si no hay cruce
     */
    fun analyzeCrossing(frame: FrameData): CrossingFeedback?
    
    /**
     * Activa el modo de asistencia de cruce.
     */
    fun startCrossingAssist()
    
    /**
     * Desactiva el modo de asistencia de cruce.
     */
    fun stopCrossingAssist()
    
    /**
     * Indica si la asistencia de cruce está activa.
     */
    fun isActive(): Boolean
}

/**
 * Configuración para feedback háptico de cruce.
 */
data class HapticCrossingConfig(
    /** Umbral de desviación para activar feedback (grados) */
    val deviationThreshold: Float = 10f,
    
    /** Duración de vibración en ms */
    val vibrationDurationMs: Long = 100,
    
    /** Intensidad de vibración (0-255) */
    val vibrationIntensity: Int = 128
)
