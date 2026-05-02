package com.ducnguyen.trafficsign

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.view.WindowManager
import android.widget.ImageView
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

class CameraActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var cameraImageView: ImageView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SignListAdapter

    private lateinit var detector: YoloDetector
    private lateinit var ocrHelper: SpeedOcrHelper
    private lateinit var signRepository: SignRepository
    private lateinit var tts: TextToSpeech
    private lateinit var cameraExecutor: ExecutorService

    private val boxPaint = Paint().apply {
        color = Color.GREEN; strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val textBgPaint = Paint().apply {
        color = Color.GREEN; style = Paint.Style.FILL
    }
    private val textPaint = Paint().apply {
        color = Color.BLACK; textSize = 28f
        style = Paint.Style.FILL; typeface = Typeface.DEFAULT_BOLD
    }

    private var ttsReady = false
    private val isInferencing = AtomicBoolean(false)

    // Lưu box từ inference lần trước để vẽ lên frame mới
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

        cameraImageView = findViewById(R.id.camera_image_view)
        recyclerView    = findViewById(R.id.recycler_view)

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

            // BƯỚC 1: Vẽ box từ inference LẦN TRƯỚC lên frame hiện tại → display ngay
            val display = drawDetections(fullBitmap, lastDetections, lastFrameW, lastFrameH)
            runOnUiThread { cameraImageView.setImageBitmap(display) }

            // BƯỚC 2: Nếu inference đang rảnh → chạy inference frame này
            if (isInferencing.compareAndSet(false, true)) {
                val smallBitmap = Bitmap.createScaledBitmap(fullBitmap, detectW, detectH, true)

                cameraExecutor.execute {
                    try {
                        val dets = detector.detect(smallBitmap)

                        // Scale tọa độ về kích thước gốc
                        val scaleX = fullBitmap.width.toFloat() / detectW
                        val scaleY = fullBitmap.height.toFloat() / detectH
                        val scaledDets = dets.map { det ->
                            det.copy(
                                x1 = det.x1 * scaleX, y1 = det.y1 * scaleY,
                                x2 = det.x2 * scaleX, y2 = det.y2 * scaleY
                            )
                        }

                        // Lưu kết quả để vẽ lên frame tiếp theo
                        lastDetections = scaledDets
                        lastFrameW = fullBitmap.width
                        lastFrameH = fullBitmap.height

                        // Xử lý TTS + list
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

    private fun drawDetections(
        src: Bitmap,
        dets: List<Detection>,
        frameW: Int,
        frameH: Int
    ): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        if (dets.isEmpty()) return out
        val canvas = Canvas(out)

        // Scale tọa độ từ frameW/frameH về src.width/src.height phòng khi kích thước khác nhau
        val scaleX = src.width.toFloat() / frameW.coerceAtLeast(1)
        val scaleY = src.height.toFloat() / frameH.coerceAtLeast(1)

        for (det in dets) {
            val rect = RectF(
                det.x1 * scaleX, det.y1 * scaleY,
                det.x2 * scaleX, det.y2 * scaleY
            )
            canvas.drawRect(rect, boxPaint)
            val label = "${det.signId} ${(det.confidence * 100).toInt()}%"
            val tw = textPaint.measureText(label)
            val th = textPaint.textSize
            val textTop = (rect.top - th - 4f).coerceAtLeast(0f)
            canvas.drawRect(rect.left, textTop, rect.left + tw + 8f, textTop + th + 8f, textBgPaint)
            canvas.drawText(label, rect.left + 4f, textTop + th, textPaint)
        }
        return out
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
