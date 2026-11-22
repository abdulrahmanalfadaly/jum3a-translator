package com.jum3a.livetranslate.ai

import kotlinx.coroutines.delay
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates each recognised Malay utterance into English with an LLM, giving it the previous
 * utterances as context so sentences that span segments still read naturally.
 *
 * Tries the configured models in order. A model that is rate-limited cools down for the time
 * Groq asks for; a model that has been retired is skipped for the rest of the session.
 */
class Translator(private val groq: GroqClient) {
    data class Context(val malay: String, val english: String)

    private val deadModels: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val coolUntil = ConcurrentHashMap<String, Long>()

    @Volatile
    var lastModelUsed: String? = null
        private set

    suspend fun translate(
        apiKey: String,
        models: List<String>,
        topic: String,
        context: List<Context>,
        malay: String,
    ): String {
        if (models.isEmpty()) throw IOException("No translation models set. Check Settings.")
        val system = systemPrompt(topic)
        val user = userPrompt(context, malay)
        var lastError: Exception? = null

        for (round in 0 until 2) {
            for (model in models) {
                if (model in deadModels) continue
                if ((coolUntil[model] ?: 0L) > System.currentTimeMillis()) continue
                try {
                    val out = cleanOutput(groq.chat(apiKey, model, system, user))
                    if (out.isNotBlank()) {
                        lastModelUsed = model
                        return out
                    }
                    lastError = IOException("$model returned an empty answer")
                } catch (e: GroqException) {
                    lastError = e
                    when {
                        e.isAuth -> throw e
                        e.isRateLimit -> {
                            val waitSec = (e.retryAfterSec ?: 20.0).coerceIn(2.0, 120.0)
                            coolUntil[model] = System.currentTimeMillis() + (waitSec * 1000).toLong()
                        }
                        e.isModelUnavailable -> deadModels += model
                        // Other server errors: just try the next model.
                    }
                } catch (e: IOException) {
                    lastError = e
                }
            }
            if (round == 0) {
                val now = System.currentTimeMillis()
                val soonest = models.filter { it !in deadModels }.mapNotNull { coolUntil[it] }.minOrNull()
                val waitMs = ((soonest ?: (now + 3000)) - now).coerceIn(1000L, 20_000L)
                delay(waitMs)
            }
        }
        throw lastError ?: IOException("No translation model is available right now.")
    }

    private fun systemPrompt(topic: String) = buildString {
        appendLine("You are a professional live interpreter. You translate Malay (Bahasa Melayu) speech into clear, natural English for a listener who does not understand Malay.")
        if (topic.isNotBlank()) appendLine("Setting: ${topic.trim()}")
        appendLine("The Malay text comes from automatic speech recognition of a loudspeaker, so it may contain misheard words, missing punctuation and sentences cut off mid-way. Use the earlier context to work out the intended meaning and silently fix obvious recognition mistakes.")
        appendLine("Rules:")
        appendLine("- Reply with ONLY the English translation of the NEW Malay text. No notes, no explanations, no Malay, no quotation marks around the answer.")
        appendLine("- Do not translate or repeat the earlier context; it is only there to help you.")
        appendLine("- If the new text continues an unfinished sentence, translate it so it reads as the continuation.")
        appendLine("- Be faithful and complete. Do not summarise and do not add ideas that were not said.")
        appendLine("- Keep Islamic terms recognisable (Allah SWT, the Prophet ﷺ, solat, taqwa, iman, zakat, ummah, jemaah) and add a short English meaning the first time a listener might not know the word, e.g. \"taqwa (God-consciousness)\".")
        appendLine("- If part of the text is Arabic (Quran verses, hadith, du'a), give its English meaning in square brackets, e.g. [Quran: \"Indeed, prayer prevents immorality and wrongdoing.\"]. If you cannot identify it, write [Arabic recitation]. Never invent verses.")
        appendLine("- If the new text is only noise or is unintelligible, reply with: …")
    }

    private fun userPrompt(context: List<Context>, malay: String) = buildString {
        if (context.isNotEmpty()) {
            appendLine("Earlier context (already translated, for reference only):")
            for (c in context) {
                appendLine("[Malay] ${c.malay.take(500)}")
                appendLine("[English] ${c.english.take(500)}")
            }
            appendLine()
        }
        appendLine("NEW Malay text to translate now:")
        append(malay.trim())
    }

    private fun cleanOutput(raw: String): String {
        var s = raw.trim()
        s = s.replace(Regex("^(english|translation|terjemahan)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        if (s.length >= 2 && s.first() == '"' && s.last() == '"') s = s.substring(1, s.length - 1)
        return s.trim()
    }
}
