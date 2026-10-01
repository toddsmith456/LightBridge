// SPDX-License-Identifier: MIT
package dev.lightbridge.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import dev.lightbridge.app.wifi.LinkPermissions
import dev.lightbridge.app.wifi.LinkPhase
import dev.lightbridge.app.wifi.LinkState
import dev.lightbridge.app.wifi.WifiLinkViewModel
import dev.lightbridge.link.CONNECT_QR_PREFIX
import dev.lightbridge.link.Contact
import dev.lightbridge.link.Trust
import dev.lightbridge.link.shortFingerprint
import kotlin.math.floor

/**
 * The Wi-Fi Direct tab: a direct, end-to-end encrypted link between two devices, paired by scanning
 * a QR code. Received files land in the same inbox as optical transfers.
 */
@Composable
internal fun WifiScreen(state: LinkState, vm: WifiLinkViewModel, actions: InboxActions) {
    val context = LocalContext.current
    var scanning by rememberSaveable { mutableStateOf(false) }
    var rememberDeviceName by rememberSaveable(state.deviceName) { mutableStateOf(state.deviceName) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.onPermissionResult() }

    ScreenColumn {
        Eyebrow("Direct link · encrypted end to end")
        Heading(
            "Wi-Fi Direct.",
            "Two devices, one private link. Pair once with a QR code, then move files at network " +
                "speed — encrypted on the devices, never uploaded anywhere.",
        )

        if (!state.supported) {
            Note("Wi-Fi Direct is unavailable", "This device's Wi-Fi hardware does not expose the direct-link APIs. The optical transfer tab still works.")
        } else {
            if (state.missingPermission) {
                Note(
                    "One permission, only for this tab",
                    "Android needs nearby-device access to create a direct link. LightBridge still has no servers: " +
                        "the link only ever reaches the device that scanned your code.",
                )
                Button(
                    { permissionLauncher.launch(LinkPermissions.required().toTypedArray()) },
                    Modifier.fillMaxWidth(),
                ) { Text("Allow nearby devices") }
            }
            if (state.wifiOff) {
                Note("Wi-Fi is off", "A direct link needs the Wi-Fi radio. Turn Wi-Fi on, then start a link.")
            }
        }

        state.error?.let { message ->
            Note("Link stopped", message)
            val blocked = state.missingPermission || state.wifiOff
            if (blocked && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                TextButton({ vm.clearError() }) { Text("Dismiss") }
            } else {
                TextButton({ vm.clearError() }) { Text("Dismiss") }
            }
        }

        when (state.phase) {
            LinkPhase.Idle, LinkPhase.Failed -> {
                Button(
                    onClick = { vm.startHosting(); scanning = false },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.supported && !state.missingPermission && !state.wifiOff,
                ) { Text("Show a code · this device shares", style = MaterialTheme.typography.titleMedium) }
                OutlinedButton(
                    onClick = { scanning = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.supported && !state.missingPermission && !state.wifiOff,
                ) { Text("Scan a code · join another device", style = MaterialTheme.typography.titleMedium) }
                Note(
                    "Who shows the code?",
                    "Either device can host. The host's screen carries the connection details and the encryption keys, " +
                        "so a scan is all the other phone needs.",
                )
            }

            LinkPhase.Preparing -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.status)
                TextButton({ vm.stop() }) { Text("Cancel") }
            }

            LinkPhase.AwaitingPeer -> {
                state.qr?.let { payload ->
                    ConnectQr(payload)
                    Text(
                        "Point the other device's camera at this code. It expires when you leave this screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(state.status, style = MaterialTheme.typography.titleSmall)
                OutlinedButton({ vm.stop() }, Modifier.fillMaxWidth()) { Text("Stop sharing") }
            }

            LinkPhase.Connecting -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(state.status)
                TextButton({ vm.stop() }) { Text("Cancel") }
            }

            LinkPhase.Verifying -> VerificationCard(state, vm)

            LinkPhase.Ready -> {
                ReadyCard(state)
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) vm.sendFile(uri)
                }
                Button({ picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text("Send a file") }
                Text(
                    "The other device accepts automatically and verifies the SHA-256 before it is stored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton({ vm.stop() }, Modifier.fillMaxWidth()) { Text("End the link") }
            }

            LinkPhase.Sending, LinkPhase.Receiving -> ProgressCard(state, vm)

            LinkPhase.Finished -> {
                state.receipt?.let { receipt ->
                    ReceiptCard(receipt, actions)
                } ?: Note("Delivered", state.status)
                Button({ vm.stop() }, Modifier.fillMaxWidth()) { Text("Done") }
            }
        }

        ContactsCard(state, vm)

        if (state.supported) {
            OutlinedTextField(
                value = rememberDeviceName,
                onValueChange = { if (it.length <= 32) rememberDeviceName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("This device's name") },
                supportingText = { Text("Shown to the other device when you pair.") },
                singleLine = true,
            )
            TextButton({ vm.setDeviceName(rememberDeviceName) }) { Text("Save name") }
        }

        Note(
            "What protects this link",
            "Wi-Fi Direct is a private radio link between the two devices — no router, no hotspot, no internet. " +
                "On top of it, both devices agree on a fresh key pair every time (ECDH P-256) and every byte is " +
                "sealed with AES-256-GCM, so nobody on the radio can read or change a file. The six-digit code is " +
                "the part a person checks: if the two screens disagree, stop.",
        )
    }

    if (scanning) {
        ConnectScanner(
            onPayload = { text -> scanning = false; vm.join(text) },
            onCancel = { scanning = false },
        )
    }
}

