// SPDX-License-Identifier: MIT
package dev.lightbridge.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeTest {

    private fun identity(name: String): LocalIdentity {
        val pair = LinkKeyPair.generate()
        return LocalIdentity(pair.keyPair.private, pair.keyPair.public, name)
    }

    @Test
    fun bothSidesDeriveTheSameKeysAndCode() {
        val host = identity("Todd's Pixel")
        val joiner = identity("Kitchen tablet")
        val sessionId = ByteArray(16) { (it * 3).toByte() }
        val ephemeral = LinkKeyPair.generate()
        val hostEphemeral = ephemeral.publicBytes
        val hostIdentityBytes = host.publicKeyBytes

        val joinerHandshake = JoinerHandshake(sessionId, hostEphemeral, hostIdentityBytes, joiner)
        val accepted = HostHandshake(sessionId, ephemeral, host).accept(joinerHandshake.hello)
        val joinerSession = joinerHandshake.complete(accepted.reply)

        assertEquals(accepted.session.keys.sas, joinerSession.keys.sas)
        assertEquals(accepted.session.keys.hostToJoiner.hex(), joinerSession.keys.hostToJoiner.hex())
        assertEquals(accepted.session.keys.joinerToHost.hex(), joinerSession.keys.joinerToHost.hex())
        assertEquals(accepted.session.keys.transcriptHash.hex(), joinerSession.keys.transcriptHash.hex())
    }

    @Test
    fun identitiesAndNamesAreExchanged() {
        val host = identity("Host")
        val joiner = identity("Joiner")
        val sessionId = ByteArray(16)
        val ephemeral = LinkKeyPair.generate()
        val joinerHandshake = JoinerHandshake(sessionId, ephemeral.publicBytes, host.publicKeyBytes, joiner)
        val accepted = HostHandshake(sessionId, ephemeral, host).accept(joinerHandshake.hello)
        val joinerSession = joinerHandshake.complete(accepted.reply)

        // The host learns the joiner's identity; the joiner learns the host's.
        assertEquals(joiner.fingerprint, accepted.session.peerFingerprint)
        assertEquals("Joiner", accepted.session.peerName)
        assertEquals(host.fingerprint, joinerSession.peerFingerprint)
        assertEquals("Host", joinerSession.peerName)
        assertFalse(accepted.session.peerIdentityFromQr)
        assertTrue(joinerSession.peerIdentityFromQr)
    }

    /** A device that is not holding the identity private key must not be able to complete. */
    @Test
    fun rejectsAJoinerThatCannotProveItsIdentity() {
        val host = identity("Host")
        val realJoiner = identity("Real")
        val impostor = identity("Impostor")
        val sessionId = ByteArray(16)
        val ephemeral = LinkKeyPair.generate()
        val honest = JoinerHandshake(sessionId, ephemeral.publicBytes, host.publicKeyBytes, realJoiner)

        // Same ephemeral and same claimed identity, but signed by a different key.
        val forged = Writer(384)
            .u8(1)
            .blob(sessionId)
            .blob(LinkKeyPair.generate().publicBytes)
            .blob(realJoiner.publicKeyBytes)
            .text("Real")
            .blob(IdentitySigner.sign(impostor.privateKey, ByteArray(8)))
            .toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            HostHandshake(sessionId, ephemeral, host).accept(forged)
        }
    }

    /**
     * The hello is signed over the host's ephemeral key, so a hello aimed at a *different* host key
     * does not verify — a middlebox cannot relay one device's hello to another key.
     */
    @Test
    fun rejectsHelloSignedAgainstADifferentHostKey() {
        val host = identity("Host")
        val joiner = identity("Joiner")
        val sessionId = ByteArray(16)
        val advertised = LinkKeyPair.generate()
        val swapped = LinkKeyPair.generate()
        val joinerHandshake = JoinerHandshake(sessionId, advertised.publicBytes, host.publicKeyBytes, joiner)
        assertThrows(IllegalArgumentException::class.java) {
            HostHandshake(sessionId, swapped, host).accept(joinerHandshake.hello)
        }
    }

    /** If the reply claims a different ephemeral than the QR advertised, the joiner stops. */
    @Test
    fun joinerRejectsAReplyThatChangesTheHostEphemeralKey() {
        val host = identity("Host")
        val joiner = identity("Joiner")
        val sessionId = ByteArray(16)
        val advertised = LinkKeyPair.generate()
        val joinerHandshake = JoinerHandshake(sessionId, advertised.publicBytes, host.publicKeyBytes, joiner)
        val accepted = HostHandshake(sessionId, advertised, host).accept(joinerHandshake.hello)

        // Layout: version(1) | sessionId(2+16) | hostEphemeral(2+65) | …
        val start = 1 + 2 + sessionId.size + 2
        val tampered = accepted.reply.copyOf()
        System.arraycopy(LinkKeyPair.generate().publicBytes, 0, tampered, start, EC_PUBLIC_KEY_BYTES)
        val failure = assertThrows(IllegalArgumentException::class.java) { joinerHandshake.complete(tampered) }
        assertTrue(failure.message!!.contains("different key"))
    }

    @Test
    fun sessionIdsMustMatch() {
        val host = identity("Host")
        val joiner = identity("Joiner")
        val ephemeral = LinkKeyPair.generate()
        val joinerHandshake = JoinerHandshake(ByteArray(16) { 1 }, ephemeral.publicBytes, host.publicKeyBytes, joiner)
        assertThrows(IllegalArgumentException::class.java) {
            HostHandshake(ByteArray(16) { 2 }, ephemeral, host).accept(joinerHandshake.hello)
        }
    }

    @Test
    fun sessionsKeysAreUsableForEncryptedTraffic() {
        val host = identity("Host")
        val joiner = identity("Joiner")
        val sessionId = ByteArray(16) { 5 }
        val ephemeral = LinkKeyPair.generate()
        val joinerHandshake = JoinerHandshake(sessionId, ephemeral.publicBytes, host.publicKeyBytes, joiner)
        val accepted = HostHandshake(sessionId, ephemeral, host).accept(joinerHandshake.hello)
        val joinerSession = joinerHandshake.complete(accepted.reply)

        val hostOut = java.io.ByteArrayOutputStream()
        val hostChannel = SecureChannel(java.io.ByteArrayInputStream(ByteArray(0)), hostOut,
            accepted.session.keys.hostToJoiner, isHost = true, sessionId = sessionId)
        hostChannel.send("private file contents".toByteArray())

        val joinerChannel = SecureChannel(java.io.ByteArrayInputStream(hostOut.toByteArray()),
            java.io.ByteArrayOutputStream(), joinerSession.keys.hostToJoiner, isHost = false, sessionId = sessionId)
        assertEquals("private file contents", String(joinerChannel.receive()))
        assertTrue(hostOut.toByteArray().size > "private file contents".length)
    }
}
