package com.jum3a.livetranslate.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jum3a.livetranslate.Graph
import com.jum3a.livetranslate.R
import com.jum3a.livetranslate.session.EngineState
import com.jum3a.livetranslate.session.Entry
import com.jum3a.livetranslate.session.EntryStatus
import com.jum3a.livetranslate.session.Meter
import com.jum3a.livetranslate.session.TranslateService
import com.jum3a.livetranslate.settings.AppSettings
import com.jum3a.livetranslate.update.UpdateState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val engine = Graph.engine
    val state by engine.state.collectAsStateWithLifecycle()
    val settings by Graph.settings.state.collectAsStateWithLifecycle()
    val update by Graph.updates.state.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    val view = LocalView.current
    DisposableEffect(state.running, settings.keepScreenOn) {
        view.keepScreenOn = state.running && settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        if (hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
            TranslateService.start(context)
        } else {
            Toast.makeText(context, "Microphone permission is needed to translate.", Toast.LENGTH_LONG).show()
        }
    }

    val onStartStop: () -> Unit = {
        when {
            state.running -> TranslateService.stop(context)
            settings.apiKey.isBlank() -> onOpenSettings()
            else -> {
                val needed = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }.filterNot { hasPermission(context, it) }
                if (needed.isEmpty()) TranslateService.start(context) else permissionLauncher.launch(needed.toTypedArray())
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Jum3a Translate")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { shareText(context, engine.exportText()) },
                        enabled = state.entries.isNotEmpty(),
                    ) { Icon(painterResource(R.drawable.ic_share), contentDescription = "Share transcript") }
                    IconButton(
                        onClick = { confirmClear = true },
                        enabled = state.entries.isNotEmpty(),
                    ) { Icon(painterResource(R.drawable.ic_delete), contentDescription = "Clear transcript") }
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { ControlPanel(state, onStartStop) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().starPattern()) {
            UpdateBanner(update)
            state.error?.let { MessageCard(it, onDismiss = engine::dismissError) }
            if (settings.apiKey.isBlank()) KeyMissingCard(onOpenSettings)
            TranscriptList(state, settings, Modifier.weight(1f))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the transcript?") },
            text = { Text("This removes it from the screen. A copy of each finished line is still saved on the phone.") },
            confirmButton = {
                TextButton(onClick = { engine.clear(); confirmClear = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TranscriptList(state: EngineState, settings: AppSettings, modifier: Modifier) {
    val entries = state.entries
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 2
        }
    }
    // Follow the newest line, unless the user has scrolled up to re-read something.
    val lastEnglish = entries.lastOrNull()?.english
    LaunchedEffect(entries.size, lastEnglish) {
        if (entries.isNotEmpty() && atBottom) listState.animateScrollToItem(entries.size)
    }

    Box(modifier.fillMaxWidth()) {
        if (entries.isEmpty() && !state.running) EmptyHint(Modifier.align(Alignment.Center))
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(entries, key = { it.id }) { EntryItem(it, settings) }
            item(key = "footer") {
                if (state.running) ListeningFooter() else Spacer(Modifier.height(1.dp))
            }
        }
        if (!atBottom && entries.isNotEmpty()) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.animateScrollToItem(entries.size) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ) { Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = "Jump to latest") }
        }
    }
}

