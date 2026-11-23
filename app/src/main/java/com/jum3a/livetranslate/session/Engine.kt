package com.jum3a.livetranslate.session

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.jum3a.livetranslate.ai.AsrFilter
import com.jum3a.livetranslate.ai.AsrResult
import com.jum3a.livetranslate.ai.GroqClient
import com.jum3a.livetranslate.ai.GroqException
import com.jum3a.livetranslate.ai.Translator
import com.jum3a.livetranslate.audio.AudioCapture
import com.jum3a.livetranslate.audio.AudioUtil
import com.jum3a.livetranslate.audio.MicConfig
import com.jum3a.livetranslate.audio.OpenedMic
import com.jum3a.livetranslate.audio.Resampler
import com.jum3a.livetranslate.audio.Segment
import com.jum3a.livetranslate.audio.Segmenter
import com.jum3a.livetranslate.audio.SegmenterParams
import com.jum3a.livetranslate.settings.AppSettings
import com.jum3a.livetranslate.settings.Defaults
import com.jum3a.livetranslate.settings.MicSource
import com.jum3a.livetranslate.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

enum class EntryStatus { QUEUED, TRANSCRIBING, TRANSLATING, DONE, FAILED }

data class Entry(
    val id: Long,
    val time: Long,
    val durationMs: Long,
    /** Malay as recognised by Whisper. */
    val asr: String? = null,
    /** Malay shown on screen: the recognised text, replaced by the interpreter's repaired version. */
    val malay: String? = null,
    val english: String? = null,
    val arabic: String? = null,
    val status: EntryStatus = EntryStatus.QUEUED,
    val error: String? = null,
) {
    val hasTranslation: Boolean get() = !english.isNullOrBlank() || !arabic.isNullOrBlank()
}

data class Meter(
    val db: Float = -90f,
    val thresholdDb: Float = -60f,
    val speaking: Boolean = false,
    val capturingMs: Int = 0,
    /** Background level the app has learned. */
    val noiseDb: Float = -90f,
    /** The speaker's typical level, once known. */
    val voiceDb: Float? = null,
)

data class EngineState(
    val running: Boolean = false,
    val micInfo: String = "",
    val lastBoostDb: Float? = null,
    val error: String? = null,
    /** Network and free-limit messages. */
    val notice: String? = null,
    /** Microphone advice: too loud, blocked, switching modes. */
    val micHint: String? = null,
    val entries: List<Entry> = emptyList(),
)

/**
 * The live pipeline:
 *   microphone → (resample to 16 kHz) → [Segmenter] → utterance WAV → Whisper (Malay text) → LLM (English) → screen.
 * Speech recognition and translation run as two queues so the next utterance can be
 * transcribed while the previous one is being translated.
 */
