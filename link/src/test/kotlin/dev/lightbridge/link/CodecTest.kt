// SPDX-License-Identifier: MIT
package dev.lightbridge.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecTest {

    @Test
    fun base64UrlMatchesKnownEncodings() {
        assertEquals("", Base64Url.encode(ByteArray(0)))
        assertEquals("Zg", Base64Url.encode("f".toByteArray()))
        assertEquals("Zm8", Base64Url.encode("fo".toByteArray()))
        assertEquals("Zm9v", Base64Url.encode("foo".toByteArray()))
        assertEquals("Zm9vYg", Base64Url.encode("foob".toByteArray()))
        assertEquals("Zm9vYmE", Base64Url.encode("fooba".toByteArray()))
        assertEquals("Zm9vYmFy", Base64Url.encode("foobar".toByteArray()))
    }

    @Test
    fun base64UrlIsUrlSafeAndUnpadded() {
        val bytes = ByteArray(64) { (it * 5 + 3).toByte() }
        val encoded = Base64Url.encode(bytes)
        assertFalse(encoded.contains('+'))
        assertFalse(encoded.contains('/'))
        assertFalse(encoded.contains('='))
        assertArrayEquals(bytes, Base64Url.decodeOrNull(encoded))
    }

    @Test
    fun base64UrlRoundTripsEveryLength() {
        for (size in 0..64) {
            val bytes = ByteArray(size) { (it * 31 % 256).toByte() }
            assertArrayEquals(bytes, Base64Url.decodeOrNull(Base64Url.encode(bytes)))
        }
    }

    @Test
    fun base64UrlRejectsNonCanonicalInput() {
        assertNull(Base64Url.decodeOrNull("Zg=="))
        assertNull(Base64Url.decodeOrNull("Zm9v+"))
        assertNull(Base64Url.decodeOrNull("Zm9v/"))
        assertNull(Base64Url.decodeOrNull("Zm9v\n"))
        assertNull(Base64Url.decodeOrNull("Zm9v "))
        assertNull(Base64Url.decodeOrNull("="))
        // Trailing bits that are not zero padding are a different byte string in disguise.
        assertNull(Base64Url.decodeOrNull("Zh"))
    }

    @Test
    fun hexIsLowercaseAndFixedWidth() {
        assertEquals("00", byteArrayOf(0).hex())
        assertEquals("ff", byteArrayOf(0xFF.toByte()).hex())
        assertEquals("0a1b2c", byteArrayOf(0x0a, 0x1b, 0x2c).hex())
        assertEquals(64, ByteArray(32).hex().length)
    }

    @Test
    fun constantTimeComparisonBehavesLikeEquals() {
        assertTrue(byteArrayOf(1, 2, 3).contentEqualsConstantTime(byteArrayOf(1, 2, 3)))
        assertFalse(byteArrayOf(1, 2, 3).contentEqualsConstantTime(byteArrayOf(1, 2, 4)))
        assertFalse(byteArrayOf(1, 2, 3).contentEqualsConstantTime(byteArrayOf(1, 2)))
        assertTrue(ByteArray(0).contentEqualsConstantTime(ByteArray(0)))
    }

    @Test
    fun readerRejectsTruncationAndTrailingBytes() {
        val record = Writer().u8(7).text("hello").u32(4294967295L).toByteArray()
        val reader = Reader(record)
        assertEquals(7, reader.u8())
        assertEquals("hello", reader.text())
        assertEquals(4294967295L, reader.u32())
        reader.expectEnd("record")

        val truncated = Reader(record.copyOfRange(0, record.size - 2))
        truncated.u8(); truncated.text()
        assertTrue(runCatching { truncated.u32() }.isFailure)

        val padded = Reader(record + byteArrayOf(0))
        padded.u8(); padded.text(); padded.u32()
        assertTrue(runCatching { padded.expectEnd("record") }.isFailure)
    }

    @Test
    fun writerRejectsOutOfRangeIntegers() {
        assertTrue(runCatching { Writer().u16(70000) }.isFailure)
        assertTrue(runCatching { Writer().u32(-1L) }.isFailure)
        assertTrue(runCatching { Writer().blob(ByteArray(70000)) }.isFailure)
    }
}
