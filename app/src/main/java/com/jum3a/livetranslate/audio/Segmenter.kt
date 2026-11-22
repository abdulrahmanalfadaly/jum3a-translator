package com.jum3a.livetranslate.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class Segment(
    val samples: FloatArray,
    val sampleRate: Int,
    /** Offset of the first sample from when recording started. */
    val startOffsetMs: Long,
    val speechMs: Int,
    /** RMS level of the frames classified as speech; used to normalise loudness. */
    val speechRms: Float,
) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate
}

class SegmenterParams(
    /** null = automatic (adapts to the room and the speaker); otherwise 0..1, higher = quieter sounds count as speech. */
    val sensitivity: Float?,
    val minSegMs: Int,
    val maxSegMs: Int,
)

class FrameStats(
    val db: Float,
    val thresholdDb: Float,
    val speaking: Boolean,
    val capturingMs: Int,
    /** Background level the detector has learned, dBFS. */
    val noiseDb: Float,
    /** The speaker's typical level, dBFS, or null until enough speech has been heard. */
    val voiceDb: Float?,
)

/**
 * Splits the continuous microphone stream into utterances of a few seconds each.
 *
 * - An adaptive noise floor follows the room's background level (fans, crowd, loudspeaker hiss),
 *   so speech is detected relative to the room rather than at a fixed volume. This is what lets
 *   a soft-spoken lecturer still be picked up.
 * - In automatic mode it also learns the speaker's level and puts the speech threshold between
 *   the two: strict when the voice is far above the room (fewer false triggers), lenient when
 *   the voice is faint or the room is noisy.
 * - Segments end at a natural pause once they are at least [SegmenterParams.minSegMs] long.
 *   The pause needed shrinks as the segment grows, and at [SegmenterParams.maxSegMs] we cut at
 *   the quietest moment of the last few seconds so words are not chopped in half.
 */
