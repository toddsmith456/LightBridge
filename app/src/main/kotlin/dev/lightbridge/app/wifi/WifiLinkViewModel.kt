// SPDX-License-Identifier: MIT
package dev.lightbridge.app.wifi

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.lightbridge.app.InboxStore
import dev.lightbridge.app.LightBridgeApplication
import dev.lightbridge.app.Receipt
import dev.lightbridge.link.CHUNK_BYTES
import dev.lightbridge.link.ConnectPayload
import dev.lightbridge.link.Contact
import dev.lightbridge.link.Contacts
import dev.lightbridge.link.DEFAULT_LINK_PORT
import dev.lightbridge.link.EstablishedSession
import dev.lightbridge.link.FileReceiver
import dev.lightbridge.link.LinkKeyPair
import dev.lightbridge.link.LinkMessage
import dev.lightbridge.link.MAX_LINK_FILE_BYTES
import dev.lightbridge.link.SecureChannel
import dev.lightbridge.link.Trust
import dev.lightbridge.link.chunkCount
import dev.lightbridge.link.chunkSize
import java.io.EOFException
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Where the link is in its short life. */
internal enum class LinkPhase {
    Idle,
    Preparing,
    AwaitingPeer,
    Connecting,
    Verifying,
    Ready,
    Sending,
    Receiving,
    Finished,
    Failed,
}

internal enum class LinkRole { None, Host, Joiner }

internal data class LinkPeer(val name: String, val fingerprint: String, val trust: Trust) {
    val shortFingerprint: String get() = dev.lightbridge.link.shortFingerprint(fingerprint)
}

/** Live progress for whichever direction a file is moving. */
internal data class LinkTransfer(
    val name: String,
    val size: Long,
    val moved: Long,
    val outgoing: Boolean,
) {
    val progress: Float get() = if (size <= 0) 0f else (moved.toDouble() / size).toFloat().coerceIn(0f, 1f)
}

internal data class LinkState(
    val phase: LinkPhase = LinkPhase.Idle,
    val role: LinkRole = LinkRole.None,
    /** The text to show as a QR code while hosting. */
    val qr: String? = null,
    val status: String = "",
    val error: String? = null,
    val peer: LinkPeer? = null,
    val sas: String? = null,
    val localConfirmed: Boolean = false,
    val peerConfirmed: Boolean = false,
    val transfer: LinkTransfer? = null,
    val receipt: Receipt? = null,
    val nearby: List<PeerDevice> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val deviceName: String = "",
    val missingPermission: Boolean = false,
    val wifiOff: Boolean = false,
    val supported: Boolean = true,
)

/**
 * Drives one Wi-Fi Direct link at a time: create or join, handshake, confirm, then move files.
 *
 * Everything is cancellation-driven: leaving the tab, failing a step or pressing Stop tears the same
 * things down (socket, channel, P2P group, key material), so a half-open group can never linger.
 */
internal class WifiLinkViewModel(application: Application) : AndroidViewModel(application) {

    private val inbox: InboxStore = LightBridgeApplication.inbox(application)
    private val identities = IdentityStore(application)
    private val controller: WifiDirectController? =
        runCatching { WifiDirectController(application) }.getOrElse { null }

    private val mutable = MutableStateFlow(
        LinkState(
            contacts = identities.contacts(),
            deviceName = identities.deviceName(),
            supported = controller != null,
            status = "Ready when you are.",
        ),
    )
    val state = mutable.asStateFlow()

    private var linkJob: Job? = null
    private var sendJob: Job? = null
    private var session: LinkSession? = null
    private var channel: SecureChannel? = null
    private var established: EstablishedSession? = null
    private var serverSocket: ServerSocket? = null
    private var acceptedId: CompletableDeferred<Unit>? = null
    private var verifiedId: CompletableDeferred<Unit>? = null
    private var rejectedReason: String? = null
    private var sender: FileSender? = null

    init {
        controller?.start()
        viewModelScope.launch { inbox.refresh() }
        // Reconcile the permission flags with reality whenever the tab is opened.
        refreshReadiness()
    }

    // ---------------------------------------------------------------- readiness

    fun refreshReadiness() {
        val context = getApplication<Application>()
        val missing = LinkPermissions.missing(context).isNotEmpty()
        val wifi = controller?.wifiEnabled() != true
        mutable.update { it.copy(missingPermission = missing, wifiOff = wifi) }
    }

    fun onPermissionResult() = refreshReadiness()

    // ---------------------------------------------------------------- hosting

