// SPDX-License-Identifier: MIT
/*
 * Where a link is allowed to point.
 *
 * The Wi-Fi Direct tab necessarily adds the INTERNET permission to the manifest (Android requires
 * it for the TCP sockets that carry a direct link — see the module README). To keep that permission
 * from ever becoming general internet access, every socket in LightBridge is opened through this
 * policy, which accepts *only* literal addresses inside a peer-to-peer range, and rejects hostnames
 * outright so no DNS lookup can happen.
 *
 * `link` deliberately has no Android dependency, so this rule is unit-tested on the JVM in CI.
 */
package dev.lightbridge.link

/** A private address a Wi-Fi Direct peer can legitimately have. */
public sealed interface LinkAddress {
    public val literal: String

    /** IPv4 literal, already range-checked. */
    public data class V4(override val literal: String) : LinkAddress

    /** Link-local IPv6 literal (fe80::/10), without a scope id. */
    public data class V6LinkLocal(override val literal: String) : LinkAddress
}

public object LinkAddressPolicy {

    /** The Wi-Fi Direct group owner always owns the first address of the P2P subnet. */
    public const val DEFAULT_GROUP_OWNER: String = "192.168.49.1"

    /**
     * Validates a peer address taken from a scanned QR code.
     *
     * Accepted: `192.168.49.1` (the standard P2P group owner address), any other literal inside
     * 192.168.49.0/24, 169.254.0.0/16 link-local IPv4, and fe80::/10 link-local IPv6.
     * Rejected: hostnames, public addresses, IPv4-mapped IPv6, anything with a scope id or port.
     */
    public fun parse(candidate: String): LinkAddress? {
        val value = candidate.trim()
        if (value.isEmpty() || value.length > 45) return null
        // No names: a hostname would mean a DNS lookup, which is the one thing we never want.
        if (!value.all { it.isDigit() || it in ".:abcdefABCDEF" }) return null
        if (value.contains('%')) return null // scope ids are not accepted on the wire
        return if (value.contains(':')) parseV6LinkLocal(value) else parseV4(value)
    }

    /** True when [candidate] is a usable direct-link peer address. */
    public fun isAllowed(candidate: String): Boolean = parse(candidate) != null

    private fun parseV4(value: String): LinkAddress? {
        val parts = value.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3) return null
            if (part.length > 1 && part[0] == '0') return null // no octal-looking literals
            val n = part.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            octets[i] = n
        }
        val p2pGroup = octets[0] == 192 && octets[1] == 168 && octets[2] == 49
        val linkLocal = octets[0] == 169 && octets[1] == 254
        if (!p2pGroup && !linkLocal) return null
        if (octets[3] == 0 || octets[3] == 255) return null
        return LinkAddress.V4(octets.joinToString("."))
    }

    private fun parseV6LinkLocal(value: String): LinkAddress? {
        if (value.count { it == ':' } < 2) return null
        if (value.contains(":::")) return null
        val compressed = value.contains("::")
        if (value.split("::").size > 2) return null
        if (!compressed && (value.startsWith(":") || value.endsWith(":"))) return null
        if (compressed) {
            val before = value.substringBefore("::")
            val after = value.substringAfter("::")
            if (before.startsWith(":") || before.endsWith(":")) return null
            if (after.startsWith(":") || after.endsWith(":")) return null
        }
        val head = value.substringBefore("::").split(':').filter { it.isNotEmpty() }
        val tail = if (compressed) value.substringAfter("::").split(':').filter { it.isNotEmpty() } else emptyList()
        val groups = head + tail
        if (groups.size > 8) return null
        if (!compressed && groups.size != 8) return null
        if (groups.any { it.length > 4 || it.toIntOrNull(16) == null }) return null
        // The first group must place the address in fe80::/10, and never in ::1 / ::/128.
        val head16 = (groups.firstOrNull() ?: return null).toIntOrNull(16) ?: return null
        if (head16 and 0xFFC0 != 0xFE80) return null
        return LinkAddress.V6LinkLocal(value.lowercase())
    }
}
