package com.ducnguyen.trafficsign

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ducnguyen.trafficsign.detector.YoloDetector
import com.ducnguyen.trafficsign.model.Detection
import com.ducnguyen.trafficsign.ocr.SpeedOcrHelper
import com.ducnguyen.trafficsign.repository.SignRepository
import com.ducnguyen.trafficsign.ui.DetectionOverlayView
import com.ducnguyen.trafficsign.ui.SignListAdapter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: DetectionOverlayView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SignListAdapter

    private lateinit var detector: YoloDetector
    private lateinit var ocrHelper: SpeedOcrHelper
    private lateinit var signRepository: SignRepository
    private lateinit var tts: TextToSpeech
    private lateinit var cameraExecutor: ExecutorService

    private val isAnalyzing = AtomicBoolean(false)
    private val isOcrRunning = AtomicBoolean(false)

    private var ttsReady = false
    private var lastAnalysisTimeMs = 0L
    private var lastDebugLogTimeMs = 0L
    private var lastGenericWarningTimeMs = 0L

    private val detectW = 320
    private val detectH = 240

    companion object {
        private const val TAG = "TrafficSign"
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
        private const val REQUEST_CODE = 100
        private const val MIN_ANALYSIS_INTERVAL_MS = 80L
        private const val DETAIL_CONFIDENCE_THRESHOLD = 0.50f
        private const val GENERIC_WARNING_INTERVAL_MS = 3000L
        private const val GENERIC_WARNING_TEXT = "Chú ý biển báo"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.preview_view)
        overlayView = findViewById(R.id.detection_overlay)
        recyclerView = findViewById(R.id.recycler_view)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

        adapter = SignListAdapter(this)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        detector = YoloDetector(this)
        ocrHelper = SpeedOcrHelper()
        signRepository = SignRepository(this)
        tts = TextToSpeech(this, this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(CAMERA_PERMISSION), REQUEST_CODE)
        }
    }

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            val provider = ProcessCameraProvider.getInstance(this).get()
            previewView.post { bindCameraUseCases(provider) }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(provider: ProcessCameraProvider) {
        val preview = Preview.Builder()
            .setTargetResolution(Size(640, 480))
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val analyzer = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(cameraExecutor) { proxy -> processFrame(proxy) } }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
        }
    }

    private fun processFrame(proxy: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalysisTimeMs < MIN_ANALYSIS_INTERVAL_MS) {
            proxy.close()
            return
        }
        if (!isAnalyzing.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        lastAnalysisTimeMs = now

        try {
            val fullBitmap = proxy.toBitmap()
            val detectStartMs = SystemClock.elapsedRealtime()
            val smallBitmap = Bitmap.createScaledBitmap(fullBitmap, detectW, detectH, true)
            val detections = detector.detect(smallBitmap)
            val detectMs = SystemClock.elapsedRealtime() - detectStartMs

            val scaleX = fullBitmap.width.toFloat() / detectW
            val scaleY = fullBitmap.height.toFloat() / detectH
            val scaledDetections = detections.map { detection ->
                detection.copy(
                    x1 = detection.x1 * scaleX,
                    y1 = detection.y1 * scaleY,
                    x2 = detection.x2 * scaleX,
                    y2 = detection.y2 * scaleY
                )
            }
            val overlayDetections = mapDetectionsWithFitCenter(
                fullBitmap.width,
                fullBitmap.height,
                scaledDetections
            )
            val resultTimeMs = SystemClock.elapsedRealtime()
            logDetectionState(now, fullBitmap, overlayDetections, detectMs)

            runOnUiThread {
                overlayView.updateDetections(overlayDetections, resultTimeMs)
            }

            handleAnnouncements(fullBitmap, scaledDetections)
        } catch (e: Exception) {
            Log.e(TAG, "processFrame error", e)
        } finally {
            proxy.close()
            isAnalyzing.set(false)
        }
    }

    private fun logDetectionState(
        now: Long,
        bitmap: Bitmap,
        overlayDetections: List<Detection>,
        detectMs: Long
    ) {
        if (now - lastDebugLogTimeMs < 1000L) return
        lastDebugLogTimeMs = now
        val firstBox = overlayDetections.firstOrNull()?.let {
            ", first=${it.signId} [${it.x1.toInt()},${it.y1.toInt()},${it.x2.toInt()},${it.y2.toInt()}]"
        } ?: ""
        Log.d(
            TAG,
            "bitmap=${bitmap.width}x${bitmap.height}, detections=${overlayDetections.size}, detectMs=$detectMs, " +
                "preview=${previewView.width}x${previewView.height}, " +
                "overlay=${overlayView.width}x${overlayView.height}$firstBox"
        )
    }

    private fun mapDetectionsWithFitCenter(
        frameWidth: Int,
        frameHeight: Int,
        detections: List<Detection>
    ): List<Detection> {
        val viewW = overlayView.width.toFloat()
        val viewH = overlayView.height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return emptyList()

        val frameW = frameWidth.toFloat()
        val frameH = frameHeight.toFloat()
        val scale = minOf(viewW / frameW, viewH / frameH)
        val dx = (viewW - frameW * scale) / 2f
        val dy = (viewH - frameH * scale) / 2f

        return detections.map { detection ->
            detection.copy(
                x1 = detection.x1 * scale + dx,
                y1 = detection.y1 * scale + dy,
                x2 = detection.x2 * scale + dx,
                y2 = detection.y2 * scale + dy
            )
        }
    }

    private fun handleAnnouncements(bitmap: Bitmap, detections: List<Detection>) {
        for (detection in detections.take(5)) {
            if (detection.confidence <= DETAIL_CONFIDENCE_THRESHOLD) {
                speakGenericWarning()
                continue
            }

            val sign = signRepository.getSign(detection.signId) ?: continue

            if (detection.signId == "P.127") {
                if (!isOcrRunning.compareAndSet(false, true)) continue
                val cropped = cropBitmap(bitmap, detection.x1, detection.y1, detection.x2, detection.y2)
                ocrHelper.recognizeSpeed(cropped) { speed ->
                    isOcrRunning.set(false)
                    if (speed == null) return@recognizeSpeed
                    runOnUiThread {
                        if (!signRepository.shouldAnnounce(detection.signId)) return@runOnUiThread
                        adapter.addSign(sign)
                        recyclerView.scrollToPosition(0)
                        speak(signRepository.buildTtsText(sign, speed))
                    }
                }
            } else if (detection.signId in SpeedOcrHelper.OCR_SIGN_IDS) {
                if (!isOcrRunning.compareAndSet(false, true)) continue
                val cropped = cropBitmap(bitmap, detection.x1, detection.y1, detection.x2, detection.y2)
                ocrHelper.recognizeSpeed(cropped) { speed ->
                    isOcrRunning.set(false)
                    runOnUiThread {
                        if (!signRepository.shouldAnnounce(detection.signId)) return@runOnUiThread
                        adapter.addSign(sign)
                        recyclerView.scrollToPosition(0)
                        speak(signRepository.buildTtsText(sign, speed))
                    }
                }
            } else {
                runOnUiThread {
                    if (!signRepository.shouldAnnounce(detection.signId)) return@runOnUiThread
                    adapter.addSign(sign)
                    recyclerView.scrollToPosition(0)
                    speak(signRepository.buildTtsText(sign, null))
                }
            }
        }
    }

    private fun speakGenericWarning() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGenericWarningTimeMs < GENERIC_WARNING_INTERVAL_MS) return
        lastGenericWarningTimeMs = now
        runOnUiThread { speak(GENERIC_WARNING_TEXT) }
    }

    private fun cropBitmap(bmp: Bitmap, x1: Float, y1: Float, x2: Float, y2: Float): Bitmap {
        val pad = ((x2 - x1) * 0.15f).coerceAtLeast(8f)
        val left = (x1 - pad).coerceIn(0f, bmp.width.toFloat()).toInt()
        val top = (y1 - pad).coerceIn(0f, bmp.height.toFloat()).toInt()
        val right = (x2 + pad).coerceIn(0f, bmp.width.toFloat()).toInt()
        val bottom = (y2 + pad).coerceIn(0f, bmp.height.toFloat()).toInt()
        return Bitmap.createBitmap(
            bmp,
            left,
            top,
            (right - left).coerceAtLeast(1),
            (bottom - top).coerceAtLeast(1)
        )
    }

    private fun speak(text: String) {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_ADD, null, null)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("vi", "VN")
            ttsReady = true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Can quyen camera", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        detector.close()
        ocrHelper.close()
        tts.stop()
        tts.shutdown()
    }
}
