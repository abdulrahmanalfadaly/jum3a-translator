package com.jum3a.livetranslate.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class GroqException(
    val httpCode: Int,
    message: String,
    val errorCode: String? = null,
    val retryAfterSec: Double? = null,
) : IOException(message) {
    val isRateLimit: Boolean get() = httpCode == 429
    val isAuth: Boolean get() = httpCode == 401
    val isModelUnavailable: Boolean
        get() {
            if (errorCode in setOf("model_decommissioned", "model_not_found", "model_not_active")) return true
            if (httpCode == 404) return true
            val m = message.orEmpty().lowercase()
            return (httpCode == 400 || httpCode == 403) && "model" in m &&
                ("decommission" in m || "does not exist" in m || "not supported" in m ||
                    "not found" in m || "blocked" in m || "access" in m)
        }
}

data class AsrSegment(val text: String, val noSpeechProb: Double, val avgLogprob: Double, val compressionRatio: Double)
data class AsrResult(val text: String, val segments: List<AsrSegment>, val language: String?)

/** Chat completions, separated out so the translator can be tested without the network. */
interface ChatBackend {
    /**
     * @param responseFormat Groq `response_format`, e.g. a strict JSON schema or `{"type":"json_object"}`;
     *   null for plain text.
     */
    suspend fun chat(
        apiKey: String,
        model: String,
        system: String,
        user: String,
        temperature: Double = 0.2,
        maxTokens: Int = 1500,
        responseFormat: JSONObject? = null,
    ): String
}

/** Minimal client for Groq's OpenAI-compatible API (free tier at console.groq.com). */
class GroqClient(private val http: OkHttpClient) : ChatBackend {
    private val base = "https://api.groq.com/openai/v1"
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun transcribe(
        apiKey: String,
        wav: ByteArray,
        model: String,
        language: String?,
        prompt: String?,
    ): AsrResult = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("temperature", "0")
            .apply {
                if (!language.isNullOrBlank()) addFormDataPart("language", language)
                if (!prompt.isNullOrBlank()) addFormDataPart("prompt", prompt)
            }
            .build()
        val json = execute(
            Request.Builder().url("$base/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .post(body)
                .build(),
        )
        val segments = json.optJSONArray("segments")?.let { arr ->
            (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                AsrSegment(
                    text = s.optString("text"),
                    noSpeechProb = s.optDouble("no_speech_prob", 0.0),
                    avgLogprob = s.optDouble("avg_logprob", 0.0),
                    compressionRatio = s.optDouble("compression_ratio", 1.0),
                )
            }
        } ?: emptyList()
        AsrResult(json.optString("text"), segments, json.optString("language").ifBlank { null })
    }

    override suspend fun chat(
        apiKey: String,
        model: String,
        system: String,
        user: String,
        temperature: Double,
        maxTokens: Int,
        responseFormat: JSONObject?,
    ): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("model", model)
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
            put("temperature", temperature)
            put("max_completion_tokens", maxTokens)
            if (responseFormat != null) put("response_format", responseFormat)
            when {
                // Reasoning models: think briefly and don't send the reasoning back.
                model.startsWith("openai/gpt-oss") -> {
                    put("reasoning_effort", "low")
                    put("include_reasoning", false)
                }
                model.contains("qwen3", ignoreCase = true) -> put("reasoning_effort", "none")
            }
        }
        val json = execute(
            Request.Builder().url("$base/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toString().toRequestBody(jsonType))
                .build(),
        )
        json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
    }

    suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        val json = execute(
            Request.Builder().url("$base/models").header("Authorization", "Bearer $apiKey").get().build(),
        )
        val data = json.optJSONArray("data") ?: JSONArray()
        (0 until data.length()).map { data.getJSONObject(it).optString("id") }.filter { it.isNotBlank() }
    }

    private fun execute(request: Request): JSONObject {
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                var message = "HTTP ${resp.code}"
                var code: String? = null
                runCatching {
                    val err = JSONObject(text).getJSONObject("error")
                    message = err.optString("message", message)
                    code = err.optString("code").ifBlank { null }
                }
                val retryAfter = resp.header("retry-after")?.toDoubleOrNull()
                throw GroqException(resp.code, message, code, retryAfter)
            }
            return JSONObject(text)
        }
    }
}