    /** Creates the group, then shows the QR code the other device scans. */
    fun startHosting() {
        if (controller == null) return fail("This device does not support Wi-Fi Direct.")
        refreshReadiness()
        if (mutable.value.missingPermission) return fail("Allow nearby-device access first, then tap Start again.")
        if (mutable.value.wifiOff) return fail("Turn Wi-Fi on to use a direct link.")
        stop(keepContacts = true)
        val identity = identities.identity()
        mutable.update {
            it.copy(phase = LinkPhase.Preparing, role = LinkRole.Host, error = null, receipt = null,
                status = "Creating a private group…")
        }
        linkJob = viewModelScope.launch {
            try {
                val details = controller.createGroup()
                val (server, port) = withContext(Dispatchers.IO) { LinkTransport.bindServer(DEFAULT_LINK_PORT) }
                serverSocket = server
                val sessionId = ByteArray(ConnectPayload.SESSION_ID_BYTES).also { SecureRandom().nextBytes(it) }
                val ephemeral = LinkKeyPair.generate()
                val payload = ConnectPayload(
                    sessionId = sessionId,
                    hostName = identities.deviceName(),
                    hostEphemeralPublic = ephemeral.publicBytes,
                    hostIdentityPublic = identity.publicKeyBytes,
                    hostFingerprint = identity.fingerprint,
                    deviceAddress = details.deviceAddress,
                    ssid = details.ssid,
                    passphrase = details.passphrase,
                    ownerAddress = details.ownerAddress,
                    port = port,
                )
                mutable.update {
                    it.copy(phase = LinkPhase.AwaitingPeer, qr = payload.encode(),
                        status = "Waiting for the other device to scan this code…")
                }
                // Blocking accept, released by closing the socket when the link is stopped. Only a
                // peer-to-peer address is let through, so a listener bound to the wildcard cannot be
                // reached by anything else that happens to be on the network.
                val socket = withContext(Dispatchers.IO) { LinkTransport.acceptPeer(server) }
                socket.soTimeout = 0
                startSession(HostLinkSession(socket, identity, sessionId, ephemeral))
            } catch (e: Throwable) {
                if (viewModelScope.isActive) fail(e.message ?: "The link stopped unexpectedly.")
            }
        }
    }

    // ---------------------------------------------------------------- joining

    /** Joins the group described by a scanned QR code. */
    fun join(qrText: String) {
        if (controller == null) return fail("This device does not support Wi-Fi Direct.")
        refreshReadiness()
        if (mutable.value.missingPermission) return fail("Allow nearby-device access first, then scan again.")
        if (mutable.value.wifiOff) return fail("Turn Wi-Fi on to use a direct link.")
        val payload = ConnectPayload.decodeOrNull(qrText)
            ?: return fail("That is not a LightBridge Wi-Fi code. Scan the code on the other device's Link tab.")
        stop(keepContacts = true)
        val identity = identities.identity()
        mutable.update {
            it.copy(phase = LinkPhase.Connecting, role = LinkRole.Joiner, error = null, receipt = null,
                status = "Connecting to ${payload.hostName}…")
        }
        linkJob = viewModelScope.launch {
            try {
                controller.connectTo(payload.deviceAddress)
                if (!controller.awaitGroupFormed(30_000)) error("The other device did not accept the connection.")
                // The interface needs a moment before it can route.
                delay(1_200)
                val socket = withContext(Dispatchers.IO) { LinkTransport.connect(payload.ownerAddress, payload.port) }
                socket.tcpNoDelay = true
                socket.soTimeout = 0
                startSession(JoinerLinkSession(socket, identity, payload))
            } catch (e: Throwable) {
                if (viewModelScope.isActive) fail(e.message ?: "Could not join that device.")
            }
        }
    }

    // ---------------------------------------------------------------- session

    private suspend fun startSession(newSession: LinkSession) {
        session = newSession
        mutable.update { it.copy(phase = LinkPhase.Connecting, status = "Securing the link…") }
        val established = withContext(Dispatchers.IO) { newSession.handshake() }
        this.established = established
        val channel = newSession.openChannel(established)
        this.channel = channel
        val trust = Contacts.trustOf(identities.contacts(), established.peerFingerprint)
        mutable.update {
            it.copy(
                phase = LinkPhase.Verifying,
                peer = LinkPeer(established.peerName, established.peerFingerprint, trust),
                sas = established.keys.sas,
                status = when (trust) {
                    Trust.Verified -> "Known contact — confirming automatically."
                    else -> "Compare the code with the other device."
                },
            )
        }
        if (trust == Trust.Verified) confirmPeer(auto = true)
        receiveLoop(channel)
    }

