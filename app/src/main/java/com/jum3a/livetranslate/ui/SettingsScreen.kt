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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.jum3a.livetranslate.settings.TargetLang
import com.jum3a.livetranslate.update.UpdateState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private sealed interface MicTestUi {
    data object Idle : MicTestUi
    data class Running(val index: Int, val total: Int, val source: MicSource) : MicTestUi
    data class Done(val results: List<MicTest.Result>, val best: MicSource?) : MicTestUi
}

private val MicSource.chip: String get() = if (this == MicSource.AUTO) "Auto" else short.removeSuffix(" mic")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val store = Graph.settings
    val s by store.state.collectAsStateWithLifecycle()
    val engineState by Graph.engine.state.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

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
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .starPattern(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                KeyCard(s, store)
                TalkCard(s, store)
                MicrophoneCard(s, store, engineState.running)
                DisplayCard(s, store)
                UpdatesCard(s, store)
                AdvancedCard(s, store)
                Footer()
            }
        }
    }
}

// ---------------------------------------------------------------------------- sections

@Composable
private fun KeyCard(s: AppSettings, store: SettingsStore) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    SettingsCard("Groq key") {
        OutlinedTextField(
            value = s.apiKey,
            onValueChange = { v -> store.update { it.copy(apiKey = v.trim()) } },
            placeholder = { Text("gsk_…") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors(),
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { uriHandler.openUri("https://console.groq.com/keys") }, border = goldBorder()) {
                Text("Get a key")
            }
            Button(
                enabled = s.apiKey.isNotBlank() && !testing,
                onClick = {
                    testing = true
                    result = null
                    scope.launch {
                        val cur = store.value
                        result = try {
                            val available = Graph.groq.listModels(cur.apiKey).toSet()
                            val missing = (Defaults.ASR_MODELS + cur.modelList()).count { it !in available }
                            true to if (missing == 0) "Key works" else "Key works · $missing model${if (missing > 1) "s" else ""} unavailable"
                        } catch (e: Exception) {
                            false to (e.message ?: "Test failed")
                        }
                        testing = false
                    }
                },
            ) { Text(if (testing) "Testing…" else "Test") }
            result?.let { (ok, msg) ->
                Text(
                    (if (ok) "✓ " else "✗ ") + msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) Mint else MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TalkCard(s: AppSettings, store: SettingsStore) {
    SettingsCard("The talk") {
        val types = TalkType.entries
        for (row in types.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    ChoiceTile(s.talkType == t, t.title, Modifier.weight(1f)) { store.update { it.copy(talkType = t) } }
                }
            }
        }
        OutlinedTextField(
            value = s.notes,
            onValueChange = { v -> store.update { it.copy(notes = v.take(300)) } },
            placeholder = { Text("Names or words (optional)") },
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors(),
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        FieldLabel("Translate to")
        Segmented(TargetLang.entries.map { it to it.title }, s.target) { v -> store.update { it.copy(target = v) } }
        FieldLabel("Speed")
        Segmented(Pace.entries.map { it to it.title }, s.pace) { v -> store.update { it.copy(pace = v) } }
    }
}

@Composable
private fun MicrophoneCard(s: AppSettings, store: SettingsStore, running: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var test by remember { mutableStateOf<MicTestUi>(MicTestUi.Idle) }

    var devices by remember { mutableStateOf(AudioCapture.inputDevices(context)) }
    DisposableEffect(Unit) {
        val stop = AudioCapture.watchDevices(context) { devices = AudioCapture.inputDevices(context) }
        onDispose { stop() }
    }
    val rawSupported = remember { AudioCapture.unprocessedSupported(context) }

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

    SettingsCard("Microphone") {
        when (val t = test) {
            is MicTestUi.Running -> {
                Text("Listening · ${t.source.chip}  ${t.index + 1}/${t.total}", style = MaterialTheme.typography.bodyMedium, color = GoldLight)
                LinearProgressIndicator(
                    progress = { (t.index + 0.5f) / t.total },
                    color = Gold,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                )
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = !running,
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            if (granted) runTest() else permission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                    ) { Text("Find best mic") }
                    val status = when {
                        t is MicTestUi.Done && t.best == null -> "No clear speech" to MaterialTheme.colorScheme.error
                        s.testedSource != null -> "✓ ${s.testedSource.chip}" to Mint
                        else -> null
                    }
                    status?.let { (text, color) ->
                        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
                        if (s.testedSource != null && !(t is MicTestUi.Done && t.best == null)) {
                            TextButton(onClick = { store.update { it.copy(testedSource = null) } }) { Text("Reset") }
                        }
                    }
                }
                if (t is MicTestUi.Done) MicTestBars(t)
            }
        }

        FieldLabel("Input")
        val selectedDevice = s.inputDeviceId.takeIf { id -> devices.any { it.first == id } } ?: 0
        ChipChoices(listOf(0 to "Auto") + devices, selectedDevice) { id -> store.update { it.copy(inputDeviceId = id) } }

        FieldLabel("Mode")
        val modes = MicSource.entries.filter { it != MicSource.UNPROCESSED || rawSupported }
        ChipChoices(modes.map { it to it.chip }, s.micSource) { m -> store.update { it.copy(micSource = m) } }
    }
}

