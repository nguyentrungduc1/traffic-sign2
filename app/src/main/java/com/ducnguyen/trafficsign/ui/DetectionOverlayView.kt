package com.ducnguyen.trafficsign.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.ducnguyen.trafficsign.model.Detection

class DetectionOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        color = Color.GREEN; strokeWidth = 4f; style = Paint.Style.STROKE
    }
    private val textBgPaint = Paint().apply {
        color = Color.GREEN; style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        color = Color.BLACK; textSize = 32f
        style = Paint.Style.FILL; typeface = Typeface.DEFAULT_BOLD
    }

    private var detections: List<Detection> = emptyList()
    private var frameW: Int = 1
    private var frameH: Int = 1

    fun updateDetections(dets: List<Detection>, fw: Int, fh: Int) {
        detections = dets; frameW = fw; frameH = fh
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // FIT_CENTER: scale đều, căn giữa — khớp với PreviewView.ScaleType.FIT_CENTER
        val scale = minOf(width.toFloat() / frameW, height.toFloat() / frameH)
        val dx = (width - frameW * scale) / 2f
        val dy = (height - frameH * scale) / 2f

        for (det in detections) {
            val rect = RectF(
                det.x1 * scale + dx, det.y1 * scale + dy,
                det.x2 * scale + dx, det.y2 * scale + dy
            )
            canvas.drawRect(rect, boxPaint)

            val label = "${det.signId} ${(det.confidence * 100).toInt()}%"
            val tw = textPaint.measureText(label)
            val th = textPaint.textSize
            val textTop = (rect.top - th - 4f).coerceAtLeast(dy)

            canvas.drawRect(rect.left, textTop, rect.left + tw + 8f, textTop + th + 8f, textBgPaint)
            canvas.drawText(label, rect.left + 4f, textTop + th, textPaint)
        }
    }
}
