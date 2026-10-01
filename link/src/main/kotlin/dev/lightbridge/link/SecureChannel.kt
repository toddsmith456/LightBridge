// SPDX-License-Identifier: MIT
/*
 * The encrypted record layer.
 *
 * Every record is `u32 length | u8 direction | u64 counter | ciphertext+tag`. The counter is the
 * GCM nonce (each direction uses its own key, so counters never collide), it is also authenticated
 * as additional data, and the receiver refuses anything that is not the exact next counter value.
 * That rejects replayed, reordered, truncated and duplicated records — not just modified ones.
 */
package dev.lightbridge.link

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Largest plaintext record we accept. Any single file chunk is far below this. */
public const val MAX_RECORD_BYTES: Int = 1 shl 20

private const val TAG_BITS = 128
private const val NONCE_BYTES = 12
private const val HEADER_BYTES = 1 + 8

public class LinkIntegrityException(message: String, cause: Throwable? = null) :
    java.io.IOException(message, cause)

/**
 * An authenticated, encrypted, ordered byte-record channel between two devices.
 *
 * Not thread-safe: one sender and one receiver coroutine, which is exactly how the link is used.
 */
public class SecureChannel(
    private val input: InputStream,
    private val output: OutputStream,
    sharedKey: ByteArray,
    /** True on the host: chooses the key direction and the wire direction byte. */
    private val isHost: Boolean,
    /** Bound into every record's additional data so records cannot move between sessions. */
    private val sessionId: ByteArray,
) : AutoCloseable {
    private val key = SecretKeySpec(sharedKey, "AES")
    private val sendDirection: Int = if (isHost) DIRECTION_HOST_TO_JOINER else DIRECTION_JOINER_TO_HOST
    private val receiveDirection: Int = if (isHost) DIRECTION_JOINER_TO_HOST else DIRECTION_HOST_TO_JOINER
    private val aad = sessionId + byteArrayOf(sendDirection.toByte())
    private val receiveAad = sessionId + byteArrayOf(receiveDirection.toByte())
    private var sent = 0L
    private var expected = 0L
    private var closed = false

    /** Number of records sent, exposed for tests and diagnostics. */
    public val recordsSent: Long get() = sent

    /** Number of records received. */
    public val recordsReceived: Long get() = expected

    /** Encrypts [plaintext] and writes one record. */
    public fun send(plaintext: ByteArray) {
        require(plaintext.size <= MAX_RECORD_BYTES) { "record too large: ${plaintext.size}" }
        check(!closed) { "channel is closed" }
        val counter = sent
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonceFor(counter)))
        cipher.updateAAD(aad + longBytes(counter))
        val sealed = cipher.doFinal(plaintext)
        val header = Writer(HEADER_BYTES + 4).u32((HEADER_BYTES + sealed.size).toLong()).u8(sendDirection)
            .u64(counter).toByteArray()
        output.write(header)
        output.write(sealed)
        output.flush()
        sent++
    }

    /** Reads exactly one record, or throws [EOFException] when the peer closed the link. */
    public fun receive(): ByteArray {
        check(!closed) { "channel is closed" }
        val length = readU32() ?: throw EOFException("peer closed the link")
        if (length < HEADER_BYTES + TAG_BITS / 8 || length > MAX_RECORD_BYTES + HEADER_BYTES + 64) {
            throw LinkIntegrityException("impossible record length $length")
        }
        val direction = readFully(1)[0].toInt() and 0xFF
        if (direction != receiveDirection) throw LinkIntegrityException("record from the wrong direction")
        val counter = readFully(8).let { bytes ->
            var value = 0L
            for (b in bytes) value = (value shl 8) or (b.toLong() and 0xFF)
            value
        }
        // Strict ordering: a replay, a duplicate or a gap all fail here, before decryption.
        if (counter != expected) {
            throw LinkIntegrityException("record $counter out of order (expected $expected)")
        }
        val sealed = readFully((length - HEADER_BYTES).toInt())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonceFor(counter)))
        cipher.updateAAD(receiveAad + longBytes(counter))
        val plaintext = try {
            cipher.doFinal(sealed)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw LinkIntegrityException("record failed authentication", e)
        } catch (e: javax.crypto.BadPaddingException) {
            throw LinkIntegrityException("record failed authentication", e)
        }
        expected++
        return plaintext
    }

    /** Returns null at a clean end of stream, so callers can distinguish close from corruption. */
    private fun readU32(): Long? {
        val first = input.read()
        if (first < 0) return null
        var value = (first and 0xFF).toLong()
        repeat(3) { value = (value shl 8) or (readFully(1)[0].toLong() and 0xFF) }
        return value
    }

    private fun readFully(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n < 0) throw EOFException("link closed mid-record")
            read += n
        }
        return buffer
    }

    private fun nonceFor(counter: Long): ByteArray = ByteArray(NONCE_BYTES).also { nonce ->
        val bytes = longBytes(counter)
        System.arraycopy(bytes, 0, nonce, NONCE_BYTES - 8, 8)
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { output.flush() }
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private companion object {
        const val DIRECTION_HOST_TO_JOINER = 0x01
        const val DIRECTION_JOINER_TO_HOST = 0x02

        fun longBytes(value: Long): ByteArray = ByteArray(8) { i ->
            ((value ushr (56 - i * 8)) and 0xFF).toByte()
        }
    }
}
