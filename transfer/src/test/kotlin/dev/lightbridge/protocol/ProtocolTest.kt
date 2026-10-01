// SPDX-License-Identifier: MIT
// Golden values from Decimen v0.3.0 tests/fountain.test.ts, copyright Evan Crawley.
package dev.lightbridge.protocol

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

class ProtocolTest {
    @Test fun upstreamDistributionFingerprints() {
        val golden = mapOf(1 to "8c6a9878", 2 to "2417b297", 17 to "2ba41e3c", 179 to "e8b6340a",
            716 to "28d31438", 5000 to "357a4c9a", 22000 to "fc512a92")
        for ((k, hash) in golden) {
            val bytes = ByteBuffer.allocate(k * 8).order(ByteOrder.LITTLE_ENDIAN)
            solitonCdf(k).forEach(bytes::putDouble)
            assertEquals("k=$k", hash, fnv1a(bytes.array()).toUInt().toString(16))
        }
    }
    @Test fun upstreamSubsets() {
        val golden = arrayOf(intArrayOf(3,14), intArrayOf(12,0), intArrayOf(6,8), intArrayOf(15,16,13), intArrayOf(11,2,16))
        intArrayOf(0,1,2,41,1000).forEachIndexed { i, seq ->
            assertArrayEquals(golden[i], frameIndices(17, solitonCdf(17), 4242, seq))
        }
    }
    @Test fun upstreamEncodedStreamFingerprints() {
        val vectors = listOf(listOf(1,64,1), listOf(23,64,7), listOf(179,2933,4242), listOf(716,1445,65535))
        val hashes = listOf("f6a115c5", "2aafe48d", "83bbd1d7", "15e10360")
        vectors.forEachIndexed { n, (k, block, session) ->
            val payload = ByteArray(k * block - 7) { ((it * 37 + (it shr 8) * 11) and 255).toByte() }
            val encoder = FountainEncoder(payload, block, session)
            val stream = ByteArray(64 * block)
            repeat(64) { encoder.frame(it).payload.copyInto(stream, it * block) }
            assertEquals(hashes[n], fnv1a(stream).toUInt().toString(16))
        }
    }
    @Test fun filesSurviveLossReorderingDuplicatesAndOddBlockSizes() {
        for (block in listOf(64,511,1445,2933)) {
            val original = Random(123).nextBytes(18000)
            val packed = Container.pack("résumé.txt", "text/plain", original)
            val encoder = FountainEncoder(packed.bytes, block, 4242)
            val decoder = FountainDecoder(encoder.stream)
            // Lose half the frames, reverse each batch, and feed each frame twice.
            for (batch in 0..150) {
                (batch * 60 until (batch + 1) * 60 step 2).reversed().forEach {
                    val frame = Frame.parse(encoder.frame(it).encode())!!
                    decoder.add(frame); decoder.add(frame)
                }
                if (decoder.complete) break
            }
            assertTrue("block=$block", decoder.complete)
            val file = Container.unpack(decoder.assemble())
            assertArrayEquals(original, file.bytes)
            assertEquals("résumé.txt", file.name)
        }
    }
    @Test fun compressionAndSafeNames() {
        val data = "hello light ".repeat(2000).toByteArray()
        val packed = Container.pack("../../hello\n.txt", "text/plain", data)
        assertTrue(packed.compressed)
        val file = Container.unpack(packed.bytes)
        assertEquals("hello.txt", file.name)
        assertArrayEquals(data, file.bytes)
        assertEquals("transfer.bin", safeName(".."))
    }
    @Test fun corruptionIsRejected() {
        val bytes = Container.pack("a", "text/plain", "hello".toByteArray()).bytes
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) { Container.unpack(bytes) }
    }
    @Test fun gzipOutputIsBounded() {
        val bytes = Container.pack("a", "text/plain", ByteArray(10000) { 65 }).bytes
        buffer(bytes).putInt(9, 10).putInt(bytes.size - 4, 10)
        assertThrows(IllegalArgumentException::class.java) { Container.unpack(bytes) }
    }
    @Test fun malformedFramesDoNotAllocateDecoders() {
        val valid = FountainEncoder(Container.pack("x", "text/plain", ByteArray(200)).bytes, 64, 1).frame(-1).encode()
        assertEquals(-1, Frame.parse(valid)!!.sequence)
        for (size in 0..20) assertNull(Frame.parse(valid.copyOf(size)))
        assertNull(Frame.parse(valid.copyOf().also { buffer(it).putInt(12, Int.MAX_VALUE) }))
        assertNull(Frame.parse(valid.copyOf().also { buffer(it).putShort(8, 65535.toShort()) }))
        assertNull(Frame.parse(valid.copyOf().also { it[0] = 0 }))
    }
    @Test fun wrongSessionCannotContaminateTransfer() {
        val a = FountainEncoder(Container.pack("x", "text/plain", ByteArray(200)).bytes, 64, 1)
        val b = FountainEncoder(Container.pack("x", "text/plain", ByteArray(200)).bytes, 64, 2)
        assertThrows(IllegalArgumentException::class.java) { FountainDecoder(a.stream).add(b.frame(1)) }
    }
    @Test fun inputBoundsAndEmptyFiles() {
        assertThrows(IllegalArgumentException::class.java) { ByteArray(11).inputStream().readBounded(10) }
        assertThrows(IllegalArgumentException::class.java) { Container.pack("x", "", ByteArray(0)) }
    }
    @Test fun unsafeMimeFallsBackWithoutAffectingContainerIntegrity() {
        assertEquals("application/octet-stream", safeMime("text/plain\nmalicious"))
        assertEquals("text/plain", safeMime("Text/Plain;charset=utf-8"))
        assertEquals("application/octet-stream", safeMime("*/*"))
    }
    @Test fun randomInputIsIgnored() {
        val random = Random(42)
        repeat(2000) { assertNull(Frame.parse(random.nextBytes(random.nextInt(3100)))) }
    }
}