/** The six-digit comparison, deliberately the most prominent thing on the screen while it matters. */
@Composable
private fun VerificationCard(state: LinkState, vm: WifiLinkViewModel) {
    val sas = state.sas ?: return
    val peer = state.peer
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Compare these digits", style = MaterialTheme.typography.titleMedium)
            Text(
                peer?.name ?: "Other device",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                sas.take(3).forEach { Digit(it.toString()) }
                sas.drop(3).forEach { Digit(it.toString()) }
            }
            Text(
                "Both devices must show exactly this code. It comes from both key pairs, so a device in the " +
                    "middle would produce different digits.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            peer?.let {
                Text(
                    "Identity ${it.shortFingerprint}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                when {
                    state.localConfirmed && state.peerConfirmed -> "Both sides confirmed."
                    state.localConfirmed -> "Waiting for the other device to confirm…"
                    state.peer?.trust == Trust.Verified -> "Known contact."
                    else -> "Only continue if the other screen shows these same digits."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (!state.localConfirmed) {
                Button({ vm.confirmPeer() }, Modifier.fillMaxWidth()) {
                    Text(if (state.peer?.trust == Trust.Verified) "Confirm again" else "The codes match — save contact")
                }
                OutlinedButton({ vm.rejectPeer() }, Modifier.fillMaxWidth()) { Text("They differ — stop") }
            } else {
                OutlinedButton({ vm.stop() }, Modifier.fillMaxWidth()) { Text("End the link") }
            }
        }
    }
}

