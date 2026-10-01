// SPDX-License-Identifier: MIT
package dev.lightbridge.link

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureChannelTest {

    private val sessionId = ByteArray(16) { (it + 1).toByte() }
    private val key = ByteArray(32) { (it * 7).toByte() }
    private val otherKey = ByteArray(32) { (it * 11).toByte() }

    private fun hostSink() = ByteArrayOutputStream()

    @Test
    fun roundTripsRecordsInBothDirections() {
        val hostOut = hostSink()
        val host = SecureChannel(ByteArrayInputStream(ByteArray(0)), hostOut, key, isHost = true, sessionId = sessionId)
        host.send("hello joiner".toByteArray())
        host.send(ByteArray(0))

        val joiner = SecureChannel(ByteArrayInputStream(hostOut.toByteArray()), ByteArrayOutputStream(), key,
            isHost = false, sessionId = sessionId)
        assertEquals("hello joiner", String(joiner.receive()))
        assertEquals(0, joiner.receive().size)
        assertEquals(2L, joiner.recordsReceived)

        val joinerOut = ByteArrayOutputStream()
        val joinerTwo = SecureChannel(ByteArrayInputStream(ByteArray(0)), joinerOut, key, isHost = false, sessionId = sessionId)
        joinerTwo.send("file chunk".toByteArray())
        val hostTwo = SecureChannel(ByteArrayInputStream(joinerOut.toByteArray()), ByteArrayOutputStream(), key,
            isHost = true, sessionId = sessionId)
        assertEquals("file chunk", String(hostTwo.receive()))
    }

    @Test
    fun rejectsATamperedRecord() {
        val hostOut = hostSink()
        val host = SecureChannel(ByteArrayInputStream(ByteArray(0)), hostOut, key, isHost = true, sessionId = sessionId)
        host.send("payload".toByteArray())
        val bytes = hostOut.toByteArray()
        bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 0x01).toByte()
        val joiner = SecureChannel(ByteArrayInputStream(bytes), ByteArrayOutputStream(), key, isHost = false, sessionId = sessionId)
        val failure = assertThrows(LinkIntegrityException::class.java) { joiner.receive() }
        assertTrue(failure.message!!.contains("authentication"))
    }

    @Test
    fun rejectsReplayedRecords() {
        val hostOut = hostSink()
        val host = SecureChannel(ByteArrayInputStream(ByteArray(0)), hostOut, key, isHost = true, sessionId = sessionId)
        host.send("one".toByteArray())
        val first = hostOut.toByteArray()

        // The same record twice, byte for byte: an attacker re-injecting a captured message.
        val joiner = SecureChannel(ByteArrayInputStream(first + first), ByteArrayOutputStream(), key,
            isHost = false, sessionId = sessionId)
        assertEquals("one", String(joiner.receive()))
        val failure = assertThrows(LinkIntegrityException::class.java) { joiner.receive() }
        assertTrue(failure.message!!.contains("out of order"))
    }

    /** A record whose counter was rewritten no longer authenticates: the counter is in the AAD. */
    @Test
    fun rejectsARecordWhoseCounterWasRewritten() {
        val hostOut = hostSink()
        val host = SecureChannel(ByteArrayInputStream(ByteArray(0)), hostOut, key, isHost = true, sessionId = sessionId)
        host.send("one".toByteArray())
        val first = hostOut.toByteArray()
        hostOut.reset()
        host.send("two".toByteArray())
        val second = hostOut.toByteArray()

        val rewritten = second.copyOf()
        System.arraycopy(first, 4, rewritten, 4, 9) // header: u32 length, then u8 direction + u64 counter
        // A fresh channel expects counter 0, so the rewritten record passes the ordering check and
        // can only fail because the counter is authenticated data.
        val joiner = SecureChannel(ByteArrayInputStream(rewritten), ByteArrayOutputStream(), key,
            isHost = false, sessionId = sessionId)
        val failure = assertThrows(LinkIntegrityException::class.java) { joiner.receive() }
        assertTrue(failure.message!!.contains("authentication"))
    }

    @Test
    fun rejectsSkippedCounters() {
        val hostOut = hostSink()
        val host = SecureChannel(ByteArrayInputStream(ByteArray(0)), hostOut, key, isHost = true, sessionId = sessionId)
        host.send("one".toByteArray())
        host.send("two".toByteArray())
        val bytes = hostOut.toByteArray()
        // Drop the first record: the next counter the joiner sees is 1, not 0.
        val firstLength = 4 + readU32(bytes, 0)
        val joiner = SecureChannel(ByteArrayInputStream(bytes.copyOfRange(firstLength, bytes.size)),
            ByteArrayOutputStream(), key, isHost = false, sessionId = sessionId)
        val failure = assertThrows(LinkIntegrityException::class.java) { joiner.receive() }
        assertTrue(failure.message!!.contains("out of order"))
    }

    @Test
    fun rejectsDataEncryptedWithAnotherKey() {
        val hostOut = hostSink()
        val host = SecureChannel(ByteArrayInputStream(ByteArray(0)), hostOut, otherKey, isHost = true, sessionId = sessionId)
        host.send("secret".toByteArray())
        val joiner = SecureChannel(ByteArrayInputStream(hostOut.toByteArray()), ByteArrayOutputStream(), key,
            isHost = false, sessionId = sessionId)
        assertThrows(LinkIntegrityException::class.java) { joiner.receive() }
    }

    @Test
    fun rejectsRecordsBoundToAnotherSession() {
        val hostOut = hostSink()
        val host = SecureChannel(ByteArrayInputStream(ByteArray(0)), hostOut, key, isHost = true,
            sessionId = ByteArray(16) { 9 })
        host.send("cross-session".toByteArray())
        val joiner = SecureChannel(ByteArrayInputStream(hostOut.toByteArray()), ByteArrayOutputStream(), key,
            isHost = false, sessionId = sessionId)
        assertThrows(LinkIntegrityException::class.java) { joiner.receive() }
    }

    @Test
    fun rejectsImpossibleLengthsAndTruncation() {
        val joiner = SecureChannel(ByteArrayInputStream(byteArrayOf(0x00, 0x00, 0x00, 0x04, 0x01, 0x02)),
            ByteArrayOutputStream(), key, isHost = false, sessionId = sessionId)
        assertThrows(LinkIntegrityException::class.java) { joiner.receive() }

        val truncated = SecureChannel(ByteArrayInputStream(byteArrayOf(0x00)), ByteArrayOutputStream(), key,
            isHost = false, sessionId = sessionId)
        assertThrows(EOFException::class.java) { truncated.receive() }
    }

    @Test
    fun cleanEndOfStreamIsEofNotCorruption() {
        val joiner = SecureChannel(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), key, isHost = false,
            sessionId = sessionId)
        assertThrows(EOFException::class.java) { joiner.receive() }
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int {
        var value = 0
        repeat(4) { value = (value shl 8) or (bytes[offset + it].toInt() and 0xFF) }
        return value
    }
}
