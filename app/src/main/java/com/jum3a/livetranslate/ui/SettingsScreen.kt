package com.jum3a.livetranslate.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jum3a.livetranslate.BuildConfig
import com.jum3a.livetranslate.Graph
import com.jum3a.livetranslate.R
import com.jum3a.livetranslate.audio.AudioCapture
import com.jum3a.livetranslate.settings.Defaults
import com.jum3a.livetranslate.settings.MicSource
import com.jum3a.livetranslate.update.UpdateState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val UNI_TOPIC =
    "University lecture in Malaysia taught in Malay. Technical and academic terms may be said in English."
private const val UNI_HINT =
    "Kuliah universiti. Baiklah pelajar sekalian, hari ini kita akan belajar tentang"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val store = Graph.settings
    val s by store.state.collectAsStateWithLifecycle()
    val engineState by Graph.engine.state.collectAsStateWithLifecycle()
    val update by Graph.updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var showAdvanced by remember { mutableStateOf(false) }
    val devices = remember { AudioCapture.inputDevices(context) }
    val rawSupported = remember { AudioCapture.unprocessedSupported(context) }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            if (engineState.running) {
                Text(
                    "Microphone and timing changes apply the next time you press Start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.tertiary,
                )
            }

            // ------------------------------------------------------------ AI
            Section("Free AI service (Groq)")
            Hint("Speech recognition (Whisper) and translation run on Groq's free tier. You need your own free key — it stays on this phone.")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = s.apiKey,
                onValueChange = { v -> store.update { it.copy(apiKey = v.trim()) } },
                label = { Text("Groq API key") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { uriHandler.openUri("https://console.groq.com/keys") }) { Text("Get a free key") }
                Button(
                    enabled = s.apiKey.isNotBlank() && !testing,
                    onClick = {
                        testing = true
                        testResult = null
                        scope.launch {
                            val cur = store.value
                            testResult = try {
                                val available = Graph.groq.listModels(cur.apiKey).toSet()
                                val missing = (listOf(cur.asrModel) + cur.modelList()).filter { it !in available }
                                if (missing.isEmpty()) {
                                    "✓ Key works and all models are available."
                                } else {
                                    "✓ Key works. Not available right now: ${missing.joinToString()}. The app will use the others."
                                }
                            } catch (e: Exception) {
                                "✗ ${e.message ?: "Test failed"}"
                            }
                            testing = false
                        }
                    },
                ) { Text(if (testing) "Testing…" else "Test key") }
            }
            testResult?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("✓")) Mint else colors.error)
            }
            Spacer(Modifier.height(6.dp))
            Hint("How: open console.groq.com → sign in (Google works) → API Keys → Create API Key → copy and paste it above.")

            // ------------------------------------------------------------ Talk
            Section("The talk")
            OutlinedTextField(
                value = s.topic,
                onValueChange = { v -> store.update { it.copy(topic = v) } },
                label = { Text("What kind of talk is it?") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Row {
                TextButton(onClick = { store.update { it.copy(topic = Defaults.TOPIC, asrHint = Defaults.ASR_HINT) } }) {
                    Text("Khutbah preset")
                }
                TextButton(onClick = { store.update { it.copy(topic = UNI_TOPIC, asrHint = UNI_HINT) } }) {
                    Text("University lecture preset")
                }
            }

            SubTitle("Spoken language")
            RadioOption(s.language == "ms", "Malay (recommended)", "Most accurate for Malay speech.") {
                store.update { it.copy(language = "ms") }
            }
            RadioOption(s.language == "auto", "Auto-detect", "Try this if the speaker switches a lot between Malay, English and Arabic.") {
                store.update { it.copy(language = "auto") }
            }

            SubTitle("Speech recognition model")
            RadioOption(s.asrModel == "whisper-large-v3", "Whisper large v3", "Most accurate (recommended).") {
                store.update { it.copy(asrModel = "whisper-large-v3") }
            }
            RadioOption(s.asrModel == "whisper-large-v3-turbo", "Whisper large v3 turbo", "Slightly faster, slightly less accurate.") {
                store.update { it.copy(asrModel = "whisper-large-v3-turbo") }
            }

            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "Hide advanced AI settings" else "Show advanced AI settings")
            }
            if (showAdvanced) {
                OutlinedTextField(
                    value = s.asrHint,
                    onValueChange = { v -> store.update { it.copy(asrHint = v) } },
                    label = { Text("Malay words to expect (helps recognition)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = s.translationModels,
                    onValueChange = { v -> store.update { it.copy(translationModels = v) } },
                    label = { Text("Translation models, in order of preference") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("If one model hits its free limit or is retired, the next one is used automatically.")
                TextButton(onClick = { store.update { it.copy(translationModels = Defaults.TRANSLATION_MODELS) } }) {
                    Text("Reset model list")
                }
            }

            // ------------------------------------------------------------ Microphone
            Section("Microphone")
            Hint("Watch the level bar on the main screen while someone speaks: the bar should pass the yellow tick when they talk.")
            SubTitle("Microphone mode")
            MicSource.entries.forEach { src ->
                val note = if (src == MicSource.UNPROCESSED && !rawSupported) " (this phone may not support it)" else ""
                RadioOption(s.micSource == src, src.title, src.detail + note) {
                    store.update { it.copy(micSource = src) }
                }
            }

            SubTitle("Input")
            RadioOption(s.inputDeviceId == 0, "Automatic", "Let Android choose (normally the phone's main mic).") {
                store.update { it.copy(inputDeviceId = 0) }
            }
            devices.forEach { (id, name) ->
                RadioOption(s.inputDeviceId == id, name, null) { store.update { it.copy(inputDeviceId = id) } }
            }

            SliderSetting(
                title = "Speech sensitivity",
                valueLabel = "${(s.sensitivity * 100).roundToInt()}%",
                value = s.sensitivity,
                range = 0f..1f,
                description = "Higher catches softer voices; lower ignores more background noise.",
            ) { v -> store.update { it.copy(sensitivity = v) } }

            SliderSetting(
                title = "Max boost for quiet voices",
                valueLabel = "+${s.maxBoostDb.roundToInt()} dB",
                value = s.maxBoostDb,
                range = 0f..40f,
                steps = 7,
                description = "How much the app may amplify a soft speaker before sending the audio to the AI.",
            ) { v -> store.update { it.copy(maxBoostDb = v) } }

            SwitchSetting(
                "Phone's automatic gain",
                "Usually best OFF — the app does its own, cleaner boosting.",
                s.phoneAgc,
            ) { v -> store.update { it.copy(phoneAgc = v) } }
            SwitchSetting(
                "Phone's noise suppression",
                "Can reduce fan or crowd noise, but may muffle distant loudspeaker speech.",
                s.noiseSuppression,
            ) { v -> store.update { it.copy(noiseSuppression = v) } }

            // ------------------------------------------------------------ Timing
            Section("Timing")
            SliderSetting(
                title = "Shortest chunk",
                valueLabel = "${s.minSegSec.roundToInt()} s",
                value = s.minSegSec,
                range = 3f..10f,
                steps = 6,
                description = "Shorter shows English sooner; longer gives the AI more context and uses less of the free quota.",
            ) { v -> store.update { it.copy(minSegSec = v, maxSegSec = maxOf(it.maxSegSec, v + 4f)) } }
            SliderSetting(
                title = "Longest chunk",
                valueLabel = "${s.maxSegSec.roundToInt()} s",
                value = s.maxSegSec,
                range = 8f..25f,
                steps = 16,
                description = "If the speaker never pauses, audio is cut at the quietest moment before this limit.",
            ) { v -> store.update { it.copy(maxSegSec = v, minSegSec = minOf(it.minSegSec, v - 4f).coerceAtLeast(3f)) } }

            // ------------------------------------------------------------ Display
            Section("Display")
            SliderSetting(
                title = "Text size",
                valueLabel = "${(s.fontScale * 100).roundToInt()}%",
                value = s.fontScale,
                range = 0.8f..1.8f,
            ) { v -> store.update { it.copy(fontScale = v) } }
            SwitchSetting("Show the Malay text", "Shown smaller under each English line.", s.showMalay) { v ->
                store.update { it.copy(showMalay = v) }
            }
            SwitchSetting("Keep screen on while translating", null, s.keepScreenOn) { v ->
                store.update { it.copy(keepScreenOn = v) }
            }

            // ------------------------------------------------------------ Updates
            Section("Updates")
            Text("Installed: build ${BuildConfig.VERSION_CODE} (v${BuildConfig.VERSION_NAME})", style = MaterialTheme.typography.bodyMedium)
            Hint("Updates come from github.com/${Graph.updates.repo}")
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { Graph.updates.check(userInitiated = true) },
                    enabled = update !is UpdateState.Checking && update !is UpdateState.Downloading,
                ) { Text("Check for updates") }
                Spacer(Modifier.width(12.dp))
                val label = when (val u = update) {
                    is UpdateState.Checking -> "Checking…"
                    is UpdateState.UpToDate -> "You're on the latest build."
                    is UpdateState.Available -> "Build ${u.release.build} available — see main screen."
                    is UpdateState.Downloading -> "Downloading… ${(u.progress * 100).roundToInt()}%"
                    is UpdateState.Ready -> "Ready to install — see main screen."
                    is UpdateState.Failed -> u.message
                    UpdateState.Idle -> ""
                }
                Text(label, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            SwitchSetting("Check for updates when the app opens", null, s.autoUpdateCheck) { v ->
                store.update { it.copy(autoUpdateCheck = v) }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(Modifier.height(14.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SubTitle(title: String) {
    Spacer(Modifier.height(10.dp))
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RadioOption(selected: Boolean, title: String, description: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Hint(description)
        }
    }
}

@Composable
private fun SwitchSetting(title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Hint(description)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SliderSetting(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    description: String? = null,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps)
        if (description != null) Hint(description)
    }
}
