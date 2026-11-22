# Jum3a Translate

Live **Malay → English** translation for Android, built for sitting in a Friday khutbah or a Malay lecture
and following along in English.

- **Hears far-away and soft speakers:** records the raw microphone signal at 16 kHz with phone-side
  processing switched off, tracks the room's noise floor so quiet voices still count as speech,
  and boosts each sentence up to +30 dB (adjustable) with a soft limiter instead of hard clipping.
- **Accurate Malay recognition:** OpenAI Whisper large-v3 via Groq's free API, with a vocabulary hint
  and the previous sentence as context, plus a filter for Whisper's typical silence "hallucinations".
- **Proper translation, not word-for-word:** an LLM (GPT-OSS 120B on Groq, with automatic fallback to
  other free models) translates each sentence using the previous ones as context, fixes
  mis-heard words, keeps Islamic terms recognisable and marks Arabic recitation.
- Works with the screen off (foreground service), keeps the screen on if you prefer, saves every
  transcript on the phone, and can share it as text.
- **Updates itself from GitHub**: every push to `main` builds a signed APK and publishes it as a
  release. The app shows *Update available → Update → Install*.

## One-time setup on the phone

1. Install the APK (allow "Install unknown apps" for your browser/file manager when asked).
2. Open the app → **Settings** → **Get a free key** → sign in at console.groq.com → *API Keys* →
   *Create API Key* → paste it into the app → **Test key**.
3. Back on the main screen, tap **Start translating** and allow microphone + notifications.

Tips for the mosque/lecture hall:
- Point the bottom of the phone (where the mic is) toward the nearest loudspeaker and keep it uncovered.
- The level bar should cross the yellow tick when the speaker talks. If it doesn't, raise
  *Speech sensitivity*; if background noise keeps triggering it, lower it.
- If the speaker is far away, try *Microphone mode → Far-field (camcorder)*.

## Free-tier limits (Groq)

Roughly 2,000 speech requests and 2 hours of audio per day, and per-model daily token limits for
translation. A 20–60 minute talk fits comfortably. If a translation model hits its limit, the app
switches to the next model in *Settings → Advanced AI settings*.

## Development loop

```
edit code → git push → GitHub Actions builds "build-N" → phone: Update → Install
```

- Workflow: `.github/workflows/release.yml`. Build number = `run_number + 1` (it becomes the
  Android versionCode, so newer builds always install over older ones).
- The APK is signed with the key in `keystore/` (git-ignored). GitHub holds a copy in the repo
  secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
  **Back up `keystore/` and `keystore.properties`** — if the key is lost, the phone can't install
  updates over the existing app (you'd have to uninstall first).
- Local build: `./gradlew assembleRelease` (uses Android Studio's bundled JDK as `JAVA_HOME`).

## Code map

| Path | What it does |
|---|---|
| `audio/AudioCapture.kt` | Opens the mic (raw/unprocessed if supported), disables phone AGC/NS/AEC by default |
| `audio/Segmenter.kt` | Adaptive voice detection; cuts speech into 6–15 s chunks at natural pauses |
| `audio/AudioUtil.kt` | Loudness normalisation with soft limiter, WAV encoding |
| `ai/GroqClient.kt` | Groq API: Whisper transcription, chat completions, model list |
| `ai/AsrFilter.kt` | Drops silence hallucinations ("Terima kasih kerana menonton", …) |
| `ai/Translator.kt` | Context-aware translation prompt, model fallback and rate-limit cooldown |
| `session/Engine.kt` | Pipeline: mic → chunks → transcription queue → translation queue → UI |
| `session/TranslateService.kt` | Foreground service so it keeps listening with the screen off |
| `update/UpdateManager.kt` | Checks GitHub releases, downloads and installs updates |
| `ui/` | Jetpack Compose screens |
