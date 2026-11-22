package com.jum3a.livetranslate.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jum3a.livetranslate.BuildConfig
import com.jum3a.livetranslate.Graph
import com.jum3a.livetranslate.R
import com.jum3a.livetranslate.audio.AudioCapture
import com.jum3a.livetranslate.audio.MicTest
import com.jum3a.livetranslate.settings.AppSettings
import com.jum3a.livetranslate.settings.Defaults
import com.jum3a.livetranslate.settings.MicSource
import com.jum3a.livetranslate.settings.Pace
import com.jum3a.livetranslate.settings.SettingsStore
import com.jum3a.livetranslate.settings.TalkType
import com.jum3a.livetranslate.update.UpdateState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private sealed interface MicTestUi {
    data object Idle : MicTestUi
    data class Running(val index: Int, val total: Int, val source: MicSource) : MicTestUi
    data class Done(val results: List<MicTest.Result>, val best: MicSource?) : MicTestUi
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val store = Graph.settings
    val s by store.state.collectAsStateWithLifecycle()
    val engineState by Graph.engine.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    var showAdvanced by remember { mutableStateOf(false) }

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
                    "Changes apply the next time you press Start.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.tertiary,
                )
            }

            KeySection(s, store)

            // ------------------------------------------------------------ Talk
            Section("What are you listening to?")
            TalkType.entries.forEach { t ->
                RadioOption(s.talkType == t, t.title, t.detail) { store.update { it.copy(talkType = t) } }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = s.notes,
                onValueChange = { v -> store.update { it.copy(notes = v.take(300)) } },
                label = { Text("Names or words to expect (optional)") },
                placeholder = { Text("e.g. Ustaz Ahmad, Surah Al-Kahf, zakat") },
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Hint("Helps the AI spell names and understand the subject.")

            SubTitle("Spoken language")
            RadioOption(s.language == "ms", "Malay (recommended)", "Most accurate when the talk is in Malay.") {
                store.update { it.copy(language = "ms") }
            }
            RadioOption(s.language == "auto", "Malay mixed with English", "Detects the language of each part. Use it if the speaker switches a lot.") {
                store.update { it.copy(language = "auto") }
            }

            SubTitle("Speed")
            Pace.entries.forEach { p ->
                RadioOption(s.pace == p, p.title, p.detail) { store.update { it.copy(pace = p) } }
            }

            MicrophoneSection(s, store, engineState.running)
            DisplaySection(s, store)
            UpdatesSection(s, store)

            // ------------------------------------------------------------ Advanced
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = colors.outlineVariant)
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(if (showAdvanced) "Hide advanced settings" else "Show advanced settings")
            }
            if (showAdvanced) AdvancedSection(s, store)
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun KeySection(s: AppSettings, store: SettingsStore) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Section("Free AI service (Groq)")
    Hint("Speech recognition and translation run on Groq's free AI. You need your own free key; it stays on this phone.")
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
                        val missing = (Defaults.ASR_MODELS + cur.modelList()).filter { it !in available }
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
        Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("✓")) Mint else MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(6.dp))
    Hint("How: open console.groq.com → sign in (Google works) → API Keys → Create API Key → copy and paste it above.")
}

