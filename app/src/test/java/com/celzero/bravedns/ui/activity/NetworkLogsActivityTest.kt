/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.activity

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.paging.PagingData
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.test.core.app.ApplicationProvider
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.*
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.fragment.ConnectionTrackerFragment
import com.celzero.bravedns.ui.fragment.DnsLogFragment
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.viewmodel.ConnectionTrackerViewModel
import com.celzero.bravedns.viewmodel.DnsLogViewModel
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Relocated original display obligation: actual Activity + actual child fragments, without @Ignore.
 * Plain Application, no repository constructors, no rows requiring manager identity resolution.
 * Streams start without emissions to verify the original initial list contract; empty emission
 * verifies the actual fragment transition. Account/WG-stat routes remain separate controller tests.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w360dp-h800dp")
class NetworkLogsActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var host: ActivityController<NetworkLogsActivity>? = null
    private val networkData = MutableLiveData<PagingData<MergedConnectionLog>>()
    private val dnsData = MutableLiveData<PagingData<DnsLog>>()
    private val calls = mutableListOf<String>()
    private var mode = AppConfig.BraveMode.FIREWALL

    @Before fun setUp() {
        stopKoin()
        val preferences = mockk<PersistentState>()
        val config = mockk<AppConfig>()
        val network = mockk<ConnectionTrackerViewModel>()
        val dns = mockk<DnsLogViewModel>()
        // Exact no-op disposal for resource-free strict mocks; application calls remain strict.
        val clear = ViewModel::class.java.getDeclaredMethod("clear\$lifecycle_viewmodel")
        every { clear.invoke(network) } returns null
        every { clear.invoke(dns) } returns null
        every { preferences.theme } returns Themes.LIGHT.id
        every { preferences.routeRethinkInRethink } returns false
        every { preferences.logsEnabled } returns true
        every { preferences.fetchFavIcon } returns false
        every { config.getBraveMode() } answers { mode }
        every { config.isRethinkDnsConnected() } returns false
        every { network.connectionTrackerListMerged } returns networkData
        every { network.setFilter(any(), any(), any()) } answers { calls += "network:${arg<String>(0)}:${arg<Set<String>>(1).sorted()}:${arg<ConnectionTrackerViewModel.TopLevelFilter>(2)}" }
        every { dns.dnsLogsList } returns dnsData
        every { dns.setFilter(any(), any()) } answers { calls += "dns:${arg<String>(0)}:${arg<DnsLogFragment.DnsLogFilter>(1)}" }
        startKoin { modules(module {
            single<Application> { ApplicationProvider.getApplicationContext() }
            single { preferences }; single { config }; single { network }; single { dns }
            single<ConnectionTrackerRepository> { mockk() }
            single<RethinkLogRepository> { mockk() }
            single<DnsLogRepository> { mockk() }
        }) }
        // These two object initializers were read: lazy injection/scalars/LiveData only.
        mockkObject(Logger, VpnController)
        every { Logger.v(any(), any()) } just Runs
        every { VpnController.connectionStatus } returns MutableLiveData<BraveVPNService.State?>()
        every { VpnController.start(any(), any()) } throws AssertionError("VPN forbidden in UI fixture")
        every { VpnController.stop(any(), any(), any()) } throws AssertionError("VPN forbidden in UI fixture")
    }
    @After fun tearDown() {
        val closing = host; host = null
        try {
            if (closing != null && !closing.get().isDestroyed) closing.pause().stop().destroy()
        } finally {
            unmockkObject(Logger, VpnController); stopKoin()
        }
    }
    private fun launch(query: String = "", saved: Bundle? = null) {
        val intent = Intent(ApplicationProvider.getApplicationContext<Application>(), NetworkLogsActivity::class.java)
            .putExtra(Constants.SEARCH_QUERY, query)
        host = Robolectric.buildActivity(NetworkLogsActivity::class.java, intent).also {
            it.create(saved).start().resume().visible()
        }
        compose.waitForIdle()
    }
    @Test fun logsRespectSystemBarsCutoutAndKeyboardInsets() {
        launch()
        val root = host!!.get().findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
        listOf(0, 24, 48, 260, 0).forEach { bottom ->
            val insets = androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars(), androidx.core.graphics.Insets.of(0, 24, 0, 0))
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout(), androidx.core.graphics.Insets.of(18, 32, 10, 0))
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, minOf(bottom, 48)))
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.of(0, 0, 0, if (bottom > 48) bottom else 0))
                .build()
            compose.runOnIdle { androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root, insets) }
            compose.waitForIdle()
            val position = IntArray(2)
            compose.runOnIdle { root.getLocationInWindow(position) }
            val bounds = compose.onNodeWithTag("network-logs-content").fetchSemanticsNode().boundsInWindow
            assertEquals(position[0] + 18f, bounds.left, 1f)
            assertEquals(position[1] + 32f, bounds.top, 1f)
            assertEquals(position[0] + root.width - 10f, bounds.right, 1f)
            assertEquals(position[1] + root.height - bottom.toFloat(), bounds.bottom, 1f)
            assertTrue(compose.onNodeWithTag("log-list").fetchSemanticsNode().boundsInWindow.bottom <= bounds.bottom + 1f)
        }
    }

    @Test fun testNetworkLogsDisplay() {
        launch()
        compose.onNodeWithTag("log-list").assertIsDisplayed()
        assertTrue(host!!.get().supportFragmentManager.fragments.any { it is ConnectionTrackerFragment })
        assertTrue(calls.any { it == "network::[]:ALL" })
    }
    @Test fun actualNetworkFragmentTransitionsToEmptyAfterMemoryOnlyPagingEmission() {
        launch()
        compose.onNodeWithTag("log-list").assertIsDisplayed()
        compose.runOnIdle {
            // PagingData.empty() without states deliberately emits no load-state event.
            // Supply an explicit completed empty generation to exercise the fragment's
            // real end-of-pagination listener without loading a row or touching a DAO.
            networkData.value = PagingData.empty(sourceLoadStates = LoadStates(
                refresh = LoadState.NotLoading(false),
                prepend = LoadState.NotLoading(true),
                append = LoadState.NotLoading(true)
            ))
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("log-list").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("log-list").assertDoesNotExist()
        compose.onNodeWithTag("log-search").assertIsDisplayed()
    }
    @Test fun nativeTabsMountActualDnsFragmentWithoutTouchingAnyService() {
        mode = AppConfig.BraveMode.DNS_FIREWALL
        launch("synthetic.example")
        compose.onNodeWithText(host!!.get().getString(R.string.dns_mode_info_title)).performClick()
        compose.waitForIdle()
        assertTrue(host!!.get().supportFragmentManager.fragments.any { it is DnsLogFragment })
        assertTrue(calls.any { it.startsWith("dns:") })
        compose.onAllNodesWithTag("log-search").assertAny(hasText("synthetic.example"))
    }
    @Test fun actualHostRestoresEditedQueryRatherThanReapplyingIntentText() {
        launch("argument.example")
        compose.onNodeWithTag("log-search").performTextReplacement("restored.example")
        val saved = Bundle()
        host!!.pause().saveInstanceState(saved).stop().destroy(); host = null
        launch("argument.example", saved)
        compose.onNodeWithTag("log-search").assertTextContains("restored.example")
    }
}
