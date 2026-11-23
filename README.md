# Jum3a Translate

Live **Malay → English** translation for Android, built for sitting in a Friday khutbah or a Malay lecture
and following along in English.

- **Tunes itself to the room:** learns the background noise and the speaker's voice level and puts the
  speech threshold between them, boosts soft voices up to +40 dB with a soft limiter, and sends longer
  chunks automatically if recognition falls behind. Nothing to adjust by hand.
- **Works on any phone and mic:** records the raw microphone signal with phone-side processing off
  when the phone allows it; falls back through other microphone modes and sample rates (converting
  to 16 kHz) when it doesn't; switches mode by itself if one gives no sound; uses a plugged-in USB mic
  automatically; warns when the sound distorts or another app takes the mic.
  *Find the best microphone* compares every mode on your phone during the talk and keeps the clearest.
- **Accurate Malay recognition:** OpenAI Whisper large-v3 via Groq's free API (falling back to
  large-v3-turbo when the free limit is hit), with a vocabulary hint for the type of talk and the
  previous sentence as context, plus a filter for Whisper's typical silence "hallucinations".
- **Interprets like a person, into English, Arabic or both:** an LLM (GPT-OSS 120B on Groq, with
  automatic fallback to other free models) first repairs the speech-recognition errors, then
  translates only complete sentences, holding back a sentence that is cut off mid-way until its end
  arrives. It sees the last few lines and a running summary of the talk (topic, names, terms), so
  references and terminology stay consistent. Arabic is Modern Standard Arabic in khutbah style.
  Quran recitation is marked, never quoted from memory; the speaker's explanation is translated.
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
- In *Settings*, pick what you're listening to (khutbah, Islamic talk, lecture…) and optionally add
  names or words to expect.
- At your first talk, run *Settings → Find the best microphone* while the speaker is talking.
- The status line shows *Sound: clear / OK / weak*. If it's weak, move closer to a loudspeaker or use
  a clip-on USB-C microphone.

## Free-tier limits (Groq)

Groq's published limits for each Whisper model are about 2,000 requests per day, 2 hours of audio
per hour and 8 hours per day, and every request counts as at least 10 seconds of audio. The
*Balanced* speed keeps chunks around 10 s so little quota is wasted. When the main Whisper model
hits its limit the app switches to the turbo model; translation models fall back the same way.
Check your own key's limits at console.groq.com → Settings → Limits.

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
  Because this folder is in OneDrive, which locks files mid-build, build output goes to
  `~/.gradle-builds/Jum3aTranslate/` instead of `app/build/`.
- CI runs the unit tests before building, so a failing change is never published to phones.

## Code map

| Path | What it does |
|---|---|
| `audio/AudioCapture.kt` | Opens the best mic mode/sample rate the phone supports, picks USB mics, watches plug/unplug |
| `audio/Resampler.kt` | Converts 48/44.1/32/22 kHz recordings to 16 kHz for Whisper |
| `audio/MicTest.kt` | "Find the best microphone": compares each mode's clarity |
| `audio/Segmenter.kt` | Self-tuning voice detection; cuts speech into chunks at natural pauses |
| `audio/AudioUtil.kt` | Loudness normalisation with soft limiter, WAV encoding |
| `ai/GroqClient.kt` | Groq API: Whisper transcription, chat completions, model list |
| `ai/AsrFilter.kt` | Drops silence hallucinations ("Terima kasih kerana menonton", …) |
| `ai/Translator.kt` | Interpreter: ASR repair, sentence hold-back, context + running summary, EN/AR, model fallback |
| `session/Engine.kt` | Pipeline: mic → chunks → transcription queue → translation queue → UI; mic recovery and hints |
| `session/TranslateService.kt` | Foreground service so it keeps listening with the screen off |
| `update/UpdateManager.kt` | Checks GitHub releases, downloads and installs updates |
| `ui/` | Jetpack Compose screens |
