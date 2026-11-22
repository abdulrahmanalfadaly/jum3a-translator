package com.jum3a.livetranslate.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

object AudioUtil {
    class Normalized(val pcm: ShortArray, val gainDb: Float)

    /**
     * Brings each utterance to a consistent loudness (about -20 dBFS for the speech parts),
     * boosting quiet/soft speakers by up to [maxBoostDb]. A soft limiter keeps loud bursts
     * from clipping instead of hard-distorting them.
     */
    fun normalize(samples: FloatArray, speechRms: Float, maxBoostDb: Float): Normalized {
        val target = 0.1f
        val maxGain = max(1f, 10f.pow(maxBoostDb / 20f))
        val gain = (target / max(speechRms, 1e-6f)).coerceIn(0.25f, maxGain)
        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val v = softClip(samples[i] * gain)
            out[i] = (v * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return Normalized(out, 20f * log10(gain))
    }

    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= 0.6f) return x
        val y = 0.6f + 0.4f * tanh((a - 0.6f) / 0.4f)
        return if (x < 0f) -y else y
    }

    /** 16-bit mono PCM WAV file in memory. */
    fun wav(pcm: ShortArray, sampleRate: Int): ByteArray {
        val dataLen = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataLen)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)              // PCM header size
        buf.putShort(1)             // PCM format
        buf.putShort(1)             // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)  // byte rate
        buf.putShort(2)             // block align
        buf.putShort(16)            // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataLen)
        for (s in pcm) buf.putShort(s)
        return buf.array()
    }
}