@Composable
private fun MicrophoneSection(s: AppSettings, store: SettingsStore, running: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    var test by remember { mutableStateOf<MicTestUi>(MicTestUi.Idle) }
    var showModes by remember { mutableStateOf(s.micSource != MicSource.AUTO) }

    // Keep the device list current as microphones are plugged in or removed.
    var devices by remember { mutableStateOf(AudioCapture.inputDevices(context)) }
    DisposableEffect(Unit) {
        val stop = AudioCapture.watchDevices(context) { devices = AudioCapture.inputDevices(context) }
        onDispose { stop() }
    }

    fun runTest() {
        scope.launch {
            val cur = store.value
            val results = MicTest.run(context, cur.inputDeviceId, cur.phoneProcessing) { i, total, src ->
                test = MicTestUi.Running(i, total, src)
            }
            val best = MicTest.best(results)
            if (best != null) store.update { it.copy(testedSource = best.source, micSource = MicSource.AUTO) }
            test = MicTestUi.Done(results, best?.source)
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runTest()
    }

    Section("Microphone")
    Hint("Sensitivity and volume adjust by themselves to the room and the speaker. If one microphone mode gives no sound, the app switches to another.")

    Spacer(Modifier.height(12.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Find the best microphone", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Hint("Do this once at the talk, while the speaker is talking. The app listens with each microphone mode for a few seconds and keeps the clearest one.")
            Spacer(Modifier.height(10.dp))
            when (val t = test) {
                is MicTestUi.Running -> {
                    Text("Listening with ${t.source.title.lowercase()}… (${t.index + 1} of ${t.total})", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { (t.index + 0.5f) / t.total }, modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    if (t is MicTestUi.Done) {
                        MicTestResults(t)
                        Spacer(Modifier.height(8.dp))
                    }
                    Button(
                        enabled = !running,
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            if (granted) runTest() else permission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                    ) { Text(if (t is MicTestUi.Done) "Test again" else "Start the test (about 15 s)") }
                    if (running) Hint("Stop translating first.")
                }
            }
            if (test !is MicTestUi.Running && s.testedSource != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Hint("In use: ${s.testedSource.title.lowercase()} (from the test)", Modifier.weight(1f))
                    TextButton(onClick = { store.update { it.copy(testedSource = null) } }) { Text("Forget") }
                }
            }
        }
    }

    SubTitle("Microphone to use")
    val selectedDevice = s.inputDeviceId.takeIf { id -> devices.any { it.first == id } } ?: 0
    RadioOption(selectedDevice == 0, "Automatic", "A USB microphone is used as soon as it's plugged in; otherwise the phone's own mic.") {
        store.update { it.copy(inputDeviceId = 0) }
    }
    devices.forEach { (id, name) ->
        RadioOption(selectedDevice == id, name, null) { store.update { it.copy(inputDeviceId = id) } }
    }
    Hint("Tip: a small clip-on microphone (USB-C or with a USB-C receiver) placed near the loudspeaker gives the clearest result. Bluetooth headsets aren't listed: their mic hears you, not the speaker.")

    TextButton(onClick = { showModes = !showModes }) {
        Text(if (showModes) "Hide microphone modes" else "Choose the microphone mode yourself")
    }
    if (showModes) {
        val rawSupported = remember { AudioCapture.unprocessedSupported(context) }
        MicSource.entries.forEach { src ->
            val note = if (src == MicSource.UNPROCESSED && !rawSupported) " This phone may not support it." else ""
            RadioOption(s.micSource == src, src.title, src.detail + note) { store.update { it.copy(micSource = src) } }
        }
    }
}

@Composable
private fun MicTestResults(t: MicTestUi.Done) {
    val colors = MaterialTheme.colorScheme
    if (t.best == null) {
        Text(
            "Couldn't hear clear speech. Try again while the speaker is talking, with the phone pointed at the loudspeaker.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.error,
        )
    } else {
        Text("✓ Using ${t.best.title.lowercase()}", style = MaterialTheme.typography.bodyMedium, color = Mint, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(6.dp))
    val top = t.results.filter { it.works }.maxOfOrNull { it.score } ?: 1f
    t.results.forEach { r ->
        val detail = when {
            !r.works -> "no sound on this phone"
            r.clipped -> "clarity ${r.clarityDb.roundToInt()} dB, distorting"
            else -> "clarity ${r.clarityDb.roundToInt()} dB"
        }
        val mark = if (r.source == t.best) "★ " else "• "
        Text(
            "$mark${r.source.title}: $detail",
            style = MaterialTheme.typography.bodySmall,
            color = if (r.source == t.best || (r.works && r.score >= top)) colors.onSurface else colors.onSurfaceVariant,
        )
    }
}

@SuppressLint("BatteryLife") // Sideloaded app: asking to run unrestricted is the only way some phones keep it alive.
@Composable
private fun DisplaySection(s: AppSettings, store: SettingsStore) {
    val context = LocalContext.current
    val pm = remember { context.getSystemService(PowerManager::class.java) }
    var unrestricted by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleResumeEffect(Unit) {
        unrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }

    Section("Display")
    SliderSetting(
        title = "Text size",
        valueLabel = "${(s.fontScale * 100).roundToInt()}%",
        value = s.fontScale,
        range = 0.8f..1.8f,
    ) { v -> store.update { it.copy(fontScale = v) } }
    Text(
        "Let us fear Allah and obey His commands.",
        fontSize = (19 * s.fontScale).sp,
        lineHeight = (27 * s.fontScale).sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SwitchSetting("Show the Malay text", "Shown smaller under each English line.", s.showMalay) { v ->
        store.update { it.copy(showMalay = v) }
    }
    SwitchSetting("Keep screen on while translating", null, s.keepScreenOn) { v ->
        store.update { it.copy(keepScreenOn = v) }
    }
    if (!unrestricted) {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep translating with the screen off", style = MaterialTheme.typography.bodyLarge)
                Hint("Some phones (Samsung, Xiaomi, Oppo, Huawei…) stop apps in the background to save battery. Allow this app to keep running.")
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = {
                val pkg = Uri.parse("package:${context.packageName}")
                try {
                    context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
                } catch (_: ActivityNotFoundException) {
                    runCatching { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
            }) { Text("Allow") }
        }
    }
}

@Composable
private fun UpdatesSection(s: AppSettings, store: SettingsStore) {
    val update by Graph.updates.state.collectAsStateWithLifecycle()
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
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
    SwitchSetting("Check for updates when the app opens", null, s.autoUpdateCheck) { v ->
        store.update { it.copy(autoUpdateCheck = v) }
    }
}

@Composable
private fun AdvancedSection(s: AppSettings, store: SettingsStore) {
    var confirmReset by remember { mutableStateOf(false) }

    SwitchSetting(
        "Automatic sensitivity",
        "Learns the room's background noise and the speaker's voice, and sets the speech threshold between them.",
        s.autoSensitivity,
    ) { v -> store.update { it.copy(autoSensitivity = v) } }
    if (!s.autoSensitivity) {
        SliderSetting(
            title = "Speech sensitivity",
            valueLabel = "${(s.sensitivity * 100).roundToInt()}%",
            value = s.sensitivity,
            range = 0f..1f,
            description = "Higher catches softer voices; lower ignores more background noise.",
        ) { v -> store.update { it.copy(sensitivity = v) } }
    }
    SwitchSetting(
        "Phone's own voice processing",
        "The phone's gain control and noise reduction. Usually best off: they can muffle distant loudspeaker speech.",
        s.phoneProcessing,
    ) { v -> store.update { it.copy(phoneProcessing = v) } }

    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = s.translationModels,
        onValueChange = { v -> store.update { it.copy(translationModels = v) } },
        label = { Text("Translation models, in order of preference") },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    Hint("If a model hits its free limit or is retired, the next one is used automatically. Speech recognition uses ${Defaults.ASR_MODELS.joinToString(" then ")}.")
    TextButton(onClick = { store.update { it.copy(translationModels = Defaults.TRANSLATION_MODELS) } }) {
        Text("Reset model list")
    }

    Spacer(Modifier.height(8.dp))
    if (confirmReset) {
        Text("Reset every setting to its default? Your API key is kept.", style = MaterialTheme.typography.bodyMedium)
        Row {
            TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            TextButton(onClick = { store.reset(); confirmReset = false }) { Text("Reset") }
        }
    } else {
        OutlinedButton(onClick = { confirmReset = true }) { Text("Reset all settings") }
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
    Spacer(Modifier.height(14.dp))
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
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