class Segmenter(
    private val sampleRate: Int,
    private val params: SegmenterParams,
    private val onSegment: (Segment) -> Unit,
) {
    val frameSize = sampleRate * FRAME_MS / 1000

    /**
     * Multiplies the chunk lengths. The engine raises it when recognition falls behind, so fewer,
     * longer requests are sent until it catches up.
     */
    @Volatile var stretch: Float = 1f

    private val minSegMs: Int get() = (params.minSegMs * stretch).toInt()
    private val maxSegMs: Int get() = (params.maxSegMs * stretch).toInt()

    private class Frame(val data: FloatArray, val rms: Float, val speech: Boolean)

    // 90 Hz high-pass removes rumble, hum and handling noise that carry no speech.
    private val highPass = HighPass(sampleRate, 90f)

    private val sens: Float? = params.sensitivity?.coerceIn(0f, 1f)
    private val ratio = lerp(4.0f, 1.6f, sens ?: 0.65f)        // manual: how far above the noise floor speech must be
    private val absMin = if (sens == null) AUTO_ABS_MIN else lerp(0.0025f, 0.0002f, sens) // never treat near-silence as speech
    private var noiseFloor = -1f
    private var voiceDb = Float.NaN
    private var voiceFrames = 0

    private val preRoll = ArrayDeque<Frame>()
    private val seg = ArrayList<Frame>()
    private var inSegment = false
    private var speechStreak = 0
    private var silenceRun = 0
    private var framesSeen = 0L
    private var segStartFrame = 0L

    fun process(pcm: ShortArray, count: Int): FrameStats {
        val data = FloatArray(count)
        var sumSq = 0.0
        for (i in 0 until count) {
            val y = highPass.next(pcm[i] / 32768f)
            data[i] = y
            sumSq += (y * y).toDouble()
        }
        val rms = if (count > 0) sqrt(sumSq / count).toFloat() else 0f

        val floor = if (noiseFloor < 0f) rms else noiseFloor
        val threshold = if (sens == null) autoThreshold(floor) else max(floor * ratio, absMin)
        val speech = noiseFloor >= 0f && rms > threshold
        if (speech && sens == null) {
            // Learn the speaker's level from speech frames (~1 s of speech to adapt).
            voiceDb += (toDb(rms) - voiceDb) * VOICE_ADAPT
            voiceFrames++
        }
        updateNoiseFloor(rms)

        handle(Frame(data, rms, speech))
        framesSeen++
        return FrameStats(
            db = toDb(rms),
            thresholdDb = toDb(threshold),
            speaking = speech,
            capturingMs = if (inSegment) seg.size * FRAME_MS else 0,
            noiseDb = toDb(floor),
            voiceDb = if (voiceFrames >= VOICE_KNOWN_FRAMES) voiceDb else null,
        )
    }

    /** Threshold between the room and the voice: 40% of the way up, at least 4 dB and at most 12 dB above the room. */
    private fun autoThreshold(floor: Float): Float {
        val floorDb = toDb(floor)
        if (voiceDb.isNaN()) voiceDb = floorDb + 15f // assume a fairly clear voice until we've heard one
        val margin = (0.4f * (voiceDb - floorDb)).coerceIn(4f, 12f)
        return max(fromDb(floorDb + margin), absMin)
    }

    /** Emit whatever speech is buffered (called when the user presses Stop). */
    fun flush() {
        if (inSegment) emit(seg.size)
        inSegment = false
        seg.clear()
        preRoll.clear()
    }

    private fun updateNoiseFloor(rms: Float) {
        noiseFloor = when {
            noiseFloor < 0f -> max(rms, 1e-5f)
            rms < noiseFloor -> noiseFloor + (rms - noiseFloor) * 0.2f    // drop fast in pauses
            else -> noiseFloor + (rms - noiseFloor) * 0.0015f             // rise slowly (~20 s)
        }.coerceAtLeast(1e-5f)
    }

    private fun handle(f: Frame) {
        if (!inSegment) {
            preRoll.addLast(f)
            if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
            speechStreak = if (f.speech) speechStreak + 1 else 0
            if (speechStreak >= START_FRAMES) {
                inSegment = true
                seg.clear()
                seg.addAll(preRoll)
                preRoll.clear()
                segStartFrame = framesSeen - seg.size + 1
                silenceRun = 0
            }
            return
        }

        seg.add(f)
        silenceRun = if (f.speech) 0 else silenceRun + 1
        val durMs = seg.size * FRAME_MS
        val silenceMs = silenceRun * FRAME_MS

        val minMs = minSegMs
        val maxMs = maxSegMs
        if (durMs >= minMs) {
            val span = max(1, maxMs - minMs)
            val progress = ((durMs - minMs).toFloat() / span).coerceIn(0f, 1f)
            val neededPause = lerp(600f, 210f, progress)
            when {
                silenceMs >= neededPause -> emit(seg.size)
                durMs >= maxMs -> emit(bestCutIndex())
            }
        } else if (silenceMs >= LONG_PAUSE_MS) {
            // A short phrase followed by a long pause: send it now instead of waiting.
            emit(seg.size)
        }
    }

    /** Quietest point in the last few seconds: the least likely place to split a word. */
    private fun bestCutIndex(): Int {
        val lo = max(max(1, minSegMs / FRAME_MS / 2), seg.size - SEARCH_BACK_FRAMES)
        val hi = seg.size - 3
        if (lo >= hi) return seg.size
        var best = seg.size
        var bestEnergy = Float.MAX_VALUE
        for (i in lo until hi) {
            val e = seg[i - 1].rms + seg[i].rms + seg[i + 1].rms
            if (e < bestEnergy) {
                bestEnergy = e
                best = i
            }
        }
        return best
    }

    private fun emit(cutIndex: Int) {
        val cut = cutIndex.coerceIn(0, seg.size)
        val head = ArrayList(seg.subList(0, cut))
        val tail = ArrayList(seg.subList(cut, seg.size))
        val headStartFrame = segStartFrame

        if (tail.any { it.speech }) {
            // Speech continues: the remainder becomes the start of the next segment.
            seg.clear()
            seg.addAll(tail)
            segStartFrame = headStartFrame + cut
            silenceRun = tail.takeLastWhile { !it.speech }.size
        } else {
            seg.clear()
            inSegment = false
            speechStreak = 0
            silenceRun = 0
            preRoll.clear()
            tail.takeLast(PRE_ROLL_FRAMES).forEach { preRoll.addLast(it) }
        }

        // Trim trailing silence but keep a short tail so the last word isn't clipped.
        var end = head.size
        while (end > 0 && !head[end - 1].speech) end--
        end = min(head.size, end + TRAIL_KEEP_FRAMES)
        if (end == 0) return
        val used = head.subList(0, end)
        val speechFrames = used.count { it.speech }
        if (speechFrames * FRAME_MS < MIN_SPEECH_MS) return // a cough, a click, a chair

        val out = FloatArray(used.sumOf { it.data.size })
        var pos = 0
        var speechEnergy = 0.0
        for (fr in used) {
            fr.data.copyInto(out, pos)
            pos += fr.data.size
            if (fr.speech) speechEnergy += (fr.rms * fr.rms).toDouble()
        }
        val speechRms = sqrt(speechEnergy / max(1, speechFrames)).toFloat()
        onSegment(Segment(out, sampleRate, headStartFrame * FRAME_MS, speechFrames * FRAME_MS, speechRms))
    }

    companion object {
        const val FRAME_MS = 30
        private const val PRE_ROLL_FRAMES = 10       // 300 ms kept before speech starts
        private const val START_FRAMES = 2           // 60 ms of speech needed to open a segment
        private const val TRAIL_KEEP_FRAMES = 8      // 240 ms kept after the last word
        private const val LONG_PAUSE_MS = 1500
        private const val MIN_SPEECH_MS = 450
        private const val SEARCH_BACK_FRAMES = 100   // look back 3 s for a cut point
        private const val AUTO_ABS_MIN = 0.0002f     // -74 dBFS: below this it's never speech
        private const val VOICE_ADAPT = 0.03f
        private const val VOICE_KNOWN_FRAMES = 30    // ~1 s of speech before we report the voice level

        fun toDb(x: Float): Float = 20f * log10(max(x, 1e-6f))
        fun fromDb(db: Float): Float = 10f.pow(db / 20f)
        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }
}
