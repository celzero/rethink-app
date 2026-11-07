/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.fragment

import android.app.Application
import androidx.compose.ui.test.*
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.fixture.SafeFirewallHostTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
class FirewallSettingsFragmentTest : SafeFirewallHostTest() {
    @Test fun testFirewallSettingsDisplay() {
        compose.onNodeWithText(activity.getString(R.string.univ_firewall_heading)).assertIsDisplayed().assertHasClickAction()
        assertTrue(activity.supportFragmentManager.fragments.any { it is FirewallSettingsFragment })
    }
    @Test fun universalChoiceDispatchesExactlyOneDestinationWithoutLaunchingIt() {
        compose.onNodeWithText(activity.getString(R.string.univ_firewall_heading)).performClick()
        val shadow = org.robolectric.Shadows.shadowOf(activity)
        assertEquals(com.celzero.bravedns.ui.activity.UniversalFirewallSettingsActivity::class.java.name, shadow.nextStartedActivity?.component?.className)
        assertNull(shadow.nextStartedActivity)
    }
}
