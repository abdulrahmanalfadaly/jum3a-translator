package com.jum3a.livetranslate.ai

import com.jum3a.livetranslate.settings.TargetLang
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Interprets live Malay speech-recognition text into English and/or Arabic, the way a human
 * interpreter would rather than line by line:
 *
 * - it first repairs the recognition errors using the context (misheard words, mangled Arabic terms);
 * - it translates only complete sentences and holds back an unfinished tail ("carry"), which is
 *   joined to the next chunk, so the listener gets whole, understandable sentences;
 * - it sees the last few interpreted lines and a running summary of the talk (topic, names, terms),
 *   so pronouns, references and terminology stay consistent.
 *
 * The long instructions are identical on every request, so Groq serves them from its prompt cache
 * (cached tokens don't count against the free limits). Only the short, changing part is new each time.
 *
 * Models are tried in order. A rate-limited model cools down for the time Groq asks for; a retired
 * one is skipped for the rest of the session.
 */
class Translator(
    private val backend: ChatBackend,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** A line that was already interpreted, given back as context. */
    data class Turn(val malay: String, val english: String?, val arabic: String?)

    data class Request(
        /** What kind of talk it is, plus anything the listener told us. */
        val setting: String,
        /** Running summary of the talk so far (may be empty). */
        val summary: String,
        /** The last few interpreted lines, oldest first. */
        val previous: List<Turn>,
        /** Unfinished Malay held back from the previous chunk. */
        val carry: String,
        /** The new speech-recognition text. */
        val malay: String,
        val target: TargetLang,
        /** Translate everything now and hold nothing back (end of the talk, or a retry). */
        val finish: Boolean,
    ) {
        val joined: String get() = listOf(carry, malay).filter { it.isNotBlank() }.joinToString(" ") { it.trim() }
    }

    data class Result(
        /** The repaired Malay of the part that was translated. */
        val malay: String,
        val english: String?,
        val arabic: String?,
        /** Unfinished Malay held back, to be completed with the next chunk. */
        val carry: String,
        val model: String,
    ) {
        val isEmpty: Boolean get() = english.isNullOrBlank() && arabic.isNullOrBlank()
    }

    private val deadModels: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val coolUntil = ConcurrentHashMap<String, Long>()
    /** Models that rejected a JSON schema / JSON mode: use the next simpler output format for them. */
    private val noSchema: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val noJson: MutableSet<String> = ConcurrentHashMap.newKeySet()

    suspend fun translate(apiKey: String, models: List<String>, req: Request): Result {
        val result = withModels(models) { model -> attempt(apiKey, model, req, FORMAT_SCHEMA) }
        // A suspiciously long "unfinished" tail would delay too much speech: translate everything instead.
        if (!req.finish && result.carry.length > MAX_CARRY_CHARS) {
            return translate(apiKey, models, req.copy(finish = true))
        }
        return result
    }

    /**
     * Updates the running summary of the talk from the latest lines. Best effort: returns null if
     * no model is available, and the interpretation simply continues with the old summary.
     */
    suspend fun summarize(
        apiKey: String,
        models: List<String>,
        setting: String,
        current: String,
        recent: List<Turn>,
    ): String? {
        if (recent.isEmpty()) return null
        val user = buildString {
            appendLine("SETTING: $setting")
            appendLine("CURRENT NOTES: ${current.ifBlank { "(none yet)" }}")
            appendLine("LATEST INTERPRETED LINES (oldest first):")
            recent.forEachIndexed { i, t ->
                appendLine("${i + 1}. Malay: ${t.malay.take(400)}")
                t.english?.let { appendLine("   English: ${it.take(400)}") }
                t.arabic?.let { appendLine("   Arabic: ${it.take(400)}") }
            }
            appendLine()
            appendLine(
                "Write the updated notes in English, at most 70 words, as four short lines:\n" +
                    "Topic: …\nPoints so far: …\nNames: …\nTerms: Malay term → translation used (English / Arabic)\n" +
                    "Keep what is still relevant, drop what isn't. No commentary.",
            )
        }
        return runCatching {
            withModels(models, rounds = 1) { model ->
                backend.chat(apiKey, model, SUMMARY_SYSTEM, user, temperature = 0.1, maxTokens = 600).trim()
                    .takeIf { it.isNotBlank() } ?: throw IOException("empty summary")
            }
        }.getOrNull()?.take(700)
    }

    // ------------------------------------------------------------------------ model handling

    private suspend fun <T> withModels(models: List<String>, rounds: Int = 2, block: suspend (String) -> T): T {
        if (models.isEmpty()) throw IOException("No translation models set. Check Settings.")
        var lastError: Exception? = null
        for (round in 0 until rounds) {
            for (model in models) {
                if (model in deadModels) continue
                if ((coolUntil[model] ?: 0L) > System.currentTimeMillis()) continue
                try {
                    return block(model)
                } catch (e: GroqException) {
                    lastError = e
                    when {
                        e.isAuth -> throw e
                        e.isRateLimit -> {
                            val waitSec = (e.retryAfterSec ?: 20.0).coerceIn(2.0, 300.0)
                            coolUntil[model] = System.currentTimeMillis() + (waitSec * 1000).toLong()
                        }
                        e.isModelUnavailable -> deadModels += model
                        // Other server errors: try the next model.
                    }
                } catch (e: IOException) {
                    lastError = e
                }
            }
            if (round < rounds - 1) {
                val now = System.currentTimeMillis()
                val soonest = models.filter { it !in deadModels }.mapNotNull { coolUntil[it] }.minOrNull()
                sleep(((soonest ?: (now + 3000)) - now).coerceIn(1000L, 20_000L))
            }
        }
        throw lastError ?: IOException("No translation model is available right now.")
    }

    /** One request to one model, stepping down to a simpler output format if the model rejects one. */
    private suspend fun attempt(apiKey: String, model: String, req: Request, format: Int): Result {
        val level = when {
            model in noJson -> FORMAT_PLAIN
            model in noSchema || !supportsSchema(model) -> minOf(format, FORMAT_JSON)
            else -> format
        }
        val responseFormat = when (level) {
            FORMAT_SCHEMA -> schemaFormat(req.target)
            FORMAT_JSON -> JSONObject().put("type", "json_object")
            else -> null
        }
        val raw = try {
            backend.chat(
                apiKey, model, SYSTEM_PROMPT, userPrompt(req, level != FORMAT_PLAIN),
                temperature = 0.2,
                maxTokens = if (req.target == TargetLang.BOTH) 2200 else 1600,
                responseFormat = responseFormat,
            )
        } catch (e: GroqException) {
            if (level > FORMAT_PLAIN && isFormatError(e)) {
                // This model (or this answer) didn't work with structured output: try a simpler format.
                if (isUnsupported(e)) {
                    if (level == FORMAT_SCHEMA) noSchema += model else noJson += model
                }
                return attempt(apiKey, model, req, level - 1)
            }
            throw e
        }
        return parse(raw, req, model)
    }

    private fun isFormatError(e: GroqException): Boolean {
        if (e.httpCode != 400) return false
        val m = (e.message.orEmpty() + " " + e.errorCode.orEmpty()).lowercase()
        return "json" in m || "schema" in m || "response_format" in m || "validate" in m
    }

    private fun isUnsupported(e: GroqException): Boolean {
        val m = e.message.orEmpty().lowercase()
        return "not supported" in m || "unsupported" in m || "does not support" in m
    }

    private fun supportsSchema(model: String) =
        model.startsWith("openai/gpt-oss") || model.startsWith("qwen/qwen3.8") || model.startsWith("meta-models/muse")

    // ------------------------------------------------------------------------ prompts

    internal fun userPrompt(req: Request, json: Boolean): String = buildString {
        appendLine("SETTING: ${req.setting.trim()}")
        appendLine("NOTES: ${req.summary.trim().ifBlank { "(none yet)" }}")
        if (req.previous.isNotEmpty()) {
            appendLine("PREVIOUS (oldest first, already interpreted, do not repeat):")
            val limit = if (req.target == TargetLang.BOTH) 220 else 300
            req.previous.forEachIndexed { i, t ->
                appendLine("${i + 1}. Malay: ${t.malay.take(limit)}")
                if (req.target.english || t.arabic == null) t.english?.let { appendLine("   English: ${it.take(limit)}") }
                if (req.target.arabic || t.english == null) t.arabic?.let { appendLine("   Arabic: ${it.take(limit)}") }
            }
        } else {
            appendLine("PREVIOUS: (this is the start of the talk)")
        }
        appendLine("CARRY: ${req.carry.trim().ifBlank { "(none)" }}")
        appendLine("NEW: ${req.malay.trim()}")
        appendLine(
            "TARGET: " + when (req.target) {
                TargetLang.ENGLISH -> "English"
                TargetLang.ARABIC -> "Arabic"
                TargetLang.BOTH -> "English and Arabic"
            },
        )
        appendLine(
            if (req.finish) "FINISH: yes (translate everything now; carry must be empty)"
            else "FINISH: no (you may hold back an unfinished last sentence in carry)",
        )
        if (json) {
            append("Reply with JSON only, keys: malay, ")
            if (req.target.english) append("english, ")
            if (req.target.arabic) append("arabic, ")
            append("carry.")
        } else {
            append("Reply with the translation only.")
        }
    }

    private fun schemaFormat(target: TargetLang): JSONObject {
        val keys = buildList {
            add("malay")
            if (target.english) add("english")
            if (target.arabic) add("arabic")
            add("carry")
        }
        val props = JSONObject()
        keys.forEach { props.put(it, JSONObject().put("type", "string")) }
        return JSONObject()
            .put("type", "json_schema")
            .put(
                "json_schema",
                JSONObject()
                    .put("name", "interpretation")
                    .put("strict", true)
                    .put(
                        "schema",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", props)
                            .put("required", JSONArray(keys))
                            .put("additionalProperties", false),
                    ),
            )
    }

    // ------------------------------------------------------------------------ parsing

    internal fun parse(raw: String, req: Request, model: String): Result {
        val json = extractJson(raw)
        if (json == null) {
            // Plain-text answer: all of it is the translation, nothing held back.
            val text = clean(raw)
            return Result(
                malay = if (text.isBlank()) "" else req.joined,
                english = text.takeIf { req.target.english && it.isNotBlank() },
                arabic = text.takeIf { !req.target.english && it.isNotBlank() },
                carry = "",
                model = model,
            )
        }
        val english = if (req.target.english) clean(json.optString("english")).ifBlank { null } else null
        val arabic = if (req.target.arabic) clean(json.optString("arabic")).ifBlank { null } else null
        val carry = if (req.finish) "" else json.optString("carry").trim().let { if (isEmptyMarker(it)) "" else it }
        var malay = json.optString("malay").trim()
        // The repaired Malay should cover only what was translated, not the held-back tail.
        if (carry.isNotEmpty() && malay.endsWith(carry)) malay = malay.removeSuffix(carry).trim()
        if (malay.isBlank() && (english != null || arabic != null)) malay = req.joined
        return Result(malay, english, arabic, carry, model)
    }

    private fun extractJson(raw: String): JSONObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull()
    }

    private fun clean(s: String): String {
        var t = s.trim()
        t = t.replace(Regex("^(english|arabic|translation|terjemahan)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        if (t.length >= 2 && t.first() == '"' && t.last() == '"') t = t.substring(1, t.length - 1)
        t = t.trim()
        return if (isEmptyMarker(t)) "" else t
    }

    private fun isEmptyMarker(s: String) = s.isBlank() || s == "…" || s == "..." || s.equals("(none)", true)

    companion object {
        /** Longer "unfinished" tails than this are not held back. */
        const val MAX_CARRY_CHARS = 220

        private const val FORMAT_PLAIN = 0
        private const val FORMAT_JSON = 1
        private const val FORMAT_SCHEMA = 2

        private const val SUMMARY_SYSTEM =
            "You keep brief running notes about a talk that is being interpreted live from Malay, " +
                "so the interpreter stays consistent. Be factual and concise."

        /** Identical on every request so it is served from Groq's prompt cache. */
        internal val SYSTEM_PROMPT = """
You are an expert live interpreter. A Muslim talk in Malay (Bahasa Melayu), such as a Friday khutbah, a kuliah or a lecture, is being captured by speech recognition (ASR) from a loudspeaker and reaches you in chunks of about ten seconds. You give a listener who does not understand Malay a faithful, natural and easy-to-follow interpretation in the requested language(s).

Each request contains:
- SETTING: the kind of talk.
- NOTES: a running summary of the talk so far (topic, points, names, terms). May be empty.
- PREVIOUS: the last lines you already interpreted. Use them only for continuity; never translate them again.
- CARRY: the unfinished end of the previous chunk that you held back. It comes directly before NEW.
- NEW: the new ASR text.
- TARGET: English, Arabic, or both.
- FINISH: whether you must translate everything now.

Steps:
1. Join CARRY and NEW. Repair the ASR text using the context and the setting: misheard words, wrong word boundaries, missing punctuation. Arabic and Islamic words are often mangled (taqwa, solat, Rasulullah, insya-Allah, subhanallah, names of prophets and surahs); restore them. Do not invent content that was not said.
2. Translate every complete sentence. If the text ends in the middle of a sentence or thought, leave that unfinished tail untranslated and return it, in Malay, as "carry" (at most about 15 words); it will be joined with the next chunk. If FINISH is yes, translate everything and return an empty carry.
3. "malay" is the repaired Malay of exactly the part you translated, without the carry.

How to translate:
- Translate the meaning, in fluent complete sentences, not word for word. Be faithful and complete: no summarising, no additions, no commentary or notes.
- Use PREVIOUS and NOTES to resolve pronouns, references and ambiguous words, and keep names and terminology the same as you translated them before.
- Leave out pure fillers and stammered repetitions (ya, ah, er) when they add nothing.
- Render set phrases naturally:
  "Sidang Jumaat yang dirahmati Allah sekalian" → "Dear congregation, may Allah have mercy on you all" / "أيها المصلون الكرام، رحمكم الله"
  "Marilah kita bertakwa kepada Allah" → "Let us be mindful of Allah (taqwa)" / "فلنتقِ الله"
  "Allah SWT" → "Allah, the Exalted" / "الله سبحانه وتعالى"
  "Rasulullah SAW", "Nabi Muhammad SAW" → "the Prophet Muhammad ﷺ" / "رسول الله ﷺ"
- English: clear, modern English. Keep core Islamic terms recognisable and explain them once when a listener may not know them, e.g. "taqwa (God-consciousness)".
- Arabic: clear Modern Standard Arabic (الفصحى) in the style of an Arabic khutbah, with correct Islamic terminology (التقوى، الصلاة، الزكاة، الإيمان، الأمة) and Arabic punctuation (، ؛ ؟).
- Quran verses, hadith or du'a recited in Arabic: never reproduce, reconstruct or quote them from memory. Write "[Quran recitation]" / "[تلاوة قرآنية]" (or "[Hadith]" / "[حديث]", "[Du'a]" / "[دعاء]"), adding the surah and verse only if the speaker names them. Then translate the speaker's own Malay explanation as usual.
- If the text is only noise, music or unintelligible, return empty strings.

Reply with a single JSON object and nothing else, with the keys named in the request: "malay", the target language key(s) "english" and/or "arabic", and "carry".
""".trim()
    }
}
