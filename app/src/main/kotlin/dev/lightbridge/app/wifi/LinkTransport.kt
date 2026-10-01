// SPDX-License-Identifier: MIT
package dev.lightbridge.app.wifi

import dev.lightbridge.link.ConnectPayload
import dev.lightbridge.link.EstablishedSession
import dev.lightbridge.link.HostHandshake
import dev.lightbridge.link.JoinerHandshake
import dev.lightbridge.link.LinkAddressPolicy
import dev.lightbridge.link.LinkKeyPair
import dev.lightbridge.link.LocalIdentity
import dev.lightbridge.link.SecureChannel
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Sockets for the direct link.
 *
 * Every address goes through [LinkAddressPolicy] first: the link can only ever reach a peer on the
 * Wi-Fi Direct group (or a link-local neighbour). No hostname is ever resolved, and the listener
 * prefers to bind only the P2P group owner address, so the socket exists on the direct link alone.
 */
internal object LinkTransport {

    /** Handshake records are small; anything larger is not one of ours. */
    private const val MAX_HANDSHAKE_BYTES = 4096

    /**
     * Binds the host's listener, preferring the P2P address.
     *
     * Returns the socket and the port it actually got (the caller puts that port in the QR code).
     * Falls back to the wildcard address only if this device does not hand out the standard group
     * owner address.
     */
    fun bindServer(preferredPort: Int = dev.lightbridge.link.DEFAULT_LINK_PORT): Pair<ServerSocket, Int> {
        val preferred: InetAddress? = runCatching { InetAddress.getByName(LinkAddressPolicy.DEFAULT_GROUP_OWNER) }
            .getOrNull()
            ?.takeIf { LinkAddressPolicy.isAllowed(it.hostAddress ?: "") }
        for (port in preferredPort until preferredPort + 8) {
            if (preferred != null) {
                runCatching {
                    return ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(preferred, port))
                    } to port
                }
            }
            runCatching {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                } to port
            }
        }
        error("Could not open a port for the link. Close other apps using the network and retry.")
    }

    /** Opens the link socket to a peer address taken from a scanned QR code. */
    fun connect(ownerAddress: String, port: Int, timeoutMs: Int = 12_000): Socket {
        val allowed = LinkAddressPolicy.parse(ownerAddress)
            ?: error("Refusing to connect to $ownerAddress: not a Wi-Fi Direct peer address.")
        require(port in 1024..65535) { "Refusing to connect to port $port." }
        val socket = Socket()
        socket.tcpNoDelay = true
        // The literal was validated above, so this cannot reach a name server.
        socket.connect(InetSocketAddress(InetAddress.getByName(allowed.literal), port), timeoutMs)
        return socket
    }

    /**
     * Accepts the next inbound connection *that is on a peer-to-peer range*.
     *
     * The host may have to fall back to binding the wildcard address, so the remote address is
     * checked here as well: if another device on the same ordinary network finds the port, its
     * socket is closed and the listener keeps waiting for the real peer. A scope suffix is stripped
     * first because the framework reports IPv6 link-local peers as `fe80::1%wlan0`.
     */
    fun acceptPeer(server: ServerSocket): Socket {
        while (true) {
            val socket = server.accept()
            val remote = socket.inetAddress?.hostAddress?.substringBefore('%').orEmpty()
            if (LinkAddressPolicy.isAllowed(remote)) {
                socket.tcpNoDelay = true
                return socket
            }
            closeQuietly(socket)
        }
    }

    fun closeQuietly(vararg closeables: Closeable?) {
        closeables.forEach { runCatching { it?.close() } }
    }

    /** Length-prefixed plaintext record used for the two handshake messages. */
    fun readHandshake(input: DataInputStream): ByteArray {
        val length = input.readUnsignedShort()
        if (length == 0 || length > MAX_HANDSHAKE_BYTES) throw EOFException("bad handshake record ($length bytes)")
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return bytes
    }

    fun writeHandshake(output: DataOutputStream, bytes: ByteArray) {
        require(bytes.size in 1..MAX_HANDSHAKE_BYTES) { "handshake record has ${bytes.size} bytes" }
        output.writeShort(bytes.size)
        output.write(bytes)
        output.flush()
    }
}

/** One end of an established link: the socket plus the encryption that wraps it. */
internal sealed class LinkSession : Closeable {

    abstract val socket: Socket
    abstract val isHost: Boolean
    abstract val localIdentity: LocalIdentity

    /** Runs the handshake and returns the verified session. */
    abstract suspend fun handshake(): EstablishedSession

    private var channel: SecureChannel? = null

    /** Wraps the socket once the handshake keys exist. Call exactly once. */
    fun openChannel(session: EstablishedSession): SecureChannel {
        val existing = channel
        if (existing != null) return existing
        val created = SecureChannel(
            input = socket.getInputStream(),
            output = socket.getOutputStream(),
            sharedKey = if (isHost) session.keys.hostToJoiner else session.keys.joinerToHost,
            isHost = isHost,
            sessionId = sessionId,
        )
        channel = created
        return created
    }

    protected abstract val sessionId: ByteArray

    override fun close() {
        channel?.close()
        LinkTransport.closeQuietly(socket)
    }
}

/** Host side: waits for the joiner's hello and answers with a signed reply. */
internal class HostLinkSession(
    override val socket: Socket,
    override val localIdentity: LocalIdentity,
    override val sessionId: ByteArray,
    private val ephemeral: LinkKeyPair,
) : LinkSession() {

    override val isHost: Boolean = true

    override suspend fun handshake(): EstablishedSession {
        val input = DataInputStream(socket.getInputStream().buffered())
        val output = DataOutputStream(socket.getOutputStream().buffered())
        val hello = LinkTransport.readHandshake(input)
        val accepted = HostHandshake(sessionId, ephemeral, localIdentity).accept(hello)
        LinkTransport.writeHandshake(output, accepted.reply)
        return accepted.session
    }
}

/** Joiner side: sends the hello derived from the scanned QR code and verifies the reply. */
internal class JoinerLinkSession(
    override val socket: Socket,
    override val localIdentity: LocalIdentity,
    private val payload: ConnectPayload,
) : LinkSession() {

    override val isHost: Boolean = false
    override val sessionId: ByteArray = payload.sessionId

    override suspend fun handshake(): EstablishedSession {
        val handshake = JoinerHandshake(
            sessionId = sessionId,
            hostEphemeral = payload.hostEphemeralPublic,
            hostIdentity = payload.hostIdentityPublic,
            identity = localIdentity,
        )
        val output = DataOutputStream(socket.getOutputStream().buffered())
        LinkTransport.writeHandshake(output, handshake.hello)
        val input = DataInputStream(socket.getInputStream().buffered())
        val reply = LinkTransport.readHandshake(input)
        return handshake.complete(reply)
    }
}
