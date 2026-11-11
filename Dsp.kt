package com.example.dualaudio

import androidx.compose.runtime.*
import kotlin.math.*

enum class Role(val label: String) {
    FULL("Full range"),
    SUB("Sub (low-pass)"),
    SATELLITE("Main (high-pass)"),
    LEFT("Left only"),
    RIGHT("Right only"),
    MONO("Mono")
}

val BAND_FREQS = floatArrayOf(60f, 230f, 910f, 3600f, 14000f)

enum class FType { LOW, HIGH, PEAK, LSHELF, HSHELF }

/** RBJ cookbook biquad with independent L/R state. */
class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0
    private val z1 = DoubleArray(2); private val z2 = DoubleArray(2)

    fun set(type: FType, fs: Double, f0: Double, q: Double, gainDb: Double = 0.0) {
        val w0 = 2 * PI * min(f0, fs * 0.45) / fs
        val cw = cos(w0); val sw = sin(w0)
        val alpha = sw / (2 * q)
        val a = 10.0.pow(gainDb / 40)
        val s = 2 * sqrt(a) * alpha
        val nb0: Double; val nb1: Double; val nb2: Double
        val na0: Double; val na1: Double; val na2: Double
        when (type) {
            FType.LOW -> { nb0 = (1 - cw) / 2; nb1 = 1 - cw; nb2 = (1 - cw) / 2; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha }
            FType.HIGH -> { nb0 = (1 + cw) / 2; nb1 = -(1 + cw); nb2 = (1 + cw) / 2; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha }
            FType.PEAK -> { nb0 = 1 + alpha * a; nb1 = -2 * cw; nb2 = 1 - alpha * a; na0 = 1 + alpha / a; na1 = -2 * cw; na2 = 1 - alpha / a }
            FType.LSHELF -> {
                nb0 = a * ((a + 1) - (a - 1) * cw + s); nb1 = 2 * a * ((a - 1) - (a + 1) * cw); nb2 = a * ((a + 1) - (a - 1) * cw - s)
                na0 = (a + 1) + (a - 1) * cw + s; na1 = -2 * ((a - 1) + (a + 1) * cw); na2 = (a + 1) + (a - 1) * cw - s
            }
            FType.HSHELF -> {
                nb0 = a * ((a + 1) + (a - 1) * cw + s); nb1 = -2 * a * ((a - 1) + (a + 1) * cw); nb2 = a * ((a + 1) + (a - 1) * cw - s)
                na0 = (a + 1) - (a - 1) * cw + s; na1 = 2 * ((a - 1) - (a + 1) * cw); na2 = (a + 1) - (a - 1) * cw - s
            }
        }
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0
    }

    fun process(x: Float, ch: Int): Float {
        val y = b0 * x + z1[ch]
        z1[ch] = b1 * x - a1 * y + z2[ch]
        z2[ch] = b2 * x - a2 * y
        return y.toFloat()
    }
}

/** Everything applied to ONE output device: role/crossover -> 5-band EQ -> volume -> delay. */
class DeviceChain {
    // UI-facing state (read by the audio thread too)
    var role by mutableStateOf(Role.FULL)
    var volume by mutableFloatStateOf(1f)
    var delayMs by mutableFloatStateOf(0f)
    val gains = mutableStateListOf(0f, 0f, 0f, 0f, 0f)

    private var sampleRate = 44100
    private val eq = Array(5) { Biquad() }
    private val xo = Array(2) { Biquad() }   // 2 cascaded Butterworth = Linkwitz-Riley 4th order
    private val cGains = FloatArray(5) { Float.NaN }
    private var cRole: Role? = null
    private var cXover = Float.NaN
    private var delayFrames = 0
    private var line = FloatArray(2)
    private var lineFrames = 1
    private var w = 0

    fun prepare(sr: Int) {
        sampleRate = sr
        lineFrames = sr / 2 + 1                 // up to 500 ms
        line = FloatArray(lineFrames * 2); w = 0
        cGains.fill(Float.NaN); cRole = null; cXover = Float.NaN
    }

    private fun refresh(xover: Float) {
        val fs = sampleRate.toDouble()
        val r = role
        if (xover != cXover || r != cRole) {
            cXover = xover; cRole = r
            val t = if (r == Role.SATELLITE) FType.HIGH else FType.LOW
            xo.forEach { it.set(t, fs, xover.toDouble(), 0.7071) }
        }
        for (b in 0 until 5) {
            val g = gains[b]
            if (g != cGains[b]) {
                cGains[b] = g
                val type = when (b) { 0 -> FType.LSHELF; 4 -> FType.HSHELF; else -> FType.PEAK }
                val q = if (b == 0 || b == 4) 0.7071 else 1.0
                eq[b].set(type, fs, BAND_FREQS[b].toDouble(), q, g.toDouble())
            }
        }
        delayFrames = (delayMs * sampleRate / 1000f).toInt().coerceIn(0, lineFrames - 1)
    }

    /** buf = interleaved stereo float, processed in place. */
    fun process(buf: FloatArray, frames: Int, xover: Float) {
        refresh(xover)
        val r = role; val vol = volume
        for (i in 0 until frames) {
            var l = buf[2 * i]; var rr = buf[2 * i + 1]
            when (r) {
                Role.LEFT -> rr = l
                Role.RIGHT -> l = rr
                Role.MONO, Role.SUB -> { val m = (l + rr) * 0.5f; l = m; rr = m }
                else -> {}
            }
            if (r == Role.SUB || r == Role.SATELLITE) {
                l = xo[1].process(xo[0].process(l, 0), 0)
                rr = xo[1].process(xo[0].process(rr, 1), 1)
            }
            for (e in eq) { l = e.process(l, 0); rr = e.process(rr, 1) }
            l *= vol; rr *= vol
            line[2 * w] = l; line[2 * w + 1] = rr
            val ri = (w - delayFrames + lineFrames) % lineFrames
            l = line[2 * ri]; rr = line[2 * ri + 1]
            w = (w + 1) % lineFrames
            buf[2 * i] = l.coerceIn(-1f, 1f); buf[2 * i + 1] = rr.coerceIn(-1f, 1f)
        }
    }
}
