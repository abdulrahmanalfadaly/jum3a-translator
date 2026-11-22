package com.jum3a.livetranslate.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

class ResamplerTest {
    private fun rmsDb(x: ShortArray, skip: Int = 200): Double {
        val part = x.copyOfRange(minOf(skip, x.size), x.size)
        val ms = part.sumOf { (it / 32768.0) * (it / 32768.0) } / part.size
        return 10 * log10(ms.coerceAtLeast(1e-20))
    }

    private fun zeroCrossings(x: ShortArray, skip: Int = 200): Int =
        (skip + 1 until x.size).count { (x[it - 1] < 0) != (x[it] < 0) }

    private fun convertInChunks(r: Resampler, input: ShortArray, chunk: Int): ShortArray {
        val out = ArrayList<Short>()
        var i = 0
        while (i < input.size) {
            val end = minOf(i + chunk, input.size)
            out += r.process(input.copyOfRange(i, end)).toList()
            i = end
        }
        return out.toShortArray()
    }

    @Test
    fun `16 kHz passes through untouched`() {
        val x = Signals.sine(16_000, 440.0, 0.5, 0.3)
        assertArrayEquals(x, Resampler(16_000, 16_000).process(x))
    }

    @Test
    fun `speech-band tones keep their pitch and loudness`() {
        for (inRate in listOf(48_000, 44_100, 32_000, 22_050)) {
            for (hz in listOf(300.0, 1_000.0, 3_000.0)) {
                val x = Signals.sine(inRate, hz, 1.0, 0.3)
                val y = convertInChunks(Resampler(inRate, 16_000), x, inRate * 30 / 1000)
                assertTrue("$inRate Hz: length ${y.size}", abs(y.size - 16_000) <= 2)
                val levelChange = rmsDb(y) - rmsDb(x)
                assertTrue("$inRate Hz, $hz Hz tone: level changed by $levelChange dB", kotlin.math.abs(levelChange) < 1.0)
                val expectedCrossings = 2 * hz * (y.size - 201) / 16_000.0
                val crossings = zeroCrossings(y)
                assertTrue(
                    "$inRate Hz, $hz Hz tone: $crossings zero crossings, expected ~$expectedCrossings",
                    kotlin.math.abs(crossings - expectedCrossings) <= expectedCrossings * 0.02 + 2,
                )
            }
        }
    }

    @Test
    fun `sounds above 8 kHz are removed instead of folding into speech`() {
        for (inRate in listOf(48_000, 44_100)) {
            val x = Signals.sine(inRate, 12_000.0, 1.0, 0.5)
            val y = Resampler(inRate, 16_000).process(x)
            val attenuation = rmsDb(x) - rmsDb(y)
            assertTrue("$inRate Hz: 12 kHz only attenuated by $attenuation dB", attenuation > 40)
        }
    }

    @Test
    fun `chunk boundaries don't change the result`() {
        val x = Signals.speechScene(48_000, listOf(2.0 to 0.08), noise = 0.002)
        val whole = Resampler(48_000, 16_000).process(x)
        val chunked = convertInChunks(Resampler(48_000, 16_000), x, 1_441) // odd size on purpose
        assertEquals(whole.size, chunked.size)
        var maxDiff = 0
        for (i in whole.indices) maxDiff = maxOf(maxDiff, kotlin.math.abs(whole[i] - chunked[i]))
        assertTrue("max difference $maxDiff", maxDiff <= 1)
    }

    private fun abs(x: Int) = kotlin.math.abs(x)
}
