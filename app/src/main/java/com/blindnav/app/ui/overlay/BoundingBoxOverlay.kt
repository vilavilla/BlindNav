package com.blindnav.app.ui.overlay

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.blindnav.app.domain.model.DetectedObstacle
import com.blindnav.app.domain.model.HazardLevel

/**
 * BoundingBoxOverlay - Vista personalizada para dibujar BoundingBoxes
 * 
 * Dibuja rectángulos sobre los objetos detectados para depuración visual.
 * Los colores varían según el nivel de peligro:
 * - SAFE: Verde
 * - WARNING: Naranja  
 * - CRITICAL: Rojo pulsante
 */
class BoundingBoxOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Lista de obstáculos a dibujar
    private var obstacles: List<DetectedObstacle> = emptyList()
    private var hazardLevel: HazardLevel = HazardLevel.SAFE
    
    // Dimensiones de la imagen de análisis (para escalar coordenadas)
    private var imageWidth: Int = 640
    private var imageHeight: Int = 480
    
    // Paints para cada nivel de peligro
    private val safePaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    
    private val warningPaint = Paint().apply {
        color = Color.parseColor("#FF9800")
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }
    
    private val criticalPaint = Paint().apply {
        color = Color.parseColor("#F44336")
        style = Paint.Style.STROKE
        strokeWidth = 12f
        isAntiAlias = true
    }
    
    private val criticalFillPaint = Paint().apply {
        color = Color.parseColor("#40F44336")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val labelPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isFakeBoldText = true
        isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }
    
    private val labelBackgroundPaint = Paint().apply {
        color = Color.parseColor("#CC000000")
        style = Paint.Style.FILL
    }

    /**
     * Actualiza los obstáculos a dibujar.
     * 
     * @param newObstacles Lista de obstáculos detectados
     * @param newHazardLevel Nivel de peligro actual
     * @param imgWidth Ancho de la imagen de análisis
     * @param imgHeight Alto de la imagen de análisis
     */
    fun updateObstacles(
        newObstacles: List<DetectedObstacle>,
        newHazardLevel: HazardLevel,
        imgWidth: Int = 640,
        imgHeight: Int = 480
    ) {
        obstacles = newObstacles
        hazardLevel = newHazardLevel
        imageWidth = imgWidth
        imageHeight = imgHeight
        invalidate() // Redibuja la vista
    }

    /**
     * Limpia todos los obstáculos.
     */
    fun clear() {
        obstacles = emptyList()
        hazardLevel = HazardLevel.SAFE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (obstacles.isEmpty()) return
        
        // Calcular factores de escala para mapear coordenadas de imagen a vista
        val scaleX = width.toFloat() / imageWidth.toFloat()
        val scaleY = height.toFloat() / imageHeight.toFloat()
        
        obstacles.forEach { obstacle ->
            drawObstacle(canvas, obstacle, scaleX, scaleY)
        }
    }

    /**
     * Dibuja un obstáculo individual con su BoundingBox y etiqueta.
     */
    private fun drawObstacle(
        canvas: Canvas,
        obstacle: DetectedObstacle,
        scaleX: Float,
        scaleY: Float
    ) {
        val box = obstacle.boundingBox
        
        // Escalar coordenadas del bounding box a la vista
        // NOTA: La cámara puede estar rotada, así que mapeamos correctamente
        val left = box.left * scaleX
        val top = box.top * scaleY
        val right = box.right * scaleX
        val bottom = box.bottom * scaleY
        
        val rect = RectF(left, top, right, bottom)
        
        // Seleccionar paint según nivel de peligro
        val paint = when (hazardLevel) {
            HazardLevel.SAFE -> safePaint
            HazardLevel.WARNING -> warningPaint
            HazardLevel.CRITICAL -> criticalPaint
        }
        
        // Si es CRITICAL, dibujar fondo semitransparente
        if (hazardLevel == HazardLevel.CRITICAL) {
            canvas.drawRect(rect, criticalFillPaint)
        }
        
        // Dibujar el rectángulo del bounding box
        canvas.drawRect(rect, paint)
        
        // Dibujar esquinas redondeadas para mejor visibilidad
        drawCorners(canvas, rect, paint)
        
        // Dibujar etiqueta con información
        drawLabel(canvas, obstacle, rect)
    }

    /**
     * Dibuja esquinas resaltadas para mejor visibilidad.
     */
    private fun drawCorners(canvas: Canvas, rect: RectF, paint: Paint) {
        val cornerLength = 30f
        val cornerPaint = Paint(paint).apply {
            strokeWidth = paint.strokeWidth * 1.5f
        }
        
        // Esquina superior izquierda
        canvas.drawLine(rect.left, rect.top, rect.left + cornerLength, rect.top, cornerPaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + cornerLength, cornerPaint)
        
        // Esquina superior derecha
        canvas.drawLine(rect.right, rect.top, rect.right - cornerLength, rect.top, cornerPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + cornerLength, cornerPaint)
        
        // Esquina inferior izquierda
        canvas.drawLine(rect.left, rect.bottom, rect.left + cornerLength, rect.bottom, cornerPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - cornerLength, cornerPaint)
        
        // Esquina inferior derecha
        canvas.drawLine(rect.right, rect.bottom, rect.right - cornerLength, rect.bottom, cornerPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - cornerLength, cornerPaint)
    }

    /**
     * Dibuja etiqueta con el nombre del objeto y confianza.
     */
    private fun drawLabel(canvas: Canvas, obstacle: DetectedObstacle, rect: RectF) {
        val label = obstacle.label ?: "Obstáculo"
        val confidence = (obstacle.confidence * 100).toInt()
        val text = "$label ($confidence%)"
        
        // Medir texto
        val textBounds = Rect()
        labelPaint.getTextBounds(text, 0, text.length, textBounds)
        
        val padding = 8f
        val labelRect = RectF(
            rect.left,
            rect.top - textBounds.height() - padding * 2,
            rect.left + textBounds.width() + padding * 2,
            rect.top
        )
        
        // Asegurar que la etiqueta no salga de la pantalla
        if (labelRect.top < 0) {
            labelRect.offset(0f, rect.bottom - rect.top + textBounds.height() + padding * 2)
        }
        
        // Fondo de la etiqueta
        canvas.drawRoundRect(labelRect, 8f, 8f, labelBackgroundPaint)
        
        // Texto
        canvas.drawText(
            text,
            labelRect.left + padding,
            labelRect.bottom - padding,
            labelPaint
        )
    }
}
