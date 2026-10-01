// SPDX-License-Identifier: MIT
package dev.lightbridge.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.google.zxing.common.BitMatrix
import dev.lightbridge.app.wifi.WifiLinkViewModel
import dev.youniversal.theme.YouniversalThemeState
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.floor

private enum class Page(val title: String, val glyph: String) {
    Home("Home", "⌂"), Send("Send", "↑"), Receive("Receive", "↓"), Link("Link", "⇄"),
    Inbox("Inbox", "▤"), Settings("Settings", "☷")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LightBridgeApp(vm: TransferViewModel, link: WifiLinkViewModel, theme: YouniversalThemeState) {
    val state by vm.state.collectAsStateWithLifecycle()
    val linkState by link.state.collectAsStateWithLifecycle()
    val receipts by vm.inbox.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(Page.Home) }
    val snackbar = remember { SnackbarHostState() }
    // One inbox, one set of actions: a receipt behaves the same however it arrived.
    val actions =
        remember(vm, context) {
            InboxActions(
                save = { receipt, uri -> vm.save(receipt, uri) },
                delete = { receipt -> vm.delete(receipt) },
                share = { receipt ->
                    try {
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.files", vm.receivedFile(receipt), receipt.name,
                        )
                        val intent = Intent(Intent.ACTION_SEND).setType(receipt.mime.substringBefore(';'))
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        intent.clipData = android.content.ClipData.newRawUri(receipt.name, uri)
                        context.startActivity(Intent.createChooser(intent, "Share ${receipt.name}"))
                    } catch (e: Exception) {
                        vm.message(e.message ?: "No app available to share this file.")
                    }
                },
                file = { receipt -> vm.receivedFile(receipt) },
                message = { message -> vm.message(message) },
            )
        }
    BackHandler(page != Page.Home) { page = Page.Home }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    Scaffold(
        topBar = { TopAppBar(title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OpticalMark(Modifier.size(30.dp))
                Text("LightBridge", fontWeight = FontWeight.Bold)
            }
        }, actions = { Text("NO SERVERS · NO ACCOUNTS", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 16.dp)) }) },
        bottomBar = {
            NavigationBar {
                Page.entries.forEach { item -> NavigationBarItem(selected = page == item, onClick = { page = item },
                    icon = { Text(item.glyph, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { }) }, label = { Text(item.title) }) }
            }
        }, snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when (page) {
                Page.Home -> HomeScreen(state, receipts.size, onSend = { page = Page.Send },
                    onReceive = { page = Page.Receive }, onLink = { page = Page.Link }, onInbox = { page = Page.Inbox })
                Page.Send -> SendScreen(state, vm)
                Page.Receive -> ReceiveScreen(state.reception, vm, actions)
                Page.Link -> WifiScreen(linkState, link, actions)
                Page.Inbox -> InboxScreen(receipts, actions)
                Page.Settings -> SettingsScreen(state.settings, vm, theme)
            }
        }
    }
}

@Composable
internal fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
}

@Composable
internal fun Eyebrow(text: String) = Text(text.uppercase(), style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)

@Composable
internal fun Heading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun Note(title: String, text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HomeScreen(
    state: TransferState,
    inboxCount: Int,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onLink: () -> Unit,
    onInbox: () -> Unit,
) {
    ScreenColumn {
        Eyebrow("Screen → camera → file")
        Heading("A little light.\nA direct connection.", "Move files between devices with animated QR codes. No Wi-Fi. No Bluetooth. No account.")
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                OpticalIllustration(Modifier.fillMaxWidth().height(130.dp))
                Text("Your screen is the connection.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("One device shows. The other scans. Missing a frame? Just keep the camera pointed at the screen.",
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Button(onSend, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("↑  Send a file", style = MaterialTheme.typography.titleMedium) }
        FilledTonalButton(onReceive, Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("↓  Receive a file", style = MaterialTheme.typography.titleMedium) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("HOW IT WORKS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            TextButton(onInbox) { Text("Inbox · $inboxCount") }
        }
        Step("01", "Choose", "Pick a file up to ${formatSize(state.maxFileBytes.toLong())} on this device, or send a text snippet.")
        Step("02", "Point", "Open Receive on the other device. Keep the entire QR code in view.")
        Step("03", "Keep", "We verify every byte before you save or share the received file.")
        Note("Offline, not encrypted", "Anyone who can see the QR stream can receive it. Transfer sensitive files in a private space. SHA-256 checks integrity, not who sent the file.")
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.tertiaryContainer) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Need it encrypted?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "The Link tab pairs two devices over Wi-Fi Direct with a QR code and encrypts the transfer " +
                        "end to end (ECDH P-256 + AES-256-GCM). Nothing goes through a server, and received files " +
                        "land in this same inbox.",
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Button(onLink, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("⇄  Open the direct link") }
            }
        }
    }
}

