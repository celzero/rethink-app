/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.activity

import android.app.Application
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.ui.fixture.StartedHostComposeOwner
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual STARTED host and production click listeners. No onResume DNS-status/backend probe.
 * The original five-card display contract is retained, with all six cards and route capture added.
 * A separate reviewed probe-boundary fixture is still required for onResume highlight behavior.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
class DnsListActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<DnsListActivity>
    private lateinit var config: AppConfig
    private var composeOwner: StartedHostComposeOwner? = null
    @Before fun setUp() {
        stopKoin()
        val prefs = mockk<PersistentState>(); config = mockk()
        every { prefs.theme } returns Themes.LIGHT.id
        startKoin { modules(module { single { prefs }; single { config } }) }
        launch()
    }
    private fun launch(saved: Bundle? = null) {
        host = Robolectric.buildActivity(DnsListActivity::class.java).create(saved).start()
        composeOwner = StartedHostComposeOwner().also { it.attachBeforeVisible(host.get()) }
        host.visible()
        compose.waitForIdle()
        assertEquals(Lifecycle.State.STARTED, host.get().lifecycle.currentState)
    }
    @After fun tearDown() {
        try {
            composeOwner?.destroy(); composeOwner = null
            if (::host.isInitialized && !host.get().isDestroyed) host.stop().destroy()
            verify { config wasNot Called }
        } finally {
            stopKoin()
        }
    }
    @Test fun testDnsOptionsDisplay() {
        listOf("cardDoh", "cardDnscrypt", "cardDot", "cardOdoh", "cardRethinkDns", "cardDnsproxy").forEach {
            compose.onNodeWithTag("dns-protocol-$it").performScrollTo().assertIsDisplayed().assertHasClickAction()
        }
    }
    @Test fun protocolRoutesKeepTheirExactInputExtrasWithoutStartingDestinations() {
        val destinations = listOf("cardDoh" to 0, "cardDnsproxy" to 1, "cardDnscrypt" to 2, "cardDot" to 3, "cardOdoh" to 4)
        for ((tag, index) in destinations) {
            compose.onNodeWithTag("dns-protocol-$tag").performScrollTo().performClick()
            val intent = shadowOf(host.get()).nextStartedActivity
            assertEquals(ConfigureOtherDnsActivity::class.java.name, intent.component?.className)
            assertEquals(index, intent.getIntExtra("dns_type", -1))
            assertEquals(setOf("dns_type"), intent.extras!!.keySet())
            assertNull(shadowOf(host.get()).nextStartedActivity)
        }
    }
    @Test fun nativeGridRemainsMountedAfterActualSavedInstanceRecreation() {
        compose.onNodeWithTag("dns-protocol-cardRethinkDns").performScrollTo()
        val saved = Bundle()
        host.saveInstanceState(saved)
        composeOwner?.destroy(); composeOwner = null
        host.stop().destroy()
        launch(saved)
        compose.onNodeWithTag("dns-protocol-cardRethinkDns").performScrollTo().assertIsDisplayed()
    }
}