@Composable
private fun Digit(value: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.large) {
        Text(
            value,
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.displaySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun ReadyCard(state: LinkState) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Link ready", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Connected to ${state.peer?.name ?: "the other device"}" +
                    (if (state.peer?.trust == Trust.Verified) " · verified contact" else " · confirmed just now"),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(state.status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProgressCard(state: LinkState, vm: WifiLinkViewModel) {
    val transfer = state.transfer
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (transfer?.outgoing == true) "Sending" else "Receiving",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(transfer?.name ?: "…", style = MaterialTheme.typography.titleMedium)
            if (transfer != null) {
                LinearProgressIndicator(
                    progress = { transfer.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${formatSize(transfer.moved)} of ${formatSize(transfer.size)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(state.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ vm.stop() }) { Text("Stop the transfer") }
        }
    }
}

@Composable
private fun ContactsCard(state: LinkState, vm: WifiLinkViewModel) {
    var forgetting by remember { mutableStateOf<Contact?>(null) }
    var renaming by remember { mutableStateOf<Contact?>(null) }
    var newName by remember { mutableStateOf("") }
    Note(
        "Paired devices",
        if (state.contacts.isEmpty()) {
            "Nobody yet. Pair by scanning a code; confirmed devices are remembered here."
        } else {
            "${state.contacts.size} device(s) can pair with you again."
        },
    )
    state.contacts.forEach { contact ->
        ElevatedCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(contact.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        shortFingerprint(contact.id),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (contact.verified) {
                    FilterChip(selected = false, onClick = { }, label = { Text("Verified") })
                }
                TextButton({ renaming = contact; newName = contact.name }) { Text("Rename") }
                TextButton({ forgetting = contact }) { Text("Forget") }
            }
        }
    }
    forgetting?.let { contact ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget ${contact.name}?") },
            text = { Text("You would have to scan and confirm a code again before this device could send you anything.") },
            confirmButton = { TextButton({ vm.forgetContact(contact.id); forgetting = null }) { Text("Forget") } },
            dismissButton = { TextButton({ forgetting = null }) { Text("Keep") } },
        )
    }
    renaming?.let { contact ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename device") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { if (it.length <= 32) newName = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton({ vm.renameContact(contact.id, newName); renaming = null }) { Text("Save") }
            },
            dismissButton = { TextButton({ renaming = null }) { Text("Cancel") } },
        )
    }
}

/** The connect code: a QR of the link payload, drawn in the same visual language as the app. */
@Composable
private fun ConnectQr(payload: String) {
    val matrix: BitMatrix? = remember(payload) {
        runCatching {
            QRCodeWriter().encode(
                payload,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to "M"),
            )
        }.getOrNull()
    }
    val label = "Wi-Fi Direct pairing code"
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.extraLarge)
            .background(Color.White).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (matrix == null) {
            Text("Could not draw the code. Stop and try again.", color = Color.Black, modifier = Modifier.padding(24.dp))
        } else {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                val scale = floor(minOf(size.width, size.height) / matrix.width).coerceAtLeast(1f)
                for (y in 0 until matrix.height) {
                    for (x in 0 until matrix.width) {
                        if (matrix[x, y]) drawRect(Color.Black, Offset(x * scale, y * scale), Size(scale, scale))
                    }
                }
            }
        }
    }
}

/** Camera scanner for a pairing code, reusing the same on-device decoder as the optical receiver. */
@Composable
private fun ConnectScanner(onPayload: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    var permission by remember { mutableStateOf(granted()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permission = it; asked = true
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permission = granted()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    KeepAwake(permission, false)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (permission) "Scan the pairing code" else "Camera access") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    permission -> {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.large)
                                .background(Color.Black),
                        ) {
                            CameraScanner(
                                Modifier.fillMaxSize(),
                                torch = false,
                                zoom = 0f,
                                onFrame = { bytes ->
                                    // The payload is ASCII, so a one-to-one byte mapping is exact.
                                    val text = String(bytes, Charsets.ISO_8859_1)
                                    if (text.startsWith(CONNECT_QR_PREFIX)) onPayload(text)
                                },
                                onError = { },
                                onCamera = { },
                            )
                        }
                        Text("Point at the code on the other device's Link tab.", style = MaterialTheme.typography.bodySmall)
                    }

                    else -> {
                        Text("LightBridge decodes the pairing code on this device. No images are stored or sent.")
                        Button({ launcher.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth()) { Text("Allow camera") }
                        if (asked) {
                            TextButton({
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }) { Text("Camera blocked? Open app settings") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onCancel() }) { Text("Cancel") } },
    )
}
