// SPDX-License-Identifier: MIT
package dev.lightbridge.protocol

import org.junit.Assert.*
import org.junit.Test

/** These bytes come from the unmodified MIT TypeScript encoder, not our Kotlin codec. */
class UpstreamInteropTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/decimen-v0.3.0/$name")!!.use { it.readBytes() }
    @Test fun receiveActualTypeScriptStreams() {
        for (name in listOf("binary", "gzip")) {
            val bytes = fixture("$name.frames")
            val frames = bytes.asList().chunked(147).map { Frame.parse(it.toByteArray())!! }
            val decoder = FountainDecoder(frames.first().stream)
            // Simulate joining late, substantial frame loss, and backwards arrival.
            frames.drop(5).filterIndexed { i, _ -> i % 3 != 0 }.reversed().forEach(decoder::add)
            assertTrue(name, decoder.complete)
            val container = decoder.assemble()
            assertArrayEquals(fixture("$name.container"), container)
            val file = Container.unpack(container)
            assertArrayEquals(fixture("$name.source"), file.bytes)
            assertEquals("résumé.bin", file.name)
            assertEquals(name == "gzip", file.compressed)
        }
    }
    @Test fun kotlinEncoderProducesIdenticalFramesToTypeScript() {
        for (name in listOf("binary", "gzip")) {
            val encoder = FountainEncoder(fixture("$name.container"), 127, 4242)
            val frames = fixture("$name.frames")
            for (i in 0 until frames.size / 147) {
                assertArrayEquals("$name seq=$i", frames.copyOfRange(i * 147, (i + 1) * 147), encoder.frame(i).encode())
            }
        }
    }
    @Test fun uncompressedContainerMatchesTypeScriptExactly() {
        assertArrayEquals(fixture("binary.container"), Container.pack("résumé.bin", "application/octet-stream", fixture("binary.source")).bytes)
    }
}
