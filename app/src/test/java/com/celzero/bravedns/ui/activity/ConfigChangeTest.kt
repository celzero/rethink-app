/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.activity

import android.app.Application
import androidx.compose.ui.test.*
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.fixture.SafeRootHostTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
class ConfigChangeTest : SafeRootHostTest() {
    @Test fun testHomeScreenStateRetentionOnRotation() {
        launch()
        compose.onNodeWithTag("fhs_dns_on_off_btn").assertIsDisplayed().performClick()
        compose.onNodeWithTag("fhs_dns_on_off_btn").assertTextContains("SYNTHETIC PROTECTION 1")
        // Actual saved-instance restoration, preserving the original rotation obligation.
        org.robolectric.RuntimeEnvironment.setQualifiers("en-rUS-w800dp-h360dp-land")
        recreateHost()
        compose.onNodeWithTag("fhs_dns_on_off_btn").assertIsDisplayed().assertTextContains("SYNTHETIC PROTECTION 1")
        org.robolectric.RuntimeEnvironment.setQualifiers("en-rUS-w360dp-h800dp-port")
        recreateHost()
        compose.onNodeWithTag("fhs_dns_on_off_btn").assertIsDisplayed().assertTextContains("SYNTHETIC PROTECTION 1")
    }
    @Test fun selectedDestinationAndHighlightSurviveActualRecreation() {
        launch()
        compose.onNodeWithTag("root_nav_${R.id.summaryStatisticsFragment}").performClick()
        awaitRootDestination(R.id.summaryStatisticsFragment)
        recreateHost()
        awaitRootDestination(R.id.summaryStatisticsFragment)
        compose.onNodeWithTag("root_nav_${R.id.summaryStatisticsFragment}").assertIsSelected()
        compose.onNodeWithTag("stats_time_0").assertIsDisplayed()
        assertEquals(R.id.summaryStatisticsFragment, destination)
    }
}
