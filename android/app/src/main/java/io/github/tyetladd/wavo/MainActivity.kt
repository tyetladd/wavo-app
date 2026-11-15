// Copyright (c) vladkens | MIT License | https://github.com/vladkens/wavo-app

package io.github.tyetladd.wavo

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings as SystemSettings
import android.text.format.DateUtils
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var dictation: Dictation

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        dictation = Dictation(this, conversations = true)
        Models.refresh()
        setContent { Theme { Wavo(dictation) } }
    }

    override fun onStart() {
        super.onStart()
        Engine.prewarm()
    }

    override fun onStop() {
        // Recording only while the person sees it.
        dictation.cancel()
        super.onStop()
    }
}

@Composable
private fun Theme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        android.os.Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private enum class Tab(val label: String) { Dictate("Dictate"), Models("Models"), History("History"), Settings("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Wavo(dictation: Dictation) {
    var tab by rememberSaveable { mutableStateOf(Tab.Dictate) }
    val models by Models.list.collectAsState()
    // Without a model there is nothing to dictate with: the first screen is where to get one.
    LaunchedEffect(Unit) { if (models.none { it.downloaded }) tab = Tab.Models }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (tab == Tab.Dictate) "Wavo" else tab.label) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        label = { Text(t.label) },
                        icon = {
                            when (t) {
                                Tab.Dictate -> Icon(painterResource(R.drawable.ic_mic), null)
                                Tab.Models -> Icon(Icons.Filled.List, null)
                                Tab.History -> Icon(Icons.Filled.Menu, null)
                                Tab.Settings -> Icon(Icons.Filled.Settings, null)
                            }
                        },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.Dictate -> DictateScreen(dictation, toModels = { tab = Tab.Models })
                Tab.Models -> ModelsScreen()
                Tab.History -> HistoryScreen()
                Tab.Settings -> SettingsScreen()
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("Wavo", text))
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null))
}

/** `11.0 s of speech in 0.94 s (12× real time)`. */
private fun timing(audioMs: Long, tookMs: Long): String {
    val times = if (tookMs > 0) " (%.0f× real time)".format(audioMs.toDouble() / tookMs) else ""
    return "%.1f s of speech in %.2f s%s".format(audioMs / 1000.0, tookMs / 1000.0, times)
}

// MARK: Dictate

