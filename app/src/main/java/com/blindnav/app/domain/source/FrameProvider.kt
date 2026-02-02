package com.blindnav.app.domain.source

import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.flow.Flow

/**
 * FrameProvider - Abstracción de fuente de frames
 * 
 * Permite desacoplar el origen de los frames (cámara local, gafas externas, etc.)
 * del sistema de análisis de seguridad.
 * 
 * IMPLEMENTACIONES:
 * - LocalCameraSource: Cámara del dispositivo vía CameraX
 * - (Futuro) ExternalGlassesSource: Gafas Bluetooth/WiFi
 */
interface FrameProvider {
    
    /**
     * Flujo de frames para análisis.
     * Emite FrameData con la imagen y metadatos.
     */
    val frameFlow: Flow<FrameData>
    
    /**
     * Inicia la captura de frames.
     * @param lifecycleOwner Owner del ciclo de vida para vinculación automática
     */
    fun start(lifecycleOwner: LifecycleOwner)
    
    /**
     * Detiene la captura y libera recursos.
     */
    fun stop()
    
    /**
     * Indica si la fuente está emitiendo frames.
     */
    fun isRunning(): Boolean
}

/**
 * Datos de un frame capturado.
 * 
 * @property image Imagen en formato ML Kit InputImage
 * @property width Ancho de la imagen en píxeles
 * @property height Alto de la imagen en píxeles
 * @property timestamp Timestamp de captura (milisegundos)
 */
data class FrameData(
    val image: InputImage,
    val width: Int,
    val height: Int,
    val timestamp: Long = System.currentTimeMillis()
)
