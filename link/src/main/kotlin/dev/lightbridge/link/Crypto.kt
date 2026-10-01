// SPDX-License-Identifier: MIT
/*
 * End-to-end encryption for the Wi-Fi Direct link.
 *
 * Design (all primitives are part of the platform since API 24 — no third-party crypto):
 *
 *   • Ephemeral ECDH on NIST P-256  →  a fresh shared secret for every session.
 *   • Long-term ECDSA P-256 identity →  lets the other side recognise you next time.
 *   • HKDF-SHA256 (RFC 5869)         →  two directional AES-256-GCM keys from that secret.
 *   • AES-256-GCM                    →  confidentiality + integrity per record.
 *   • Six-digit SAS                  →  the humans confirm the same code on both screens.
 *
 * The QR code is the authenticated out-of-band channel: it carries the host's ephemeral public key,
 * so a device that scans it knows it is talking to the device that displayed it. The joiner's
 * identity starts unauthenticated, which is exactly what the SAS confirmation resolves.
 */
package dev.lightbridge.link

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.interfaces.ECPublicKey
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Curve used by every key in this protocol. P-256 is available on every Android version we ship. */
private const val CURVE = "secp256r1"

/** Info string bound into HKDF so keys cannot be confused with another protocol's keys. */
private const val HKDF_INFO = "LightBridge/WiFiDirect/v1/record-keys"

/** Uncompressed P-256 points are 65 bytes: 0x04 || X(32) || Y(32). */
public const val EC_PUBLIC_KEY_BYTES: Int = 65

/** A key pair plus the raw point encoding peers exchange over the wire. */
public class LinkKeyPair(public val keyPair: KeyPair) {
    public val publicBytes: ByteArray = encodePoint(keyPair.public as ECPublicKey)

    public companion object {
        public fun generate(random: SecureRandom = SecureRandom()): LinkKeyPair {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec(CURVE), random)
            return LinkKeyPair(generator.generateKeyPair())
        }

        /** Rebuilds a public key from the 65-byte uncompressed point a peer sent. */
        public fun decodePublic(bytes: ByteArray): PublicKey {
            require(bytes.size == EC_PUBLIC_KEY_BYTES && bytes[0] == 0x04.toByte()) {
                "not an uncompressed P-256 point"
            }
            val params = p256Params()
            val x = java.math.BigInteger(1, bytes.copyOfRange(1, 33))
            val y = java.math.BigInteger(1, bytes.copyOfRange(33, 65))
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
        }

        /** Restores a persisted identity key from its PKCS#8 encoding. */
        public fun decodePrivate(bytes: ByteArray): PrivateKey =
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))

        /** Restores a persisted identity public key from its X.509 encoding. */
        public fun decodeX509(bytes: ByteArray): PublicKey =
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))

        internal fun p256Params(): ECParameterSpec =
            java.security.AlgorithmParameters.getInstance("EC").run {
                init(ECGenParameterSpec(CURVE))
                getParameterSpec(ECParameterSpec::class.java)
            }
    }
}

private fun encodePoint(key: ECPublicKey): ByteArray {
    fun fixed(value: java.math.BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        val start = if (raw.size > 32) raw.size - 32 else 0
        val length = minOf(32, raw.size)
        System.arraycopy(raw, start, out, 32 - length, length)
        return out
    }
    return byteArrayOf(0x04) + fixed(key.w.affineX) + fixed(key.w.affineY)
}

/** SHA-256 over a public key's X.509 encoding — the stable "who is this" value for contacts. */
public fun identityFingerprint(publicKey: PublicKey): String =
    MessageDigest.getInstance("SHA-256").digest(publicKey.encoded).hex()

/** Human-facing short form: first 16 hex characters, grouped 4-4-4-4. */
public fun shortFingerprint(fingerprint: String): String =
    fingerprint.take(16).chunked(4).joinToString("-")

/** ECDH shared secret between our private key and a peer's public point. */
public fun ecdh(privateKey: PrivateKey, peerPublic: PublicKey): ByteArray =
    KeyAgreement.getInstance("ECDH").run {
        init(privateKey)
        doPhase(peerPublic, true)
        generateSecret()
    }

/** RFC 5869 HKDF-SHA256. */
public object Hkdf {
    public fun extract(salt: ByteArray, ikm: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(ikm)
        }

    public fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "invalid HKDF length $length" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = java.io.ByteArrayOutputStream(length)
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            mac.reset()
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    /** One-shot extract-then-expand. */
    public fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)
}

/** The two directional record keys and the six-digit verification code for one session. */
public class SessionKeys(
    /** Host → joiner AES-256-GCM key. */
    public val hostToJoiner: ByteArray,
    /** Joiner → host AES-256-GCM key. */
    public val joinerToHost: ByteArray,
    /** Six decimal digits both screens must display identically. */
    public val sas: String,
    /** SHA-256 of every handshake input; both sides can compare it after the fact. */
    public val transcriptHash: ByteArray,
) {
    override fun toString(): String =
        "SessionKeys(sas=$sas, transcript=${transcriptHash.hex().take(16)}…)"

    /** Wipes key material. Called when a session ends. */
    public fun destroy() {
        hostToJoiner.fill(0)
        joinerToHost.fill(0)
    }
}

/**
 * Derives the session keys from the ECDH secret and both identities.
 *
 * Everything that identifies the session goes into the transcript: the QR session id, both
 * ephemeral points and both identity keys. The SAS is a truncated hash of that transcript, so the
 * two people are confirming the *whole* handshake — not just a shared secret some middlebox could
 * have arranged.
 */
public fun deriveSessionKeys(
    sharedSecret: ByteArray,
    sessionId: ByteArray,
    hostEphemeralPublic: ByteArray,
    joinerEphemeralPublic: ByteArray,
    hostIdentity: ByteArray,
    joinerIdentity: ByteArray,
): SessionKeys {
    val okm = Hkdf.derive(sharedSecret, sessionId, HKDF_INFO.toByteArray(Charsets.UTF_8), 64)
    val digest = MessageDigest.getInstance("SHA-256").run {
        update(sessionId)
        update(hostEphemeralPublic); update(joinerEphemeralPublic)
        update(hostIdentity); update(joinerIdentity)
        digest()
    }
    val digits = ((digest[0].toInt() and 0xFF shl 16) or (digest[1].toInt() and 0xFF shl 8) or
        (digest[2].toInt() and 0xFF)) % 1_000_000
    return SessionKeys(
        hostToJoiner = okm.copyOfRange(0, 32),
        joinerToHost = okm.copyOfRange(32, 64),
        sas = digits.toString().padStart(6, '0'),
        transcriptHash = digest,
    )
}

/** ECDSA-P256 signature over a caller-supplied message, using a long-term identity key. */
public object IdentitySigner {
    public fun sign(identity: PrivateKey, message: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(identity)
            update(message)
            sign()
        }

    public fun verify(identity: PublicKey, message: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(identity)
                update(message)
                verify(signature)
            }
        }.getOrDefault(false)
}
