// SPDX-License-Identifier: MIT
/*
 * The QR connect payload.
 *
 * The host device creates the Wi-Fi Direct group, binds a socket, then displays this payload as a
 * QR code. Scanning it gives the other device everything it needs: which peer to connect to, the
 * group credentials as a fallback, where the link socket lives, and the host's ephemeral public key
 * so the encryption is authenticated by the physical act of scanning.
 *
 * It is encoded as a compact binary record (not JSON) and prefixed so a receiver can reject any
 * other QR code instantly — including the optical-transfer frames LightBridge itself shows.
 */
package dev.lightbridge.link

/** Prefix that identifies a LightBridge Wi-Fi connect QR code. */
public const val CONNECT_QR_PREFIX: String = "LBWIFI1:"

/** Version of the payload below. Bump only for an incompatible change. */
public const val CONNECT_PAYLOAD_VERSION: Int = 1

/** Default link port; the host falls back to the next free port and reports it here. */
public const val DEFAULT_LINK_PORT: Int = 47800

public class ConnectPayload(
    public val sessionId: ByteArray,
    public val hostName: String,
    /** Host ephemeral ECDH point (65 bytes), the authenticated encryption anchor. */
    public val hostEphemeralPublic: ByteArray,
    /** Host long-term identity public key (X.509), so repeat peers are recognised. */
    public val hostIdentityPublic: ByteArray,
    /** Full SHA-256 fingerprint of the identity key, lowercase hex. */
    public val hostFingerprint: String,
    /** The host's Wi-Fi Direct device address (MAC); the joiner connects straight to it. */
    public val deviceAddress: String,
    /** Group credentials, used to join if the framework needs them. */
    public val ssid: String,
    public val passphrase: String,
    /** Address the joiner should open the link socket to (the P2P group owner). */
    public val ownerAddress: String,
    public val port: Int,
) {
    init {
        require(sessionId.size == SESSION_ID_BYTES) { "session id must be $SESSION_ID_BYTES bytes" }
        require(hostEphemeralPublic.size == EC_PUBLIC_KEY_BYTES) { "bad ephemeral key length" }
        require(port in 1024..65535) { "port out of range: $port" }
    }

    /** The text carried inside the QR code. */
    public fun encode(): String {
        val body =
            Writer(320)
                .u8(CONNECT_PAYLOAD_VERSION)
                .blob(sessionId)
                .text(hostName.take(64))
                .blob(hostEphemeralPublic)
                .blob(hostIdentityPublic)
                .text(hostFingerprint)
                .text(deviceAddress)
                .text(ssid)
                .text(passphrase)
                .text(ownerAddress)
                .u16(port)
                .toByteArray()
        return CONNECT_QR_PREFIX + Base64Url.encode(body)
    }

    public companion object {
        /**
         * Parses a scanned QR string.
         *
         * Every field is validated — including that the peer address is inside a peer-to-peer range
         * and that the identity key actually parses as a P-256 public key. Returns null (never
         * throws) so callers can simply keep scanning.
         */
        public fun decodeOrNull(text: String): ConnectPayload? = runCatching {
            val trimmed = text.trim()
            if (!trimmed.startsWith(CONNECT_QR_PREFIX)) return null
            val body = Base64Url.decodeOrNull(trimmed.removePrefix(CONNECT_QR_PREFIX)) ?: return null
            val reader = Reader(body)
            require(reader.u8() == CONNECT_PAYLOAD_VERSION) { "unsupported payload version" }
            val sessionId = reader.blob()
            require(sessionId.size == SESSION_ID_BYTES) { "bad session id" }
            val hostName = reader.text().takeIf { it.isNotBlank() } ?: "LightBridge device"
            val ephemeral = reader.blob()
            require(ephemeral.size == EC_PUBLIC_KEY_BYTES) { "bad ephemeral key" }
            LinkKeyPair.decodePublic(ephemeral) // must be a real curve point
            val identity = reader.blob()
            require(identity.size in 32..512) { "bad identity key" }
            LinkKeyPair.decodeX509(identity) // must be a real public key
            val fingerprint = reader.text()
            require(fingerprint.length == 64 && fingerprint.all { it.isDigit() || it in 'a'..'f' }) {
                "bad fingerprint"
            }
            val deviceAddress = reader.text()
            require(isDeviceAddress(deviceAddress)) { "bad peer address" }
            val ssid = reader.text()
            require(ssid.length in 1..32) { "bad group ssid" }
            val passphrase = reader.text()
            require(passphrase.length in 8..63) { "bad group passphrase" }
            val ownerAddress = reader.text()
            require(LinkAddressPolicy.isAllowed(ownerAddress)) { "peer address is not a direct link" }
            val port = reader.u16()
            require(port >= 1024) { "port out of range" }
            reader.expectEnd("connect payload")
            ConnectPayload(
                sessionId, hostName, ephemeral, identity, fingerprint,
                deviceAddress, ssid, passphrase, ownerAddress, port,
            )
        }.getOrNull()

        /** `aa:bb:cc:dd:ee:ff` — the P2P device address, never a name or an IP. */
        public fun isDeviceAddress(value: String): Boolean {
            val parts = value.lowercase().split(':')
            if (parts.size != 6) return false
            return parts.all { it.length == 2 && it.all { c -> c.isDigit() || c in 'a'..'f' } }
        }

        public const val SESSION_ID_BYTES: Int = 16
    }
}
