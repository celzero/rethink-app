/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.fixture

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.Text
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.navigation.fragment.NavHostFragment
import androidx.paging.PagingData
import androidx.test.core.app.ApplicationProvider
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.data.DataUsageSummary
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.InAppMessageProvider
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.home.HomeContent
import com.celzero.bravedns.ui.compose.home.HomePresentation
import com.celzero.bravedns.ui.fragment.SummaryStatisticsFragment
import com.celzero.bravedns.ui.stats.StatsViewMode
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Actual Home Activity and original navigation graph, not a reconstructed navigator.
 * Unsafe destination controllers are replaced by a test FragmentFactory BEFORE onCreate.
 * Home uses real native content with local callbacks; Summary uses its REAL Fragment and
 * strict fake ViewModel with empty, memory-only sources. No row may contain a real app.
 *
 * Safety ordering: plain Application (class @Config) -> strict Koin -> factory -> onCreate.
 * FirewallManager, WireguardManager and account managers must never be initialized here.
 * VpnController's inspected initializer contains only scalar state/lazy injection/LiveData;
 * only its status stream is used, and command entry points fail rather than call a service.
 * This fixture verifies root-shell navigation, not HomeScreenFragment's manager integration.
 */
abstract class SafeRootHostTest {
    @get:Rule val compose = createEmptyComposeRule()
    protected lateinit var preferences: PersistentState
    protected lateinit var statistics: SummaryStatisticsViewModel
    protected lateinit var updater: AppUpdater
    protected var themeId = Themes.LIGHT.id
    protected var timeCategory = SummaryStatisticsViewModel.TimeCategory.ONE_HOUR
    protected val calls = mutableListOf<String>()
    protected var host: ActivityController<HomeScreenActivity>? = null
    protected val activity: HomeScreenActivity get() = checkNotNull(host).get()
    protected val navHost: NavHostFragment get() = activity.supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
    protected val destination: Int? get() = navHost.navController.currentDestination?.id

