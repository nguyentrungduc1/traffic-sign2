package com.ducnguyen.trafficsign.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.ducnguyen.trafficsign.model.Detection

class DebugImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val boxPaint = Paint().apply {
        color = Color.RED; strokeWidth = 4f; style = Paint.Style.STROKE
    }
    private val textBgPaint = Paint().apply { color = Color.RED; style = Paint.Style.FILL }
    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = 32f
        style = Paint.Style.FILL; typeface = Typeface.DEFAULT_BOLD
    }
    private val infoPaint = Paint().apply { color = Color.YELLOW; textSize = 26f }

    private var bitmap: Bitmap? = null
    private var detections: List<Detection> = emptyList()

    fun setImage(bmp: Bitmap) { bitmap = bmp; invalidate() }
    fun setDetections(dets: List<Detection>) { detections = dets; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return

        val scale = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val dx = (width - bmp.width * scale) / 2f
        val dy = (height - bmp.height * scale) / 2f

        val src = Rect(0, 0, bmp.width, bmp.height)
        val dst = RectF(dx, dy, dx + bmp.width * scale, dy + bmp.height * scale)
        canvas.drawBitmap(bmp, src, dst, null)

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

        canvas.drawText(
            "DEBUG | ${detections.size} detections | ${bmp.width}x${bmp.height}",
            dx + 8f, dy + 32f, infoPaint
        )
    }
}
