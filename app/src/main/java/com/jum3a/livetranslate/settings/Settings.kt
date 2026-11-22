package com.jum3a.livetranslate.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MicSource(val title: String, val detail: String) {
    AUTO("Auto (recommended)", "Raw microphone if the phone supports it, otherwise speech-recognition mode. The app boosts quiet voices itself."),
    UNPROCESSED("Raw / unprocessed", "Full microphone range with no phone filtering at all."),
    VOICE_RECOGNITION("Speech recognition", "Clean, lightly processed signal tuned for speech-to-text."),
    CAMCORDER("Far-field (camcorder)", "Tuned for distant sound; often uses the back microphone. Try it if the speaker is far away."),
    MIC("Standard", "The phone's default microphone processing."),
}

object Defaults {
    const val TOPIC =
        "Friday khutbah (sermon) or Islamic religious talk at a mosque in Malaysia. " +
            "It may include Arabic Quran verses, hadith and du'a."
    const val ASR_HINT =
        "Khutbah Jumaat. Sidang Jumaat yang dirahmati Allah sekalian, marilah kita bertakwa kepada Allah SWT. " +
            "Sabda Rasulullah SAW."
    const val ASR_MODEL = "whisper-large-v3"
    const val TRANSLATION_MODELS = "openai/gpt-oss-120b, openai/gpt-oss-20b, llama-3.3-70b-versatile"
}

data class AppSettings(
    val apiKey: String = "",
    val topic: String = Defaults.TOPIC,
    val asrHint: String = Defaults.ASR_HINT,
    /** "ms" to force Malay, "auto" to let the model detect the language. */
    val language: String = "ms",
    val asrModel: String = Defaults.ASR_MODEL,
    val translationModels: String = Defaults.TRANSLATION_MODELS,
    val micSource: MicSource = MicSource.AUTO,
    /** 0 = let Android pick the input device. */
    val inputDeviceId: Int = 0,
    val phoneAgc: Boolean = false,
    val noiseSuppression: Boolean = false,
    val maxBoostDb: Float = 30f,
    /** 0..1, higher picks up quieter speech. */
    val sensitivity: Float = 0.65f,
    val minSegSec: Float = 6f,
    val maxSegSec: Float = 15f,
    val fontScale: Float = 1.15f,
    val showMalay: Boolean = true,
    val keepScreenOn: Boolean = true,
    val autoUpdateCheck: Boolean = true,
) {
    fun modelList(): List<String> =
        translationModels.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value: AppSettings get() = _state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_state.value)
        _state.value = next
        save(next)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            apiKey = prefs.getString("apiKey", d.apiKey) ?: d.apiKey,
            topic = prefs.getString("topic", d.topic) ?: d.topic,
            asrHint = prefs.getString("asrHint", d.asrHint) ?: d.asrHint,
            language = prefs.getString("language", d.language) ?: d.language,
            asrModel = prefs.getString("asrModel", d.asrModel) ?: d.asrModel,
            translationModels = prefs.getString("translationModels", d.translationModels) ?: d.translationModels,
            micSource = runCatching { MicSource.valueOf(prefs.getString("micSource", d.micSource.name)!!) }
                .getOrDefault(d.micSource),
            inputDeviceId = prefs.getInt("inputDeviceId", d.inputDeviceId),
            phoneAgc = prefs.getBoolean("phoneAgc", d.phoneAgc),
            noiseSuppression = prefs.getBoolean("noiseSuppression", d.noiseSuppression),
            maxBoostDb = prefs.getFloat("maxBoostDb", d.maxBoostDb),
            sensitivity = prefs.getFloat("sensitivity", d.sensitivity),
            minSegSec = prefs.getFloat("minSegSec", d.minSegSec),
            maxSegSec = prefs.getFloat("maxSegSec", d.maxSegSec),
            fontScale = prefs.getFloat("fontScale", d.fontScale),
            showMalay = prefs.getBoolean("showMalay", d.showMalay),
            keepScreenOn = prefs.getBoolean("keepScreenOn", d.keepScreenOn),
            autoUpdateCheck = prefs.getBoolean("autoUpdateCheck", d.autoUpdateCheck),
        )
    }

    private fun save(s: AppSettings) = prefs.edit {
        putString("apiKey", s.apiKey)
        putString("topic", s.topic)
        putString("asrHint", s.asrHint)
        putString("language", s.language)
        putString("asrModel", s.asrModel)
        putString("translationModels", s.translationModels)
        putString("micSource", s.micSource.name)
        putInt("inputDeviceId", s.inputDeviceId)
        putBoolean("phoneAgc", s.phoneAgc)
        putBoolean("noiseSuppression", s.noiseSuppression)
        putFloat("maxBoostDb", s.maxBoostDb)
        putFloat("sensitivity", s.sensitivity)
        putFloat("minSegSec", s.minSegSec)
        putFloat("maxSegSec", s.maxSegSec)
        putFloat("fontScale", s.fontScale)
        putBoolean("showMalay", s.showMalay)
        putBoolean("keepScreenOn", s.keepScreenOn)
        putBoolean("autoUpdateCheck", s.autoUpdateCheck)
    }
}
