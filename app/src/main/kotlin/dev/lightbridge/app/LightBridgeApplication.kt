// SPDX-License-Identifier: MIT
package dev.lightbridge.app

import android.app.Application
import android.content.Context

/**
 * Owns the pieces that both transfer paths share.
 *
 * The inbox is a single directory with a single list: a file that arrives over Wi-Fi Direct has to
 * appear in exactly the same place as one that arrives as a stream of QR codes, and both ViewModels
 * have to see the same list, so it lives here rather than inside either ViewModel.
 */
internal class LightBridgeApplication : Application() {
    val inbox: InboxStore by lazy { InboxStore(this) }

    companion object {
        fun inbox(context: Context): InboxStore {
            val application = context.applicationContext as LightBridgeApplication
            return application.inbox
        }
    }
}
