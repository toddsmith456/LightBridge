// SPDX-License-Identifier: MIT
package dev.lightbridge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lightbridge.app.wifi.WifiLinkViewModel
import dev.youniversal.theme.YouniversalTheme
import dev.youniversal.theme.YouniversalThemeState
import dev.youniversal.theme.rememberYouniversalThemeState

class MainActivity : ComponentActivity() {

    /**
     * Theme writes are coalesced while a slider is being dragged; this keeps a reference so the last
     * value is committed when the app leaves the foreground instead of waiting for a process death.
     */
    private var themeState: YouniversalThemeState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme = rememberYouniversalThemeState()
            themeState = theme
            val link: WifiLinkViewModel = viewModel()
            YouniversalTheme(state = theme) {
                LightBridgeApp(viewModel(), link, theme)
            }
        }
    }

    override fun onPause() {
        // The optical settings coalesce the same way; flush so a drag that just ended is not lost.
        themeState?.flushWrites()
        super.onPause()
    }
}
