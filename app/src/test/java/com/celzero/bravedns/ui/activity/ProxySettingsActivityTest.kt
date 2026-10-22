/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.activity

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.ui.fixture.StartedHostComposeOwner
import io.mockk.*
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Original control-display obligation on the actual STARTED Activity.
 * Both proxies are disabled in strict fake AppConfig, so onCreate does not fetch endpoints.
 * Never resume this fixture: onResume accesses Orbot, WireguardManager and RPN account state.
 * No proxy toggles or refresh actions are invoked. Resumed behavior remains a separate gate.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
class ProxySettingsActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ProxySettingsActivity>
    private var composeOwner: StartedHostComposeOwner? = null
    @Before fun setUp() {
        stopKoin()
        val preferences = mockk<PersistentState>()
        val config = mockk<AppConfig>()
        every { preferences.theme } returns Themes.LIGHT.id
        every { config.isCustomHttpProxyEnabled() } returns false
        every { config.isCustomSocks5Enabled() } returns false
        startKoin { modules(module { single { preferences }; single { config } }) }
        host = Robolectric.buildActivity(ProxySettingsActivity::class.java).create().start()
        composeOwner = StartedHostComposeOwner().also { it.attachBeforeVisible(host.get()) }
        host.visible()
        compose.waitForIdle()
        assertEquals(Lifecycle.State.STARTED, host.get().lifecycle.currentState)
    }
    @After fun tearDown() {
        try {
            composeOwner?.destroy(); composeOwner = null
            if (::host.isInitialized && !host.get().isDestroyed) host.stop().destroy()
        } finally {
            stopKoin()
        }
    }
    @Test fun testProxySettingsDisplay() {
        compose.onNodeWithText(host.get().getString(R.string.settings_socks5_heading)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(host.get().getString(R.string.settings_https_heading)).performScrollTo().assertIsDisplayed()
    }
}
