package com.ducnguyen.trafficsign.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class SpeedOcrHelper {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    companion object {
        val OCR_SIGN_IDS = setOf("DP.134", "P.127", "P.127A", "R.E.10d", "R.E.9d")
        val VALID_SPEEDS = setOf(5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120)
    }

    fun recognizeSpeed(bitmap: Bitmap, onResult: (Int?) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val numbers = result.text.replace(Regex("[^0-9]"), "").trim()
                val speed = numbers.toIntOrNull()
                // Validate số hợp lệ
                onResult(if (speed != null && speed in VALID_SPEEDS) speed else null)
            }
            .addOnFailureListener { onResult(null) }
    }

    fun close() { recognizer.close() }
}
