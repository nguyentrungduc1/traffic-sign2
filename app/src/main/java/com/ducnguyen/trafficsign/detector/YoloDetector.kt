package com.ducnguyen.trafficsign.detector

import android.content.Context
import android.graphics.Bitmap
import com.ducnguyen.trafficsign.model.Detection
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class YoloDetector(context: Context) {

    private val interpreter: Interpreter
    private val labels: List<String>
    private val inputSize = 640
    private val confThreshold = 0.25f
    private val iouThreshold = 0.45f
    private val numClasses = 70
    private val maxNmsCandidates = 120
    private val maxDetections = 10
    private val inputBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val pixels = IntArray(inputSize * inputSize)
    private val output = Array(1) { Array(74) { FloatArray(8400) } }

    companion object {
        private const val MODEL_PATH = "model/yolo_traffic_sign.tflite"
        private const val LABELS_PATH = "model/labels.txt"
    }

    init {
        labels = context.assets.open(LABELS_PATH)
            .bufferedReader(Charsets.UTF_8)
            .readLines()
            .filter { it.isNotBlank() }

        check(labels.size == numClasses) {
            "labels.txt must contain exactly $numClasses lines, found ${labels.size}"
        }

        val model = loadModelFile(context)
        val options = Interpreter.Options().apply { numThreads = 4 }
        interpreter = Interpreter(model, options)
    }

    private fun loadModelFile(context: Context): MappedByteBuffer {
        val afd = context.assets.openFd(MODEL_PATH)
        val inputStream = FileInputStream(afd.fileDescriptor)
        return inputStream.channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
    }

    @Synchronized
    fun detect(bitmap: Bitmap): List<Detection> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        bitmapToByteBuffer(resized)
        if (resized !== bitmap) resized.recycle()
        interpreter.run(inputBuffer, output)
        return parseOutput(output[0], bitmap.width, bitmap.height)
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap) {
        inputBuffer.rewind()
        bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        for (pixel in pixels) {
            inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            inputBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            inputBuffer.putFloat((pixel and 0xFF) / 255.0f)
        }
        inputBuffer.rewind()
    }

    private fun parseOutput(output: Array<FloatArray>, origW: Int, origH: Int): List<Detection> {
        val boxes = mutableListOf<FloatArray>()
        for (i in 0 until 8400) {
            val xc = output[0][i]
            val yc = output[1][i]
            val w  = output[2][i]
            val h  = output[3][i]

            var maxConf = 0f
            var maxIdx = 0
            for (c in 0 until numClasses) {
                val conf = output[4 + c][i]
                if (conf > maxConf) { maxConf = conf; maxIdx = c }
            }

            if (maxConf < confThreshold) continue

            val x1 = ((xc - w / 2f) * origW).coerceIn(0f, origW.toFloat())
            val y1 = ((yc - h / 2f) * origH).coerceIn(0f, origH.toFloat())
            val x2 = ((xc + w / 2f) * origW).coerceIn(0f, origW.toFloat())
            val y2 = ((yc + h / 2f) * origH).coerceIn(0f, origH.toFloat())

            if (x2 <= x1 || y2 <= y1) continue
            boxes.add(floatArrayOf(x1, y1, x2, y2, maxConf, maxIdx.toFloat()))
        }
        val candidates = boxes
            .sortedByDescending { it[4] }
            .take(maxNmsCandidates)

        return nms(candidates).map { box ->
            Detection(
                signId = labels[box[5].toInt()],
                confidence = box[4],
                x1 = box[0], y1 = box[1], x2 = box[2], y2 = box[3]
            )
        }
    }

    private fun nms(boxes: List<FloatArray>): List<FloatArray> {
        val sorted = boxes.sortedByDescending { it[4] }
        val keep = mutableListOf<FloatArray>()
        val suppressed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            keep.add(sorted[i])
            if (keep.size >= maxDetections) break
            for (j in i + 1 until sorted.size) {
                if (!suppressed[j] && iou(sorted[i], sorted[j]) > iouThreshold) suppressed[j] = true
            }
        }
        return keep
    }

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val ix1 = maxOf(a[0], b[0]); val iy1 = maxOf(a[1], b[1])
        val ix2 = minOf(a[2], b[2]); val iy2 = minOf(a[3], b[3])
        val inter = maxOf(0f, ix2 - ix1) * maxOf(0f, iy2 - iy1)
        val aArea = (a[2] - a[0]) * (a[3] - a[1])
        val bArea = (b[2] - b[0]) * (b[3] - b[1])
        return inter / (aArea + bArea - inter + 1e-6f)
    }

    fun close() { interpreter.close() }
}
