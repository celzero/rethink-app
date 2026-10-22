/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.activity

import android.app.Application
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.*
import androidx.lifecycle.Lifecycle
import com.celzero.bravedns.ui.fixture.SafeDnsHostTest
import com.celzero.bravedns.ui.fragment.DnsSettingsFragment
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
class DnsDetailNavigationTest : SafeDnsHostTest() {
    @Test fun testDnsTabsDisplay() {
        assertNativeSettingsPage()
        // The old tabs were hidden; the single native page remains the entire host.
        compose.onNodeWithTag("dns-choice-network").performScrollTo().assertIsDisplayed()
    }
    @Test fun customDnsChoicePreservesActivityRouteWithoutLaunchingDestination() {
        compose.onNodeWithTag("dns-choice-custom").performScrollTo().performClick()
        val shadow = org.robolectric.Shadows.shadowOf(activity)
        assertEquals(DnsListActivity::class.java.name, shadow.nextStartedActivity?.component?.className)
        assertNull(shadow.nextStartedActivity)
    }
    @Test fun actualHostRecreationRestoresNativeDnsPage() {
        repeat(2) {
            host.recreate()
            compose.waitForIdle()
            assertNativeSettingsPage()
            compose.onNodeWithTag("dns-choice-network").performScrollTo().assertIsDisplayed()
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
    @Test
    @Config(sdk = [28])
    fun backFinishesTheSettingsHostWithoutLaunchingAnotherActivity() {
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun settingsFragment(): DnsSettingsFragment {
        val fragments = activity.supportFragmentManager.fragments
        assertEquals(1, fragments.size)
        return fragments.single() as DnsSettingsFragment
    }

    private fun assertNativeSettingsPage() {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        assertEquals(1, content.childCount)
        assertTrue(content.getChildAt(0) is ComposeView)
        assertTrue(settingsFragment().requireView() is ComposeView)
    }
}
