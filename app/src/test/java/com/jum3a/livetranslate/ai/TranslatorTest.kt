package com.jum3a.livetranslate.ai

import com.jum3a.livetranslate.settings.TargetLang
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorTest {
    private class Call(val model: String, val system: String, val user: String, val format: JSONObject?)

    /** Plays back scripted replies; a reply may throw to simulate a Groq error. */
    private class FakeBackend(vararg replies: (Call) -> String) : ChatBackend {
        private val queue = replies.toMutableList()
        val calls = mutableListOf<Call>()
        override suspend fun chat(
            apiKey: String, model: String, system: String, user: String,
            temperature: Double, maxTokens: Int, responseFormat: JSONObject?,
        ): String {
            val call = Call(model, system, user, responseFormat)
            calls += call
            return queue.removeAt(0)(call)
        }
    }

    private val models = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b", "llama-3.3-70b-versatile")

    private fun request(target: TargetLang = TargetLang.ENGLISH, carry: String = "", finish: Boolean = false) =
        Translator.Request(
            setting = "Friday khutbah",
            summary = "Topic: trust (amanah).",
            previous = listOf(Translator.Turn("Amanah ialah tanggungjawab.", "Trust is a responsibility.", "الأمانة مسؤولية.")),
            carry = carry,
            malay = "Rasulullah SAW bersabda tidak ada iman bagi orang yang tidak ada amanah padanya dan tidak ada",
            target = target,
            finish = finish,
        )

    private fun translator(backend: FakeBackend) = Translator(backend, sleep = {})

    @Test
    fun `complete sentences are translated and the unfinished tail is held back`() = runBlocking {
        val backend = FakeBackend({
            """{"malay":"Rasulullah SAW bersabda: Tidak ada iman bagi orang yang tidak amanah.","english":"The Prophet ﷺ said: There is no faith for one who is not trustworthy.","carry":"dan tidak ada"}"""
        })
        val r = translator(backend).translate("k", models, request())
        assertEquals("The Prophet ﷺ said: There is no faith for one who is not trustworthy.", r.english)
        assertNull(r.arabic)
        assertEquals("dan tidak ada", r.carry)
        assertFalse(r.malay.contains("dan tidak ada"))
        assertEquals("openai/gpt-oss-120b", r.model)
    }

    @Test
    fun `the request carries context, the held-back text and the target`() = runBlocking {
        val backend = FakeBackend({ """{"malay":"x","english":"y","carry":""}""" })
        translator(backend).translate("k", models, request(carry = "Sidang Jumaat sekalian,"))
        val user = backend.calls.single().user
        assertTrue(user.contains("NOTES: Topic: trust (amanah)."))
        assertTrue(user.contains("Trust is a responsibility."))
        assertTrue(user.contains("CARRY: Sidang Jumaat sekalian,"))
        assertTrue(user.contains("NEW: Rasulullah SAW bersabda"))
        assertTrue(user.contains("TARGET: English"))
        assertTrue(user.contains("FINISH: no"))
    }

    @Test
    fun `the fixed instructions are identical on every request so they can be cached`() = runBlocking {
        val reply: (Call) -> String = { """{"malay":"x","arabic":"ص","carry":""}""" }
        val backend = FakeBackend(reply, reply)
        val t = translator(backend)
        t.translate("k", models, request(TargetLang.ARABIC))
        t.translate("k", models, request(TargetLang.ARABIC, carry = "lain"))
        assertSame(backend.calls[0].system, backend.calls[1].system)
        assertTrue(backend.calls[0].user != backend.calls[1].user)
    }

    @Test
    fun `arabic only and both languages use a strict schema with the right keys`() = runBlocking {
        val backend = FakeBackend(
            { """{"malay":"a","arabic":"قال رسول الله ﷺ: لا إيمان لمن لا أمانة له.","carry":""}""" },
            { """{"malay":"a","english":"No faith without trust.","arabic":"لا إيمان لمن لا أمانة له.","carry":""}""" },
        )
        val t = translator(backend)
        val ar = t.translate("k", models, request(TargetLang.ARABIC))
        assertNull(ar.english)
        assertTrue(ar.arabic!!.contains("رسول الله"))
        val both = t.translate("k", models, request(TargetLang.BOTH))
        assertEquals("No faith without trust.", both.english)
        assertEquals("لا إيمان لمن لا أمانة له.", both.arabic)

        val arSchema = backend.calls[0].format!!.getJSONObject("json_schema")
        assertTrue(arSchema.getBoolean("strict"))
        val arKeys = arSchema.getJSONObject("schema").getJSONArray("required").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(listOf("malay", "arabic", "carry"), arKeys)
        val bothKeys = backend.calls[1].format!!.getJSONObject("json_schema").getJSONObject("schema").getJSONArray("required")
            .let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(listOf("malay", "english", "arabic", "carry"), bothKeys)
    }

    @Test
    fun `finish means nothing is held back`() = runBlocking {
        val backend = FakeBackend({ """{"malay":"a","english":"b","carry":"should be ignored"}""" })
        val r = translator(backend).translate("k", models, request(finish = true))
        assertEquals("", r.carry)
        assertTrue(backend.calls.single().user.contains("FINISH: yes"))
    }

    @Test
    fun `a too-long held-back tail is translated in full instead`() = runBlocking {
        val longTail = "kata ".repeat(60).trim()
        val backend = FakeBackend(
            { """{"malay":"a","english":"b","carry":"$longTail"}""" },
            { """{"malay":"a $longTail","english":"b and the rest","carry":""}""" },
        )
        val r = translator(backend).translate("k", models, request())
        assertEquals("b and the rest", r.english)
        assertEquals("", r.carry)
        assertTrue(backend.calls[1].user.contains("FINISH: yes"))
    }

    @Test
    fun `a model that rejects the schema gets plain JSON mode, and it is remembered`() = runBlocking {
        val ok: (Call) -> String = { """{"malay":"a","english":"b","carry":""}""" }
        val backend = FakeBackend(
            { throw GroqException(400, "response_format `json_schema` is not supported with this model", "invalid_request_error") },
            ok,
            ok,
        )
        val t = translator(backend)
        assertEquals("b", t.translate("k", models, request()).english)
        assertEquals("json_object", backend.calls[1].format!!.getString("type"))
        assertEquals("openai/gpt-oss-120b", backend.calls[1].model) // same model, not marked dead
        t.translate("k", models, request())
        assertEquals("json_object", backend.calls[2].format!!.getString("type"))
    }

    @Test
    fun `invalid JSON from the model is retried in a simpler format`() = runBlocking {
        val backend = FakeBackend(
            { throw GroqException(400, "Failed to generate JSON. Please adjust your prompt.", "json_validate_failed") },
            { """{"malay":"a","english":"b","carry":""}""" },
            { """{"malay":"a","english":"c","carry":""}""" },
        )
        val t = translator(backend)
        assertEquals("b", t.translate("k", models, request()).english)
        // A one-off validation failure doesn't stop the schema being used next time.
        t.translate("k", models, request())
        assertEquals("json_schema", backend.calls[2].format!!.getString("type"))
    }

    @Test
    fun `a rate-limited model hands over to the next one`() = runBlocking {
        val backend = FakeBackend(
            { throw GroqException(429, "Rate limit reached for tokens per day", "rate_limit_exceeded", 600.0) },
            { """{"malay":"a","english":"from the second model","carry":""}""" },
        )
        val r = translator(backend).translate("k", models, request())
        assertEquals("openai/gpt-oss-20b", r.model)
        assertEquals("from the second model", r.english)
    }

    @Test
    fun `models without schema support use JSON mode`() = runBlocking {
        val backend = FakeBackend({ """{"malay":"a","english":"b","carry":""}""" })
        translator(backend).translate("k", listOf("llama-3.3-70b-versatile"), request())
        assertEquals("json_object", backend.calls.single().format!!.getString("type"))
    }

    @Test
    fun `a plain-text answer is used as the translation`() = runBlocking {
        val backend = FakeBackend({ "English: The Prophet said there is no faith without trust." })
        val r = translator(backend).translate("k", models, request())
        assertEquals("The Prophet said there is no faith without trust.", r.english)
        assertEquals("", r.carry)
    }

    @Test
    fun `noise gives an empty result`() = runBlocking {
        val backend = FakeBackend({ """{"malay":"","english":"","carry":""}""" })
        assertTrue(translator(backend).translate("k", models, request()).isEmpty)
    }

    @Test
    fun `the running summary is produced from recent lines`() = runBlocking {
        val backend = FakeBackend({ "Topic: trust (amanah)\nPoints so far: faith needs trust\nNames: —\nTerms: amanah → trust / الأمانة" })
        val notes = translator(backend).summarize(
            "k", listOf("llama-3.1-8b-instant"), "Friday khutbah", "",
            listOf(Translator.Turn("Amanah ialah tanggungjawab.", "Trust is a responsibility.", null)),
        )
        assertTrue(notes!!.startsWith("Topic: trust"))
        assertTrue(backend.calls.single().user.contains("Trust is a responsibility."))
    }
}
