// SPDX-License-Identifier: MIT
/*
 * Handshake.
 *
 * Joiner → host "hello":  joiner ephemeral point, joiner identity key, device name, and an ECDSA
 *                         signature over every one of those fields.
 * Host → joiner "reply":  the same for the host, signed with the host identity key.
 *
 * After both signatures verify, each side derives the two direction record keys and the six digit
 * code the two people compare on screen. The host's identity is already known to the joiner (it was
 * inside the scanned QR), the joiner's is new — which is exactly what the code confirms.
 */
package dev.lightbridge.link

import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom

private const val HANDSHAKE_VERSION = 1

/** A long-term device identity: the key pair from `files/identity`, plus the name peers will see. */
public class LocalIdentity(
    public val privateKey: PrivateKey,
    public val publicKey: PublicKey,
    public val name: String,
) {
    public val fingerprint: String = identityFingerprint(publicKey)

    public val publicKeyBytes: ByteArray get() = publicKey.encoded
}

/** The result of a completed handshake. */
public class EstablishedSession(
    public val keys: SessionKeys,
    public val peerIdentity: PublicKey,
    public val peerFingerprint: String,
    public val peerName: String,
    /** True when the peer's identity key is the one the QR code carried (i.e. we scanned them). */
    public val peerIdentityFromQr: Boolean,
) {
    /** Short form for the UI: `a1b2-c3d4-e5f6-7890`. */
    public val peerShortFingerprint: String get() = shortFingerprint(peerFingerprint)

    public fun destroy() = keys.destroy()
}

/** Joiner side: builds the hello, then completes once the host replies. */
public class JoinerHandshake(
    private val sessionId: ByteArray,
    private val hostEphemeral: ByteArray,
    private val hostIdentity: ByteArray,
    private val identity: LocalIdentity,
    private val random: SecureRandom = SecureRandom(),
) {
    private val ephemeral = LinkKeyPair.generate(random)

    /** The one record to send to the host. */
    public val hello: ByteArray = Writer(320)
        .u8(HANDSHAKE_VERSION)
        .blob(sessionId)
        .blob(ephemeral.publicBytes)
        .blob(identity.publicKeyBytes)
        .text(identity.name.take(64))
        .blob(IdentitySigner.sign(identity.privateKey, signatureBase(ephemeral.publicBytes)))
        .toByteArray()

    private fun signatureBase(joinerEphemeral: ByteArray): ByteArray =
        Writer(256).blob(sessionId).blob(hostEphemeral).blob(joinerEphemeral)
            .blob(identity.publicKeyBytes).toByteArray()

    /** Verifies the host's reply and derives the session keys. */
    public fun complete(reply: ByteArray): EstablishedSession {
        val reader = Reader(reply)
        require(reader.u8() == HANDSHAKE_VERSION) { "unsupported handshake version" }
        require(reader.blob().contentEqualsConstantTime(sessionId)) { "session id mismatch" }
        val hostEphemeralEcho = reader.blob()
        require(hostEphemeralEcho.contentEqualsConstantTime(hostEphemeral)) {
            "the host used a different key than its QR code promised"
        }
        val hostIdentityEcho = reader.blob()
        require(hostIdentityEcho.contentEqualsConstantTime(hostIdentity)) {
            "the host identity does not match its QR code"
        }
        val hostName = reader.text().takeIf { it.isNotBlank() } ?: "LightBridge device"
        val signature = reader.blob()
        reader.expectEnd("handshake reply")

        val hostPublicKey = LinkKeyPair.decodePublic(hostEphemeral)
        val hostIdentityKey = LinkKeyPair.decodeX509(hostIdentity)
        val base = Writer(384).blob(sessionId).blob(hostEphemeralEcho).blob(ephemeral.publicBytes)
            .blob(hostIdentityEcho).blob(identity.publicKeyBytes).toByteArray()
        require(IdentitySigner.verify(hostIdentityKey, base, signature)) {
            "the host could not prove it holds its identity key"
        }

        val shared = ecdh(ephemeral.keyPair.private, hostPublicKey)
        val keys = deriveSessionKeys(
            sharedSecret = shared,
            sessionId = sessionId,
            hostEphemeralPublic = hostEphemeral,
            joinerEphemeralPublic = ephemeral.publicBytes,
            hostIdentity = hostIdentity,
            joinerIdentity = identity.publicKeyBytes,
        )
        return EstablishedSession(
            keys = keys,
            peerIdentity = hostIdentityKey,
            peerFingerprint = identityFingerprint(hostIdentityKey),
            peerName = hostName,
            peerIdentityFromQr = true,
        )
    }
}

/** Host side: consumes the joiner's hello and produces the signed reply. */
public class HostHandshake(
    private val sessionId: ByteArray,
    private val ephemeral: LinkKeyPair,
    private val identity: LocalIdentity,
) {
    /** Verifies the joiner's hello; returns the session plus the reply to send back. */
    public fun accept(hello: ByteArray): AcceptedHello {
        val reader = Reader(hello)
        require(reader.u8() == HANDSHAKE_VERSION) { "unsupported handshake version" }
        require(reader.blob().contentEqualsConstantTime(sessionId)) { "session id mismatch" }
        val joinerEphemeral = reader.blob()
        require(joinerEphemeral.size == EC_PUBLIC_KEY_BYTES) { "bad ephemeral key" }
        val joinerIdentity = reader.blob()
        require(joinerIdentity.size in 32..512) { "bad identity key" }
        val joinerName = reader.text().takeIf { it.isNotBlank() } ?: "LightBridge device"
        val signature = reader.blob()
        reader.expectEnd("handshake hello")

        val joinerPublicKey = LinkKeyPair.decodePublic(joinerEphemeral)
        val joinerIdentityKey = LinkKeyPair.decodeX509(joinerIdentity)
        val joinerBase = Writer(256).blob(sessionId).blob(ephemeral.publicBytes).blob(joinerEphemeral)
            .blob(joinerIdentity).toByteArray()
        require(IdentitySigner.verify(joinerIdentityKey, joinerBase, signature)) {
            "the other device could not prove it holds its identity key"
        }

        val shared = ecdh(ephemeral.keyPair.private, joinerPublicKey)
        val keys = deriveSessionKeys(
            sharedSecret = shared,
            sessionId = sessionId,
            hostEphemeralPublic = ephemeral.publicBytes,
            joinerEphemeralPublic = joinerEphemeral,
            hostIdentity = identity.publicKeyBytes,
            joinerIdentity = joinerIdentity,
        )
        val reply = Writer(384)
            .u8(HANDSHAKE_VERSION)
            .blob(sessionId)
            .blob(ephemeral.publicBytes)
            .blob(identity.publicKeyBytes)
            .text(identity.name.take(64))
            .blob(
                IdentitySigner.sign(
                    identity.privateKey,
                    Writer(384).blob(sessionId).blob(ephemeral.publicBytes).blob(joinerEphemeral)
                        .blob(identity.publicKeyBytes).blob(joinerIdentity).toByteArray(),
                ),
            )
            .toByteArray()

        return AcceptedHello(
            session = EstablishedSession(
                keys = keys,
                peerIdentity = joinerIdentityKey,
                peerFingerprint = identityFingerprint(joinerIdentityKey),
                peerName = joinerName,
                peerIdentityFromQr = false,
            ),
            reply = reply,
        )
    }
}

/** The host's verified view of the joiner, plus the bytes to answer with. */
public class AcceptedHello(public val session: EstablishedSession, public val reply: ByteArray)