class Engine(
    private val app: Context,
    private val settings: SettingsStore,
    private val groq: GroqClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _meter = MutableStateFlow(Meter())
    val meter: StateFlow<Meter> = _meter.asStateFlow()

    private val translator = Translator(groq)
    private val audio = ConcurrentHashMap<Long, ByteArray>()
    private val asrQueue = Channel<Long>(Channel.UNLIMITED)
    private val mtQueue = Channel<Long>(Channel.UNLIMITED)
    private val ids = AtomicLong(0)
    private var captureJob: Job? = null

    // Speech models that hit their free limit cool down; retired ones are skipped for the session.
    private val asrCoolUntil = ConcurrentHashMap<String, Long>()
    private val asrDead: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile private var lastMalay = ""
    @Volatile private var transcriptFile: File? = null

    // Interpreter memory (only touched by the translation worker, apart from clear()).
    /** Unfinished Malay held back from the last chunk, completed with the next one. */
    @Volatile private var carry = ""
    /** The entry whose text the carry came from; a late flush is appended to it. */
    @Volatile private var carryEntryId = 0L
    /** Last entry translated in order; older ids (retries) are translated on their own. */
    @Volatile private var lastMtId = 0L
    /** Running summary of the talk: topic, points, names, terms. */
    @Volatile private var summary = ""
    private var doneSinceSummary = 0
    private var summaryJob: Job? = null

    init {
        scope.launch { asrWorker() }
        scope.launch { mtWorker() }
    }

    val isRunning: Boolean get() = captureJob?.isActive == true

    fun start(): Boolean {
        if (isRunning) return true
        val s = settings.value
        if (s.apiKey.isBlank()) {
            _state.update { it.copy(error = "Add your free Groq API key in Settings first.") }
            return false
        }
        _state.update { it.copy(running = true, error = null, notice = null, micHint = null, micInfo = "Starting microphone…") }
        captureJob = scope.launch(Dispatchers.IO) { capture(s) }
        return true
    }

    fun stop() {
        captureJob?.cancel()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun clear() {
        audio.clear()
        lastMalay = ""
        carry = ""
        summaryJob?.cancel()
        summary = ""
        doneSinceSummary = 0
        transcriptFile = null
        _state.update { it.copy(entries = emptyList(), notice = null) }
    }

    fun retry(id: Long) {
        val e = find(id) ?: return
        when {
            e.asr != null -> {
                updateEntry(id) { it.copy(status = EntryStatus.TRANSLATING, error = null) }
                mtQueue.trySend(id)
            }
            audio.containsKey(id) -> {
                updateEntry(id) { it.copy(status = EntryStatus.QUEUED, error = null) }
                asrQueue.trySend(id)
            }
            else -> removeEntry(id)
        }
    }

    fun exportText(): String = buildString {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US)
        appendLine("Jum3a Translate — ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}")
        appendLine()
        for (e in _state.value.entries) {
            if (e.malay == null && !e.hasTranslation) continue
            appendLine("[${time.format(Date(e.time))}]")
            e.english?.let { appendLine(it) }
            e.arabic?.let { appendLine(it) }
            e.malay?.let { appendLine("(Malay) $it") }
            appendLine()
        }
    }

    // ---------------------------------------------------------------- capture

    private suspend fun capture(s: AppSettings) {
        val startWall = System.currentTimeMillis()
        val segmenter = Segmenter(
            AudioCapture.SAMPLE_RATE,
            SegmenterParams(
                sensitivity = if (s.autoSensitivity) null else s.sensitivity,
                minSegMs = s.pace.minSegMs,
                maxSegMs = s.pace.maxSegMs,
            ),
        ) { seg -> onSegment(seg, startWall) }
        val config = MicConfig(s.micSource, s.testedSource, s.inputDeviceId, s.phoneProcessing)
        val am = app.getSystemService(AudioManager::class.java)

        val silentSources = mutableSetOf<MicSource>()
        var allSilent = false
        val devicesChanged = AtomicBoolean(false)
        val silenced = AtomicBoolean(false)
        val stopWatchingDevices = AudioCapture.watchDevices(app) { devicesChanged.set(true) }
        var recordingCb: AudioManager.AudioRecordingCallback? = null
        var mic: OpenedMic? = null

        try {
            val frame = ShortArray(segmenter.frameSize)
            var frameFill = 0
            var meterTick = 0
            var chunks = 0L

            while (currentCoroutineContext().isActive) {
                // Open the best mode that hasn't been silent. If every mode was silent, stay on the
                // first one and keep advising the user rather than giving up.
                val opened = try {
                    AudioCapture.open(app, config, if (allSilent) emptySet() else silentSources)
                } catch (e: IllegalStateException) {
                    if (allSilent || silentSources.isEmpty()) throw e
                    allSilent = true // the remaining modes won't open: go back to the first one
                    _state.update { it.copy(micHint = NO_SOUND_HINT) }
                    continue
                }
                mic = opened
                val rec = opened.record
                rec.startRecording()
                if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    error("The microphone is busy. Close other apps that may be recording and try again.")
                }
                silenced.set(false)
                recordingCb = watchSilencing(am, rec, silenced)
                _state.update { it.copy(micInfo = opened.description) }

                val resampler = Resampler(opened.sampleRate, AudioCapture.SAMPLE_RATE)
                val chunk = ShortArray(opened.sampleRate * Segmenter.FRAME_MS / 1000)
                var heardAnything = false
                var zeroMs = 0
                var clipChunks = 0

                reading@ while (currentCoroutineContext().isActive) {
                    var filled = 0
                    while (filled < chunk.size) {
                        val n = rec.read(chunk, filled, chunk.size - filled)
                        if (n < 0) error("Microphone stopped working (code $n).")
                        filled += n
                    }

                    // Raw-signal checks: pure digital silence (blocked/broken mode) and clipping.
                    var peak = 0
                    var clipped = 0
                    for (i in 0 until filled) {
                        val a = abs(chunk[i].toInt())
                        if (a > peak) peak = a
                        if (a >= CLIP_LEVEL) clipped++
                    }
                    if (peak > 0) heardAnything = true
                    zeroMs = if (peak == 0 && !silenced.get()) zeroMs + Segmenter.FRAME_MS else 0
                    if (clipped > filled / 100) clipChunks++

                    if (!heardAnything && !allSilent && zeroMs >= SILENT_SWITCH_MS) {
                        silentSources += opened.source
                        if (AudioCapture.candidates(app, config, silentSources).isEmpty()) allSilent = true
                        _state.update { it.copy(micHint = if (allSilent) NO_SOUND_HINT else "No sound from ${opened.source.short}, trying another mode…") }
                        break@reading
                    }

                    if (++chunks % HINT_WINDOW_CHUNKS == 0L) {
                        val hint = when {
                            silenced.get() -> SILENCED_HINT
                            !heardAnything -> NO_SOUND_HINT.takeIf { allSilent || zeroMs >= SILENT_SWITCH_MS }
                            clipChunks >= 3 -> TOO_LOUD_HINT
                            else -> null
                        }
                        clipChunks = 0
                        if (_state.value.micHint != hint) _state.update { it.copy(micHint = hint) }
                        // Recognition falling behind (slow internet, free limit): send fewer, longer chunks.
                        val backlog = _state.value.entries.count {
                            it.status == EntryStatus.QUEUED || it.status == EntryStatus.TRANSCRIBING
                        }
                        segmenter.stretch = when {
                            backlog >= 3 -> 1.5f
                            backlog <= 1 -> 1f
                            else -> segmenter.stretch
                        }
                    }

                    // Convert to 16 kHz and feed whole 30 ms frames to the segmenter.
                    for (v in resampler.process(chunk, filled)) {
                        frame[frameFill++] = v
                        if (frameFill == frame.size) {
                            frameFill = 0
                            val st = segmenter.process(frame, frame.size)
                            if (++meterTick % 2 == 0) {
                                _meter.value = Meter(st.db, st.thresholdDb, st.speaking, st.capturingMs, st.noiseDb, st.voiceDb)
                            }
                        }
                    }

                    // A USB mic was plugged in or removed: reopen on the right device.
                    if (devicesChanged.getAndSet(false) &&
                        AudioCapture.preferredDeviceId(app, config.deviceId) != opened.deviceId
                    ) {
                        break@reading
                    }
                }
                recordingCb?.let { runCatching { am.unregisterAudioRecordingCallback(it) } }
                recordingCb = null
                opened.release()
                mic = null
            }
        } catch (e: CancellationException) {
            // Normal stop.
        } catch (e: Throwable) {
            _state.update { it.copy(error = e.message ?: "Microphone error") }
        } finally {
            runCatching { segmenter.flush() }
            recordingCb?.let { runCatching { am.unregisterAudioRecordingCallback(it) } }
            stopWatchingDevices()
            mic?.release()
            _meter.value = Meter()
            _state.update { it.copy(running = false, micHint = null) }
        }
    }

    /** Android 10+ tells us when another app (a call, a recorder) has taken the microphone. */
    private fun watchSilencing(am: AudioManager, rec: AudioRecord, silenced: AtomicBoolean): AudioManager.AudioRecordingCallback? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val session = rec.audioSessionId
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                configs.firstOrNull { it.clientAudioSessionId == session }?.let { silenced.set(it.isClientSilenced) }
            }
        }
        am.registerAudioRecordingCallback(cb, Handler(Looper.getMainLooper()))
        rec.activeRecordingConfiguration?.let { silenced.set(it.isClientSilenced) }
        return cb
    }

    private fun onSegment(seg: Segment, startWall: Long) {
        val norm = AudioUtil.normalize(seg.samples, seg.speechRms, MAX_BOOST_DB)
        val wav = AudioUtil.wav(norm.pcm, seg.sampleRate)
        val id = ids.incrementAndGet()

        // If the internet is down for a long time, don't hold unlimited audio in memory.
        while (audio.size >= MAX_BACKLOG) {
            val oldest = audio.keys.minOrNull() ?: break
            audio.remove(oldest)
            updateEntry(oldest) { it.copy(status = EntryStatus.FAILED, error = "Skipped: fell too far behind (check your internet)") }
        }
        audio[id] = wav
        _state.update {
            it.copy(
                entries = it.entries + Entry(id, startWall + seg.startOffsetMs, seg.durationMs),
                lastBoostDb = norm.gainDb,
            )
        }
        asrQueue.trySend(id)
    }

    // ---------------------------------------------------------------- workers

    private suspend fun asrWorker() {
        for (id in asrQueue) {
            val wav = audio[id] ?: continue
            if (find(id) == null) {
                audio.remove(id)
                continue
            }
            val s = settings.value
            updateEntry(id) { it.copy(status = EntryStatus.TRANSCRIBING, error = null) }
            try {
                val prompt = whisperPrompt(s)
                val result = transcribe(s, wav, prompt)
                audio.remove(id)
                val text = AsrFilter.clean(result, prompt)
                if (text.isBlank()) {
                    removeEntry(id)
                    continue
                }
                lastMalay = text
                updateEntry(id) { it.copy(asr = text, malay = text, status = EntryStatus.TRANSLATING) }
                mtQueue.send(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateEntry(id) { it.copy(status = EntryStatus.FAILED, error = friendly(e)) }
                if (e is GroqException && e.isAuth) _state.update { it.copy(error = friendly(e)) }
            }
        }
    }

    /**
     * Interprets entries in order, like a human interpreter: each request carries the unfinished
     * sentence held back last time, the last few interpreted lines and a running summary of the talk.
     */
    private suspend fun mtWorker() {
        while (true) {
            val id = nextForTranslation()
            if (id == null) {
                flushCarry() // the held-back sentence never got its ending (pause or Stop)
                continue
            }
            translateEntry(id)
        }
    }

    /** The next entry to translate, or null if a held-back sentence has waited too long for its end. */
    private suspend fun nextForTranslation(): Long? {
        if (carry.isBlank()) return mtQueue.receive()
        val deadline = System.currentTimeMillis() + CARRY_FLUSH_MS
        while (System.currentTimeMillis() < deadline) {
            mtQueue.tryReceive().getOrNull()?.let { return it }
            delay(200)
        }
        return null
    }

    private suspend fun translateEntry(id: Long) {
        val entry = find(id) ?: return
        val asr = entry.asr ?: return
        val s = settings.value
        val inOrder = id > lastMtId // retries of older lines are translated on their own
        val carryIn = if (inOrder) carry else ""
        updateEntry(id) { it.copy(status = EntryStatus.TRANSLATING, error = null) }
        try {
            val r = translator.translate(
                s.apiKey,
                s.modelList(),
                Translator.Request(
                    setting = s.topic,
                    summary = summary,
                    previous = recentTurns(before = id, count = if (s.target.english && s.target.arabic) 2 else 3),
                    carry = carryIn,
                    malay = asr,
                    target = s.target,
                    finish = !inOrder,
                ),
            )
            if (inOrder) {
                lastMtId = id
                carry = r.carry
                if (r.carry.isNotBlank()) {
                    // If the whole chunk was held back, a late flush belongs to the line before it.
                    carryEntryId = if (r.isEmpty) {
                        if (carryIn.isNotBlank() && find(carryEntryId) != null) carryEntryId else lastDoneId(before = id)
                    } else {
                        id
                    }
                }
            }
            lastMalay = "${r.malay} ${r.carry}".trim().ifBlank { lastMalay }
            if (r.isEmpty) {
                removeEntry(id) // noise, or the whole chunk is held back to finish the sentence next time
            } else {
                updateEntry(id) {
                    it.copy(malay = r.malay.ifBlank { asr }, english = r.english, arabic = r.arabic, status = EntryStatus.DONE)
                }
                find(id)?.let { saveToTranscript(it) }
                maybeUpdateSummary(s)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (inOrder) {
                // Keep the held-back words with this line, so Retry translates them together.
                lastMtId = id
                carry = ""
                if (carryIn.isNotBlank()) updateEntry(id) { it.copy(asr = "$carryIn $asr", malay = "$carryIn $asr") }
            }
            updateEntry(id) { it.copy(status = EntryStatus.FAILED, error = "Translation failed: ${friendly(e)}") }
            if (e is GroqException && e.isAuth) _state.update { it.copy(error = friendly(e)) }
        }
    }

    /** Translates a held-back sentence that never got its ending, and adds it to its line. */
    private suspend fun flushCarry() {
        val text = carry
        if (text.isBlank()) return
        carry = ""
        val s = settings.value
        val line = find(carryEntryId)?.takeIf { it.status == EntryStatus.DONE }
        try {
            val r = translator.translate(
                s.apiKey,
                s.modelList(),
                Translator.Request(
                    setting = s.topic,
                    summary = summary,
                    previous = recentTurns(before = Long.MAX_VALUE, count = 2),
                    carry = "",
                    malay = text,
                    target = s.target,
                    finish = true,
                ),
            )
            if (r.isEmpty) return
            if (line != null) {
                updateEntry(line.id) {
                    it.copy(malay = join(it.malay, r.malay), english = join(it.english, r.english), arabic = join(it.arabic, r.arabic))
                }
            } else {
                val added = Entry(
                    ids.incrementAndGet(), System.currentTimeMillis(), 0, asr = text,
                    malay = r.malay, english = r.english, arabic = r.arabic, status = EntryStatus.DONE,
                )
                _state.update { it.copy(entries = it.entries + added) }
            }
            saveToTranscript(Entry(0, System.currentTimeMillis(), 0, malay = r.malay, english = r.english, arabic = r.arabic))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the words visible even if the translation failed.
            if (line != null) updateEntry(line.id) { it.copy(malay = join(it.malay, text)) }
        }
    }

    /** Every few lines, refresh the running summary in the background (topic, points, names, terms). */
    private fun maybeUpdateSummary(s: AppSettings) {
        if (++doneSinceSummary < SUMMARY_EVERY || summaryJob?.isActive == true) return
        doneSinceSummary = 0
        val recent = recentTurns(before = Long.MAX_VALUE, count = 6)
        val current = summary
        summaryJob = scope.launch {
            translator.summarize(s.apiKey, Defaults.SUMMARY_MODELS, s.topic, current, recent)?.let { summary = it }
        }
    }

    private fun recentTurns(before: Long, count: Int): List<Translator.Turn> =
        _state.value.entries
            .filter { it.id < before && it.status == EntryStatus.DONE && it.hasTranslation }
            .takeLast(count)
            .map { Translator.Turn(it.malay.orEmpty(), it.english, it.arabic) }

    private fun lastDoneId(before: Long): Long =
        _state.value.entries.lastOrNull { it.id < before && it.status == EntryStatus.DONE }?.id ?: 0L

    private fun join(a: String?, b: String?): String? =
        listOfNotNull(a?.takeIf { it.isNotBlank() }, b?.takeIf { it.isNotBlank() }).joinToString(" ").ifBlank { null }

    /**
     * Speech recognition with automatic fallback: if the main Whisper model hits its free limit,
     * the backup model (separate quota) is used until the main one is available again.
     */
    private suspend fun transcribe(s: AppSettings, wav: ByteArray, prompt: String): AsrResult {
        val language = Defaults.SOURCE_LANGUAGE
        var failures = 0
        var limitWaits = 0
        var lastError: Exception? = null
        while (true) {
            val now = System.currentTimeMillis()
            val live = Defaults.ASR_MODELS.filter { it !in asrDead }
            if (live.isEmpty()) throw lastError ?: IOException("Speech recognition isn't available on Groq right now.")
            val model = live.firstOrNull { (asrCoolUntil[it] ?: 0L) <= now }
            if (model == null) {
                // Every model is at its free limit: wait for the first one to come back.
                if (++limitWaits > 3) throw lastError ?: IOException("Groq's free limit was reached.")
                val waitMs = (live.minOf { asrCoolUntil[it] ?: now } - now).coerceIn(1_000L, 60_000L)
                _state.update { it.copy(notice = "Groq free limit reached — waiting ${(waitMs + 999) / 1000}s…") }
                delay(waitMs)
                continue
            }
            try {
                val result = groq.transcribe(s.apiKey, wav, model, language, prompt)
                if (_state.value.notice != null) _state.update { it.copy(notice = null) }
                return result
            } catch (e: GroqException) {
                lastError = e
                when {
                    e.isAuth -> throw e
                    e.isRateLimit -> {
                        val coolSec = (e.retryAfterSec ?: 20.0).coerceIn(2.0, 300.0)
                        asrCoolUntil[model] = System.currentTimeMillis() + (coolSec * 1000).toLong()
                    }
                    e.isModelUnavailable -> asrDead += model
                    e.httpCode in 400..499 -> throw e
                    else -> {
                        if (++failures >= 4) throw e
                        delay(1500L * failures)
                    }
                }
            } catch (e: IOException) {
                lastError = e
                if (++failures >= 4) throw e
                _state.update { it.copy(notice = "Connection problem — retrying…") }
                delay(2000L * failures)
            }
        }
    }

    /** Vocabulary hint + the tail of what was just said, so Whisper keeps names and spelling consistent. */
    private fun whisperPrompt(s: AppSettings): String =
        "${s.asrHint.trim()} ${lastMalay.takeLast(250)}".trim().take(600)

    private suspend fun saveToTranscript(e: Entry) = withContext(Dispatchers.IO) {
        runCatching {
            val file = transcriptFile ?: File(app.filesDir, "transcripts").let { dir ->
                dir.mkdirs()
                File(dir, SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(e.time)) + ".txt")
            }.also { transcriptFile = it }
            val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(e.time))
            val lines = listOfNotNull(e.english, e.arabic, e.malay?.let { "(Malay) $it" }).joinToString("\n")
            file.appendText("[$t]\n$lines\n\n")
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun find(id: Long): Entry? = _state.value.entries.firstOrNull { it.id == id }

    private fun updateEntry(id: Long, transform: (Entry) -> Entry) = _state.update { st ->
        st.copy(entries = st.entries.map { if (it.id == id) transform(it) else it })
    }

    private fun removeEntry(id: Long) = _state.update { st -> st.copy(entries = st.entries.filterNot { it.id == id }) }

    private fun friendly(e: Throwable): String = when {
        e is GroqException && e.isAuth -> "Your Groq API key was rejected. Check it in Settings."
        e is GroqException && e.isRateLimit -> "Groq's free limit was reached. Wait a minute and tap Retry."
        e is GroqException -> "Groq error: ${e.message}"
        e is UnknownHostException -> "No internet connection."
        e is SocketTimeoutException -> "The connection timed out."
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        private const val MAX_BACKLOG = 40
        /** A held-back unfinished sentence is translated on its own if no continuation comes in this time. */
        private const val CARRY_FLUSH_MS = 12_000L
        /** Refresh the running summary of the talk after this many translated lines. */
        private const val SUMMARY_EVERY = 4
        /** Soft voices are boosted up to this much; the limiter stops loud ones clipping. */
        private const val MAX_BOOST_DB = 40f
        private const val CLIP_LEVEL = 32_000
        /** A mode that gives nothing but zeros for this long from the start is treated as not working. */
        private const val SILENT_SWITCH_MS = 4_000
        /** Hints are re-evaluated every ~3 s. */
        private const val HINT_WINDOW_CHUNKS = 100

        private const val NO_SOUND_HINT =
            "The microphone gives no sound. Check that microphone access is on " +
                "(quick settings → Microphone access) and that no call or recorder app is using it."
        private const val SILENCED_HINT =
            "Another app (a call or recorder) is using the microphone. Translation continues when it stops."
        private const val TOO_LOUD_HINT =
            "Too loud: the sound is distorting. Move the phone a little further from the loudspeaker."
    }
}
