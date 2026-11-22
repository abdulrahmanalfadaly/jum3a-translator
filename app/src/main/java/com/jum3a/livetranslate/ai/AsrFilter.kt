package com.jum3a.livetranslate.ai

/**
 * Whisper sometimes "hallucinates" text on silence, music or noise. In Malay the classic ones are
 * YouTube-style sign-offs ("Terima kasih kerana menonton"). This drops those, and drops pieces the
 * model itself flags as probably-not-speech.
 */
object AsrFilter {
    private val junkPhrases = listOf(
        "terima kasih kerana menonton",
        "terima kasih kerana menyaksikan",
        "terima kasih kerana mendengar",
        "terima kasih menonton",
        "terima kasih",
        "jangan lupa subscribe",
        "jangan lupa langgan",
        "sila langgan",
        "sila subscribe",
        "like dan subscribe",
        "like share dan subscribe",
        "subscribe",
        "sarikata oleh",
        "terjemahan oleh",
        "disarikan oleh",
        "thank you for watching",
        "thanks for watching",
        "thank you",
        "please subscribe",
        "amara org",
    )

    fun clean(result: AsrResult, prompt: String?): String {
        val parts = if (result.segments.isNotEmpty()) {
            result.segments
                .filterNot { s ->
                    (s.noSpeechProb > 0.6 && s.avgLogprob < -1.0) || s.compressionRatio > 2.6
                }
                .map { it.text.trim() }
        } else {
            listOf(result.text.trim())
        }
        val normPrompt = prompt?.let { normalize(it) }.orEmpty()
        val kept = parts.filter { p ->
            val n = normalize(p)
            n.isNotEmpty() && !isJunk(n) && !(n.length > 12 && normPrompt.contains(n)) // echoed prompt
        }
        val text = kept.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        if (text.count { it.isLetter() } < 2) return ""
        return text
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun isJunk(n: String): Boolean = junkPhrases.any { phrase ->
        n == phrase || (phrase.length > 12 && n.contains(phrase) && n.length < phrase.length + 15)
    }
}
