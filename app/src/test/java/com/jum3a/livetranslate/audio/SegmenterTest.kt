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

class SegmenterTest {
    private val rate = 16_000
    private val auto = SegmenterParams(null, 6000, 15000)
    private val manual = SegmenterParams(0.65f, 6000, 15000)

    private fun run(audio: ShortArray, params: SegmenterParams, stretch: Float = 1f): List<Segment> {
        val out = mutableListOf<Segment>()
        val seg = Segmenter(rate, params) { out += it }
        seg.stretch = stretch
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
        val audio = Signals.speechScene(rate, listOf(2.0 to 0.0, 8.0 to 0.08, 1.0 to 0.0, 7.0 to 0.08, 2.0 to 0.0), noise = 0.002)
        for (params in listOf(auto, manual)) {
            val segs = run(audio, params)
            val mode = if (params.sensitivity == null) "auto" else "manual"
            assertEquals("$mode: segments", 2, segs.size)
            assertTrue("$mode: first ~8 s, was ${segs[0].durationMs}", segs[0].durationMs in 7_500..9_500)
            assertTrue("$mode: second ~7 s, was ${segs[1].durationMs}", segs[1].durationMs in 6_500..8_500)
        }
    }

    @Test
    fun `continuous speech without pauses is cut before the max length`() {
        val audio = Signals.speechScene(rate, listOf(1.0 to 0.0, 50.0 to 0.08, 1.0 to 0.0), noise = 0.002)
        for (params in listOf(auto, manual)) {
            val segs = run(audio, params)
            assertTrue("expected several chunks, got ${segs.size}", segs.size >= 3)
            segs.forEach { assertTrue("chunk too long: ${it.durationMs}", it.durationMs <= 15_500) }
            val total = segs.sumOf { it.durationMs }
            assertTrue("audio lost: $total ms of ~50 s", total >= 48_000)
        }
    }

    @Test
    fun `stretch makes chunks longer when recognition falls behind`() {
        val audio = Signals.speechScene(rate, listOf(1.0 to 0.0, 50.0 to 0.08, 1.0 to 0.0), noise = 0.002)
        val normal = run(audio, auto)
        val stretched = run(audio, auto, stretch = 1.5f)
        assertTrue("fewer requests when stretched: ${stretched.size} vs ${normal.size}", stretched.size < normal.size)
        stretched.forEach { assertTrue("chunk too long: ${it.durationMs}", it.durationMs <= 23_000) }
        assertTrue(stretched.maxOf { it.durationMs } > 15_500)
    }

    @Test
    fun `soft far-away voice is still detected`() {
        // Speech only ~10 dB above the room noise and about -45 dBFS overall.
        val audio = Signals.speechScene(rate, listOf(3.0 to 0.0, 8.0 to 0.007, 3.0 to 0.0), noise = 0.0015)
        for (params in listOf(auto, manual)) {
            val segs = run(audio, params)
            assertEquals(1, segs.size)
            assertTrue("speech ${segs[0].speechMs} ms", segs[0].speechMs >= 4_000)
            assertTrue("length ${segs[0].durationMs} ms", segs[0].durationMs >= 7_500)
        }
    }

    @Test
    fun `auto mode hears a faint voice in a noisy room`() {
        // Loud fans/crowd (-40 dBFS) and a voice only ~7 dB above them.
        val audio = Signals.speechScene(rate, listOf(4.0 to 0.0, 10.0 to 0.03, 4.0 to 0.0), noise = 0.016)
        val segs = run(audio, auto)
        assertEquals(1, segs.size)
        assertTrue("speech ${segs[0].speechMs} ms", segs[0].speechMs >= 3_000)
        assertTrue("length ${segs[0].durationMs} ms", segs[0].durationMs >= 9_000)
    }

    @Test
    fun `auto mode learns the speaker's level`() {
        val audio = Signals.speechScene(rate, listOf(2.0 to 0.0, 10.0 to 0.05, 2.0 to 0.0), noise = 0.002)
        var last: FrameStats? = null
        val seg = Segmenter(rate, auto) {}
        var i = 0
        while (i + seg.frameSize <= audio.size) {
            last = seg.process(audio.copyOfRange(i, i + seg.frameSize), seg.frameSize)
            i += seg.frameSize
        }
        val voice = last!!.voiceDb
        assertTrue("voice level should be known", voice != null)
        assertTrue("voice ${voice!!} should be well above noise ${last.noiseDb}", voice - last.noiseDb > 15f)
    }

    @Test
    fun `background noise alone produces nothing`() {
        val audio = Signals.speechScene(rate, listOf(20.0 to 0.0), noise = 0.003)
        assertTrue(run(audio, auto).isEmpty())
        assertTrue(run(audio, manual).isEmpty())
    }

    @Test
    fun `quiet segment is boosted without clipping`() {
        val audio = Signals.speechScene(rate, listOf(1.0 to 0.0, 7.0 to 0.006, 2.0 to 0.0), noise = 0.0005)
        val seg = run(audio, auto).single()
        val norm = AudioUtil.normalize(seg.samples, seg.speechRms, 40f)
        assertTrue("gain ${norm.gainDb}", norm.gainDb > 15f)
        val peak = norm.pcm.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak < 32767)
        val wav = AudioUtil.wav(norm.pcm, rate)
        assertEquals(44 + norm.pcm.size * 2, wav.size)
    }

    @Test
    fun `audio recorded at 48 kHz segments the same after conversion`() {
        val audio48 = Signals.speechScene(48_000, listOf(2.0 to 0.0, 8.0 to 0.08, 1.0 to 0.0, 7.0 to 0.08, 2.0 to 0.0), noise = 0.002)
        val resampler = Resampler(48_000, rate)
        val chunk = 48_000 * 30 / 1000
        val converted = ArrayList<Short>()
        var i = 0
        while (i + chunk <= audio48.size) {
            converted += resampler.process(audio48.copyOfRange(i, i + chunk)).toList()
            i += chunk
        }
        val segs = run(converted.toShortArray(), auto)
        assertEquals(2, segs.size)
        assertTrue("first ~8 s, was ${segs[0].durationMs}", segs[0].durationMs in 7_500..9_500)
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

/** Synthetic test audio: room noise plus "speech" (a voiced tone with a 4 Hz syllable rhythm). */
object Signals {
    fun speechScene(rate: Int, parts: List<Pair<Double, Double>>, noise: Double, seed: Int = 7): ShortArray {
        val rnd = kotlin.random.Random(seed)
        val total = parts.sumOf { (it.first * rate).toInt() }
        val out = ShortArray(total)
        var pos = 0
        for ((seconds, speech) in parts) {
            val n = (seconds * rate).toInt()
            for (k in 0 until n) {
                val t = (pos + k).toDouble() / rate
                val syllable = 0.5 + 0.5 * sin(2 * PI * 4 * t)
                val voice = speech * syllable * (sin(2 * PI * 180 * t) + 0.5 * sin(2 * PI * 360 * t))
                val v = voice + noise * (rnd.nextDouble() * 2 - 1)
                out[pos + k] = (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
            }
            pos += n
        }
        return out
    }

    fun sine(rate: Int, hz: Double, seconds: Double, amplitude: Double): ShortArray =
        ShortArray((seconds * rate).toInt()) { i -> (amplitude * 32767 * sin(2 * PI * hz * i / rate)).toInt().toShort() }
}
