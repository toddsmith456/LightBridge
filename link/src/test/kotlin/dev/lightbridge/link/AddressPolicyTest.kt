// SPDX-License-Identifier: MIT
package dev.lightbridge.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Wi-Fi tab is the only part of LightBridge that opens a socket, so this policy is what keeps
 * the network permission from becoming internet access. It is a boundary test on purpose.
 */
class AddressPolicyTest {

    @Test
    fun acceptsThePeerToPeerSubnet() {
        assertTrue(LinkAddressPolicy.isAllowed("192.168.49.1"))
        assertTrue(LinkAddressPolicy.isAllowed("192.168.49.23"))
        assertTrue(LinkAddressPolicy.isAllowed("169.254.10.7"))
        assertNotNull(LinkAddressPolicy.parse("192.168.49.1"))
    }

    @Test
    fun acceptsLinkLocalIpv6Only() {
        assertTrue(LinkAddressPolicy.isAllowed("fe80::1"))
        assertTrue(LinkAddressPolicy.isAllowed("fe80::a1b2:c3d4"))
        assertFalse(LinkAddressPolicy.isAllowed("2001:db8::1"))
        assertFalse(LinkAddressPolicy.isAllowed("::1"))
        assertFalse(LinkAddressPolicy.isAllowed("fe80::1%wlan0"))
    }

    @Test
    fun rejectsAnythingPublicRoutable() {
        // The addresses a leaked network permission would be used for.
        assertFalse(LinkAddressPolicy.isAllowed("8.8.8.8"))
        assertFalse(LinkAddressPolicy.isAllowed("1.1.1.1"))
        assertFalse(LinkAddressPolicy.isAllowed("203.0.113.10"))
        assertFalse(LinkAddressPolicy.isAllowed("93.184.216.34"))
        assertFalse(LinkAddressPolicy.isAllowed("2606:4700:4700::1111"))
    }

    @Test
    fun rejectsOtherPrivateNetworks() {
        // Home/office LANs are reachable without Wi-Fi Direct; a link must not point there.
        assertFalse(LinkAddressPolicy.isAllowed("10.0.0.5"))
        assertFalse(LinkAddressPolicy.isAllowed("172.16.4.4"))
        assertFalse(LinkAddressPolicy.isAllowed("192.168.1.1"))
        assertFalse(LinkAddressPolicy.isAllowed("192.168.48.1"))
        assertFalse(LinkAddressPolicy.isAllowed("192.168.50.1"))
    }

    @Test
    fun rejectsNamesSoNoLookupCanHappen() {
        assertFalse(LinkAddressPolicy.isAllowed("example.com"))
        assertFalse(LinkAddressPolicy.isAllowed("localhost"))
        assertFalse(LinkAddressPolicy.isAllowed("abcdef"))
        assertFalse(LinkAddressPolicy.isAllowed("http://192.168.49.1"))
        assertFalse(LinkAddressPolicy.isAllowed("192.168.49.1:47800"))
        assertFalse(LinkAddressPolicy.isAllowed("192.168.49.1/24"))
    }

    @Test
    fun scopeSuffixesAreRejectedOnTheWire() {
        // The framework reports IPv6 link-local peers as "fe80::1%wlan0". A scope id is host-local
        // routing information and is never accepted here, so LinkTransport strips it before asking;
        // this pins that contract down.
        assertFalse(LinkAddressPolicy.isAllowed("fe80::1%wlan0"))
        assertFalse(LinkAddressPolicy.isAllowed("fe80::1%"))
        assertTrue(LinkAddressPolicy.isAllowed("fe80::1%wlan0".substringBefore('%')))
    }

    @Test
    fun rejectsAmbiguousOrUnusableLiterals() {
        assertFalse(LinkAddressPolicy.isAllowed(""))
        assertFalse(LinkAddressPolicy.isAllowed("192.168.49.0")) // network address
        assertFalse(LinkAddressPolicy.isAllowed("192.168.49.255")) // broadcast
        assertFalse(LinkAddressPolicy.isAllowed("192.168.049.1")) // lookalike octal
        assertFalse(LinkAddressPolicy.isAllowed("192.168.49")) // too short
        assertFalse(LinkAddressPolicy.isAllowed("192.168.49.1.1"))
        assertFalse(LinkAddressPolicy.isAllowed("300.168.49.1"))
        assertFalse(LinkAddressPolicy.isAllowed("x".repeat(60)))
    }
}
