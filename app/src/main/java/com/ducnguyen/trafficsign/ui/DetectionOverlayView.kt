package com.ducnguyen.trafficsign.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.ducnguyen.trafficsign.model.Detection
import kotlin.math.abs

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

    private var tracks: List<Track> = emptyList()

    fun updateDetections(detections: List<Detection>, frameTimeMs: Long = SystemClock.elapsedRealtime()) {
        if (detections.isEmpty()) {
            pruneStaleTracks(frameTimeMs)
            postInvalidateOnAnimation()
            return
        }

        tracks = detections.map { detection ->
            val previous = findPreviousTrack(detection)
            val dt = previous?.let { (frameTimeMs - it.frameTimeMs).coerceAtLeast(1L).toFloat() }

            Track(
                signId = detection.signId,
                confidence = detection.confidence,
                rect = RectF(detection.x1, detection.y1, detection.x2, detection.y2),
                vx1 = if (previous != null && dt != null) (detection.x1 - previous.rect.left) / dt else 0f,
                vy1 = if (previous != null && dt != null) (detection.y1 - previous.rect.top) / dt else 0f,
                vx2 = if (previous != null && dt != null) (detection.x2 - previous.rect.right) / dt else 0f,
                vy2 = if (previous != null && dt != null) (detection.y2 - previous.rect.bottom) / dt else 0f,
                frameTimeMs = frameTimeMs
            )
        }
        postInvalidateOnAnimation()
    }

    private fun pruneStaleTracks(nowMs: Long) {
        tracks = tracks.filter { nowMs - it.frameTimeMs <= MAX_STALE_MS }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val now = SystemClock.elapsedRealtime()
        var hasFreshTrack = false

        for (track in tracks) {
            val ageMs = now - track.frameTimeMs
            if (ageMs > MAX_STALE_MS) continue

            hasFreshTrack = true
            val predictMs = ageMs.coerceIn(0L, MAX_PREDICTION_MS).toFloat()
            val rect = RectF(
                track.rect.left + track.vx1 * predictMs,
                track.rect.top + track.vy1 * predictMs,
                track.rect.right + track.vx2 * predictMs,
                track.rect.bottom + track.vy2 * predictMs
            )

            canvas.drawRect(rect, boxPaint)
            drawLabel(canvas, rect, "${track.signId} ${(track.confidence * 100).toInt()}%")
        }

        if (hasFreshTrack) postInvalidateOnAnimation()
    }

    private fun drawLabel(canvas: Canvas, rect: RectF, label: String) {
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

    private fun findPreviousTrack(detection: Detection): Track? {
        val cx = (detection.x1 + detection.x2) / 2f
        val cy = (detection.y1 + detection.y2) / 2f

        return tracks
            .asSequence()
            .filter { it.signId == detection.signId }
            .minByOrNull { track ->
                val tcx = (track.rect.left + track.rect.right) / 2f
                val tcy = (track.rect.top + track.rect.bottom) / 2f
                abs(cx - tcx) + abs(cy - tcy)
            }
    }

    private data class Track(
        val signId: String,
        val confidence: Float,
        val rect: RectF,
        val vx1: Float,
        val vy1: Float,
        val vx2: Float,
        val vy2: Float,
        val frameTimeMs: Long
    )

    companion object {
        private const val MAX_PREDICTION_MS = 140L
        private const val MAX_STALE_MS = 800L
    }
}
