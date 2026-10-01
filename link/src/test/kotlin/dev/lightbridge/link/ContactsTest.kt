// SPDX-License-Identifier: MIT
package dev.lightbridge.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsTest {

    private fun contact(
        name: String,
        verified: Boolean = false,
        seenAt: Long = 1_000L,
        addedAt: Long = 500L,
    ): Contact {
        val pair = LinkKeyPair.generate()
        return Contact(
            id = identityFingerprint(pair.keyPair.public),
            name = name,
            publicKey = pair.keyPair.public.encoded,
            addedAt = addedAt,
            lastSeenAt = seenAt,
            verified = verified,
        )
    }

    @Test
    fun roundTripsThroughTheTextFormat() {
        val contacts = listOf(contact("Kitchen tablet", verified = true), contact("Pixel"))
        val decoded = Contacts.decode(Contacts.encode(contacts))
        assertEquals(2, decoded.size)
        assertEquals(contacts.map { it.id }.toSet(), decoded.map { it.id }.toSet())
        assertEquals("Kitchen tablet", decoded.first { it.id == contacts[0].id }.name)
        assertTrue(decoded.first { it.id == contacts[0].id }.verified)
    }

    @Test
    fun dropsRecordsWhoseIdDoesNotMatchTheirKey() {
        val real = contact("Real")
        val text = Contacts.encode(listOf(real))
        // Swap in a different key while keeping the original id: a hand-edited contacts file.
        val impostor = contact("Impostor")
        val tampered = text.replace(Base64Url.encode(real.publicKey), Base64Url.encode(impostor.publicKey))
        assertEquals(emptyList<Contact>(), Contacts.decode(tampered))
    }

    @Test
    fun skipsUnparseableLinesInsteadOfFailing() {
        val good = contact("Good")
        val text = listOf(
            "",
            "# comment",
            "garbage",
            "id-only\tname",
            "zz\tname\tnotbase64\t0\t0\t1",
            Contacts.encode(listOf(good)).lineSequence().last { it.isNotBlank() },
        ).joinToString("\n")
        val decoded = Contacts.decode(text)
        assertEquals(1, decoded.size)
        assertEquals(good.id, decoded.first().id)
    }

    @Test
    fun upsertKeepsTheLocalNameAndNeverDropsTrust() {
        val existing = contact("My Pixel", verified = true, seenAt = 1_000L, addedAt = 500L)
        val refreshed = Contact(existing.id, "Pixel (renamed on the other device)", existing.publicKey,
            addedAt = 9_999L, lastSeenAt = 2_000L, verified = false)
        val merged = Contacts.upsert(listOf(existing), refreshed).single()
        assertEquals("My Pixel", merged.name)
        assertTrue(merged.verified)
        // The local record keeps when we first met them, not the peer's claim.
        assertEquals(500L, merged.addedAt)
        assertEquals(2_000L, merged.lastSeenAt)
    }

    @Test
    fun upsertAddsNewContactsAndKeepsTheListNewestFirst() {
        val older = contact("Older", seenAt = 100L)
        val newer = contact("Newer", seenAt = 900L)
        val list = Contacts.upsert(Contacts.upsert(emptyList(), older), newer)
        assertEquals(listOf("Newer", "Older"), list.map { it.name })
    }

    @Test
    fun trustReflectsVerificationAndLookup() {
        val unknown = contact("Unknown")
        val verified = contact("Verified", verified = true)
        val contacts = listOf(unknown, verified)
        assertEquals(Trust.New, Contacts.trustOf(contacts, "f".repeat(64)))
        assertEquals(Trust.Known, Contacts.trustOf(contacts, unknown.id))
        assertEquals(Trust.Verified, Contacts.trustOf(contacts, verified.id))

        val afterVerify = Contacts.verify(contacts, unknown.id, at = 5_000L)
        assertEquals(Trust.Verified, Contacts.trustOf(afterVerify, unknown.id))

        assertEquals(1, Contacts.remove(afterVerify, unknown.id).size)
        assertNull(Contacts.find(Contacts.remove(afterVerify, unknown.id), unknown.id))
    }

    @Test
    fun renameKeepsIdentityAndTrust() {
        val original = contact("Old name", verified = true)
        val renamed = Contacts.rename(listOf(original), original.id, "Todd's phone").single()
        assertEquals("Todd's phone", renamed.name)
        assertEquals(original.id, renamed.id)
        assertTrue(renamed.verified)
    }
}
