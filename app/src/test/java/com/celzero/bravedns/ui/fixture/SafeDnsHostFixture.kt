/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.fixture

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.DnsDetailActivity
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

/** Actual DNS pager + production DnsSettingsFragment. All settings/config reads are strict fakes.
 * Connected-DNS LiveData never emits: no latency probe or backend constant is resolved.
 * No restore, worker, WireguardManager, write or permission action is allowed by this fixture.
 */
abstract class SafeDnsHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    protected lateinit var host: ActivityController<DnsDetailActivity>
    protected val activity: DnsDetailActivity get() = host.get()
    protected lateinit var preferences: PersistentState
    protected lateinit var config: AppConfig
    @Before fun launchSafeDns() {
        stopKoin()
        preferences = mockk(); config = mockk()
        val events = mockk<EventLogger>()
        every { preferences.theme } returns Themes.LIGHT.id
        every { preferences.fetchFavIcon } returns false
        every { preferences.enableDnsAlg } returns false
        every { preferences.periodicallyCheckBlocklistUpdate } returns false
        every { preferences.useCustomDownloadManager } returns false
        every { preferences.enableDnsCache } returns false
        every { preferences.useSystemDnsForUndelegatedDomains } returns false
        every { preferences.blockDnsForUnknownApp } returns false
        every { preferences.preventDnsLeaks } returns false
        every { preferences.splitDns } returns false
        every { preferences.dnsRecordTypesAutoMode } returns true
        every { preferences.proxyDns } returns true
        every { preferences.blocklistEnabled } returns false
        every { config.getDnsType() } returns AppConfig.DnsType.SYSTEM_DNS
        every { config.isDnsProxyActive() } returns false
        every { config.isSmartDnsEnabled() } returns false
        every { config.isSystemDns() } returns true
        every { config.isRethinkDnsConnected() } returns false
        every { config.getConnectedDnsObservable() } returns MutableLiveData<String>()
        startKoin { modules(module { single { preferences }; single { config }; single { events } }) }
        // Inspected initializer: scalars + lazy DI + LiveData, no native/manager hydration.
        mockkObject(VpnController)
        every { VpnController.connectionStatus } returns MutableLiveData<BraveVPNService.State?>()
        coEvery { VpnController.getSystemDns() } returns "192.0.2.53"
        every { VpnController.start(any(), any()) } throws AssertionError("No VPN in DNS fixture")
        every { VpnController.stop(any(), any(), any()) } throws AssertionError("No VPN in DNS fixture")
        host = Robolectric.buildActivity(DnsDetailActivity::class.java).setup().visible()
        compose.waitForIdle()
    }
    @After fun closeSafeDns() {
        if (::host.isInitialized) host.pause().stop().destroy()
        unmockkObject(VpnController); stopKoin()
    }
}

/**
 * Presentation-only lifecycle for actual hosts that must never enter production onResume.
 * Compose 1.12's test registry discovers only RESUMED view-tree owners. Replacing the
 * decor's owner before attachment lets its native content participate in semantics tests
 * while the real Activity and its lifecycleScope remain STARTED. It does not dispatch
 * Activity lifecycle callbacks or invoke controller/service code.
 */
internal class StartedHostComposeOwner : LifecycleOwner {
    override val lifecycle = LifecycleRegistry(this)

    fun attachBeforeVisible(activity: ComponentActivity) {
        check(activity.lifecycle.currentState == Lifecycle.State.STARTED)
        check(!activity.window.decorView.isAttachedToWindow)
        activity.window.decorView.setViewTreeLifecycleOwner(this)
        lifecycle.currentState = Lifecycle.State.RESUMED
        check(activity.lifecycle.currentState == Lifecycle.State.STARTED)
    }

    fun destroy() {
        lifecycle.currentState = Lifecycle.State.DESTROYED
    }
}
