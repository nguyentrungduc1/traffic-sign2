package com.ducnguyen.trafficsign.model

data class TrafficSign(
    val id: String,
    val code: String,
    val name: String,
    val tts_text: String,
    val requires_ocr: Boolean = false
)

data class Detection(
    val signId: String,
    val confidence: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
)
