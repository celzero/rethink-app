/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.activity

import android.app.Application
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import com.celzero.bravedns.R
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.fixture.SafeFirewallHostTest
import com.celzero.bravedns.ui.fragment.FirewallSettingsFragment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
class FirewallActivityTest : SafeFirewallHostTest() {
    @Test fun testFirewallActivityDisplay() {
        assertNativeSettingsPage()
        compose.onNodeWithText(activity.getString(R.string.firewall_act_universal_tab)).assertIsDisplayed()
        compose.onNodeWithText(activity.getString(R.string.univ_firewall_heading)).assertIsDisplayed().assertHasClickAction()
    }
    @Test fun actualFirewallHostRecreationRetainsItsNativePage() {
        repeat(2) {
            host.recreate()
            compose.waitForIdle()
            assertNativeSettingsPage()
            compose.onNodeWithText(activity.getString(R.string.univ_firewall_heading)).assertIsDisplayed()
        }
    }
    @Test fun settingsPageFollowsActivityPauseAndResumeWithoutReplacement() {
        val fragment = settingsFragment()
        assertEquals(Lifecycle.State.RESUMED, fragment.lifecycle.currentState)
        assertEquals(Lifecycle.State.RESUMED, fragment.viewLifecycleOwner.lifecycle.currentState)
        host.pause()
        try {
            assertEquals(Lifecycle.State.STARTED, fragment.lifecycle.currentState)
            assertEquals(Lifecycle.State.STARTED, fragment.viewLifecycleOwner.lifecycle.currentState)
        } finally {
            host.resume()
        }
        compose.waitForIdle()
        assertSame(fragment, settingsFragment())
        assertEquals(Lifecycle.State.RESUMED, fragment.lifecycle.currentState)
        assertEquals(Lifecycle.State.RESUMED, fragment.viewLifecycleOwner.lifecycle.currentState)
    }
    @Test fun pauseStatusAfterRecreationDispatchesExactlyOnePauseDestination() {
        host.recreate()
        compose.waitForIdle()
        // The fixture's status stream is memory-only; Robolectric captures this Intent.
        compose.runOnIdle { VpnController.connectionStatus.value = BraveVPNService.State.PAUSED }
        assertEquals(PauseActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }
    @Test
    @Config(sdk = [28])
    fun backFinishesTheSettingsHostWithoutLaunchingAnotherActivity() {
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun settingsFragment(): FirewallSettingsFragment {
        val fragments = activity.supportFragmentManager.fragments
        assertEquals(1, fragments.size)
        return fragments.single() as FirewallSettingsFragment
    }

    private fun assertNativeSettingsPage() {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        assertEquals(1, content.childCount)
        assertTrue(content.getChildAt(0) is ComposeView)
        assertTrue(settingsFragment().requireView() is ComposeView)
    }
}
