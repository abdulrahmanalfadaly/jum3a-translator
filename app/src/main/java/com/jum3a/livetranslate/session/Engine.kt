package com.jum3a.livetranslate.session

import android.content.Context
import android.media.AudioRecord
import com.jum3a.livetranslate.ai.AsrFilter
import com.jum3a.livetranslate.ai.GroqClient
import com.jum3a.livetranslate.ai.GroqException
import com.jum3a.livetranslate.ai.Translator
import com.jum3a.livetranslate.audio.AudioCapture
import com.jum3a.livetranslate.audio.AudioUtil
import com.jum3a.livetranslate.audio.MicConfig
import com.jum3a.livetranslate.audio.OpenedMic
import com.jum3a.livetranslate.audio.Segment
import com.jum3a.livetranslate.audio.Segmenter
import com.jum3a.livetranslate.audio.SegmenterParams
import com.jum3a.livetranslate.settings.AppSettings
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
import java.util.concurrent.atomic.AtomicLong

enum class EntryStatus { QUEUED, TRANSCRIBING, TRANSLATING, DONE, FAILED }

data class Entry(
    val id: Long,
    val time: Long,
    val durationMs: Long,
    val malay: String? = null,
    val english: String? = null,
    val status: EntryStatus = EntryStatus.QUEUED,
    val error: String? = null,
)

data class Meter(
    val db: Float = -90f,
    val thresholdDb: Float = -60f,
    val speaking: Boolean = false,
    val capturingMs: Int = 0,
)

data class EngineState(
    val running: Boolean = false,
    val micInfo: String = "",
    val lastBoostDb: Float? = null,
    val error: String? = null,
    val notice: String? = null,
    val entries: List<Entry> = emptyList(),
)

/**
 * The live pipeline:
 *   microphone → [Segmenter] → utterance WAV → Whisper (Malay text) → LLM (English) → screen.
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

    @Volatile private var lastMalay = ""
    @Volatile private var transcriptFile: File? = null

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
        _state.update { it.copy(running = true, error = null, notice = null, micInfo = "Starting microphone…") }
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
        transcriptFile = null
        _state.update { it.copy(entries = emptyList(), notice = null) }
    }

    fun retry(id: Long) {
        val e = find(id) ?: return
        when {
            e.malay != null -> {
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
            if (e.malay == null && e.english == null) continue
            appendLine("[${time.format(Date(e.time))}]")
            e.english?.let { appendLine(it) }
            e.malay?.let { appendLine("(Malay) $it") }
            appendLine()
        }
    }

    // ---------------------------------------------------------------- capture

    private suspend fun capture(s: AppSettings) {
        var mic: OpenedMic? = null
        val startWall = System.currentTimeMillis()
        val segmenter = Segmenter(
            AudioCapture.SAMPLE_RATE,
            SegmenterParams(
                sensitivity = s.sensitivity,
                minSegMs = (s.minSegSec * 1000).toInt(),
                maxSegMs = (maxOf(s.maxSegSec, s.minSegSec + 2f) * 1000).toInt(),
            ),
        ) { seg -> onSegment(seg, startWall, s) }

        try {
            val opened = AudioCapture.open(app, MicConfig(s.micSource, s.inputDeviceId, s.phoneAgc, s.noiseSuppression))
            mic = opened
            val rec = opened.record
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                error("The microphone is busy. Close other apps that may be recording and try again.")
            }
            _state.update { it.copy(micInfo = opened.description) }

            val buf = ShortArray(segmenter.frameSize)
            var frames = 0
            while (currentCoroutineContext().isActive) {
                var filled = 0
                while (filled < buf.size) {
                    val n = rec.read(buf, filled, buf.size - filled)
                    if (n < 0) error("Microphone stopped working (code $n).")
                    filled += n
                }
                val st = segmenter.process(buf, filled)
                if (++frames % 2 == 0) {
                    _meter.value = Meter(st.db, st.thresholdDb, st.speaking, st.capturingMs)
                }
            }
        } catch (e: CancellationException) {
            // Normal stop.
        } catch (e: Throwable) {
            _state.update { it.copy(error = e.message ?: "Microphone error") }
        } finally {
            runCatching { segmenter.flush() }
            mic?.release()
            _meter.value = Meter()
            _state.update { it.copy(running = false) }
        }
    }

    private fun onSegment(seg: Segment, startWall: Long, s: AppSettings) {
        val norm = AudioUtil.normalize(seg.samples, seg.speechRms, s.maxBoostDb)
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
                val result = withRetry {
                    groq.transcribe(s.apiKey, wav, s.asrModel, s.language.takeIf { it != "auto" }, prompt)
                }
                audio.remove(id)
                val text = AsrFilter.clean(result, prompt)
                if (text.isBlank()) {
                    removeEntry(id)
                    continue
                }
                lastMalay = text
                updateEntry(id) { it.copy(malay = text, status = EntryStatus.TRANSLATING) }
                mtQueue.send(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateEntry(id) { it.copy(status = EntryStatus.FAILED, error = friendly(e)) }
                if (e is GroqException && e.isAuth) _state.update { it.copy(error = friendly(e)) }
            }
        }
    }

    private suspend fun mtWorker() {
        for (id in mtQueue) {
            val entry = find(id) ?: continue
            val malay = entry.malay ?: continue
            val s = settings.value
            val context = _state.value.entries
                .filter { it.id < id && it.english != null && it.malay != null && it.status == EntryStatus.DONE }
                .takeLast(2)
                .map { Translator.Context(it.malay!!, it.english!!) }
            updateEntry(id) { it.copy(status = EntryStatus.TRANSLATING, error = null) }
            try {
                val english = translator.translate(s.apiKey, s.modelList(), s.topic, context, malay)
                updateEntry(id) { it.copy(english = english, status = EntryStatus.DONE) }
                find(id)?.let { saveToTranscript(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateEntry(id) { it.copy(status = EntryStatus.FAILED, error = "Translation failed: ${friendly(e)}") }
                if (e is GroqException && e.isAuth) _state.update { it.copy(error = friendly(e)) }
            }
        }
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                val result = block()
                if (_state.value.notice != null) _state.update { it.copy(notice = null) }
                return result
            } catch (e: GroqException) {
                attempt++
                if (e.isAuth || (e.httpCode in 400..499 && !e.isRateLimit) || attempt >= 4) throw e
                val waitMs = if (e.isRateLimit) {
                    ((e.retryAfterSec ?: 10.0).coerceIn(1.0, 60.0) * 1000).toLong()
                } else {
                    1500L * attempt
                }
                if (e.isRateLimit) {
                    _state.update { it.copy(notice = "Groq free limit reached — waiting ${waitMs / 1000}s…") }
                }
                delay(waitMs)
            } catch (e: IOException) {
                attempt++
                if (attempt >= 4) throw e
                _state.update { it.copy(notice = "Connection problem — retrying…") }
                delay(2000L * attempt)
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
            file.appendText("[$t]\n${e.english}\n(Malay) ${e.malay}\n\n")
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
    }
}
