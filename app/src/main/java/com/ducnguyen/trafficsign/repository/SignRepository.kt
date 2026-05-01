package com.ducnguyen.trafficsign.repository

import android.content.Context
import com.ducnguyen.trafficsign.model.TrafficSign
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SignRepository(context: Context) {

    private val signsMap: Map<String, TrafficSign>
    private val seenSigns = mutableMapOf<String, Long>()
    private val dedupeWindowMs = 5000L

    init {
        val json = context.assets.open("signs.json")
            .bufferedReader(Charsets.UTF_8).readText()
        val type = object : TypeToken<List<TrafficSign>>() {}.type
        val list: List<TrafficSign> = Gson().fromJson(json, type) ?: emptyList()
        signsMap = list.map { sign ->
            sign.copy(requires_ocr = sign.requires_ocr || sign.tts_text.contains("{speed}"))
        }.associateBy { it.id }
    }

    fun getSign(id: String): TrafficSign? = signsMap[id]

    fun buildTtsText(sign: TrafficSign, speed: Int?): String {
        return if (sign.requires_ocr) {
            if (speed != null) sign.tts_text.replace("{speed}", speed.toString())
            else "Chú ý, ${sign.name}"
        } else {
            sign.tts_text
        }
    }

    fun shouldAnnounce(id: String): Boolean {
        val now = System.currentTimeMillis()
        val last = seenSigns[id] ?: 0L
        return if (now - last > dedupeWindowMs) {
            seenSigns[id] = now; true
        } else false
    }
}