@Composable
private fun Step(number: String, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
            Text(number, Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SendScreen(state: TransferState, vm: TransferViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    var playing by rememberSaveable { mutableStateOf(true) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { playing = true; vm.importFile(uri) }
    }
    val sending = state.sending
    var matrices by remember(sending) { mutableStateOf(emptyList<BitMatrix>()) }
    var emitted by remember(sending) { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(sending, playing, state.settings.fpsTenths, state.settings.tiles, lifecycle) {
        if (sending == null || !playing) return@LaunchedEffect
        lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val start = android.os.SystemClock.elapsedRealtime()
                try { matrices = vm.qrFrames(sending, state.settings.tiles) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { playing = false; vm.message(e.message ?: "Could not generate QR code."); break }
                emitted = sending.sequence.get()
                val period = FpsScale.framePeriodMillis(state.settings.fpsTenths)
                delay(maxOf(1L, period - (android.os.SystemClock.elapsedRealtime() - start)))
            }
        }
    }
    KeepAwake(sending != null && playing, sending != null && playing && state.settings.brighten)
    ScreenColumn {
        Eyebrow(if (sending == null) "Send over light" else if (playing) "Broadcasting · no network" else "Broadcast paused")
        Heading(if (sending == null) "What’s going across?" else "Ready on the other side?",
            if (sending == null) "Choose a file or write a message. Nothing is uploaded." else "Open Receive on the other device and point its camera here.")
        if (sending == null) {
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Preparing and hashing your file…")
                TextButton(vm::clearSender) { Text("Cancel preparation") }
            }
            Button({ picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().heightIn(min = 56.dp), enabled = !state.busy) { Text("Choose a file") }
            Text("Any file type · up to ${formatSize(state.maxFileBytes.toLong())} on this device", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            OutlinedTextField(text, { if (it.length <= 50000) text = it }, Modifier.fillMaxWidth(),
                label = { Text("Or send a text snippet") }, minLines = 4, maxLines = 8, enabled = !state.busy,
                supportingText = { Text("${text.length} / 50,000 characters") })
            FilledTonalButton({ playing = true; vm.sendText(text) }, Modifier.fillMaxWidth(), enabled = text.isNotBlank() && !state.busy) { Text("Send text") }
            Note("Make it easy to scan", "Start with one QR code at 8 frames per second. Lower the speed or density in Settings if the camera struggles. Large files can take a long time.")
        } else {
            FileSummary(sending.name, sending.originalSize.toLong(),
                if (sending.compressed) "Compressed for a quicker transfer" else "Original bytes · no compression needed")
            QrDisplay(matrices)
            TextButton({ fullscreen = true }, Modifier.fillMaxWidth()) { Text("Expand QR display") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${emitted} frames shown", style = MaterialTheme.typography.labelMedium)
                Text("${FpsScale.label(state.settings.fpsTenths)} target · ${state.settings.tiles} QR", style = MaterialTheme.typography.labelMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({ playing = !playing }, Modifier.weight(1f)) { Text(if (playing) "Pause" else "Resume") }
                OutlinedButton({ vm.clearSender() }, Modifier.weight(1f)) { Text("Finish") }
            }
            Text("This is a one-way link. Keep sending until the receiving device says “Verified”; the sender cannot know when it is done.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Note("Visible to nearby cameras", "The QR stream is not encrypted. Finishing stops the display and releases this file from the sender.")
        }
    }
    if (fullscreen && sending != null) androidx.compose.ui.window.Dialog(
        onDismissRequest = { fullscreen = false },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        KeepAwake(playing, playing && state.settings.brighten)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton({ playing = !playing }) { Text(if (playing) "Pause" else "Resume") }
                    TextButton({ fullscreen = false }) { Text("Close full screen") }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val ratio = if (matrices.size == 2) 2f else 1f
                    Box(Modifier.width(minOf(maxWidth, maxHeight * ratio))) { QrDisplay(matrices) }
                }
                Text("Keep sending until the receiver confirms verification.",
                    Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
internal fun QrDisplay(matrices: List<BitMatrix>) {
    val columns = if (matrices.size > 1) 2 else 1
    val rows = if (matrices.size == 4) 2 else 1
    Canvas(Modifier.fillMaxWidth().aspectRatio(if (matrices.size == 2) 2f else 1f)
        .background(Color.White).semantics { contentDescription = "Animated file transfer QR codes. Scan with the receiving device." }) {
        matrices.forEachIndexed { i, matrix ->
            val cellW = size.width / columns; val cellH = size.height / rows
            val scale = floor(minOf(cellW, cellH) / matrix.width).coerceAtLeast(1f)
            val x0 = (i % columns) * cellW + (cellW - matrix.width * scale) / 2
            val y0 = (i / columns) * cellH + (cellH - matrix.height * scale) / 2
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color.Black, Offset(x0 + x * scale, y0 + y * scale), Size(scale, scale))
            }
        }
    }
}

@Composable
private fun ReceiveScreen(reception: Reception, vm: TransferViewModel, actions: InboxActions) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var permission by remember { mutableStateOf(hasPermission()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var torch by rememberSaveable { mutableStateOf(false) }
    var flashAvailable by remember { mutableStateOf(false) }
    var zoom by rememberSaveable { mutableFloatStateOf(0f) }
    var resetConfirm by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permission = it; asked = true }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permission = hasPermission() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    KeepAwake(permission && reception.receipt == null && reception.error == null, false)
    ScreenColumn {
        Eyebrow(if (reception.receipt != null) "Transfer complete" else "Receive over light")
        Heading(if (reception.receipt != null) "Every byte, verified." else "Point. Hold. Receive.",
            if (reception.receipt != null) "Your file is safely stored in this app’s inbox." else "Keep the full QR code inside the camera view. Missed frames are OK.")
        when {
            reception.receipt != null -> ReceiptCard(reception.receipt, actions)
            reception.error != null -> {
                Note("Transfer stopped", reception.error)
                Button({ vm.resetReceiver() }, Modifier.fillMaxWidth()) { Text("Try again") }
            }
            !permission -> {
                Note("Camera access, only when you receive", "LightBridge decodes on your device. Camera images are never saved or sent anywhere. You can still send files without camera access.")
                Button({ launcher.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth()) { Text("Allow camera") }
                if (asked) TextButton({ context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) {
                    Text("Camera blocked? Open app settings")
                }
            }
            else -> {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.extraLarge).background(Color.Black)) {
                    CameraScanner(Modifier.fillMaxSize(), torch, zoom, vm::acceptQr, vm::cameraError) { flashAvailable = it }
                    Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp), color = Color.Black.copy(alpha = 0.72f),
                        contentColor = Color.White, shape = MaterialTheme.shapes.medium) {
                        Text(if (reception.stream == null) "Looking for a LightBridge / Decimen QR…" else "Collecting frames · keep steady",
                            Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Torch", style = MaterialTheme.typography.titleSmall)
                    Switch(torch, { torch = it }, enabled = flashAvailable)
                }
                Text("Camera zoom", style = MaterialTheme.typography.labelLarge)
                Slider(zoom, { zoom = it }, modifier = Modifier.semantics { contentDescription = "Camera zoom" })
                val stream = reception.stream
                if (stream != null) {
                    val progress = minOf(0.99f, reception.frames / (stream.k * 1.15f))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${reception.frames} unique frames · ${reception.solved} / ${stream.k} blocks recovered",
                        style = MaterialTheme.typography.titleSmall)
                    Text("Collection estimate, not a completion guarantee. Fountain decoding may finish all at once. Integrity verification follows.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (reception.differentStream) Note("Another stream is in view", "Keeping your current progress. Reset only if you want to receive a different file.")
                    OutlinedButton({ resetConfirm = true }) { Text("Reset transfer") }
                } else Note("Not finding the QR?", "Increase the sender’s screen brightness, reduce glare, and move closer. Start with a single, lower-density QR at 4–8 fps. Standard web-link QR codes are ignored.")
            }
        }
        if (reception.receipt != null) Button({ vm.resetReceiver() }, Modifier.fillMaxWidth()) { Text("Receive another file") }
        Text("Camera frames are never recorded or uploaded. The optical path needs no network at all; the Link tab asks for local-network access only for its direct device-to-device socket.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (resetConfirm) AlertDialog(onDismissRequest = { resetConfirm = false }, title = { Text("Discard partial transfer?") },
        text = { Text("You will need to scan this file again from the beginning.") },
        confirmButton = { TextButton({ resetConfirm = false; vm.resetReceiver() }) { Text("Reset") } },
        dismissButton = { TextButton({ resetConfirm = false }) { Text("Keep receiving") } })
}

@Composable
internal fun FileSummary(name: String, size: Long, subtitle: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("▤", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${formatSize(size)} · $subtitle", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ReceiptCard(receipt: Receipt, actions: InboxActions) {
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(receipt.mime.substringBefore(';').takeIf { it.contains('/') } ?: "application/octet-stream")) {
        if (it != null) actions.save(receipt, it)
    }
    var confirm by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Eyebrow("✓ SHA-256 verified")
            Text(receipt.name, style = MaterialTheme.typography.titleLarge)
            Text("${formatSize(receipt.size)} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(receipt.time))}",
                style = MaterialTheme.typography.bodySmall)
            Text("${receipt.mime} · ${receipt.source.label}", style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(receipt.hash, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ save.launch(receipt.name) }, Modifier.weight(1f)) { Text("Save as…") }
                OutlinedButton({ actions.share(receipt) }, Modifier.weight(1f)) { Text("Share") }
            }
            TextButton({ confirm = true }) { Text("Delete from inbox", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete this copy?") },
        text = { Text("${receipt.name} will be removed from LightBridge. Copies you saved elsewhere will not be affected.") },
        confirmButton = { TextButton({ actions.delete(receipt); confirm = false }) { Text("Delete") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}

@Composable
private fun InboxScreen(history: List<Receipt>, actions: InboxActions) {
    // LazyColumn keeps large inboxes from composing every card and activity-result launcher.
    androidx.compose.foundation.lazy.LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxWidth(),
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Heading("Your inbox", "Verified files from both the optical and direct-link tabs, stored only on this device. Save a copy before uninstalling the app.") }
        item { Eyebrow("${history.size} files · ${formatSize(history.sumOf { it.size })} / 256 MiB") }
        if (history.isEmpty()) item { Note("A clear space for what’s next", "Received files appear here after their SHA-256 checks pass. Head to Receive to scan your first transfer.") }
        items(history.size, key = { history[it].id }) { ReceiptCard(history[it], actions) }
    }
}

@Composable
private fun SettingsScreen(settings: TransferSettings, vm: TransferViewModel, theme: YouniversalThemeState) {
    var licenses by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    ScreenColumn {
        Heading("Make it yours.", "Fine-tune your optical link. Then find your favorite light.")
        Eyebrow("Transfer preferences")
        Text("Frame rate · ${FpsScale.label(settings.fpsTenths)}", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = FpsScale.tenthsToPosition(settings.fpsTenths),
            onValueChange = { position ->
                vm.settings(settings.copy(fpsTenths = FpsScale.positionToTenths(position)))
            },
            onValueChangeFinished = { vm.flushSettings() },
            valueRange = 0f..1f,
            modifier = Modifier.semantics { contentDescription = "Frames per second" },
        )
        Text(
            "0.1 to 20 frames per second, in tenths of a frame. The slider is stretched at the slow end so " +
                "every tenth is reachable. Slower is easier for older cameras.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (FpsScale.isVerySlow(settings.fpsTenths)) {
            Note(
                "Very slow rate",
                "At ${FpsScale.label(settings.fpsTenths)} a single QR code is on screen for " +
                    "${FpsScale.framePeriodMillis(settings.fpsTenths) / 1000} seconds. That is kind to a poor camera " +
                    "but a large file will take a very long time — use it to get a stubborn transfer started, then speed up.",
            )
        }
        Text("QR density", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(512 to "Easy", 1024 to "Balanced", 2048 to "Dense").forEach { (bytes, label) ->
                FilterChip(settings.blockSize == bytes, { vm.settings(settings.copy(blockSize = bytes)) }, label = { Text(label) })
            }
        }
        Text("Applies to the next file. Dense codes carry more data but need a sharper camera. For files near 64 MiB, use Dense.", style = MaterialTheme.typography.bodySmall)
        Text("Codes on screen", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1,2,4).forEach { count -> FilterChip(settings.tiles == count, { vm.settings(settings.copy(tiles = count)) }, label = { Text("$count QR") }) }
        }
        Text("One is best for phone screens. Multiple codes work best on larger displays.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Boost brightness while sending", Modifier.weight(1f))
            Switch(settings.brighten, { vm.settings(settings.copy(brighten = it)) })
        }
        ThemePanel(theme)
        Note("About LightBridge · 0.2.0", "Native Kotlin + Jetpack Compose. Material You by Youniversal. MIT-licensed fountain protocol ported from Decimen Optical Transfer v0.3.0. Not affiliated with or endorsed by Decimen.")
        Note(
            "Privacy & storage",
            "No analytics, no accounts, no ads and no servers. The optical tab needs no network permission at all. " +
                "The Link tab declares the network permission Android requires for any socket, and uses it only for the " +
                "direct Wi-Fi Direct link: connections are refused unless the peer is a literal address on the peer-to-peer " +
                "range, so the app cannot reach the internet even in principle. Camera frames exist only while decoding. " +
                "Verified files stay in the private inbox until you delete them or uninstall. Android backup is disabled. " +
                "Sharing hands a file to the app you choose, which may use a network.",
        )
        Note("Compatibility", "Uses Decimen’s D1 0C frames and DCF2 containers. Supports gzip, binary QR, fountain recovery, filename/type preservation and SHA-256. This native version does not export APNG animations, play received media, or include the web project’s translations and diagnostics tools.")
        TextButton({ licenses = context.assets.open("licenses/NOTICE.txt").bufferedReader().use { it.readText() } }) { Text("Open-source licenses & attribution") }
    }
    if (licenses != null) AlertDialog(onDismissRequest = { licenses = null }, title = { Text("Open source") },
        text = { Text(licenses!!, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
        confirmButton = { TextButton({ licenses = null }) { Text("Done") } })
}

/** Restore the user's window brightness and wake policy when leaving or backgrounding. */
@Composable
internal fun KeepAwake(enabled: Boolean, brighten: Boolean) {
    val view = LocalView.current
    val activity = androidx.activity.compose.LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current
    val targetWindow = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: activity?.window
    DisposableEffect(view, targetWindow, lifecycle, enabled, brighten) {
        val previousKeep = view.keepScreenOn
        val previousBrightness = targetWindow?.attributes?.screenBrightness ?: -1f
        fun apply(active: Boolean) {
            view.keepScreenOn = if (active && enabled) true else previousKeep
            targetWindow?.let { window ->
                window.attributes = window.attributes.apply { screenBrightness = if (active && brighten) 1f else previousBrightness }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) apply(true)
            if (event == Lifecycle.Event.ON_PAUSE) apply(false)
        }
        lifecycle.lifecycle.addObserver(observer)
        apply(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose { lifecycle.lifecycle.removeObserver(observer); apply(false) }
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MiB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.1f KiB".format(bytes / 1024.0)
    else -> "$bytes bytes"
}

@Composable
private fun OpticalMark(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val u = size.minDimension / 7
        listOf(Offset(0f, 0f), Offset(4 * u, 0f), Offset(0f, 4 * u)).forEach {
            drawRoundRect(color, it, Size(3*u,3*u), androidx.compose.ui.geometry.CornerRadius(u*0.5f), style = Stroke(u*0.55f))
        }
        drawCircle(color, u, Offset(5.5f*u,5.5f*u))
    }
}

@Composable
private fun OpticalIllustration(modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    Canvas(modifier.semantics { contentDescription = "A QR code on one screen sends light to a second device" }) {
        val h = size.height * 0.9f; val w = h * 0.57f
        val left = size.width * 0.12f; val right = size.width * 0.88f - w; val top = (size.height - h) / 2
        for (x in listOf(left, right)) {
            drawRoundRect(ink, Offset(x, top), Size(w,h), androidx.compose.ui.geometry.CornerRadius(14f), style = Stroke(4f))
            drawLine(ink, Offset(x+w*.35f,top+h*.88f), Offset(x+w*.65f,top+h*.88f), 4f, StrokeCap.Round)
        }
        val u = w / 10
        for (y in 0..6) for (x in 0..6) if ((x+y)%3 != 1 || (x<2 && y<2))
            drawRect(ink, Offset(left+u*1.5f+x*u, top+h*.22f+y*u), Size(u*.8f,u*.8f))
        val start = left + w + 12; val end = right - 12
        for (i in 0..4) drawCircle(ink.copy(alpha = 0.25f+i*.15f), 3f+i, Offset(start+(end-start)*i/4, size.height/2))
        drawLine(ink, Offset(right+w*.25f,top+h*.48f), Offset(right+w*.44f,top+h*.6f), 5f, StrokeCap.Round)
        drawLine(ink, Offset(right+w*.44f,top+h*.6f), Offset(right+w*.77f,top+h*.35f), 5f, StrokeCap.Round)
    }
}
