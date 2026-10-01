// SPDX-License-Identifier: MIT
package dev.lightbridge.app.wifi

import android.content.Context
import android.os.Build
import dev.lightbridge.link.Contact
import dev.lightbridge.link.Contacts
import dev.lightbridge.link.LinkKeyPair
import dev.lightbridge.link.LocalIdentity
import java.io.File

/**
 * Persistence for the Wi-Fi link: this device's long-term identity key, the name peers see, and the
 * contact list.
 *
 * The identity key is what a contact list is built on, so it must never change: it is generated once
 * (P-256) and kept in the app's private directory. Losing it means every peer sees a new device;
 * that is why the file is written before it is ever used and never rotated automatically.
 */
internal class IdentityStore(context: Context) {

    private val directory = File(context.filesDir, "link").apply { mkdirs() }
    private val identityPrivate = File(directory, "identity.pk8")
    private val identityPublic = File(directory, "identity.pub")
    private val contactsFile = File(directory, "contacts.txt")
    private val prefs = context.getSharedPreferences("link", Context.MODE_PRIVATE)

    /** This device's identity, generated on first use. */
    fun identity(): LocalIdentity {
        val private = identityPrivate.takeIf { it.isFile && it.length() > 0 }
        val public = identityPublic.takeIf { it.isFile && it.length() > 0 }
        if (private != null && public != null) {
            runCatching {
                return LocalIdentity(
                    privateKey = LinkKeyPair.decodePrivate(private.readBytes()),
                    publicKey = LinkKeyPair.decodeX509(public.readBytes()),
                    name = deviceName(),
                )
            }.onFailure {
                // A corrupt key file is worse than a new identity: move it aside and start over.
                identityPrivate.renameTo(File(directory, "identity.pk8.corrupt-${System.currentTimeMillis()}"))
                identityPublic.renameTo(File(directory, "identity.pub.corrupt-${System.currentTimeMillis()}"))
            }
        }
        val generated = LinkKeyPair.generate()
        identityPrivate.writeBytes(generated.keyPair.private.encoded)
        identityPublic.writeBytes(generated.keyPair.public.encoded)
        return LocalIdentity(generated.keyPair.private, generated.keyPair.public, deviceName())
    }

    /** Name other devices see during a pairing. Defaults to the phone's model. */
    fun deviceName(): String =
        prefs.getString("device_name", null)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL?.takeIf { it.isNotBlank() }?.take(32)
            ?: "Android device"

    fun setDeviceName(name: String) {
        prefs.edit().putString("device_name", name.trim().take(32)).apply()
    }

    fun contacts(): List<Contact> =
        if (contactsFile.isFile) runCatching { Contacts.decode(contactsFile.readText()) }.getOrDefault(emptyList())
        else emptyList()

    fun saveContacts(contacts: List<Contact>) {
        contactsFile.writeText(Contacts.encode(contacts))
    }
}
