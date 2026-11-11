package com.example.dualaudio

import android.content.Context

data class Preset(val name: String, val gains: List<Float>)

// Bands: 60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz (dB)
val BUILT_IN_PRESETS = listOf(
    Preset("Flat", listOf(0f, 0f, 0f, 0f, 0f)),
    Preset("Bass boost", listOf(6f, 4f, 0f, 0f, 0f)),
    Preset("Sub boost", listOf(9f, 5f, -2f, -4f, -6f)),
    Preset("Sub tight", listOf(3f, 2f, -4f, -8f, -10f)),
    Preset("Satellite clarity", listOf(-6f, -2f, 1f, 3f, 3f)),
    Preset("Vocal", listOf(-3f, -1f, 3f, 3f, 0f)),
    Preset("Treble boost", listOf(0f, 0f, 0f, 4f, 6f)),
    Preset("Loudness", listOf(5f, 2f, -1f, 2f, 5f)),
    Preset("Rock", listOf(4f, 2f, -2f, 3f, 4f)),
    Preset("Electronic", listOf(5f, 3f, 0f, 2f, 4f)),
    Preset("Acoustic", listOf(3f, 1f, 1f, 2f, 2f)),
)

class PresetStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("presets", Context.MODE_PRIVATE)

    fun load(): List<Preset> =
        (prefs.getString("custom", "") ?: "").lines().mapNotNull { line ->
            val p = line.split("|")
            if (p.size != 2) null else {
                val g = p[1].split(",").mapNotNull { it.toFloatOrNull() }
                if (g.size == 5) Preset(p[0], g) else null
            }
        }

    fun save(all: List<Preset>) {
        prefs.edit().putString("custom", all.joinToString("\n") { "${it.name.replace("|", " ")}|${it.gains.joinToString(",")}" }).apply()
    }
}
