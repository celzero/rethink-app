/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.fragment

import android.app.Application
import androidx.compose.ui.test.*
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.fixture.SafeDnsHostTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
class DnsSettingsFragmentTest : SafeDnsHostTest() {
    @Test fun captivePortalPreferenceRemainsAvailableAfterMigration() {
        compose.onNodeWithTag("dns-switch-dcCaptivePortalRl").performScrollTo().assertIsOff()
    }
    @Test fun testDnsSettingsDisplay() {
        compose.onNodeWithTag("dns-choice-network").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("dns-choice-custom").performScrollTo().assertIsDisplayed()
        assertTrue(activity.supportFragmentManager.fragments.any { it is DnsSettingsFragment })
    }
    @Test fun restoreDialogCancelDoesNotWriteSettingsOrInitializeAnyManager() {
        compose.onNodeWithTag("dns-restore").performClick()
        compose.onNodeWithTag("dns-dialog-positive").assertIsDisplayed()
        compose.onNodeWithTag("dns-dialog-secondary").performClick()
        compose.onNodeWithTag("dns-dialog-positive").assertDoesNotExist()
        // Strict preference/config mocks reject every write; no blanket relaxed managers.
    }
    @Test fun selectedNetworkDnsOpensSyntheticServerInfoAndDismisses() {
        compose.onNodeWithTag("dns-choice-network").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("192.0.2.53").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("192.0.2.53").assertIsDisplayed()
        compose.onNodeWithTag("dns-dialog-positive").performClick()
        compose.onNodeWithText("192.0.2.53").assertDoesNotExist()
    }
}
