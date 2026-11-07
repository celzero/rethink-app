/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.fragment

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
/** Actual Home root route and actual SummaryStatisticsFragment; no @Ignore or content-only stand-in. */
class SummaryStatisticsFragmentTest : SafeRootHostTest() {
    @Test fun testStatisticsDisplay() {
        launch()
        compose.onNodeWithTag("root_nav_${R.id.summaryStatisticsFragment}").performClick()
        awaitRootDestination(R.id.summaryStatisticsFragment)
        assertEquals(R.id.summaryStatisticsFragment, destination)
        assertTrue(navHost.childFragmentManager.primaryNavigationFragment is SummaryStatisticsFragment)
        compose.onNodeWithTag("stats_time_0").assertIsDisplayed()
        assertEquals(1, calls.count { it == "statistics-prime" })
    }
    @Test fun timeSelectionRecreationReattachesWithoutChangingSelectedTime() {
        launch()
        compose.onNodeWithTag("root_nav_${R.id.summaryStatisticsFragment}").performClick()
        awaitRootDestination(R.id.summaryStatisticsFragment)
        compose.onNodeWithTag("stats_time_1").performClick().assertIsSelected()
        recreateHost()
        awaitRootDestination(R.id.summaryStatisticsFragment)
        compose.onNodeWithTag("stats_time_1").assertIsSelected()
        // The real fragment re-applies the ViewModel category once on restored onResume.
        assertEquals(2, calls.count { it == "statistics-time:1" })
    }
}
