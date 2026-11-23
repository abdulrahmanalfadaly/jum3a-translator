package com.jum3a.livetranslate.ai

import com.jum3a.livetranslate.settings.TalkType
import com.jum3a.livetranslate.settings.TargetLang
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Runs a sample khutbah (with typical speech-recognition errors, cut mid-sentence like the live
 * chunks) through the real Groq models, the same way the app does, and prints the result.
 *
 * Skipped unless GROQ_API_KEY is set, so it never runs on GitHub. To try it:
 *   $env:GROQ_API_KEY = "gsk_..."; .\gradlew testDebugUnitTest --tests "*TranslatorLiveTest*"
 */
class TranslatorLiveTest {
    private val chunks = listOf(
        "sidang jumaat yang dirahmati allah sekalian marilah kita berta kwa kepada allah dengan sebenar benar takwa iaitu dengan melaksanakan segala suruhan nya dan meninggalkan segala",
        "larangan nya mudah mudahan kita menjadi insan yang berjaya di dunia dan di akhirat mimbar pada hari ini akan membicarakan khutbah yang bertajuk menjaga amanah dalam",
        "kehidupan amanah adalah satu tanggungjawab yang besar yang dipikul oleh setiap muslim rasul lullah saw bersabda tidak ada iman bagi orang yang tidak ada amanah padanya",
        "dan tidak ada agama bagi orang yang tidak menepati janji sidang jumaat sekalian amanah bukan hanya berkaitan dengan harta benda tetapi juga meliputi masa tugas dan",
        "kepercayaan yang diberikan kepada kita oleh majikan keluarga dan masyarakat oleh itu marilah kita menunaikan amanah dengan penuh tanggungjawab",
    )

    @Test
    fun `sample khutbah in English and Arabic`() = runBlocking {
        val key = System.getenv("GROQ_API_KEY")
        assumeTrue("Set GROQ_API_KEY to run the live test", !key.isNullOrBlank())
        val http = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).build()
        val translator = Translator(GroqClient(http))
        val models = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "qwen/qwen3.8-27b", "llama-3.3-70b-versatile")

        for (target in listOf(TargetLang.ENGLISH, TargetLang.ARABIC)) {
            println("\n================ ${target.name} ================")
            var carry = ""
            val previous = ArrayList<Translator.Turn>()
            for ((i, chunk) in chunks.withIndex()) {
                val last = i == chunks.lastIndex
                val r = translator.translate(
                    key!!, models,
                    Translator.Request(TalkType.KHUTBAH.topic, "", previous.takeLast(3), carry, chunk, target, finish = last),
                )
                println("\n--- chunk ${i + 1} (${r.model})")
                println("ASR:   $chunk")
                println("Malay: ${r.malay}")
                r.english?.let { println("EN:    $it") }
                r.arabic?.let { println("AR:    $it") }
                if (r.carry.isNotBlank()) println("held:  ${r.carry}")
                carry = r.carry
                if (!r.isEmpty) previous += Translator.Turn(r.malay, r.english, r.arabic)
            }
            assertTrue("no translations came back", previous.isNotEmpty())
            if (target == TargetLang.ARABIC) {
                assertTrue("expected Arabic script", previous.any { t -> t.arabic.orEmpty().any { it in '؀'..'ۿ' } })
            }
        }
    }
}
