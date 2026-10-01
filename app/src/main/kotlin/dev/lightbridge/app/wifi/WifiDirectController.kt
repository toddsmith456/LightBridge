// SPDX-License-Identifier: MIT
package dev.lightbridge.app.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What a device needs before it can use Wi-Fi Direct on this Android version. */
internal object LinkPermissions {

    /**
     * Android 13 replaced the location requirement with the (narrower) nearby-devices permission.
     * Everything below 13 still gates the P2P APIs behind location.
     */
    fun required(): List<String> =
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun missing(context: Context): List<String> =
        required().filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
}

/** Details of the group this device just created. */
internal data class GroupDetails(
    val ssid: String,
    val passphrase: String,
    val ownerAddress: String,
    val deviceAddress: String,
)

/** A nearby device found by the optional fallback search. */
internal data class PeerDevice(val name: String, val address: String)

internal class WifiP2pFailure(message: String) : IllegalStateException(message)

/**
 * Thin coroutine wrapper around [WifiP2pManager].
 *
 * The QR path needs no discovery: the host creates a group and advertises its own device address,
 * and the joiner connects straight to that address. Discovery (and therefore the location
 * requirement on older Android versions) is only used by the explicit "search nearby" fallback.
 */
@SuppressLint("MissingPermission") // Every entry point is gated on LinkPermissions.missing() by the UI.
internal class WifiDirectController(private val context: Context) {

    private val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        ?: throw WifiP2pFailure("This device does not support Wi-Fi Direct.")
    private val channel = manager.initialize(context, Looper.getMainLooper()) {
        // Channel lost (Wi-Fi toggled): surfaced as a group failure by the next call.
        mutableGroupFormed.value = false
    }

    private val mutableGroupFormed = MutableStateFlow(false)

    /**
     * This device's own Wi-Fi Direct address, as pushed by the framework. The QR code has to advertise
     * it so the other device can connect straight to us, and this is the only source that works on
     * every supported Android version — `requestDeviceInfo` only exists from API 29.
     */
    private val mutableLocalAddress = MutableStateFlow("")

    /** True while a P2P group is up (either as owner or as client). */
    val groupFormed: StateFlow<Boolean> = mutableGroupFormed.asStateFlow()

