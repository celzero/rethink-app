/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.fixture

import android.app.Application
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.FirewallActivity
import com.celzero.bravedns.util.Themes
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/** Actual firewall pager Activity and actual native FirewallSettingsFragment. No manager used. */
abstract class SafeFirewallHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    protected lateinit var host: ActivityController<FirewallActivity>
    protected val activity: FirewallActivity get() = host.get()
    @Before fun launchSafeFirewall() {
        stopKoin()
        val prefs = mockk<PersistentState>()
        every { prefs.theme } returns Themes.LIGHT.id
        startKoin { modules(module { single { prefs } }) }
        mockkObject(VpnController)
        every { VpnController.connectionStatus } returns MutableLiveData<BraveVPNService.State?>()
        every { VpnController.start(any(), any()) } throws AssertionError("VPN forbidden in fixture")
        every { VpnController.stop(any(), any(), any()) } throws AssertionError("VPN forbidden in fixture")
        host = Robolectric.buildActivity(FirewallActivity::class.java).setup().visible()
        compose.waitForIdle()
    }
    @After fun closeSafeFirewall() {
        if (::host.isInitialized) host.pause().stop().destroy()
        unmockkObject(VpnController); stopKoin()
    }
}