@Composable
private fun MicTestBars(t: MicTestUi.Done) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        t.results.forEach { r ->
            val best = r.source == t.best
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.source.chip,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (best) GoldLight else colors.onSurfaceVariant,
                    fontWeight = if (best) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.width(76.dp),
                )
                LinearProgressIndicator(
                    progress = { if (r.works) (r.clarityDb / 35f).coerceIn(0.02f, 1f) else 0f },
                    color = if (best) Gold else colors.outline,
                    trackColor = colors.surfaceVariant,
                    drawStopIndicator = {},
                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
                Text(
                    when {
                        !r.works -> "—"
                        r.clipped -> "${r.clarityDb.roundToInt()} dB ⚠"
                        else -> "${r.clarityDb.roundToInt()} dB"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(56.dp),
                )
            }
        }
    }
}

@SuppressLint("BatteryLife") // Sideloaded app: asking to run unrestricted is the only way some phones keep it alive.
@Composable
private fun DisplayCard(s: AppSettings, store: SettingsStore) {
    val context = LocalContext.current
    val pm = remember { context.getSystemService(PowerManager::class.java) }
    var unrestricted by remember { mutableStateOf(pm.isIgnoringBatteryOptimizations(context.packageName)) }
    LifecycleResumeEffect(Unit) {
        unrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }

    SettingsCard("Display") {
        Text(
            "Let us fear Allah and obey His commands.",
            fontSize = (19 * s.fontScale).sp,
            lineHeight = (27 * s.fontScale).sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("A", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = s.fontScale.coerceIn(0.8f, 1.8f),
                onValueChange = { v -> store.update { it.copy(fontScale = v) } },
                valueRange = 0.8f..1.8f,
                colors = goldSlider(),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text("A", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ToggleRow("Show Malay text", s.showMalay) { v -> store.update { it.copy(showMalay = v) } }
        ToggleRow("Keep screen on", s.keepScreenOn) { v -> store.update { it.copy(keepScreenOn = v) } }
        if (!unrestricted) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Run with screen off", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                OutlinedButton(
                    border = goldBorder(),
                    onClick = {
                        val pkg = Uri.parse("package:${context.packageName}")
                        try {
                            context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
                        } catch (_: ActivityNotFoundException) {
                            runCatching { context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                        }
                    },
                ) { Text("Allow") }
            }
        }
    }
}

@Composable
private fun UpdatesCard(s: AppSettings, store: SettingsStore) {
    val update by Graph.updates.state.collectAsStateWithLifecycle()
    SettingsCard("Updates") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Build ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.bodyLarge)
                val label = when (val u = update) {
                    is UpdateState.Checking -> "Checking…"
                    is UpdateState.UpToDate -> "Up to date"
                    is UpdateState.Available -> "Build ${u.release.build} available"
                    is UpdateState.Downloading -> "Downloading ${(u.progress * 100).roundToInt()}%"
                    is UpdateState.Ready -> "Ready to install"
                    is UpdateState.Failed -> u.message
                    UpdateState.Idle -> null
                }
                label?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
            OutlinedButton(
                onClick = { Graph.updates.check(userInitiated = true) },
                enabled = update !is UpdateState.Checking && update !is UpdateState.Downloading,
                border = goldBorder(),
            ) { Text("Check") }
        }
        ToggleRow("Check when app opens", s.autoUpdateCheck) { v -> store.update { it.copy(autoUpdateCheck = v) } }
    }
}

