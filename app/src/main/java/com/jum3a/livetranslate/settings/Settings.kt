package com.jum3a.livetranslate.settings

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MicSource(val title: String, val short: String, val detail: String) {
    AUTO(
        "Automatic (recommended)", "Auto",
        "Uses the mode that works best on this phone, and switches by itself if one gives no sound.",
    ),
    UNPROCESSED("Raw microphone", "Raw mic", "Full microphone range with no phone filtering."),
    VOICE_RECOGNITION("Speech mode", "Speech mic", "Lightly cleaned signal tuned for speech-to-text."),
    CAMCORDER("Far-field mode", "Far-field mic", "Tuned for distant sound; often uses the back microphone."),
    MIC("Standard mode", "Standard mic", "The phone's default microphone processing."),
}

/** What kind of talk it is: sets the context the AI gets for recognition and translation. */
enum class TalkType(val title: String, val detail: String, val topic: String, val hint: String) {
    KHUTBAH(
        "Khutbah", "Jumaat sermon at the mosque",
        "Friday khutbah (sermon) at a mosque in Malaysia, delivered in Malay over a loudspeaker. " +
            "It includes Arabic Quran verses, hadith and du'a.",
        "Khutbah Jumaat. Sidang Jumaat yang dirahmati Allah sekalian, marilah kita bertakwa kepada Allah SWT. " +
            "Sabda Rasulullah SAW.",
    ),
    KULIAH(
        "Kuliah", "Kuliah, ceramah or tazkirah",
        "Islamic religious talk (kuliah, ceramah or tazkirah) in Malay at a mosque or surau in Malaysia. " +
            "The speaker may quote the Quran and hadith in Arabic and tell stories.",
        "Kuliah agama. Tuan-tuan dan puan-puan yang dirahmati Allah. Firman Allah SWT, sabda Nabi SAW, insya-Allah.",
    ),
    UNIVERSITY(
        "Lecture", "Academic class taught in Malay",
        "University lecture in Malaysia taught in Malay. Technical and academic terms may be said in English.",
        "Kuliah universiti. Baiklah pelajar sekalian, hari ini kita akan belajar tentang",
    ),
    GENERAL(
        "Other", "Speech, briefing or event in Malay",
        "A talk, speech or meeting in Malay in Malaysia.",
        "Assalamualaikum dan salam sejahtera. Tuan-tuan dan puan-puan, saya ingin menyentuh tentang",
    ),
}

/** Trade-off between how fast English appears and how much context the AI gets. */
enum class Pace(val title: String, val detail: String, val minSegMs: Int, val maxSegMs: Int) {
    FAST("Fast", "English appears sooner, in smaller pieces. Uses more of the free quota.", 6_000, 12_000),
    BALANCED("Balanced", "Good translations with a short delay.", 9_000, 16_000),
    ACCURATE("Accurate", "Longer pieces give the AI more context; English comes a few seconds later.", 13_000, 24_000),
}

/** The language(s) the Malay speech is translated into. */
enum class TargetLang(val title: String, val english: Boolean, val arabic: Boolean) {
    ENGLISH("English", english = true, arabic = false),
    ARABIC("العربية", english = false, arabic = true),
    BOTH("Both", english = true, arabic = true),
}

object Defaults {
    /** The speech is always Malay. */
    const val SOURCE_LANGUAGE = "ms"

    /** Tried in order; each has its own free quota on Groq, so the second extends the daily limit. */
    val ASR_MODELS = listOf("whisper-large-v3", "whisper-large-v3-turbo")

    /** Translation: strongest first. Each has its own free daily token quota, so the chain also extends it. */
    const val TRANSLATION_MODELS = "openai/gpt-oss-120b, openai/gpt-oss-20b, qwen/qwen3.8-27b, llama-3.3-70b-versatile"

    /** The previous default, replaced automatically if the user never changed it. */
    const val OLD_TRANSLATION_MODELS = "openai/gpt-oss-120b, openai/gpt-oss-20b, llama-3.3-70b-versatile"

    /** Small, fast models for keeping the running summary of the talk. */
    val SUMMARY_MODELS = listOf("llama-3.1-8b-instant", "openai/gpt-oss-20b")
}