    /** True when Wi-Fi itself is on; Wi-Fi Direct cannot work with the radio off. */
    fun wifiEnabled(): Boolean =
        (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refreshGroupState()
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION ->
                    intent.ownDevice()?.deviceAddress?.takeIf { it.isNotBlank() }
                        ?.let { mutableLocalAddress.value = it }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION ->
                    if (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) !=
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    ) {
                        mutableGroupFormed.value = false
                    }
            }
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** Creates a group and returns what the QR code needs. */
    suspend fun createGroup(): GroupDetails = onMain {
        action { listener -> manager.createGroup(channel, listener) }
        val group = withTimeout(10_000) { groupInfo() }
            ?: throw WifiP2pFailure("Wi-Fi Direct did not start a group. Turn Wi-Fi off and on, then retry.")
        val connection = requestConnectionInfo()
        mutableGroupFormed.value = true
        GroupDetails(
            ssid = group.networkName ?: throw WifiP2pFailure("The group has no name; retry."),
            passphrase = group.passphrase ?: throw WifiP2pFailure("The group has no passphrase; retry."),
            ownerAddress = connection?.groupOwnerAddress?.hostAddress
                ?: dev.lightbridge.link.LinkAddressPolicy.DEFAULT_GROUP_OWNER,
            deviceAddress = deviceAddress(),
        )
    }

    /**
     * Joins the group a QR code points at.
     *
     * The connection is negotiated by the framework; the caller polls [groupFormed] afterwards,
     * because the socket can only be opened once the interface exists.
     */
    suspend fun connectTo(deviceAddress: String, asClient: Boolean = true): Unit = onMain {
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = if (asClient) 0 else 15
        }
        action { listener -> manager.connect(channel, config, listener) }
    }

    /** Waits until the framework reports a formed group. */
    suspend fun awaitGroupFormed(timeoutMs: Long = 30_000): Boolean = withTimeout(timeoutMs) {
        while (true) {
            if (requestConnectionInfo()?.groupFormed == true) {
                mutableGroupFormed.value = true
                return@withTimeout true
            }
            kotlinx.coroutines.delay(500)
        }
        @Suppress("UNREACHABLE_CODE") false
    }

    /** Optional fallback: list nearby P2P devices. Needs the runtime permission and, on API < 33, location on. */
    suspend fun discoverPeers(timeoutMs: Long = 20_000): List<PeerDevice> = onMain {
        action { listener -> manager.discoverPeers(channel, listener) }
        kotlinx.coroutines.delay(4_000)
        var peers = requestPeers()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (peers.size < 2 && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(2_000)
            peers = requestPeers()
        }
        peers.mapNotNull { device ->
            device.deviceAddress?.takeIf { it.isNotBlank() }
                ?.let { PeerDevice(device.deviceName?.takeIf { name -> name.isNotBlank() } ?: "Nearby device", it) }
        }
    }

    suspend fun removeGroup() = onMain {
        runCatching { action { listener -> manager.removeGroup(channel, listener) } }
        mutableGroupFormed.value = false
    }

    suspend fun cancelConnect() = onMain {
        runCatching { action { listener -> manager.cancelConnect(channel, listener) } }
    }

    /** Called from the broadcast receiver, so it stays synchronous and simply posts the result. */
    private fun refreshGroupState() {
        runCatching {
            manager.requestConnectionInfo(channel) { info -> mutableGroupFormed.value = info?.groupFormed == true }
        }
    }

    private suspend fun groupInfo(): WifiP2pGroup? = onMain {
        suspendCancellableCoroutine { continuation ->
            manager.requestGroupInfo(channel) { group ->
                if (continuation.isActive) continuation.resume(group)
            }
        }
    }

    private suspend fun requestConnectionInfo(): WifiP2pInfo? = onMain {
        suspendCancellableCoroutine { continuation ->
            manager.requestConnectionInfo(channel) { info ->
                if (continuation.isActive) continuation.resume(info)
            }
        }
    }

    private suspend fun requestPeers(): Collection<WifiP2pDevice> = onMain {
        suspendCancellableCoroutine { continuation ->
            manager.requestPeers(channel) { peers ->
                if (continuation.isActive) continuation.resume(peers.deviceList)
            }
        }
    }

    /**
     * This device's P2P address, preferring the framework broadcast and falling back to the API 29+
     * query. A blank address would produce a QR code nobody can connect to, so it is an error.
     */
    private suspend fun deviceAddress(): String {
        mutableLocalAddress.value.takeIf { it.isNotBlank() }?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { queryDeviceInfoAddress() }.getOrDefault("").takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        // The broadcast is sticky, so this usually completes immediately; the wait covers a cold radio.
        val pushed = withTimeoutOrNull(3_000) { mutableLocalAddress.first { it.isNotBlank() } }
        return pushed ?: throw WifiP2pFailure(
            "Could not read this device's Wi-Fi Direct address. Turn Wi-Fi off and on, then retry.",
        )
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun queryDeviceInfoAddress(): String = onMain {
        suspendCancellableCoroutine { continuation ->
            manager.requestDeviceInfo(channel) { device ->
                if (continuation.isActive) continuation.resume(device?.deviceAddress.orEmpty())
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.ownDevice(): WifiP2pDevice? =
        getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE) as? WifiP2pDevice

    private suspend fun <T> onMain(block: suspend () -> T): T = withContext(Dispatchers.Main) { block() }

    private suspend fun action(invoke: (WifiP2pManager.ActionListener) -> Unit) =
        suspendCancellableCoroutine { continuation ->
            invoke(
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onFailure(reason: Int) {
                        if (continuation.isActive) continuation.resumeWithException(WifiP2pFailure(describe(reason)))
                    }
                },
            )
        }

    private fun describe(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "Wi-Fi Direct reported an internal error. Toggle Wi-Fi and retry."
        WifiP2pManager.P2P_UNSUPPORTED -> "This device does not support Wi-Fi Direct."
        WifiP2pManager.BUSY -> "The Wi-Fi Direct service is busy. Close the system Wi-Fi dialog and retry."
        else -> "Wi-Fi Direct refused the request (code $reason)."
    }
}