@Composable
private fun AdvancedCard(s: AppSettings, store: SettingsStore) {
    var open by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val arrow by animateFloatAsState(if (open) 180f else 0f, label = "arrow")

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { open = !open }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OrnamentHeading("Advanced", Modifier.weight(1f))
            Icon(
                painterResource(R.drawable.ic_expand),
                contentDescription = if (open) "Collapse" else "Expand",
                tint = Gold,
                modifier = Modifier.padding(start = 8.dp).rotate(arrow),
            )
        }
        AnimatedVisibility(open) {
            CardBody(Modifier.padding(top = 10.dp)) {
                ToggleRow("Auto sensitivity", s.autoSensitivity) { v -> store.update { it.copy(autoSensitivity = v) } }
                if (!s.autoSensitivity) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(
                            value = s.sensitivity,
                            onValueChange = { v -> store.update { it.copy(sensitivity = v) } },
                            colors = goldSlider(),
                            modifier = Modifier.weight(1f),
                        )
                        Text("${(s.sensitivity * 100).roundToInt()}%", color = GoldLight, modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
                    }
                }
                ToggleRow("Phone voice processing", s.phoneProcessing) { v -> store.update { it.copy(phoneProcessing = v) } }
                FieldLabel("Translation models")
                OutlinedTextField(
                    value = s.translationModels,
                    onValueChange = { v -> store.update { it.copy(translationModels = v) } },
                    shape = RoundedCornerShape(14.dp),
                    colors = fieldColors(),
                    minLines = 2,
                    textStyle = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { store.update { it.copy(translationModels = Defaults.TRANSLATION_MODELS) } }) {
                        Text("Reset models")
                    }
                    Spacer(Modifier.weight(1f))
                    if (confirmReset) {
                        TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
                        Button(onClick = { store.reset(); confirmReset = false }) { Text("Reset all") }
                    } else {
                        OutlinedButton(onClick = { confirmReset = true }, border = goldBorder()) { Text("Reset all") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Footer() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            "Jum3a Translate · v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

// ---------------------------------------------------------------------------- building blocks

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        OrnamentHeading(title, Modifier.padding(start = 2.dp, bottom = 10.dp))
        CardBody(content = content)
    }
}

@Composable
private fun CardBody(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, Gold.copy(alpha = 0.16f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ChoiceTile(selected: Boolean, label: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(if (selected) Gold.copy(alpha = 0.14f) else colors.surfaceContainerHigh)
            .border(1.dp, if (selected) Gold.copy(alpha = 0.8f) else colors.outlineVariant, shape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                StarGlyph(9.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) GoldLight else colors.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val colors = MaterialTheme.colorScheme
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Gold.copy(alpha = 0.16f),
                    activeContentColor = GoldLight,
                    activeBorderColor = Gold.copy(alpha = 0.75f),
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = colors.onSurfaceVariant,
                    inactiveBorderColor = colors.outlineVariant,
                ),
                icon = {},
            ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipChoices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val colors = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (value, label) ->
            val sel = value == selected
            FilterChip(
                selected = sel,
                onClick = { onSelect(value) },
                label = { Text(label) },
                leadingIcon = if (sel) ({ StarGlyph(8.dp) }) else null,
                shape = RoundedCornerShape(12.dp),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent,
                    labelColor = colors.onSurfaceVariant,
                    selectedContainerColor = Gold.copy(alpha = 0.16f),
                    selectedLabelColor = GoldLight,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = sel,
                    borderColor = colors.outlineVariant,
                    selectedBorderColor = Gold.copy(alpha = 0.75f),
                ),
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF2B2003),
                checkedTrackColor = Gold,
                checkedBorderColor = Gold,
            ),
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Gold,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    cursorColor = Gold,
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
)

@Composable
private fun goldSlider() = SliderDefaults.colors(
    thumbColor = Gold,
    activeTrackColor = Gold,
    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
)

private fun goldBorder() = BorderStroke(1.dp, Gold.copy(alpha = 0.6f))
