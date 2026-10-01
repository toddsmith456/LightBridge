// SPDX-License-Identifier: MIT
package dev.lightbridge.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {

    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** RFC 5869 test case 1 — this pins our HKDF against the standard, not against itself. */
    @Test
    fun hkdfMatchesRfc5869CaseOne() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = hex("000102030405060708090a0b0c")
        val info = hex("f0f1f2f3f4f5f6f7f8f9")
        val expected = hex(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
                "34007208d5b887185865",
        )
        assertEquals(expected.hex(), Hkdf.derive(ikm, salt, info, 42).hex())
    }

    @Test
    fun hkdfExpandsToRequestedLength() {
        val ikm = ByteArray(32) { 0x42 }
        assertEquals(64, Hkdf.derive(ikm, ByteArray(16), "info".toByteArray(), 64).size)
        assertEquals(32, Hkdf.derive(ikm, ByteArray(16), "info".toByteArray(), 32).size)
    }

    @Test
    fun ecdhAgreesInBothDirections() {
        val a = LinkKeyPair.generate()
        val b = LinkKeyPair.generate()
        val ab = ecdh(a.keyPair.private, LinkKeyPair.decodePublic(b.publicBytes))
        val ba = ecdh(b.keyPair.private, LinkKeyPair.decodePublic(a.publicBytes))
        assertEquals(ab.hex(), ba.hex())
        assertEquals(32, ab.size)
    }

    @Test
    fun publicKeyRoundTripsThroughItsPointEncoding() {
        val pair = LinkKeyPair.generate()
        assertEquals(65, pair.publicBytes.size)
        assertEquals(0x04, pair.publicBytes[0].toInt())
        val decoded = LinkKeyPair.decodePublic(pair.publicBytes)
        // Same curve point, so the same X.509 encoding, whichever provider rebuilt it.
        assertEquals(pair.keyPair.public.encoded.hex(), decoded.encoded.hex())
    }

    @Test
    fun fingerprintIsStableAndShortFormIsGrouped() {
        val pair = LinkKeyPair.generate()
        val first = identityFingerprint(pair.keyPair.public)
        val second = identityFingerprint(pair.keyPair.public)
        assertEquals(first, second)
        assertEquals(64, first.length)
        assertEquals("${
            first.take(4)
        }-${first.substring(4, 8)}-${first.substring(8, 12)}-${first.substring(12, 16)}",
            shortFingerprint(first))
    }

    @Test
    fun sessionKeysAreDirectionalAndSasMatchesOnBothSides() {
        val sessionId = ByteArray(16) { it.toByte() }
        val hostEphemeral = LinkKeyPair.generate().publicBytes
        val joinerEphemeral = LinkKeyPair.generate().publicBytes
        val hostIdentity = LinkKeyPair.generate().keyPair.public.encoded
        val joinerIdentity = LinkKeyPair.generate().keyPair.public.encoded
        val secret = ByteArray(32) { 0x7f }

        val hostSide = deriveSessionKeys(secret, sessionId, hostEphemeral, joinerEphemeral, hostIdentity, joinerIdentity)
        val joinerSide = deriveSessionKeys(secret, sessionId, hostEphemeral, joinerEphemeral, hostIdentity, joinerIdentity)

        assertEquals(hostSide.sas, joinerSide.sas)
        assertEquals(6, hostSide.sas.length)
        assertTrue(hostSide.sas.all { it.isDigit() })
        assertEquals(hostSide.hostToJoiner.hex(), joinerSide.hostToJoiner.hex())
        assertNotEquals(hostSide.hostToJoiner.hex(), hostSide.joinerToHost.hex())
    }

    /** A middlebox that swaps a key must produce a different code — that is what the SAS is for. */
    @Test
    fun sasChangesWhenAnyHandshakeInputChanges() {
        val sessionId = ByteArray(16)
        val secret = ByteArray(32) { 0x11 }
        val base = deriveSessionKeys(secret, sessionId, ByteArray(65) { 4 }, ByteArray(65) { 4 },
            ByteArray(91) { 1 }, ByteArray(91) { 2 })
        val swapped = deriveSessionKeys(secret, sessionId, ByteArray(65) { 4 }, ByteArray(65) { 5 },
            ByteArray(91) { 1 }, ByteArray(91) { 2 })
        assertNotEquals(base.sas, swapped.sas)
        assertNotEquals(base.transcriptHash.hex(), swapped.transcriptHash.hex())
    }

    @Test
    fun identitySignaturesVerifyAndRejectTampering() {
        val pair = LinkKeyPair.generate()
        val message = "connect:session-42".toByteArray()
        val signature = IdentitySigner.sign(pair.keyPair.private, message)
        assertTrue(IdentitySigner.verify(pair.keyPair.public, message, signature))
        assertFalse(IdentitySigner.verify(pair.keyPair.public, "connect:session-43".toByteArray(), signature))
        val otherKey = LinkKeyPair.generate()
        assertFalse(IdentitySigner.verify(otherKey.keyPair.public, message, signature))
    }

    @Test
    fun destroysWipesKeyMaterial() {
        val keys = deriveSessionKeys(ByteArray(32), ByteArray(16), ByteArray(65), ByteArray(65),
            ByteArray(91), ByteArray(91))
        keys.destroy()
        assertTrue(keys.hostToJoiner.all { it == 0.toByte() })
        assertTrue(keys.joinerToHost.all { it == 0.toByte() })
    }
}