    /**
     * The gate. Both devices must confirm the same six digits before any file moves, so a device in
     * the middle cannot relay a file to someone who never compared the code.
     */
    fun confirmPeer(auto: Boolean = false) {
        val state = mutable.value
        val session = established ?: return
        val channel = channel ?: return
        mutable.update { it.copy(localConfirmed = true) }
        // Remember this identity. A manual confirmation also means the user vouched for the code.
        val now = System.currentTimeMillis()
        val contact = Contact(
            id = session.peerFingerprint,
            name = state.peer?.name ?: "LightBridge device",
            publicKey = session.peerIdentity.encoded,
            addedAt = now,
            lastSeenAt = now,
            verified = !auto || state.peer?.trust == Trust.Verified,
        )
        val updated = Contacts.upsert(identities.contacts(), contact)
        identities.saveContacts(updated)
        mutable.update { it.copy(contacts = updated, status = if (auto) "Contact verified. Link ready." else "Code confirmed. Link ready.") }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { channel.send(LinkMessage.Confirm(session.keys.sas).encode()) } }
        }
        markReadyIfBothConfirmed()
    }

    /** The user says the codes do not match: drop the link without sending anything. */
    fun rejectPeer() {
        val channel = channel
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { channel?.send(LinkMessage.Failure("The code did not match.").encode()) } }
        }
        stop(keepContacts = true)
        mutable.update { it.copy(phase = LinkPhase.Failed, error = "You stopped the pairing because the codes did not match. Nothing was transferred.") }
    }

    private fun markReadyIfBothConfirmed() {
        val current = mutable.value
        if (current.localConfirmed && current.peerConfirmed && current.phase != LinkPhase.Sending) {
            mutable.update { it.copy(phase = LinkPhase.Ready, status = "Link ready. Send a file, or wait for one.") }
        }
    }

    /** Reads records until the peer closes the link. */
    private suspend fun receiveLoop(channel: SecureChannel) {
        var receiver: FileReceiver? = null
        var receivingId: String? = null
        try {
            while (viewModelScope.isActive) {
                val message = withContext(Dispatchers.IO) {
                    if (session == null) throw EOFException("link closed")
                    LinkMessage.decode(channel.receive())
                }
                when (message) {
                    is LinkMessage.Confirm -> {
                        if (message.sas != established?.keys?.sas) {
                            throw IllegalStateException("The other device confirmed a different code.")
                        }
                        mutable.update { it.copy(peerConfirmed = true, status = "Both devices confirmed the code.") }
                        markReadyIfBothConfirmed()
                    }

                    is LinkMessage.Offer -> {
                        val decision = acceptOffer(message)
                        if (decision == null) {
                            withContext(Dispatchers.IO) { channel.send(LinkMessage.Decline(message.id, rejectedReason ?: "Declined").encode()) }
                        } else {
                            withContext(Dispatchers.IO) { channel.send(LinkMessage.Accept(message.id).encode()) }
                            receiver = decision
                            receivingId = message.id
                            mutable.update {
                                it.copy(phase = LinkPhase.Receiving, receipt = null,
                                    transfer = LinkTransfer(message.name, message.size, 0, outgoing = false),
                                    status = "Receiving ${message.name}…")
                            }
                        }
                    }

                    is LinkMessage.Chunk -> {
                        val active = receiver ?: throw IllegalStateException("Unexpected chunk outside a transfer.")
                        withContext(Dispatchers.IO) { active.accept(message) }
                        mutable.update { current ->
                            current.copy(transfer = current.transfer?.copy(moved = active.received))
                        }
                    }

                    is LinkMessage.Complete -> {
                        val active = receiver ?: throw IllegalStateException("Transfer finished before it started.")
                        val id = receivingId ?: throw IllegalStateException("Missing transfer id.")
                        val part = inbox.partFile(id)
                        val digest = withContext(Dispatchers.IO) { active.finish() }
                        withContext(Dispatchers.IO) { active.close() }
                        val receipt = inbox.commitPart(
                            part = part, id = id, name = active.offer.name, mime = active.offer.mime,
                            size = active.offer.size, digestHex = digest, source = Receipt.Source.Link,
                        )
                        inbox.refresh()
                        withContext(Dispatchers.IO) { channel.send(LinkMessage.Verified(id).encode()) }
                        receiver = null
                        receivingId = null
                        mutable.update {
                            it.copy(phase = LinkPhase.Finished, receipt = receipt, transfer = null,
                                status = "${receipt.name} is in your inbox.")
                        }
                    }

                    is LinkMessage.Verified -> verifiedId?.complete(Unit)

                    is LinkMessage.Decline -> {
                        rejectedReason = message.reason
                        acceptedId?.completeExceptionally(IllegalStateException("The other device declined: ${message.reason}"))
                    }

                    is LinkMessage.Failure -> throw IllegalStateException(message.message)

                    is LinkMessage.Bye -> return
                    is LinkMessage.Accept -> acceptedId?.complete(Unit)
                }
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (viewModelScope.isActive) {
                val friendly = when {
                    e is EOFException || e is java.net.SocketException -> "The other device closed the link."
                    else -> e.message ?: "The link stopped."
                }
                fail(friendly)
            }
        } finally {
            runCatching { receiver?.close() }
        }
    }

    /** Validates an incoming offer and opens the part file it will stream into. */
    private suspend fun acceptOffer(offer: LinkMessage.Offer): FileReceiver? {
        val context = getApplication<Application>()
        val limit = minOf(inbox.limitBytes, MAX_LINK_FILE_BYTES)
        if (offer.size > limit) {
            rejectedReason = "Larger than this device accepts."
            return null
        }
        if (!inbox.hasRoomFor(offer.size)) {
            rejectedReason = "Not enough free storage."
            return null
        }
        val id = offer.id
        if (UUID.fromString(id).toString() != id) {
            rejectedReason = "Malformed transfer id."
            return null
        }
        return withContext(Dispatchers.IO) {
            FileReceiver(offer, inbox.partFile(id).outputStream(), maxBytes = limit)
        }
    }

    // ---------------------------------------------------------------- sending

    /** Sends a file to the other device. Only possible once both sides confirmed the code. */
    fun sendFile(uri: Uri) {
        val channel = channel ?: return fail("No link is open.")
        if (mutable.value.phase != LinkPhase.Ready) return fail("Confirm the code on both devices first.")
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            var prepared: FileSender? = null
            try {
                mutable.update { it.copy(phase = LinkPhase.Sending, status = "Preparing the file…", error = null) }
                prepared = withContext(Dispatchers.IO) { prepareOutgoing(uri) }
                sender = prepared
                mutable.update {
                    it.copy(transfer = LinkTransfer(prepared.name, prepared.size, 0, outgoing = true),
                        status = "Waiting for the other device to accept…")
                }
                acceptedId = CompletableDeferred()
                verifiedId = CompletableDeferred()
                withContext(Dispatchers.IO) { channel.send(prepared.offer.encode()) }
                withTimeout(30_000) { acceptedId!!.await() }

                mutable.update { it.copy(status = "Sending ${prepared.name}…") }
                withContext(Dispatchers.IO) {
                    prepared.openStream().use { stream ->
                        val buffer = ByteArray(CHUNK_BYTES)
                        val total = chunkCount(prepared.size)
                        for (index in 0 until total) {
                            val expected = chunkSize(prepared.size, index)
                            var read = 0
                            while (read < expected) {
                                val n = stream.read(buffer, read, expected - read)
                                if (n < 0) throw EOFException("The file got shorter while sending.")
                                read += n
                            }
                            val data = if (expected == buffer.size) buffer.copyOf() else buffer.copyOf(expected)
                            channel.send(LinkMessage.Chunk(prepared.id, index, data).encode())
                            val moved = prepared.movedSoFar(index)
                            mutable.update { current -> current.copy(transfer = current.transfer?.copy(moved = moved)) }
                        }
                    }
                    channel.send(LinkMessage.Complete(prepared.id).encode())
                }
                mutable.update { it.copy(status = "Waiting for verification…") }
                withTimeout(120_000) { verifiedId!!.await() }
                mutable.update {
                    it.copy(phase = LinkPhase.Ready, transfer = null,
                        status = "${prepared.name} was delivered and verified on the other device.")
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                fail(e.message ?: "The transfer failed.")
            } finally {
                runCatching { withContext(Dispatchers.IO) { prepared?.deleteTemp() } }
            }
        }
    }

    /** Copies the picked document into cache while hashing it, so sending never buffers it all. */
    private fun prepareOutgoing(uri: Uri): FileSender {
        val context = getApplication<Application>()
        val resolver = context.contentResolver
        var name = "transfer.bin"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { name = it }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    require(cursor.getLong(sizeIndex) <= MAX_LINK_FILE_BYTES) {
                        "That file is too large to send over a link (limit ${MAX_LINK_FILE_BYTES / (1024 * 1024)} MiB)."
                    }
                }
            }
        }
        val temp = File.createTempFile("outgoing-", ".bin", context.cacheDir)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        size += read
                        require(size <= MAX_LINK_FILE_BYTES) {
                            "That file is too large to send over a link (limit ${MAX_LINK_FILE_BYTES / (1024 * 1024)} MiB)."
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("That file could not be opened.")
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        require(size > 0) { "That file is empty." }
        return FileSender(
            id = UUID.randomUUID().toString(),
            name = name.take(dev.lightbridge.link.MAX_FILE_NAME_LENGTH),
            mime = resolver.getType(uri)?.takeIf { it.contains('/') } ?: "application/octet-stream",
            size = size,
            digestHex = digest.digest().joinToString("") { "%02x".format(it) },
            temp = temp,
        )
    }

    // ---------------------------------------------------------------- contacts and housekeeping

    fun setDeviceName(name: String) {
        identities.setDeviceName(name)
        mutable.update { it.copy(deviceName = identities.deviceName()) }
    }

    fun forgetContact(id: String) {
        val updated = Contacts.remove(identities.contacts(), id)
        identities.saveContacts(updated)
        mutable.update { it.copy(contacts = updated) }
    }

    fun renameContact(id: String, name: String) {
        if (name.isBlank()) return
        val updated = Contacts.rename(identities.contacts(), id, name)
        identities.saveContacts(updated)
        mutable.update { it.copy(contacts = updated) }
    }

    fun searchNearby() {
        val controller = controller ?: return
        viewModelScope.launch {
            try {
                mutable.update { it.copy(status = "Searching for nearby devices…") }
                val found = controller.discoverPeers()
                mutable.update {
                    it.copy(nearby = found, status = if (found.isEmpty()) "No nearby devices yet. Try again next to the other phone." else "${found.size} nearby device(s).")
                }
            } catch (e: Throwable) {
                fail(e.message ?: "Could not search for nearby devices.")
            }
        }
    }

    fun clearError() = mutable.update { it.copy(error = null, phase = if (it.receipt != null) LinkPhase.Finished else LinkPhase.Idle) }

    fun stop(keepContacts: Boolean = true) {
        sendJob?.cancel(); sendJob = null
        linkJob?.cancel(); linkJob = null
        acceptedId?.cancel(); verifiedId?.cancel()
        acceptedId = null; verifiedId = null
        runCatching { channel?.close() }
        runCatching { session?.close() }
        runCatching { serverSocket?.close() }
        runCatching { sender?.deleteTemp() }
        channel = null; session = null; serverSocket = null; sender = null
        established?.destroy()
        established = null
        viewModelScope.launch { runCatching { controller?.removeGroup() } }
        mutable.update {
            it.copy(
                phase = LinkPhase.Idle,
                role = LinkRole.None,
                qr = null,
                peer = null,
                sas = null,
                localConfirmed = false,
                peerConfirmed = false,
                transfer = null,
                receipt = null,
                nearby = emptyList(),
                status = "Ready when you are.",
                error = null,
                contacts = if (keepContacts) it.contacts else emptyList(),
            )
        }
    }

    private fun fail(message: String) {
        runCatching { channel?.let { ch -> viewModelScope.launch { runCatching { withContext(Dispatchers.IO) { ch.send(LinkMessage.Failure(message).encode()) } } } } }
        runCatching { channel?.close() }
        runCatching { session?.close() }
        runCatching { serverSocket?.close() }
        channel = null; session = null; serverSocket = null
        mutable.update { it.copy(phase = LinkPhase.Failed, error = message, status = "Link stopped.", transfer = null) }
    }

    override fun onCleared() {
        controller?.stop()
        runCatching { channel?.close() }
        runCatching { session?.close() }
        runCatching { serverSocket?.close() }
        runCatching { sender?.deleteTemp() }
        super.onCleared()
    }
}

/** An outgoing transfer: the offer plus the cached copy that will be streamed. */
internal class FileSender(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val digestHex: String,
    private val temp: File,
) {
    val offer: LinkMessage.Offer get() = LinkMessage.Offer(id, name, mime, size, digestHex)

    fun openStream() = temp.inputStream()

    fun movedSoFar(chunkIndex: Int): Long =
        (0..chunkIndex).sumOf { chunkSize(size, it).toLong() }

    fun deleteTemp() {
        temp.delete()
    }
}

/** Small helper so a scope can check whether it is still alive inside long loops. */
private val CoroutineScope.alive: Boolean get() = isActive
