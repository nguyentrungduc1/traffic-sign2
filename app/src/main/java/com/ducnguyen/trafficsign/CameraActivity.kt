package com.ducnguyen.trafficsign

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ducnguyen.trafficsign.detector.YoloDetector
import com.ducnguyen.trafficsign.ocr.SpeedOcrHelper
import com.ducnguyen.trafficsign.repository.SignRepository
import com.ducnguyen.trafficsign.ui.DebugImageView
import com.ducnguyen.trafficsign.ui.DetectionOverlayView
import com.ducnguyen.trafficsign.ui.SignListAdapter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: DetectionOverlayView
    private lateinit var debugImageView: DebugImageView
    private lateinit var btnDebug: Button
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SignListAdapter

    private lateinit var detector: YoloDetector
    private lateinit var ocrHelper: SpeedOcrHelper
    private lateinit var signRepository: SignRepository
    private lateinit var tts: TextToSpeech
    private lateinit var cameraExecutor: ExecutorService

    private var ttsReady = false
    private val isAnalyzing = AtomicBoolean(false)
    private var isDebugMode = false
    private var loggedOnce = false

    companion object {
        private const val TAG = "TrafficSign"
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
        private const val REQUEST_CODE = 100
        private const val DEBUG_IMAGE = "test_image.jpg"
        // Khớp với bitmap thực tế từ ImageProxy
        private const val ANALYSIS_WIDTH = 640
        private const val ANALYSIS_HEIGHT = 480
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        previewView    = findViewById(R.id.preview_view)
        overlayView    = findViewById(R.id.overlay_view)
        debugImageView = findViewById(R.id.debug_image_view)
        btnDebug       = findViewById(R.id.btn_debug)
        recyclerView   = findViewById(R.id.recycler_view)

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        overlayView.bringToFront()

        adapter = SignListAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        detector       = YoloDetector(this)
        ocrHelper      = SpeedOcrHelper()
        signRepository = SignRepository(this)
        tts            = TextToSpeech(this, this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        btnDebug.setOnClickListener { toggleDebug() }

        if (ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(CAMERA_PERMISSION), REQUEST_CODE)
        }
    }

    private fun toggleDebug() {
        isDebugMode = !isDebugMode
        if (isDebugMode) {
            previewView.visibility = View.GONE
            overlayView.visibility = View.GONE
            debugImageView.visibility = View.VISIBLE
            btnDebug.text = "Camera"
            runDebug()
        } else {
            previewView.visibility = View.VISIBLE
            overlayView.visibility = View.VISIBLE
            debugImageView.visibility = View.GONE
            btnDebug.text = "Debug"
        }
    }

    private fun runDebug() {
        val bitmap = try {
            assets.open(DEBUG_IMAGE).use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            Toast.makeText(this, "Không tìm thấy $DEBUG_IMAGE trong assets", Toast.LENGTH_LONG).show()
            return
        }
        debugImageView.setImage(bitmap)
        debugImageView.setDetections(emptyList())
        cameraExecutor.execute {
            val dets = detector.detect(bitmap)
            Log.d(TAG, "DEBUG: ${dets.size} detections on ${bitmap.width}x${bitmap.height}")
            dets.forEach { Log.d(TAG, "DEBUG: ${it.signId} ${it.confidence} [${it.x1},${it.y1},${it.x2},${it.y2}]") }
            runOnUiThread { debugImageView.setDetections(dets) }
        }
    }

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            val provider = ProcessCameraProvider.getInstance(this).get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analyzer = ImageAnalysis.Builder()
                // Hardcode 640x480 để khớp bitmap thực tế — không để CameraX tự chọn
                .setTargetResolution(Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(cameraExecutor) { proxy -> processFrame(proxy) }
                }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(proxy: ImageProxy) {
        if (isDebugMode) { proxy.close(); return }
        if (!isAnalyzing.compareAndSet(false, true)) { proxy.close(); return }

        try {
            val bitmap = proxy.toBitmap()

            if (!loggedOnce) {
                Log.d(TAG, "bitmap: ${bitmap.width}x${bitmap.height}")
                Log.d(TAG, "rotation: ${proxy.imageInfo.rotationDegrees}")
                runOnUiThread {
                    Log.d(TAG, "previewView: ${previewView.width}x${previewView.height}")
                    Log.d(TAG, "overlayView: ${overlayView.width}x${overlayView.height}")
                }
                loggedOnce = true
            }

            val dets = detector.detect(bitmap)
            runOnUiThread {
                overlayView.updateDetections(dets, bitmap.width, bitmap.height)
            }

            for (det in dets.take(5)) {
                val sign = signRepository.getSign(det.signId) ?: continue
                if (!signRepository.shouldAnnounce(det.signId)) continue
                if (det.signId in SpeedOcrHelper.OCR_SIGN_IDS) {
                    val cropped = cropBitmap(bitmap, det.x1, det.y1, det.x2, det.y2)
                    ocrHelper.recognizeSpeed(cropped) { speed ->
                        runOnUiThread { adapter.addSign(sign); speak(signRepository.buildTtsText(sign, speed)) }
                    }
                } else {
                    runOnUiThread { adapter.addSign(sign); speak(signRepository.buildTtsText(sign, null)) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFrame error", e)
        } finally {
            proxy.close()
            isAnalyzing.set(false)
        }
    }

    private fun cropBitmap(bmp: Bitmap, x1: Float, y1: Float, x2: Float, y2: Float): Bitmap {
        val pad = ((x2 - x1) * 0.15f).coerceAtLeast(8f)
        val l = (x1 - pad).coerceIn(0f, bmp.width.toFloat()).toInt()
        val t = (y1 - pad).coerceIn(0f, bmp.height.toFloat()).toInt()
        val r = (x2 + pad).coerceIn(0f, bmp.width.toFloat()).toInt()
        val b = (y2 + pad).coerceIn(0f, bmp.height.toFloat()).toInt()
        return Bitmap.createBitmap(bmp, l, t, (r - l).coerceAtLeast(1), (b - t).coerceAtLeast(1))
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else Toast.makeText(this, "Cần quyền camera", Toast.LENGTH_SHORT).show()
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
