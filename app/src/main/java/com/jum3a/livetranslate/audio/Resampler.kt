package com.jum3a.livetranslate.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Streaming sample-rate converter for speech (e.g. 48 kHz or 44.1 kHz → 16 kHz).
 *
 * Not every phone can record at 16 kHz with every microphone mode (the raw mode in particular is
 * often 48 kHz only), so we record at whatever rate the phone accepts and convert here.
 * A windowed-sinc low-pass at 7 kHz removes everything Whisper can't use before the rate drops,
 * then linear interpolation picks the output samples. State carries across calls, so chunks
 * can be fed one at a time with no clicks at the boundaries.
 */
class Resampler(private val inRate: Int, private val outRate: Int) {
    val isPassthrough = inRate == outRate
    private val step = inRate.toDouble() / outRate
    private val taps: FloatArray = if (isPassthrough) floatArrayOf(1f) else lowPass(inRate, CUTOFF_HZ)
    private var history = FloatArray(taps.size - 1)
    private var prev = 0f
    private var pos = 1.0 // position of the next output sample; 0 = [prev], 1 = first sample of the new chunk

    fun process(input: ShortArray, count: Int = input.size): ShortArray {
        if (isPassthrough) return input.copyOf(count)

        // 1) Low-pass filter, continuing from the previous chunk's history.
        val h = history.size
        val buf = FloatArray(h + count)
        history.copyInto(buf)
        for (i in 0 until count) buf[h + i] = input[i].toFloat()
        val filtered = FloatArray(count)
        for (k in 0 until count) {
            var acc = 0f
            for (j in taps.indices) acc += taps[j] * buf[k + j]
            filtered[k] = acc
        }
        history = buf.copyOfRange(count, count + h)

        // 2) Pick output samples by linear interpolation. g = [prev, filtered...].
        val out = ShortArray(((count + 1) / step).toInt() + 2)
        var n = 0
        while (pos < count) {
            val i = pos.toInt()
            val frac = (pos - i).toFloat()
            val a = if (i == 0) prev else filtered[i - 1]
            val b = filtered[i]
            out[n++] = (a + (b - a) * frac).roundToInt().coerceIn(-32768, 32767).toShort()
            pos += step
        }
        pos -= count
        if (count > 0) prev = filtered[count - 1]
        return out.copyOf(n)
    }

    companion object {
        private const val CUTOFF_HZ = 7_000.0

        /** Blackman-windowed sinc low-pass, normalised to unity gain at DC. */
        private fun lowPass(rate: Int, cutoffHz: Double): FloatArray {
            val n = ((rate / 600) or 1).coerceAtLeast(15) // ~81 taps at 48 kHz
            val fc = cutoffHz / rate
            val mid = (n - 1) / 2.0
            val h = DoubleArray(n) { i ->
                val m = i - mid
                val sinc = if (m == 0.0) 2 * fc else sin(2 * PI * fc * m) / (PI * m)
                val w = 0.42 - 0.5 * cos(2 * PI * i / (n - 1)) + 0.08 * cos(4 * PI * i / (n - 1))
                sinc * w
            }
            val sum = h.sum()
            return FloatArray(n) { (h[it] / sum).toFloat() }
        }
    }
}

/** One-pole high-pass filter (removes rumble, hum and handling noise below ~90 Hz). */
class HighPass(sampleRate: Int, cutoffHz: Float = 90f) {
    private val alpha: Float = run {
        val rc = 1f / (2f * PI.toFloat() * cutoffHz)
        val dt = 1f / sampleRate
        rc / (rc + dt)
    }
    private var prevIn = 0f
    private var prevOut = 0f

    fun next(x: Float): Float {
        val y = alpha * (prevOut + x - prevIn)
        prevIn = x
        prevOut = y
        return y
    }
}