data class AppSettings(
    val apiKey: String = "",
    val talkType: TalkType = TalkType.KHUTBAH,
    /** Optional names or words to expect (speaker, place, subject). Helps recognition and translation. */
    val notes: String = "",
    val target: TargetLang = TargetLang.ENGLISH,
    val pace: Pace = Pace.BALANCED,
    val micSource: MicSource = MicSource.AUTO,
    /** Winner of "Find the best microphone"; used first by [MicSource.AUTO]. */
    val testedSource: MicSource? = null,
    /** 0 = automatic: an external USB mic if one is plugged in, otherwise the phone's mic. */
    val inputDeviceId: Int = 0,
    val autoSensitivity: Boolean = true,
    /** Manual sensitivity, 0..1, used only when [autoSensitivity] is off. */
    val sensitivity: Float = 0.65f,
    /** The phone's own gain control and noise reduction. Off: the app does its own, cleaner processing. */
    val phoneProcessing: Boolean = false,
    val translationModels: String = Defaults.TRANSLATION_MODELS,
    val fontScale: Float = 1.15f,
    val showMalay: Boolean = true,
    val keepScreenOn: Boolean = true,
    val autoUpdateCheck: Boolean = true,
) {
    fun modelList(): List<String> =
        translationModels.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** Context for the translator. */
    val topic: String
        get() = talkType.topic + notes.trim().takeIf { it.isNotEmpty() }?.let { " Notes from the listener: $it" }.orEmpty()

    /** Vocabulary hint for Whisper. */
    val asrHint: String
        get() = (talkType.hint + " " + notes.trim()).trim()
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

    /** Back to defaults, keeping the API key. */
    fun reset() = update { AppSettings(apiKey = it.apiKey) }

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E): E =
        prefs.getString(key, null)?.let { name -> runCatching { enumValueOf<E>(name) }.getOrNull() } ?: default

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            apiKey = prefs.getString("apiKey", d.apiKey) ?: d.apiKey,
            talkType = enumPref("talkType", d.talkType),
            notes = prefs.getString("notes", d.notes) ?: d.notes,
            target = enumPref("target", d.target),
            pace = enumPref("pace", d.pace),
            micSource = enumPref("micSource", d.micSource),
            testedSource = prefs.getString("testedSource", null)
                ?.let { runCatching { MicSource.valueOf(it) }.getOrNull() },
            inputDeviceId = prefs.getInt("inputDeviceId", d.inputDeviceId),
            autoSensitivity = prefs.getBoolean("autoSensitivity", d.autoSensitivity),
            sensitivity = prefs.getFloat("sensitivity", d.sensitivity),
            phoneProcessing = prefs.getBoolean("phoneProcessing", d.phoneProcessing),
            translationModels = (prefs.getString("translationModels", d.translationModels) ?: d.translationModels)
                .let { if (it == Defaults.OLD_TRANSLATION_MODELS) Defaults.TRANSLATION_MODELS else it },
            fontScale = prefs.getFloat("fontScale", d.fontScale),
            showMalay = prefs.getBoolean("showMalay", d.showMalay),
            keepScreenOn = prefs.getBoolean("keepScreenOn", d.keepScreenOn),
            autoUpdateCheck = prefs.getBoolean("autoUpdateCheck", d.autoUpdateCheck),
        )
    }

    private fun save(s: AppSettings) = prefs.edit {
        putString("apiKey", s.apiKey)
        putString("talkType", s.talkType.name)
        putString("notes", s.notes)
        remove("language") // the speech is always Malay now
        putString("target", s.target.name)
        putString("pace", s.pace.name)
        putString("micSource", s.micSource.name)
        if (s.testedSource != null) putString("testedSource", s.testedSource.name) else remove("testedSource")
        putInt("inputDeviceId", s.inputDeviceId)
        putBoolean("autoSensitivity", s.autoSensitivity)
        putFloat("sensitivity", s.sensitivity)
        putBoolean("phoneProcessing", s.phoneProcessing)
        putString("translationModels", s.translationModels)
        putFloat("fontScale", s.fontScale)
        putBoolean("showMalay", s.showMalay)
        putBoolean("keepScreenOn", s.keepScreenOn)
        putBoolean("autoUpdateCheck", s.autoUpdateCheck)
    }
}
