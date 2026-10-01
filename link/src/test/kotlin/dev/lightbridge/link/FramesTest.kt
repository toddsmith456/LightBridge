// SPDX-License-Identifier: MIT
package dev.lightbridge.link

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FramesTest {

    private val digest = "a".repeat(64)

    private fun offer(size: Long) = LinkMessage.Offer("transfer-1", "photo.jpg", "image/jpeg", size, digest)

    @Test
    fun everyMessageRoundTrips() {
        val messages = listOf(
            LinkMessage.Offer("transfer-1", "notes.txt", "text/plain", 1234, "b".repeat(64)),
            LinkMessage.Accept("transfer-1"),
            LinkMessage.Decline("transfer-1", "Not enough space"),
            LinkMessage.Chunk("transfer-1", 7, ByteArray(16) { it.toByte() }),
            LinkMessage.Complete("transfer-1"),
            LinkMessage.Verified("transfer-1"),
            LinkMessage.Failure("link dropped"),
            LinkMessage.Bye(),
        )
        for (message in messages) {
            val decoded = LinkMessage.decode(message.encode())
            assertEquals(message::class, decoded::class)
        }
        val chunk = LinkMessage.decode(LinkMessage.Chunk("transfer-1", 7, ByteArray(16) { it.toByte() }).encode())
        assertTrue(chunk is LinkMessage.Chunk)
        assertArrayEquals(ByteArray(16) { it.toByte() }, (chunk as LinkMessage.Chunk).data)
        assertEquals(7, chunk.index)
    }

    @Test
    fun rejectsUnknownAndTrailingData() {
        assertThrows(LinkFormatException::class.java) { LinkMessage.decode(byteArrayOf(99)) }
        assertThrows(IllegalArgumentException::class.java) {
            LinkMessage.decode(LinkMessage.Accept("transfer-1").encode() + byteArrayOf(0))
        }
    }

    @Test
    fun rejectsOversizedChunksAndBadOffers() {
        assertThrows(IllegalArgumentException::class.java) {
            LinkMessage.decode(LinkMessage.Chunk("transfer-1", 0, ByteArray(CHUNK_BYTES + 1)).encode())
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinkMessage.decode(LinkMessage.Offer("transfer-1", "x".repeat(400), "text/plain", 10, digest).encode())
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinkMessage.decode(LinkMessage.Offer("transfer-1", "a.txt", "text/plain", 0, digest).encode())
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinkMessage.decode(LinkMessage.Offer("transfer-1", "a.txt", "text/plain",
                MAX_LINK_FILE_BYTES + 1, digest).encode())
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinkMessage.decode(LinkMessage.Offer("transfer-1", "a.txt", "text/plain", 10, "nothex").encode())
        }
    }

    @Test
    fun chunkArithmeticCoversTheWholeFile() {
        assertEquals(1, chunkCount(1))
        assertEquals(1, chunkCount(CHUNK_BYTES.toLong()))
        assertEquals(2, chunkCount(CHUNK_BYTES + 1L))
        assertEquals(4, chunkCount(4L * CHUNK_BYTES))
        val size = 3L * CHUNK_BYTES + 17
        assertEquals(CHUNK_BYTES, chunkSize(size, 0))
        assertEquals(17, chunkSize(size, 3))
        assertThrows(IllegalArgumentException::class.java) { chunkSize(size, 4) }
    }

    @Test
    fun receiverAssemblesAndVerifiesAWholeFile() {
        val data = ByteArray(2 * CHUNK_BYTES + 123) { (it % 251).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(data).hex()
        val sink = ByteArrayOutputStream()
        val receiver = FileReceiver(
            LinkMessage.Offer("transfer-1", "big.bin", "application/octet-stream", data.size.toLong(), sha),
            sink,
        )
        var offset = 0
        for (index in 0 until chunkCount(data.size.toLong())) {
            val length = chunkSize(data.size.toLong(), index)
            receiver.accept(LinkMessage.Chunk("transfer-1", index, data.copyOfRange(offset, offset + length)))
            offset += length
            assertTrue(receiver.progress > 0f)
        }
        assertEquals(1f, receiver.progress, 0.0001f)
        assertEquals(sha, receiver.finish())
        assertArrayEquals(data, sink.toByteArray())
    }

    @Test
    fun receiverRejectsOutOfOrderAndDuplicateChunks() {
        val data = ByteArray(CHUNK_BYTES + 5) { 1 }
        val sha = MessageDigest.getInstance("SHA-256").digest(data).hex()
        val receiver = FileReceiver(
            LinkMessage.Offer("transfer-1", "a.bin", "application/octet-stream", data.size.toLong(), sha),
            ByteArrayOutputStream(),
        )
        val first = LinkMessage.Chunk("transfer-1", 0, data.copyOfRange(0, CHUNK_BYTES))
        receiver.accept(first)
        assertThrows(IllegalArgumentException::class.java) { receiver.accept(first) }
        assertThrows(IllegalArgumentException::class.java) {
            receiver.accept(LinkMessage.Chunk("transfer-1", 3, ByteArray(5)))
        }
    }

    @Test
    fun receiverRejectsWrongSizedAndWrongTransferChunks() {
        val receiver = FileReceiver(offer(100), ByteArrayOutputStream())
        assertThrows(IllegalArgumentException::class.java) {
            receiver.accept(LinkMessage.Chunk("other-transfer", 0, ByteArray(100)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            receiver.accept(LinkMessage.Chunk("transfer-1", 0, ByteArray(99)))
        }
    }

    @Test
    fun receiverRefusesToFinishATruncatedTransfer() {
        val receiver = FileReceiver(offer(CHUNK_BYTES.toLong() * 2), ByteArrayOutputStream())
        receiver.accept(LinkMessage.Chunk("transfer-1", 0, ByteArray(CHUNK_BYTES)))
        assertThrows(IllegalArgumentException::class.java) { receiver.finish() }
    }

    @Test
    fun receiverRefusesToFinishWhenTheDigestDoesNotMatch() {
        val data = ByteArray(64) { 7 }
        val receiver = FileReceiver(offer(data.size.toLong()), ByteArrayOutputStream())
        receiver.accept(LinkMessage.Chunk("transfer-1", 0, data))
        val failure = assertThrows(IllegalArgumentException::class.java) { receiver.finish() }
        assertTrue(failure.message!!.contains("SHA-256"))
    }

    @Test
    fun receiverEnforcesTheDeviceLimit() {
        val oversized = LinkMessage.Offer("transfer-1", "huge.bin", "application/octet-stream",
            MAX_LINK_FILE_BYTES, digest)
        assertThrows(IllegalArgumentException::class.java) {
            FileReceiver(oversized, ByteArrayOutputStream(), maxBytes = 1024)
        }
    }
}
