// SPDX-License-Identifier: MIT
/*
 * File messages and the receiving state machine.
 *
 * One transfer at a time: offer → (accept) → ordered chunks → complete → verified. The receiver
 * writes straight to a stream (never buffering a whole file in memory), checks each chunk's index
 * and length as it arrives, and only reports success after the SHA-256 in the offer matches the
 * bytes it actually received.
 */
package dev.lightbridge.link

import java.io.OutputStream
import java.security.MessageDigest

/** 64 KiB chunks keep latency low without flooding a slow P2P link. */
public const val CHUNK_BYTES: Int = 64 * 1024

/** Mirrors the inbox ceiling, so a link transfer can always be stored. */
public const val MAX_LINK_FILE_BYTES: Long = 256L * 1024 * 1024

public const val MAX_FILE_NAME_LENGTH: Int = 200

/** Message type tags on the wire. */
private object Type {
    const val OFFER = 1
    const val ACCEPT = 2
    const val DECLINE = 3
    const val CHUNK = 4
    const val COMPLETE = 5
    const val VERIFIED = 6
    const val FAILURE = 7
    const val BYE = 8
    const val CONFIRM = 9
}

/** Every message exchanged after the handshake is done. */
public sealed interface LinkMessage {
    public fun encode(): ByteArray

    /** A file about to be sent. */
    public class Offer(
        public val id: String,
        public val name: String,
        public val mime: String,
        public val size: Long,
        public val sha256: String,
    ) : LinkMessage {
        public val chunks: Int get() = chunkCount(size)

        override fun encode(): ByteArray = Writer(256).u8(Type.OFFER).text(id).text(name).text(mime)
            .u64(size).text(sha256).toByteArray()

        public companion object {
            internal fun decode(reader: Reader): Offer {
                val id = reader.text()
                val name = reader.text()
                val mime = reader.text()
                val size = reader.u64()
                val sha256 = reader.text()
                require(id.length in 8..64 && id.all { it.isLetterOrDigit() || it == '-' }) { "bad transfer id" }
                require(name.isNotBlank() && name.length <= MAX_FILE_NAME_LENGTH) { "bad file name" }
                require(mime.length <= 128 && mime.all { it.code in 32..126 }) { "bad mime type" }
                require(size in 1..MAX_LINK_FILE_BYTES) { "file size $size is outside the supported range" }
                require(sha256.length == 64 && sha256.all { it.isDigit() || it in 'a'..'f' }) { "bad sha256" }
                return Offer(id, name, mime, size, sha256)
            }
        }
    }

    /** Receiver agreed to take the file. */
    public class Accept(public val id: String) : LinkMessage {
        override fun encode(): ByteArray = Writer(48).u8(Type.ACCEPT).text(id).toByteArray()
    }

    /** Receiver refused it (too large, no space, user said no). */
    public class Decline(public val id: String, public val reason: String) : LinkMessage {
        override fun encode(): ByteArray = Writer(160).u8(Type.DECLINE).text(id).text(reason).toByteArray()
    }

    /** One slice of file data, in order. */
    public class Chunk(public val id: String, public val index: Int, public val data: ByteArray) : LinkMessage {
        override fun encode(): ByteArray = Writer(data.size + 64).u8(Type.CHUNK).text(id).u32(index.toLong())
            .blob(data).toByteArray()

        public companion object {
            internal fun decode(reader: Reader): Chunk {
                val id = reader.text()
                val index = reader.u32()
                val data = reader.blob()
                require(index <= Int.MAX_VALUE) { "chunk index out of range" }
                require(data.isNotEmpty() && data.size <= CHUNK_BYTES) { "bad chunk length ${data.size}" }
                return Chunk(id, index.toInt(), data)
            }
        }
    }

    /** The sender sent everything it had for this file. */
    public class Complete(public val id: String) : LinkMessage {
        override fun encode(): ByteArray = Writer(48).u8(Type.COMPLETE).text(id).toByteArray()
    }

    /** The receiver verified the digest and stored the file. */
    public class Verified(public val id: String) : LinkMessage {
        override fun encode(): ByteArray = Writer(48).u8(Type.VERIFIED).text(id).toByteArray()
    }

    /** Something went wrong; the transfer is over. */
    public class Failure(public val message: String) : LinkMessage {
        override fun encode(): ByteArray = Writer(160).u8(Type.FAILURE).text(message.take(120)).toByteArray()
    }

