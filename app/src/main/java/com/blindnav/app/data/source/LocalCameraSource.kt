package com.blindnav.app.data.source

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.blindnav.app.domain.source.FrameData
import com.blindnav.app.domain.source.FrameProvider
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.Executors

/**
 * LocalCameraSource - Implementación de FrameProvider usando CameraX
 * 
 * Captura frames de la cámara trasera del dispositivo y los emite
 * como Flow<FrameData> para su procesamiento.
 * 
 * @param context Contexto de Android
 * @param previewView Vista opcional para mostrar el preview de cámara
 */
class LocalCameraSource(
    private val context: Context,
    private val previewView: PreviewView? = null
) : FrameProvider {
    
    // ============================================
    // FLUJO DE FRAMES
    // ============================================
    
    private val _frameFlow = MutableSharedFlow<FrameData>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    override val frameFlow: Flow<FrameData> = _frameFlow.asSharedFlow()
    
    // ============================================
    // COMPONENTES DE CAMERAX
    // ============================================
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var isActive = false
    
    // Executor dedicado para análisis (no bloquea UI)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    
    // ============================================
    // IMPLEMENTACIÓN DE FrameProvider
    // ============================================
    
    override fun start(lifecycleOwner: LifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(lifecycleOwner)
        }, ContextCompat.getMainExecutor(context))
    }
    
    override fun stop() {
        isActive = false
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }
    
    override fun isRunning(): Boolean = isActive
    
    // ============================================
    // CONFIGURACIÓN DE CAMERAX
    // ============================================
    
    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases(lifecycleOwner: LifecycleOwner) {
        val cameraProvider = cameraProvider ?: return
        
        cameraProvider.unbindAll()
        
        // Preview (opcional)
        val preview = Preview.Builder()
            .setTargetResolution(Size(640, 480))
            .build()
            .also { previewUseCase ->
                previewView?.let { view ->
                    previewUseCase.setSurfaceProvider(view.surfaceProvider)
                }
            }
        
        // Image Analysis
        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor, createFrameAnalyzer())
            }
        
        // Cámara trasera
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()
        
        try {
            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalyzer
            )
            isActive = true
        } catch (e: Exception) {
            e.printStackTrace()
            isActive = false
        }
    }
    
    /**
     * Crea el analizador que convierte ImageProxy a FrameData.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun createFrameAnalyzer(): ImageAnalysis.Analyzer {
        return ImageAnalysis.Analyzer { imageProxy ->
            val mediaImage = imageProxy.image
            
            if (mediaImage != null) {
                // Copia los planos antes de cerrar ImageProxy. InputImage.fromMediaImage
                // conservaría una referencia a memoria ya liberada al emitir por el Flow.
                val bytes = imageProxyToNv21(imageProxy)
                val rotation = imageProxy.imageInfo.rotationDegrees
                val inputImage = InputImage.fromByteArray(
                    bytes,
                    imageProxy.width,
                    imageProxy.height,
                    rotation,
                    InputImage.IMAGE_FORMAT_NV21
                )
                val quarterTurn = rotation % 180 != 0
                
                val frameData = FrameData(
                    image = inputImage,
                    width = if (quarterTurn) imageProxy.height else imageProxy.width,
                    height = if (quarterTurn) imageProxy.width else imageProxy.height,
                    timestamp = System.currentTimeMillis()
                )
                
                // Emitir frame al flow
                _frameFlow.tryEmit(frameData)
            }
            
            // CRÍTICO: Siempre cerrar el proxy
            imageProxy.close()
        }
    }

    private fun imageProxyToNv21(image: ImageProxy): ByteArray {
        require(image.format == ImageFormat.YUV_420_888)
        val width = image.width
        val height = image.height
        val output = ByteArray(width * height * 3 / 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        var offset = 0
        for (row in 0 until height) {
            for (column in 0 until width) {
                output[offset++] = yPlane.buffer.get(row * yPlane.rowStride + column * yPlane.pixelStride)
            }
        }
        for (row in 0 until height / 2) {
            for (column in 0 until width / 2) {
                output[offset++] = vPlane.buffer.get(row * vPlane.rowStride + column * vPlane.pixelStride)
                output[offset++] = uPlane.buffer.get(row * uPlane.rowStride + column * uPlane.pixelStride)
            }
        }
        return output
    }
}
