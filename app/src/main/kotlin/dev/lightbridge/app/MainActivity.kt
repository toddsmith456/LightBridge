// SPDX-License-Identifier: MIT
package dev.lightbridge.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.youniversal.theme.YouniversalTheme
import dev.youniversal.theme.rememberYouniversalThemeState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val theme = rememberYouniversalThemeState()
            YouniversalTheme(state = theme) {
                LightBridgeApp(viewModel(), theme)
            }
        }
    }
}