    /** Polite close, so the other side does not report a broken link. */
    public class Bye : LinkMessage {
        override fun encode(): ByteArray = Writer(1).u8(Type.BYE).toByteArray()
    }

    /**
     * The human on this device confirmed the six-digit code.
     *
     * Traffic is only allowed once *both* sides have sent this, so a machine in the middle cannot
     * push a file at a device whose owner has not compared the code yet.
     */
    public class Confirm(public val sas: String) : LinkMessage {
        override fun encode(): ByteArray = Writer(16).u8(Type.CONFIRM).text(sas).toByteArray()

        public companion object {
            internal fun decode(reader: Reader): Confirm {
                val sas = reader.text()
                require(sas.length == 6 && sas.all { it.isDigit() }) { "bad confirmation code" }
                return Confirm(sas)
            }
        }
    }

    public companion object {
        /** Parses one decrypted record. Throws [LinkFormatException] for anything unexpected. */
        public fun decode(bytes: ByteArray): LinkMessage {
            val reader = Reader(bytes)
            val message = when (val type = reader.u8()) {
                Type.OFFER -> Offer.decode(reader)
                Type.ACCEPT -> Accept(reader.text())
                Type.DECLINE -> Decline(reader.text(), reader.text())
                Type.CHUNK -> Chunk.decode(reader)
                Type.COMPLETE -> Complete(reader.text())
                Type.VERIFIED -> Verified(reader.text())
                Type.FAILURE -> Failure(reader.text())
                Type.BYE -> Bye()
                Type.CONFIRM -> Confirm.decode(reader)
                else -> throw LinkFormatException("unknown message type $type")
            }
            reader.expectEnd("message ${message::class.simpleName}")
            return message
        }
    }
}

/** Number of chunks a file of [size] bytes will be sent in. */
public fun chunkCount(size: Long): Int {
    require(size > 0) { "empty files are not sent" }
    return ((size + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
}

/** Size of chunk [index] for a file of [size] bytes. */
public fun chunkSize(size: Long, index: Int): Int {
    val count = chunkCount(size)
    require(index in 0 until count) { "chunk $index outside 0..${count - 1}" }
    val start = index.toLong() * CHUNK_BYTES
    return minOf(CHUNK_BYTES.toLong(), size - start).toInt()
}

/**
 * Streams received chunks into [sink] and verifies the result.
 *
 * Out-of-order, duplicated, oversized or truncated data is rejected immediately — the file is never
 * reported as received unless its SHA-256 matches the digest in the offer.
 */
public class FileReceiver(
    public val offer: LinkMessage.Offer,
    private val sink: OutputStream,
    private val maxBytes: Long = MAX_LINK_FILE_BYTES,
) : AutoCloseable {
    private val digest = MessageDigest.getInstance("SHA-256")
    private var nextIndex = 0
    private var finished = false

    init {
        require(offer.size <= maxBytes) { "file is larger than this device accepts" }
    }

    /** Bytes accepted so far. */
    public var received: Long = 0L
        private set

    /** 0..1, for the progress bar. */
    public val progress: Float
        get() = if (offer.size <= 0) 1f else (received.toDouble() / offer.size.toDouble()).toFloat()

    /** True once every chunk has arrived. */
    public val complete: Boolean get() = nextIndex == offer.chunks

    /** Validates and stores one chunk. */
    public fun accept(chunk: LinkMessage.Chunk) {
        require(!finished) { "transfer already finished" }
        require(chunk.id == offer.id) { "chunk for a different transfer" }
        require(chunk.index == nextIndex) { "expected chunk $nextIndex, got ${chunk.index}" }
        val expected = chunkSize(offer.size, chunk.index)
        require(chunk.data.size == expected) { "chunk ${chunk.index} has ${chunk.data.size} bytes, expected $expected" }
        require(received + chunk.data.size <= maxBytes) { "transfer exceeds this device's limit" }
        digest.update(chunk.data)
        sink.write(chunk.data)
        received += chunk.data.size
        nextIndex++
    }

    /** Verifies digest and length. Returns the digest, or throws if the file did not arrive intact. */
    public fun finish(): String {
        require(!finished) { "transfer already finished" }
        require(complete) { "only $nextIndex of ${offer.chunks} chunks arrived" }
        require(received == offer.size) { "size mismatch: $received bytes, expected ${offer.size}" }
        val actual = digest.digest().hex()
        require(actual == offer.sha256) { "SHA-256 mismatch — the file did not arrive intact" }
        sink.flush()
        finished = true
        return actual
    }

    override fun close() {
        runCatching { sink.close() }
    }
}