@Composable
private fun DictateScreen(dictation: Dictation, toModels: () -> Unit) {
    val context = LocalContext.current
    val ui by dictation.ui.collectAsState()
    val engine by Engine.state.collectAsState()
    var allowed by remember { mutableStateOf(dictation.allowed) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        if (granted) dictation.start()
    }
    val chosen = App.instance.settings.model

    // The clock of a recording.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val recording = ui.phase == Dictation.Phase.Recording
    LaunchedEffect(recording) {
        while (recording) {
            now = SystemClock.elapsedRealtime()
            delay(200)
        }
    }
    // The screen stays on while the person speaks.
    val view = LocalView.current
    DisposableEffect(recording) {
        view.keepScreenOn = recording
        onDispose { view.keepScreenOn = false }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            val last = ui.last
            if (ui.phase != Dictation.Phase.Idle && ui.partial.isNotEmpty()) {
                // The pieces ready while the person speaks on.
                Text(ui.partial, style = MaterialTheme.typography.headlineSmall)
            } else if (last == null) {
                Text(
                    "Tap the microphone and speak. The text shows here; nothing leaves the phone.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SelectionContainer { Text(last.text, style = MaterialTheme.typography.headlineSmall) }
                Spacer(Modifier.height(12.dp))
                Text(
                    "${timing(last.audioMs, last.tookMs)}\n" +
                        "Ready %.1f s after the stop\n".format(last.waitMs / 1000.0) +
                        "${last.model} on ${last.device}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { copy(context, last.text) }) { Text("Copy") }
                    OutlinedButton(onClick = { share(context, last.text) }) { Text("Share") }
                }
            }
        }

        val status = when {
            recording -> "Listening  ${clock(now - ui.startedAt)}"
            ui.phase == Dictation.Phase.Transcribing ->
                (engine as? Engine.State.Loading)?.let { "Loading ${it.model}…" }
                    ?: if (ui.conversation) "Transcribing and telling the speakers apart…" else "Transcribing…"
            chosen == null -> "No model yet"
            ui.error != null -> ui.error!!
            engine is Engine.State.Loading -> "Loading ${(engine as Engine.State.Loading).model}…"
            engine is Engine.State.Failed -> (engine as Engine.State.Failed).message
            engine is Engine.State.Ready -> "$chosen is ready"
            else -> chosen
        }
        if (chosen != null) ConversationRow(enabled = ui.phase == Dictation.Phase.Idle)
        Text(status, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        if (chosen == null) {
            Button(onClick = toModels) { Text("Download a model") }
        } else {
            val scale = if (recording) 1f + minOf(ui.level * 2f, 1f) * 0.15f else 1f
            LargeFloatingActionButton(
                onClick = {
                    when {
                        ui.phase != Dictation.Phase.Idle -> dictation.toggle()
                        dictation.allowed -> dictation.start()
                        else -> ask.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                containerColor = when {
                    recording -> MaterialTheme.colorScheme.error
                    ui.phase == Dictation.Phase.Transcribing -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
                contentColor = if (recording) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale),
            ) {
                Icon(
                    painterResource(if (recording) R.drawable.ic_stop else R.drawable.ic_mic),
                    contentDescription = if (recording) "Stop" else "Dictate",
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (!allowed && chosen != null) {
            Text("Wavo asks for the microphone at the first tap.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The switch that makes the next recording a conversation, and the speaker models' download under it. */
@Composable
private fun ConversationRow(enabled: Boolean) {
    val settings = App.instance.settings
    var on by remember { mutableStateOf(settings.conversation) }
    val ready by Speakers.ready.collectAsState()
    val download by Speakers.download.collectAsState()

    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Conversation")
                Text(
                    "Labels who said what, after the stop. Up to 10 minutes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = on, enabled = enabled, onCheckedChange = {
                on = it
                settings.conversation = it
            })
        }

        val pull = download
        if (on && !ready) {
            when {
                pull != null && pull.error == null -> {
                    LinearProgressIndicator(
                        progress = { pull.done.toFloat() / pull.total },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${size(pull.done)} of ${size(pull.total)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { Speakers.cancel() }) { Text("Cancel") }
                    }
                }
                else -> {
                    pull?.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    FilledTonalButton(onClick = { Speakers.download() }) {
                        Text(if (pull?.error != null) "Retry" else "Download the speaker models (${size(Speakers.size)})")
                    }
                }
            }
        }
    }
}

// MARK: Models

@Composable
private fun ModelsScreen() {
    val models by Models.list.collectAsState()
    val downloads by Models.downloads.collectAsState()
    var chosen by remember { mutableStateOf(App.instance.settings.model) }
    // The first download chooses itself.
    LaunchedEffect(models) { chosen = App.instance.settings.model }

    // A download goes on only while the app does: the screen stays on for it.
    val view = LocalView.current
    val downloading = downloads.values.any { it.error == null }
    DisposableEffect(downloading) {
        view.keepScreenOn = downloading
        onDispose { view.keepScreenOn = false }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "A model is downloaded once and then works offline. Pick one for your language: " +
                    "Parakeet Ultra for 25 European languages, GigaAM v3 for Russian, Moonshine for the smallest.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(models, key = { it.name }) { model ->
            ModelCard(
                model = model,
                download = downloads[model.name],
                chosen = chosen == model.name,
                choose = {
                    App.instance.settings.model = model.name
                    chosen = model.name
                    Engine.unload()
                    Engine.prewarm()
                },
            )
        }
    }
}

@Composable
private fun ModelCard(model: ModelInfo, download: Download?, chosen: Boolean, choose: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (model.downloaded) RadioButton(selected = chosen, onClick = choose)
                Column(Modifier.weight(1f)) {
                    Text(model.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${model.languages} · ${size(model.size)} · ${model.maker}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val notes = listOfNotNull(
                        "Recommended".takeIf { model.recommended },
                        "Slow on a phone".takeIf { model.slow },
                        "Lowercase, no punctuation".takeIf { !model.punctuated },
                    )
                    if (notes.isNotEmpty()) {
                        Text(
                            notes.joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (model.slow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                when {
                    download != null && download.error == null ->
                        TextButton(onClick = { Models.cancel(model.name) }) { Text("Cancel") }
                    model.downloaded ->
                        IconButton(onClick = { confirm = true }) { Icon(Icons.Filled.Delete, "Delete ${model.name}") }
                    else ->
                        FilledTonalButton(onClick = { Models.download(model.name) }) {
                            Text(if (download?.error != null) "Retry" else "Download")
                        }
                }
            }

            if (download != null) {
                Spacer(Modifier.height(8.dp))
                if (download.error != null) {
                    Text(download.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else if (download.total > 0) {
                    LinearProgressIndicator(
                        progress = { download.done.toFloat() / download.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${size(download.done)} of ${size(download.total)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${model.name}?") },
            text = { Text("It frees ${size(model.size)}. The model can be downloaded again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    Models.delete(model.name)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

// MARK: History

@Composable
private fun HistoryScreen() {
    val context = LocalContext.current
    val history = App.instance.history
    val entries by history.entries.collectAsState()
    var confirm by remember { mutableStateOf(false) }

    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "Your dictations show here, from the app and from the keyboard. Only the text is kept.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { confirm = true }) { Text("Clear all") }
            }
        }
        items(entries, key = { it.id }) { entry ->
            Card(Modifier.fillMaxWidth().clickable { copy(context, entry.text) }) {
                Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.text)
                        Spacer(Modifier.height(4.dp))
                        val at = DateUtils.getRelativeTimeSpanString(entry.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                        Text(
                            "$at · ${entry.model} · ${timing(entry.audioMs, entry.tookMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { history.delete(entry.id) }) { Icon(Icons.Filled.Delete, "Delete") }
                }
            }
        }
        item { Text("Tap a dictation to copy it.", style = MaterialTheme.typography.bodySmall) }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clear the history?") },
            text = { Text("Every dictation kept here is deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    history.clear()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

// MARK: Settings

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val settings = App.instance.settings
    val engine by Engine.state.collectAsState()
    val models by Models.list.collectAsState()

    var backend by remember { mutableStateOf(settings.backend) }
    var language by remember { mutableStateOf(settings.language) }
    var clean by remember { mutableStateOf(settings.clean) }
    var crashed by remember { mutableStateOf(settings.gpuCrashed) }
    var live by remember { mutableStateOf(settings.live) }
    var unloadAfter by remember { mutableIntStateOf(settings.unloadAfterMinutes) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Section("Keyboard")
        Text(
            "Turn the Wavo voice keyboard on once, then switch to it in any app to dictate into a field.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { context.startActivity(Intent(SystemSettings.ACTION_INPUT_METHOD_SETTINGS)) }) {
                Text("Turn on")
            }
            OutlinedButton(onClick = { context.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }) {
                Text("Switch keyboard")
            }
        }

        Section("Engine")
        Choice("CPU", "The phone's fast cores. Works on every phone.", backend == "cpu") {
            backend = "cpu"
            settings.backend = "cpu"
            Engine.unload()
            Engine.prewarm()
        }
        Choice("GPU (experimental)", "Vulkan. Untested on phones: it may be faster, fail to load or crash the app.", backend == "gpu") {
            backend = "gpu"
            settings.backend = "gpu"
            settings.gpuCrashed = false
            crashed = false
            Engine.unload()
            Engine.prewarm()
        }
        if (crashed) {
            Text(
                "The GPU crashed the app last time, so the engine went back to the CPU.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            when (val e = engine) {
                is Engine.State.Ready -> "${e.model} runs on ${e.device}"
                is Engine.State.Loading -> "Loading ${e.model}…"
                is Engine.State.Failed -> "Failed: ${e.message}"
                Engine.State.Idle -> "No model in memory"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (engine is Engine.State.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Free the model's memory after", Modifier.weight(1f))
            Picker(
                shown = "$unloadAfter min",
                options = listOf(1, 5, 10, 30, 120).map { it to "$it min" },
            ) {
                unloadAfter = it
                settings.unloadAfterMinutes = it
            }
        }

        val speakers by Speakers.ready.collectAsState()
        if (speakers) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speaker models, ${size(Speakers.size)}", Modifier.weight(1f))
                OutlinedButton(onClick = { Speakers.delete() }) { Text("Delete") }
            }
        }

        Section("Text")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Transcribe while speaking")
                Text(
                    "Each part is transcribed at your pause, so the text is ready sooner after the stop, " +
                        "and the keyboard types it as you go. A pause may end a sentence.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = live, onCheckedChange = {
                live = it
                settings.live = it
            })
        }
        val langs = models.firstOrNull { it.name == settings.model }?.langs.orEmpty().sorted()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Language of the speech")
                Text(
                    "Whisper transcribes in it instead of guessing; the cleanup follows it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Picker(
                shown = language ?: "Auto",
                options = listOf<Pair<String?, String>>(null to "Auto") + langs.map { it to it },
            ) {
                language = it
                settings.language = it
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Clean up the text")
                Text(
                    "Takes out “um” and “uh”, and writes Russian numbers in digits.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = clean, onCheckedChange = {
                clean = it
                settings.clean = it
            })
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "Wavo for Android runs the wavo engine by vladkens on this phone. Only a model's download uses the network.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    HorizontalDivider()
}

@Composable
private fun Choice(title: String, about: String, selected: Boolean, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = choose), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = choose)
        Column {
            Text(title)
            Text(about, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun <T> Picker(shown: String, options: List<Pair<T, String>>, choose: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(shown) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    open = false
                    choose(value)
                })
            }
        }
    }
}
