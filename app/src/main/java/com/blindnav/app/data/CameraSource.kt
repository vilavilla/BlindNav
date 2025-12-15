package com.blindnav.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.blindnav.app.domain.SafetyAnalyzer
import com.blindnav.app.domain.model.SafetyAnalysisResult
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.util.concurrent.Executors

/**
 * CameraSource - Fuente de datos de la cámara
 * 
 * Gestiona el pipeline de CameraX para captura de frames en tiempo real.
 * 
 * CONFIGURACIÓN CRÍTICA:
 * - BackpressureStrategy.KEEP_ONLY_LATEST: Descarta frames antiguos si el análisis es lento
 * - Resolución 640x480: Balance entre calidad y velocidad de procesamiento
 * - Executor dedicado: No bloquea el Main Thread
 * 
 * @param context Contexto de Android
 * @param safetyAnalyzer Analizador de seguridad para procesar frames
 */
class CameraSource(
    private val context: Context,
    private val safetyAnalyzer: SafetyAnalyzer
) {
    
    // ============================================
    // FLUJO DE DATOS
    // ============================================
    
    private val _analysisResultFlow = MutableSharedFlow<SafetyAnalysisResult>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    /**
     * Flujo público de resultados de análisis.
     * Los observers reciben el último resultado de cada frame procesado.
     */
    val analysisResultFlow: SharedFlow<SafetyAnalysisResult> = _analysisResultFlow.asSharedFlow()
    
    // ============================================
    // COMPONENTES DE CAMERAX
    // ============================================
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    
    // Executor dedicado para análisis de imágenes (no bloquea UI)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    
    // Scope de coroutinas para procesamiento asíncrono
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Control de estado
    private var isAnalyzing = false

    /**
     * Inicializa y vincula la cámara al ciclo de vida.
     * 
     * @param lifecycleOwner Owner del ciclo de vida (Activity/Fragment)
     * @param previewView Vista de preview opcional (puede ser null para modo headless)
     */
    suspend fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: androidx.camera.view.PreviewView? = null
    ) = withContext(Dispatchers.Main) {
        
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            
            bindCameraUseCases(lifecycleOwner, previewView)
            
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Configura y vincula los casos de uso de CameraX.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases(
        lifecycleOwner: LifecycleOwner,
        previewView: androidx.camera.view.PreviewView?
    ) {
        val cameraProvider = cameraProvider ?: return
        
        // Desvincular casos de uso anteriores
        cameraProvider.unbindAll()
        
        // ============================================
        // CASO DE USO 1: PREVIEW (Opcional)
        // ============================================
        val preview = Preview.Builder()
            .setTargetResolution(Size(640, 480))
            .build()
            .also { previewUseCase ->
                previewView?.let { view ->
                    previewUseCase.setSurfaceProvider(view.surfaceProvider)
                }
            }
        
        // ============================================
        // CASO DE USO 2: IMAGE ANALYSIS (Crítico)
        // ============================================
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            // CRÍTICO: Descarta frames si el análisis es lento
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // Usar YUV para mejor rendimiento
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor, createImageAnalyzer())
            }
        
        // ============================================
        // SELECTOR DE CÁMARA: TRASERA
        // ============================================
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()
        
        // ============================================
        // VINCULAR AL CICLO DE VIDA
        // ============================================
        try {
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            
            isAnalyzing = true
            
        } catch (e: Exception) {
            e.printStackTrace()
            isAnalyzing = false
        }
    }

    /**
     * Crea el analizador de imágenes que procesa cada frame.
     * 
     * FLUJO:
     * 1. Recibe ImageProxy de CameraX
     * 2. Convierte a InputImage de ML Kit
     * 3. Pasa al SafetyAnalyzer
     * 4. Emite resultado al Flow
     * 5. Cierra ImageProxy (CRÍTICO: evita memory leaks)
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun createImageAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            
            val mediaImage = imageProxy.image
            
            if (mediaImage != null) {
                // Convertir a InputImage de ML Kit
                val inputImage = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.imageInfo.rotationDegrees
                )
                
                val imageWidth = imageProxy.width
                val imageHeight = imageProxy.height
                
                // Procesar en coroutine (no bloquea el executor)
                scope.launch {
                    try {
                        val result = safetyAnalyzer.analyze(
                            inputImage,
                            imageWidth,
                            imageHeight
                        )
                        
                        // Emitir resultado al flow
                        _analysisResultFlow.emit(result)
                        
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        // CRÍTICO: Siempre cerrar el ImageProxy
                        imageProxy.close()
                    }
                }
            } else {
                // Si no hay imagen, cerrar el proxy de todas formas
                imageProxy.close()
            }
        }
    }

    /**
     * Pausa el análisis de frames (mantiene la cámara activa).
     */
    fun pauseAnalysis() {
        isAnalyzing = false
        imageAnalyzer?.clearAnalyzer()
    }

    /**
     * Reanuda el análisis de frames.
     */
    fun resumeAnalysis() {
        imageAnalyzer?.setAnalyzer(analysisExecutor, createImageAnalyzer())
        isAnalyzing = true
    }

    /**
     * Detiene completamente la cámara y libera recursos.
     */
    fun stopCamera() {
        isAnalyzing = false
        scope.cancel()
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        safetyAnalyzer.close()
    }

    /**
     * Indica si el análisis está activo.
     */
    fun isAnalyzing(): Boolean = isAnalyzing
}
