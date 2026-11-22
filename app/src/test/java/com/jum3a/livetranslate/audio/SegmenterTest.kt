package com.jum3a.livetranslate.audio

import com.jum3a.livetranslate.ai.AsrFilter
import com.jum3a.livetranslate.ai.AsrResult
import com.jum3a.livetranslate.ai.AsrSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class SegmenterTest {
    private val rate = 16_000
    private val rnd = Random(7)

    /** Room noise plus optional "speech": a voiced tone with a 4 Hz syllable rhythm. */
    private fun signal(seconds: Double, noise: Double, speech: Double): ShortArray {
        val n = (seconds * rate).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / rate
            val syllable = 0.5 + 0.5 * sin(2 * PI * 4 * t)
            val voice = speech * syllable * (sin(2 * PI * 180 * t) + 0.5 * sin(2 * PI * 360 * t))
            val v = voice + noise * (rnd.nextDouble() * 2 - 1)
            (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun run(audio: ShortArray, params: SegmenterParams = SegmenterParams(0.65f, 6000, 15000)): List<Segment> {
        val out = mutableListOf<Segment>()
        val seg = Segmenter(rate, params) { out += it }
        var i = 0
        while (i + seg.frameSize <= audio.size) {
            seg.process(audio.copyOfRange(i, i + seg.frameSize), seg.frameSize)
            i += seg.frameSize
        }
        seg.flush()
        return out
    }

    @Test
    fun `two sentences separated by a pause become two segments`() {
        val audio = signal(2.0, 0.002, 0.0) + signal(8.0, 0.002, 0.08) + signal(1.0, 0.002, 0.0) +
            signal(7.0, 0.002, 0.08) + signal(2.0, 0.002, 0.0)
        val segs = run(audio)
        assertEquals(2, segs.size)
        assertTrue("first ~8 s, was ${segs[0].durationMs}", segs[0].durationMs in 7_500..9_500)
        assertTrue("second ~7 s, was ${segs[1].durationMs}", segs[1].durationMs in 6_500..8_500)
    }

    @Test
    fun `continuous speech without pauses is cut before the max length`() {
        val audio = signal(1.0, 0.002, 0.0) + signal(50.0, 0.002, 0.08) + signal(1.0, 0.002, 0.0)
        val segs = run(audio)
        assertTrue("expected several chunks, got ${segs.size}", segs.size >= 3)
        segs.forEach { assertTrue("chunk too long: ${it.durationMs}", it.durationMs <= 15_500) }
        val total = segs.sumOf { it.durationMs }
        assertTrue("audio lost: $total ms of ~50 s", total >= 48_000)
    }

    @Test
    fun `soft far-away voice is still detected`() {
        // Speech only ~10 dB above the room noise and about -45 dBFS overall.
        val audio = signal(3.0, 0.0015, 0.0) + signal(8.0, 0.0015, 0.007) + signal(3.0, 0.0015, 0.0)
        val segs = run(audio)
        assertEquals(1, segs.size)
        assertTrue("speech ${segs[0].speechMs} ms", segs[0].speechMs >= 4_000)
        assertTrue("length ${segs[0].durationMs} ms", segs[0].durationMs >= 7_500)
    }

    @Test
    fun `background noise alone produces nothing`() {
        assertTrue(run(signal(20.0, 0.003, 0.0)).isEmpty())
    }

    @Test
    fun `quiet segment is boosted without clipping`() {
        val audio = signal(1.0, 0.0005, 0.0) + signal(7.0, 0.0005, 0.006) + signal(2.0, 0.0005, 0.0)
        val seg = run(audio).single()
        val norm = AudioUtil.normalize(seg.samples, seg.speechRms, 30f)
        assertTrue("gain ${norm.gainDb}", norm.gainDb > 15f)
        val peak = norm.pcm.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak < 32767)
        val wav = AudioUtil.wav(norm.pcm, rate)
        assertEquals(44 + norm.pcm.size * 2, wav.size)
    }

    @Test
    fun `whisper silence hallucinations are dropped`() {
        fun r(vararg texts: String) = AsrResult(texts.joinToString(" "), texts.map { AsrSegment(it, 0.1, -0.3, 1.2) }, "ms")
        assertEquals("", AsrFilter.clean(r("Terima kasih kerana menonton."), null))
        assertEquals("", AsrFilter.clean(r(" Terima kasih."), null))
        assertEquals(
            "Marilah kita bertakwa kepada Allah.",
            AsrFilter.clean(r("Marilah kita bertakwa kepada Allah.", "Sila langgan!"), null),
        )
        val noisy = AsrResult("x", listOf(AsrSegment("Bla bla", 0.9, -1.4, 1.0)), "ms")
        assertEquals("", AsrFilter.clean(noisy, null))
    }
}
