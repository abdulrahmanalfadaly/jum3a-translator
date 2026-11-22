package com.jum3a.livetranslate.audio

import android.content.Context
import android.media.AudioRecord
import com.jum3a.livetranslate.settings.MicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * "Find the best microphone": records a few seconds with each microphone mode while the speaker
 * is talking and picks the one where the voice stands out most from the room.
 *
 * Phones differ a lot here: on some the raw mode is clearest, on others it is very quiet or
 * silent, and the far-field mode sometimes uses a better-placed microphone.
 */
object MicTest {
    class Result(
        val source: MicSource,
        val works: Boolean,
        /** How far the loud (speech) parts are above the quiet (room) parts. */
        val clarityDb: Float,
        /** Level of the loud parts, dBFS. */
        val levelDb: Float,
        val clipped: Boolean,
    ) {
        /** Higher is better. Distortion and very low level count against a mode. */
        val score: Float
            get() = if (!works) -100f else clarityDb - (if (clipped) 6f else 0f) - max(0f, -65f - levelDb)
    }

    /** Below this, the test probably ran during a pause rather than speech. */
    const val MIN_CLARITY_DB = 8f

    fun sourcesToTest(context: Context): List<MicSource> = buildList {
        if (AudioCapture.unprocessedSupported(context)) add(MicSource.UNPROCESSED)
        add(MicSource.VOICE_RECOGNITION)
        add(MicSource.CAMCORDER)
        add(MicSource.MIC)
    }

    suspend fun run(
        context: Context,
        deviceId: Int,
        phoneProcessing: Boolean,
        secondsEach: Float = 4f,
        onProgress: suspend (index: Int, total: Int, source: MicSource) -> Unit,
    ): List<Result> {
        val sources = sourcesToTest(context)
        val results = ArrayList<Result>()
        for ((i, src) in sources.withIndex()) {
            onProgress(i, sources.size, src)
            results += withContext(Dispatchers.IO) { measure(context, src, deviceId, phoneProcessing, secondsEach) }
        }
        return results
    }

    fun best(results: List<Result>): Result? =
        results.filter { it.works }.maxByOrNull { it.score }?.takeIf { it.clarityDb >= MIN_CLARITY_DB }

    private suspend fun measure(
        context: Context,
        src: MicSource,
        deviceId: Int,
        phoneProcessing: Boolean,
        seconds: Float,
    ): Result {
        val failed = Result(src, works = false, clarityDb = 0f, levelDb = -120f, clipped = false)
        val opened = AudioCapture.openExact(context, src, deviceId, phoneProcessing) ?: return failed
        try {
            val rec = opened.record
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) return failed
            val rate = opened.sampleRate
            val resampler = Resampler(rate, AudioCapture.SAMPLE_RATE)
            val hp = HighPass(AudioCapture.SAMPLE_RATE)
            val chunk = ShortArray(rate * FRAME_MS / 1000)
            val frameDb = ArrayList<Float>()
            var clippedSamples = 0
            var totalSamples = 0
            val frames = (seconds * 1000 / FRAME_MS).toInt() + WARMUP_FRAMES
            for (f in 0 until frames) {
                if (!currentCoroutineContext().isActive) break
                var filled = 0
                while (filled < chunk.size) {
                    val n = rec.read(chunk, filled, chunk.size - filled)
                    if (n < 0) return failed
                    filled += n
                }
                val out = resampler.process(chunk, filled)
                var sumSq = 0.0
                for (s in out) {
                    val y = hp.next(s / 32768f)
                    sumSq += (y * y).toDouble()
                }
                if (f < WARMUP_FRAMES) continue // microphones often start with a click or ramp
                for (i in 0 until filled) if (abs(chunk[i].toInt()) >= CLIP_LEVEL) clippedSamples++
                totalSamples += filled
                frameDb += Segmenter.toDb(if (out.isEmpty()) 0f else sqrt(sumSq / out.size).toFloat())
            }
            if (frameDb.size < 10) return failed
            frameDb.sort()
            val quiet = frameDb[(frameDb.size * 0.10).toInt()]
            val loud = frameDb[(frameDb.size * 0.95).toInt().coerceAtMost(frameDb.size - 1)]
            return Result(
                source = src,
                works = loud > -90f, // pure digital silence means this mode doesn't really work here
                clarityDb = loud - quiet,
                levelDb = loud,
                clipped = clippedSamples > totalSamples / 1000,
            )
        } catch (_: Exception) {
            return failed
        } finally {
            opened.release()
        }
    }

    private const val FRAME_MS = 30
    private const val WARMUP_FRAMES = 10
    private const val CLIP_LEVEL = 32_000
}