@Composable
private fun EntryItem(e: Entry, settings: AppSettings) {
    val scale = settings.fontScale
    val colors = MaterialTheme.colorScheme
    val time = remember(e.time) { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(e.time)) }
    Column(Modifier.fillMaxWidth()) {
        Text(time, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant.copy(alpha = 0.55f))
        Spacer(Modifier.height(2.dp))
        when {
            e.english != null -> Text(
                e.english,
                fontSize = (19 * scale).sp,
                lineHeight = (27 * scale).sp,
                fontWeight = FontWeight.Medium,
                color = colors.onBackground,
            )
            e.status != EntryStatus.FAILED -> Text(
                if (e.malay == null) "Recognising speech…" else "Translating…",
                fontSize = (16 * scale).sp,
                fontStyle = FontStyle.Italic,
                color = colors.onSurfaceVariant,
            )
        }
        if (settings.showMalay && e.malay != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                e.malay,
                fontSize = (14 * scale).sp,
                lineHeight = (20 * scale).sp,
                color = colors.onSurfaceVariant,
            )
        }
        if (e.status == EntryStatus.FAILED) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.error ?: "Failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { Graph.engine.retry(e.id) }) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun ListeningFooter() {
    val meter by Graph.engine.meter.collectAsStateWithLifecycle()
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    val text = if (meter.capturingMs > 0) "● Hearing speech…" else "● Listening…"
    Text(
        text,
        modifier = Modifier.alpha(if (meter.capturingMs > 0) pulse else 0.6f),
        style = MaterialTheme.typography.bodyMedium,
        color = if (meter.capturingMs > 0) Mint else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ControlPanel(state: EngineState, onStartStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Brush.horizontalGradient(listOf(Color.Transparent, Gold.copy(alpha = 0.5f), Color.Transparent))),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (state.running) {
                val meter by Graph.engine.meter.collectAsStateWithLifecycle()
                LevelMeter(meter, Modifier.fillMaxWidth().height(10.dp))
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val pending = state.entries.count {
                        it.status == EntryStatus.QUEUED || it.status == EntryStatus.TRANSCRIBING ||
                            it.status == EntryStatus.TRANSLATING
                    }
                    val status = buildString {
                        append(if (meter.capturingMs > 0) "Hearing speech · ${meter.capturingMs / 1000}s" else "Listening…")
                        if (pending > 0) append(" · $pending processing")
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (meter.speaking) Mint else colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // How far the speaker's voice stands out from the room, once the app has learned it.
                    meter.voiceDb?.let { voice ->
                        val snr = voice - meter.noiseDb
                        val (label, color) = when {
                            snr >= 18f -> "Sound: clear" to Mint
                            snr >= 10f -> "Sound: OK" to colors.onSurfaceVariant
                            else -> "Sound: weak" to colors.tertiary
                        }
                        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
                    }
                }
                val info = buildList {
                    if (state.micInfo.isNotBlank()) add(state.micInfo)
                    state.lastBoostDb?.let { add("boost ${String.format(Locale.US, "%+.0f", it)} dB") }
                }.joinToString(" · ")
                if (info.isNotBlank()) {
                    Text(
                        info,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                state.micHint?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = colors.tertiary)
                }
                Spacer(Modifier.height(10.dp))
            }
            state.notice?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = colors.tertiary)
                Spacer(Modifier.height(8.dp))
            }
            Button(
                onClick = onStartStop,
                modifier = Modifier.fillMaxWidth().height(60.dp),
                shape = RoundedCornerShape(30.dp),
                colors = if (state.running) {
                    ButtonDefaults.buttonColors(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Icon(painterResource(if (state.running) R.drawable.ic_stop else R.drawable.ic_mic), contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(if (state.running) "Stop" else "Start translating", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Live input level (-70…0 dBFS). The yellow tick is the current speech-detection threshold. */
@Composable
private fun LevelMeter(meter: Meter, modifier: Modifier) {
    val level = ((meter.db + 70f) / 70f).coerceIn(0f, 1f)
    val threshold = ((meter.thresholdDb + 70f) / 70f).coerceIn(0f, 1f)
    val animated by animateFloatAsState(level, animationSpec = tween(80), label = "level")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = if (meter.speaking) Mint else MaterialTheme.colorScheme.outline
    val marker = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.clip(RoundedCornerShape(5.dp))) {
        drawRect(track)
        drawRect(fill, size = Size(size.width * animated, size.height))
        val x = size.width * threshold
        drawLine(marker, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
    }
}

@Composable
private fun UpdateBanner(update: UpdateState) {
    val context = LocalContext.current
    val mgr = Graph.updates
    when (update) {
        is UpdateState.Available -> BannerCard {
            Text("Update available — build ${update.release.build}", fontWeight = FontWeight.SemiBold)
            if (update.release.notes.isNotBlank()) {
                Text(
                    update.release.notes.lines().first().take(160),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = mgr::dismiss) { Text("Later") }
                Button(onClick = { mgr.download(update.release) }) { Text("Update") }
            }
        }
        is UpdateState.Downloading -> BannerCard {
            Text("Downloading build ${update.release.build}… ${(update.progress * 100).toInt()}%")
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { update.progress }, modifier = Modifier.fillMaxWidth())
        }
        is UpdateState.Ready -> BannerCard {
            Text("Build ${update.release.build} is ready to install", fontWeight = FontWeight.SemiBold)
            if (!context.packageManager.canRequestPackageInstalls()) {
                Text(
                    "First time only: Android will ask you to allow installs from Jum3a Translate. " +
                        "Turn it on, come back, and tap Install again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = mgr::dismiss) { Text("Later") }
                Button(onClick = { mgr.install(context) }) { Text("Install") }
            }
        }
        is UpdateState.Failed -> MessageCard(update.message, onDismiss = mgr::dismiss)
        else -> Unit
    }
}

@Composable
private fun KeyMissingCard(onOpenSettings: () -> Unit) {
    BannerCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Add your free Groq key", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Button(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}

@Composable
private fun BannerCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Gold.copy(alpha = 0.35f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) { Column(Modifier.padding(14.dp), content = content) }
}

@Composable
private fun MessageCard(text: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun EmptyHint(modifier: Modifier) {
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(112.dp))
        Spacer(Modifier.height(18.dp))
        Text("الجمعة", fontSize = 34.sp, color = Gold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text("Ready when the talk starts", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(
            "Point the phone's microphone at the nearest loudspeaker.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun hasPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share transcript"))
}
