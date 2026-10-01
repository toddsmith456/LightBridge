// SPDX-License-Identifier: MIT
package dev.lightbridge.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectPayloadTest {

    private val identity = LinkKeyPair.generate()
    private val ephemeral = LinkKeyPair.generate()

    private fun payload(
        deviceAddress: String = "02:1a:2b:3c:4d:5e",
        ownerAddress: String = LinkAddressPolicy.DEFAULT_GROUP_OWNER,
        port: Int = DEFAULT_LINK_PORT,
        passphrase: String = "correct-horse-battery",
        ssid: String = "DIRECT-xy-LightBridge",
    ) = ConnectPayload(
        sessionId = ByteArray(ConnectPayload.SESSION_ID_BYTES) { (it + 1).toByte() },
        hostName = "Todd's Pixel",
        hostEphemeralPublic = ephemeral.publicBytes,
        hostIdentityPublic = identity.keyPair.public.encoded,
        hostFingerprint = identityFingerprint(identity.keyPair.public),
        deviceAddress = deviceAddress,
        ssid = ssid,
        passphrase = passphrase,
        ownerAddress = ownerAddress,
        port = port,
    )

    @Test
    fun roundTripsEveryField() {
        val original = payload()
        val text = original.encode()
        assertTrue(text.startsWith(CONNECT_QR_PREFIX))
        val decoded = ConnectPayload.decodeOrNull(text)!!
        assertEquals(original.sessionId.hex(), decoded.sessionId.hex())
        assertEquals(original.hostName, decoded.hostName)
        assertEquals(original.hostEphemeralPublic.hex(), decoded.hostEphemeralPublic.hex())
        assertEquals(original.hostIdentityPublic.hex(), decoded.hostIdentityPublic.hex())
        assertEquals(original.hostFingerprint, decoded.hostFingerprint)
        assertEquals(original.deviceAddress, decoded.deviceAddress)
        assertEquals(original.ssid, decoded.ssid)
        assertEquals(original.passphrase, decoded.passphrase)
        assertEquals(original.ownerAddress, decoded.ownerAddress)
        assertEquals(original.port, decoded.port)
    }

    /** The payload must fit comfortably in one QR code at the lowest error-correction level. */
    @Test
    fun payloadStaysSmallEnoughToScan() {
        val text = payload().encode()
        assertTrue("payload is ${text.length} chars", text.length < 600)
    }

    @Test
    fun ignoresQrCodesThatAreNotOurs() {
        assertNull(ConnectPayload.decodeOrNull("https://example.com"))
        assertNull(ConnectPayload.decodeOrNull(""))
        assertNull(ConnectPayload.decodeOrNull("LBWIFI1:"))
        assertNull(ConnectPayload.decodeOrNull("LBWIFI2:AAAA"))
        assertNull(ConnectPayload.decodeOrNull(payload().encode().removePrefix(CONNECT_QR_PREFIX)))
    }

    @Test
    fun rejectsAPeerAddressOutsideTheDirectLinkRange() {
        assertNull(ConnectPayload.decodeOrNull(payload(ownerAddress = "8.8.8.8").encode()))
        assertNull(ConnectPayload.decodeOrNull(payload(ownerAddress = "203.0.113.7").encode()))
        assertNull(ConnectPayload.decodeOrNull(payload(ownerAddress = "evil.example.com").encode()))
        assertNull(ConnectPayload.decodeOrNull(payload(ownerAddress = "2001:db8::1").encode()))
    }

    @Test
    fun rejectsMalformedGroupAndDeviceFields() {
        assertNull(ConnectPayload.decodeOrNull(payload(deviceAddress = "not-a-mac").encode()))
        assertNull(ConnectPayload.decodeOrNull(payload(passphrase = "short").encode()))
        assertNull(ConnectPayload.decodeOrNull(payload(passphrase = "x".repeat(64)).encode()))
        assertNull(ConnectPayload.decodeOrNull(payload(ssid = "x".repeat(33)).encode()))
        assertNull(ConnectPayload.decodeOrNull(payload(deviceAddress = "02:1a:2b:3c:4d:5e:6f").encode()))
    }

    /** A privileged port is refused by the constructor, so it can never reach a QR code. */
    @Test
    fun rejectsPrivilegedPorts() {
        assertThrows(IllegalArgumentException::class.java) { payload(port = 80) }
        assertThrows(IllegalArgumentException::class.java) { payload(port = 1023) }
    }

    @Test
    fun rejectsTruncatedAndTamperedBodies() {
        val text = payload().encode()
        val body = text.removePrefix(CONNECT_QR_PREFIX)
        assertNull(ConnectPayload.decodeOrNull(CONNECT_QR_PREFIX + body.dropLast(8)))
        assertNull(ConnectPayload.decodeOrNull(CONNECT_QR_PREFIX + "!" + body.drop(1)))
        assertNull(ConnectPayload.decodeOrNull(CONNECT_QR_PREFIX + body + "AAAA"))
    }

    @Test
    fun acceptsLinkLocalIpv6AsTheOwnerAddress() {
        val decoded = ConnectPayload.decodeOrNull(payload(ownerAddress = "fe80::1").encode())
        assertEquals("fe80::1", decoded!!.ownerAddress)
    }
}