    @Before fun installSafeRootDependencies() {
        check(ApplicationProvider.getApplicationContext<Application>().javaClass == Application::class.java) {
            "This fixture requires @Config(application = Application::class); production startup is forbidden"
        }
        stopKoin()
        preferences = mockk()
        statistics = mockk()
        // The constructor-free strict VM owns no resources. Stub only framework disposal,
        // retaining fail-closed behavior for every unlisted application method.
        every { ViewModel::class.java.getDeclaredMethod("clear\$lifecycle_viewmodel").invoke(statistics) } returns null
        updater = mockk()
        val config = mockk<AppConfig>()
        val messages = mockk<InAppMessageProvider>()
        val eventLogger = mockk<EventLogger>()
        every { preferences.theme } answers { themeId }
        every { preferences.theme = any() } answers { themeId = firstArg(); calls += "theme:$themeId" }
        every { preferences.firstTimeLaunch } returns false
        every { preferences.appVersion } returns BuildConfig.BASE_VERSION_CODE
        every { preferences.checkForAppUpdate } returns false
        every { preferences.firebaseErrorReportingEnabled } returns false
        every { preferences.appUpdateTimeTs } answers { System.currentTimeMillis() }
        every { preferences.statsViewMode } returns StatsViewMode.LIST.id
        every { preferences.downloadIpInfo } returns false
        every { preferences.vpnEnabledLiveData } returns MutableLiveData(false)
        every { config.getBraveMode() } returns AppConfig.BraveMode.DNS_FIREWALL
        every { messages.showMessages(any()) } answers { calls += "messages" }
        every { updater.unregisterListener(any()) } answers { calls += "unregister-updater" }
        every { updater.completeUpdate() } answers { calls += "complete-update" }
        every { preferences.lastAppUpdateCheck = any() } answers { calls += "postpone-update" }
        every { statistics.getTimeCategory() } answers { timeCategory }
        every { statistics.timeCategoryChanged(any()) } answers { timeCategory = firstArg(); calls += "statistics-time:${timeCategory.value}" }
        every { statistics.setLoadMoreClicked(true) } answers { calls += "statistics-prime" }
        // No paging rows: row identity resolvers must never initialize manager singletons.
        fun emptySource() = MutableLiveData<PagingData<AppConnection>>()
        every { statistics.getTopActiveConns } returns emptySource()
        every { statistics.getAllowedAppNetworkActivity } returns emptySource()
        every { statistics.getBlockedAppNetworkActivity } returns emptySource()
        every { statistics.mcd } returns emptySource()
        every { statistics.mbd } returns emptySource()
        every { statistics.getMostContactedIps } returns emptySource()
        every { statistics.getMostBlockedIps } returns emptySource()
        every { statistics.getMostContactedCountries } returns emptySource()
        every { statistics.mostBlockedBlocklists } returns MutableLiveData(emptyList())
        coEvery { statistics.totalUsage() } returns DataUsageSummary(0, 0, 0, 0)
        // Install DI before touching any object whose initializer could resolve a dependency.
        startKoin { modules(module {
            single<Application> { ApplicationProvider.getApplicationContext() }
            single { preferences }; single { config }; single { statistics }
            single { updater }; single { messages }; single { eventLogger }
        }) }
        mockkObject(Logger)
        every { Logger.vv(any(), any()) } just Runs
        every { Logger.v(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        every { Logger.crash(any(), any(), any()) } throws AssertionError("Unexpected crash path in isolated host")
        mockkObject(VpnController)
        every { VpnController.connectionStatus } returns MutableLiveData<BraveVPNService.State?>()
        every { VpnController.start(any(), any()) } throws AssertionError("No VPN starts in UI fixtures")
        every { VpnController.stop(any(), any(), any()) } throws AssertionError("No VPN stops in UI fixtures")
        every { VpnController.pauseApp() } throws AssertionError("No VPN pauses in UI fixtures")
        every { VpnController.resumeApp() } throws AssertionError("No VPN resumes in UI fixtures")
        every { VpnController.closeConnectionsIfNeeded(any(), any()) } throws AssertionError("No connection changes in UI fixtures")
    }

    protected fun launch(saved: Bundle? = null) {
        check(host == null)
        val next = Robolectric.buildActivity(HomeScreenActivity::class.java)
        next.get().supportFragmentManager.fragmentFactory = SafeRootFragmentFactory()
        host = next
        next.create(saved).start().resume().visible()
        compose.waitForIdle()
    }
    /** Selection posts navigation to the Android queue; Compose idleness alone is not enough. */
    protected fun awaitRootDestination(id: Int) {
        compose.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            activity.supportFragmentManager.executePendingTransactions()
            navHost.childFragmentManager.executePendingTransactions()
            destination == id &&
                navHost.childFragmentManager.primaryNavigationFragment?.lifecycle?.currentState == Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
    }
    protected fun recreateHost() {
        // Flush a pending selection before snapshotting the real NavHost state.
        shadowOf(Looper.getMainLooper()).idle()
        activity.supportFragmentManager.executePendingTransactions()
        navHost.childFragmentManager.executePendingTransactions()
        val saved = Bundle()
        checkNotNull(host).pause().saveInstanceState(saved).stop().destroy()
        host = null
        launch(saved)
    }
    @After fun closeSafeRootHost() {
        val closing = host; host = null
        try {
            if (closing != null && !closing.get().isDestroyed) closing.pause().stop().destroy()
        } finally {
            unmockkObject(VpnController, Logger)
            stopKoin()
        }
    }
}

/** Fail closed: only listed root-shell destinations can be instantiated. */
private class SafeRootFragmentFactory : FragmentFactory() {
    override fun instantiate(classLoader: ClassLoader, className: String): Fragment = when (className) {
        NavHostFragment::class.java.name -> NavHostFragment()
        "com.celzero.bravedns.ui.fragment.HomeScreenFragment", SyntheticHomeFragment::class.java.name -> SyntheticHomeFragment()
        "com.celzero.bravedns.ui.fragment.ConfigureFragment", "com.celzero.bravedns.ui.fragment.AboutFragment",
        SyntheticRootDestination::class.java.name -> SyntheticRootDestination()
        SummaryStatisticsFragment::class.java.name -> SummaryStatisticsFragment()
        else -> error("Unsafe/unreviewed destination requested: $className")
    }
}

/** Native Home renderer only. The toggle records a local count and never invokes a VPN API. */
class SyntheticHomeFragment : Fragment() {
    var clicks = 0
        private set
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        clicks = savedInstanceState?.getInt("fixture.clicks") ?: 0
        val state = HomePresentation(requireContext())
        state.fhsDnsOnOffBtn.text = "Synthetic protection $clicks"
        state.fhsDnsOnOffBtn.setOnClickListener {
            clicks++
            state.fhsDnsOnOffBtn.text = "Synthetic protection $clicks"
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { HomeContent(state) } }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("fixture.clicks", clicks)
        super.onSaveInstanceState(outState)
    }
}

class SyntheticRootDestination : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { Text("Synthetic root destination") } }
        }
}
