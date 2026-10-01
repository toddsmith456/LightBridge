// SPDX-License-Identifier: MIT
package dev.lightbridge.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun verifiedOpticalFramesPersistToInboxWithoutNetworkPermission() {
        lateinit var vm: TransferViewModel
        compose.runOnIdle { vm = androidx.lifecycle.ViewModelProvider(compose.activity)[TransferViewModel::class.java] }
        val original = "Native receiver integration".repeat(100).toByteArray()
        val packed = dev.lightbridge.protocol.Container.pack("integration.txt", "text/plain", original)
        val encoder = dev.lightbridge.protocol.FountainEncoder(packed.bytes, 512, 4242)
        // Feed more than enough frames; a bounded channel is allowed to drop them.
        repeat(80) { vm.acceptQr(encoder.frame(it).encode()) }
        compose.waitUntil(15000) { vm.state.value.reception.receipt != null }
        val receipt = vm.state.value.reception.receipt!!
        org.junit.Assert.assertArrayEquals(original, vm.receivedFile(receipt).readBytes())
        val permissions = compose.activity.packageManager.getPackageInfo(
            compose.activity.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        org.junit.Assert.assertFalse(permissions.contains(android.Manifest.permission.INTERNET))
        vm.delete(receipt)
        compose.waitUntil(5000) { !vm.receivedFile(receipt).exists() }
    }
    @Test fun homeSendInboxAndSettingsAreReachable() {
        compose.onNodeWithText("A little light.\nA direct connection.").assertIsDisplayed()
        compose.onNodeWithText("Send", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Choose a file").assertIsDisplayed()
        compose.onNodeWithText("Inbox", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Your inbox").assertIsDisplayed()
        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Make it yours.").assertIsDisplayed()
    }
    @Test fun textCreatesRealQrAndFinishClearsSender() {
        compose.onNodeWithText("Send", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Or send a text snippet").performTextInput("Hello over light")
        compose.onNodeWithText("Send text").performScrollTo().performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithText("message.txt").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("message.txt").assertExists()
        compose.onNodeWithText("Finish").performScrollTo().performClick()
        compose.onNodeWithText("Choose a file").assertExists()
    }
}
