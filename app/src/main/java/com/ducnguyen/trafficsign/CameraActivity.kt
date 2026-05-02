package com.ducnguyen.trafficsign

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ducnguyen.trafficsign.detector.YoloDetector
import com.ducnguyen.trafficsign.model.Detection
import com.ducnguyen.trafficsign.ocr.SpeedOcrHelper
import com.ducnguyen.trafficsign.repository.SignRepository
import com.ducnguyen.trafficsign.ui.SignListAdapter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraActivity : AppCompatActivity(), TextToSpeech.OnInitListener, SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var surfaceHolder: SurfaceHolder
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SignListAdapter

    private lateinit var detector: YoloDetector
    private lateinit var ocrHelper: SpeedOcrHelper
    private lateinit var signRepository: SignRepository
    private lateinit var tts: TextToSpeech
    private lateinit var cameraExecutor: ExecutorService

    private val boxPaint = Paint().apply {
        color = Color.GREEN; strokeWidth = 3f; style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val textBgPaint = Paint().apply {
        color = Color.GREEN; style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        color = Color.BLACK; textSize = 28f
        style = Paint.Style.FILL; typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    private val bitmapPaint = Paint().apply {
        isFilterBitmap = true
    }

    private var ttsReady = false
    private val isInferencing = AtomicBoolean(false)
    private var surfaceReady = false

    @Volatile private var lastDetections: List<Detection> = emptyList()
    @Volatile private var lastFrameW: Int = 1
    @Volatile private var lastFrameH: Int = 1

    private val detectW = 320
    private val detectH = 240

    companion object {
        private const val TAG = "TrafficSign"
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
        private const val REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_camera)

        surfaceView = findViewById(R.id.surface_view)
        recyclerView = findViewById(R.id.recycler_view)

        surfaceHolder = surfaceView.holder
        surfaceHolder.addCallback(this)

        adapter = SignListAdapter(this)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        detector       = YoloDetector(this)
        ocrHelper      = SpeedOcrHelper()
        signRepository = SignRepository(this)
        tts            = TextToSpeech(this, this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(CAMERA_PERMISSION), REQUEST_CODE)
        }
    }

    // SurfaceHolder callbacks
    override fun surfaceCreated(holder: SurfaceHolder) { surfaceReady = true }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { surfaceReady = true }
    override fun surfaceDestroyed(holder: SurfaceHolder) { surfaceReady = false }

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            val provider = ProcessCameraProvider.getInstance(this).get()
            val analyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(cameraExecutor) { proxy -> processFrame(proxy) }
                }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analyzer)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(proxy: ImageProxy) {
        try {
            val fullBitmap = proxy.toBitmap()

            // Vẽ frame + box lên SurfaceView trực tiếp — không qua main thread
            drawToSurface(fullBitmap, lastDetections, lastFrameW, lastFrameH)

            // Chạy inference song song nếu rảnh
            if (isInferencing.compareAndSet(false, true)) {
                val smallBitmap = Bitmap.createScaledBitmap(fullBitmap, detectW, detectH, true)
                cameraExecutor.execute {
                    try {
                        val dets = detector.detect(smallBitmap)
                        val scaleX = fullBitmap.width.toFloat() / detectW
                        val scaleY = fullBitmap.height.toFloat() / detectH
                        val scaledDets = dets.map { det ->
                            det.copy(
                                x1 = det.x1 * scaleX, y1 = det.y1 * scaleY,
                                x2 = det.x2 * scaleX, y2 = det.y2 * scaleY
                            )
                        }
                        lastDetections = scaledDets
                        lastFrameW = fullBitmap.width
                        lastFrameH = fullBitmap.height

                        for (det in scaledDets.take(5)) {
                            val sign = signRepository.getSign(det.signId) ?: continue
                            if (!signRepository.shouldAnnounce(det.signId)) continue
                            if (det.signId in SpeedOcrHelper.OCR_SIGN_IDS) {
                                val cropped = cropBitmap(fullBitmap, det.x1, det.y1, det.x2, det.y2)
                                ocrHelper.recognizeSpeed(cropped) { speed ->
                                    runOnUiThread {
                                        adapter.addSign(sign)
                                        recyclerView.scrollToPosition(0)
                                        speak(signRepository.buildTtsText(sign, speed))
                                    }
                                }
                            } else {
                                runOnUiThread {
                                    adapter.addSign(sign)
                                    recyclerView.scrollToPosition(0)
                                    speak(signRepository.buildTtsText(sign, null))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Inference error", e)
                    } finally {
                        isInferencing.set(false)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processFrame error", e)
        } finally {
            proxy.close()
        }
    }

    private fun drawToSurface(bitmap: Bitmap, dets: List<Detection>, frameW: Int, frameH: Int) {
        if (!surfaceReady) return
        val canvas = surfaceHolder.lockCanvas() ?: return
        try {
            val sw = canvas.width.toFloat()
            val sh = canvas.height.toFloat()

            // Scale bitmap vào surface giữ tỉ lệ FIT_CENTER
            val scale = minOf(sw / bitmap.width, sh / bitmap.height)
            val dx = (sw - bitmap.width * scale) / 2f
            val dy = (sh - bitmap.height * scale) / 2f

            val dst = RectF(dx, dy, dx + bitmap.width * scale, dy + bitmap.height * scale)
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bitmap, null, dst, bitmapPaint)

            // Vẽ box từ inference lần trước
            val scaleX = bitmap.width.toFloat() / frameW.coerceAtLeast(1)
            val scaleY = bitmap.height.toFloat() / frameH.coerceAtLeast(1)

            for (det in dets) {
                val rect = RectF(
                    (det.x1 * scaleX) * scale + dx,
                    (det.y1 * scaleY) * scale + dy,
                    (det.x2 * scaleX) * scale + dx,
                    (det.y2 * scaleY) * scale + dy
                )
                canvas.drawRect(rect, boxPaint)
                val label = "${det.signId} ${(det.confidence * 100).toInt()}%"
                val tw = textPaint.measureText(label)
                val th = textPaint.textSize
                val textTop = (rect.top - th - 4f).coerceAtLeast(dy)
                canvas.drawRect(rect.left, textTop, rect.left + tw + 8f, textTop + th + 8f, textBgPaint)
                canvas.drawText(label, rect.left + 4f, textTop + th, textPaint)
            }
        } finally {
            surfaceHolder.unlockCanvasAndPost(canvas)
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
