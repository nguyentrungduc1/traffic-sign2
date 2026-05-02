package com.ducnguyen.trafficsign.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.ducnguyen.trafficsign.model.Detection

class DetectionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }
    private val textBgPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 32f
        style = Paint.Style.FILL
        typeface = Typeface.DEFAULT_BOLD
    }

    private var detections: List<Detection> = emptyList()

    fun updateDetections(dets: List<Detection>) {
        detections = dets
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (det in detections) {
            val rect = RectF(det.x1, det.y1, det.x2, det.y2)
            canvas.drawRect(rect, boxPaint)

            val label = "${det.signId} ${(det.confidence * 100).toInt()}%"
            val textWidth = textPaint.measureText(label)
            val textHeight = textPaint.textSize
            val textTop = (rect.top - textHeight - 4f).coerceAtLeast(0f)

            canvas.drawRect(
                rect.left,
                textTop,
                rect.left + textWidth + 8f,
                textTop + textHeight + 8f,
                textBgPaint
            )
            canvas.drawText(label, rect.left + 4f, textTop + textHeight, textPaint)
        }
    }
}
