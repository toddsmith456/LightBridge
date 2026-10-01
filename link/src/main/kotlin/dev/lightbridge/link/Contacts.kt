// SPDX-License-Identifier: MIT
/*
 * Contacts: the devices you have paired with before.
 *
 * A contact is an identity public key plus the name its owner claimed and whether a human confirmed
 * the six digit code when the pairing happened. The file is plain text (one tab-separated record per
 * line) so it can be inspected, diffed and unit-tested; the identity key is stored as url-safe base64.
 *
 * Loading is deliberately strict: a record whose stored id does not match the fingerprint of its own
 * public key is dropped, so editing the file cannot promote an attacker's key into a known contact.
 */
package dev.lightbridge.link

public class Contact(
    /** Full SHA-256 identity fingerprint, lowercase hex — also the stable id. */
    public val id: String,
    public val name: String,
    /** X.509 (DER) identity public key. */
    public val publicKey: ByteArray,
    public val addedAt: Long,
    public val lastSeenAt: Long,
    /** True once a user confirmed the six-digit code for this identity. */
    public val verified: Boolean,
) {
    public val shortId: String get() = shortFingerprint(id)
}

/** What the UI should show when a session starts with a given identity. */
public enum class Trust {
    /** Never seen this identity before. */
    New,

    /** Seen before, but nobody ever confirmed the code. */
    Known,

    /** Confirmed by a human in an earlier pairing. */
    Verified,
}

public object Contacts {

    public fun encode(contacts: List<Contact>): String = buildString {
        appendLine("# LightBridge contacts — one record per line: id\tname\tpublicKey\taddedAt\tlastSeenAt\tverified")
        contacts.forEach { contact ->
            append(contact.id).append('\t')
            append(contact.name.replace('\t', ' ').replace('\n', ' ')).append('\t')
            append(Base64Url.encode(contact.publicKey)).append('\t')
            append(contact.addedAt).append('\t')
            append(contact.lastSeenAt).append('\t')
            append(if (contact.verified) "1" else "0").append('\n')
        }
    }

    /** Parses a contacts file. Unparseable or inconsistent lines are skipped, never trusted. */
    public fun decode(text: String): List<Contact> = text.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 6) return@mapNotNull null
            val id = parts[0]
            val name = parts[1].take(64)
            val key = Base64Url.decodeOrNull(parts[2]) ?: return@mapNotNull null
            if (name.isBlank()) return@mapNotNull null
            val added = parts[3].toLongOrNull() ?: return@mapNotNull null
            val seen = parts[4].toLongOrNull() ?: return@mapNotNull null
            // The id must be the fingerprint of the key that is stored next to it.
            val actual = runCatching { identityFingerprint(LinkKeyPair.decodeX509(key)) }.getOrNull()
                ?: return@mapNotNull null
            if (actual != id) return@mapNotNull null
            Contact(id, name, key, added, seen, parts[5] == "1")
        }
        .distinctBy { it.id }
        .sortedByDescending { it.lastSeenAt }
        .toList()

    /** Adds [contact] or refreshes the existing record for the same identity. */
    public fun upsert(contacts: List<Contact>, contact: Contact): List<Contact> {
        val existing = contacts.firstOrNull { it.id == contact.id }
        val merged =
            if (existing == null) {
                contact
            } else {
                Contact(
                    id = contact.id,
                    // Keep the name the user chose locally over whatever the peer claims now.
                    name = existing.name,
                    publicKey = contact.publicKey,
                    addedAt = existing.addedAt,
                    lastSeenAt = contact.lastSeenAt,
                    // Trust, once given, is never silently dropped.
                    verified = existing.verified || contact.verified,
                )
            }
        return (contacts.filterNot { it.id == contact.id } + merged).sortedByDescending { it.lastSeenAt }
    }

    public fun remove(contacts: List<Contact>, id: String): List<Contact> =
        contacts.filterNot { it.id == id }

    public fun find(contacts: List<Contact>, id: String): Contact? = contacts.firstOrNull { it.id == id }

    public fun trustOf(contacts: List<Contact>, id: String): Trust = when (val contact = find(contacts, id)) {
        null -> Trust.New
        else -> if (contact.verified) Trust.Verified else Trust.Known
    }

    public fun verify(contacts: List<Contact>, id: String, at: Long): List<Contact> =
        contacts.map { if (it.id == id) Contact(it.id, it.name, it.publicKey, it.addedAt, at, true) else it }

    public fun rename(contacts: List<Contact>, id: String, name: String): List<Contact> =
        contacts.map { if (it.id == id) Contact(it.id, name.take(64), it.publicKey, it.addedAt, it.lastSeenAt, it.verified) else it }
}
