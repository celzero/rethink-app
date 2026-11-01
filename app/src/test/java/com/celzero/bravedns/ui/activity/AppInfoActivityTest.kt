/* Copyright 2025 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.activity

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Process
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.database.*
import com.celzero.bravedns.glide.FavIconDownloader
import com.celzero.bravedns.service.*
import com.celzero.bravedns.ui.compose.appdetails.AppDetailsState
import com.celzero.bravedns.ui.stats.StatsViewMode
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.*
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowProcess
import java.util.Collections

/**
 * Actual AppInfoActivity host contracts, relocated from androidTest to a plain-Application JVM
 * sandbox so launching a test never starts RethinkDnsApplication or its services.
 *
 * Real lifecycle-owned ViewModels use strict constructor-injected DAO doubles and in-memory
 * PagingSources. No Room/repository constructor, disk cache, network client, VPN service, native
 * trie or permission flow is reachable from this fixture. The only production singleton with an
 * eager worker, FirewallManager, is initialized after its strict empty repository is installed;
 * its two startup jobs are joined BEFORE its controller-facing operations are mocked.
 *
 * All 24 original names remain. The seven "Performance"/memory workload cases assert deterministic
 * behavior here, not device speed. Their original numeric device acceptance requirements remain
 * explicitly unrun in docs/compose-migration/app-settings-host-tests.md. No exceptions are swallowed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w420dp-h900dp")
class AppInfoActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var persistent: PersistentState
    private lateinit var config: AppConfig
    private lateinit var events: EventLogger
    private lateinit var startupRepository: AppInfoRepository
    private lateinit var appDao: AppInfoDAO
    private lateinit var ipDao: CustomIpDao
    private lateinit var domainDao: CustomDomainDAO
    private lateinit var connectionDao: ConnectionTrackerDAO
    private lateinit var rethinkDao: RethinkLogDao
    private lateinit var statsDao: StatsSummaryDao
    private val apps = mutableMapOf<Int, AppInfo>()
    private val controllers = mutableListOf<ActivityController<AppInfoActivity>>()
    private lateinit var controller: ActivityController<AppInfoActivity>
    private val firewallCommands = Collections.synchronizedList(mutableListOf<Triple<Int, FirewallManager.FirewallStatus, FirewallManager.ConnectionStatus>>())
    private val androidInfoPackages = Collections.synchronizedList(mutableListOf<String>())
    private val ipCount = MutableLiveData(3)
    private val domainCount = MutableLiveData(5)
    private var statsMode = StatsViewMode.LIST.id
    private var missing = false
    private var unknownPackage = false
    private var emptyLogs = false

    companion object {
        private const val TEST_UID = 12345
        private const val TEST_PACKAGE = "example.synthetic.app"
        private const val TEST_NAME = "Synthetic app"
        private const val DOMAIN = "allowed.example"
        private const val ACTIVE_IP = "192.0.2.1"
        private const val DESTINATION_IP = "192.0.2.2"
    }

    @Before fun setUp() {
        assertEquals(Application::class.java, RuntimeEnvironment.getApplication().javaClass)
        ShadowProcess.setUid(10001)
        assertNotEquals("Fixture must not accidentally enter the self-app warning", TEST_UID, Process.myUid())
        check(GlobalContext.getOrNull() == null) { "This fixture requires its own empty Koin graph" }
        persistent = mockk(); config = mockk(); events = mockk(); startupRepository = mockk()
        appDao = mockk(); ipDao = mockk(); domainDao = mockk(); connectionDao = mockk(); rethinkDao = mockk(); statsDao = mockk()
        every { persistent.theme } returns 0
        every { persistent.statsViewMode } answers { statsMode }
        every { persistent.statsViewMode = any() } answers { statsMode = firstArg() }
        every { persistent.downloadIpInfo } returns true
        every { config.getBraveMode() } returns AppConfig.BraveMode.DNS_FIREWALL
        every { events.log(EventType.FW_RULE_MODIFIED, Severity.LOW, any(), EventSource.UI, true, any()) } just Runs
        // These two and only these two repository reads are safe during singleton initialization.
        coEvery { startupRepository.getAppInfo() } returns emptyList()
        coEvery { startupRepository.getTempAllowedApps() } returns emptyList()
        every { ipDao.getAppWiseIpRulesCount(any()) } returns ipCount
        every { domainDao.getAppWiseDomainRulesCount(any()) } returns domainCount
        every { statsDao.getTopActiveConns(any<Int>(), any()) } answers { page(firstArg(), ACTIVE_IP, "active.example") }
        every { statsDao.getMostDomainsByUid(any(), any()) } answers { page(firstArg(), "", DOMAIN) }
        every { statsDao.getAsnLogsLimited(any(), any()) } answers { page(firstArg(), "AS64500", "Synthetic ASN") }
        every { statsDao.getMostContactedCountriesByUid(any(), any()) } answers { page(firstArg(), "", "", flag = "🇺🇸") }
        every { statsDao.getBlockedDomainsByUid(any(), any()) } answers { emptyPage() }
        every { statsDao.getBlockedAsnByUid(any(), any()) } answers { emptyPage() }
        every { connectionDao.getAppIpLogsLimited(any(), any()) } answers { page(firstArg(), DESTINATION_IP, "destination.example") }
        every { connectionDao.getBlockedAppIpLogsLimited(any(), any()) } answers { emptyPage() }
        coEvery { statsDao.getBlockedBlocklistCombosByUid(any(), any()) } returns emptyList()
        startKoin { modules(module {
            single { persistent }; single { config }; single { events }; single { startupRepository }
            viewModel { AppInfoViewModel(appDao) }
            viewModel { CustomIpViewModel(ipDao) }
            viewModel { CustomDomainViewModel(domainDao) }
            viewModel { AppConnectionsViewModel(connectionDao, rethinkDao, statsDao) }
        }) }
        // Install log sinks BEFORE FirewallManager's first reference: its empty startup logs cannot
        // fall through to VpnController/console storage. Logger has only lazy injected properties.
        mockkObject(Logger)
        every { Logger.vv(any(), any()) } just Runs
        every { Logger.v(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        initializeEmptyFirewallManager()
        mockkObject(FirewallManager, ProxyManager, VpnController, DomainRulesManager, IpRulesManager, Utilities, UIUtils, FavIconDownloader.Companion)
        listOf(TEST_UID, 0, 1000, Int.MAX_VALUE).forEach { apps[it] = syntheticApp(it) }
        coEvery { FirewallManager.getAppInfoByUid(any()) } answers { if (missing) null else apps[firstArg()] }
        coEvery { FirewallManager.getAppInfoByUidAndPackage(any(), any()) } answers { if (missing) null else apps[firstArg()] }
        coEvery { FirewallManager.getPackageNamesByUid(any()) } answers { listOf(requireNotNull(apps[firstArg()]).packageName) }
        coEvery { FirewallManager.getAppNamesByUid(any()) } answers { listOf(requireNotNull(apps[firstArg()]).appName) }
        coEvery { FirewallManager.appStatus(any()) } answers { FirewallManager.FirewallStatus.NONE }
        coEvery { FirewallManager.connectionStatus(any()) } answers { FirewallManager.ConnectionStatus.ALLOW }
        coEvery { FirewallManager.isTempAllowed(any()) } returns false
        every { FirewallManager.isUnknownPackage(any()) } answers { unknownPackage }
        every { FirewallManager.updateIsProxyExcluded(any(), any()) } just Runs
        coEvery { FirewallManager.updateTempAllow(any(), any()) } just Runs
        coEvery { FirewallManager.updateFirewallStatus(any(), any(), any()) } answers {
            firewallCommands.add(Triple(firstArg(), secondArg(), thirdArg())); Unit
        }
        every { ProxyManager.getProxyIdForApp(any()) } returns emptyList()
        every { VpnController.uptimeMs() } returns 60_000L
        every { VpnController.isVpnLockdown() } returns false
        every { VpnController.closeConnectionsIfNeeded(any(), any()) } just Runs
        // Explicit denial trips if future lifecycle code tries to start/stop the real service.
        every { VpnController.start(any(), any()) } answers { error("Forbidden service start in AppInfo host fixture") }
        every { VpnController.stop(any(), any(), any()) } answers { error("Forbidden service stop in AppInfo host fixture") }
        every { DomainRulesManager.status(any(), any()) } returns DomainRulesManager.Status.NONE
        every { IpRulesManager.getMostSpecificRuleMatch(any(), any(), any()) } returns IpRulesManager.IpRuleStatus.NONE
        every { Utilities.getIcon(any(), any(), any()) } answers { ColorDrawable(Color.BLUE) }
        every { UIUtils.openAndroidAppInfo(any(), any()) } answers {
            androidInfoPackages.add(secondArg()); Unit
        }
        // A failed-cache hit renders the flag and never constructs a Glide request/cache target.
        every { FavIconDownloader.isUrlAvailableInFailedCache(any()) } returns true
        ShadowDialog.reset()
    }

    private fun initializeEmptyFirewallManager() {
        // Reading the singleton starts ONLY the two initialization coroutines, whose repository is
        // already the strict double above. Wait for both before installing object interception.
        val singleton = FirewallManager
        val field = FirewallManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
        val scope = field.get(singleton) as CoroutineScope
        runBlocking { withTimeout(5000) { requireNotNull(scope.coroutineContext[Job]).children.toList().joinAll() } }
        assertFalse(requireNotNull(scope.coroutineContext[Job]).children.any())
    }

    @After fun tearDown() {
        ShadowDialog.getShownDialogs().filter { it.isShowing }.forEach { it.dismiss() }
        controllers.asReversed().forEach { if (!it.get().isDestroyed) it.pause().stop().destroy() }
        val lifecycleJobs = controllers.map { requireNotNull(it.get().lifecycleScope.coroutineContext[Job]) }
        // IO children can have cancelled continuations queued on Robolectric's paused main looper.
        // Never block that looper in joinAll: drain it until every job is complete, keeping all
        // fake boundaries installed throughout cancellation. The timeout still fails closed.
        compose.waitUntil(5000) {
            shadowOf(Looper.getMainLooper()).idle()
            lifecycleJobs.all { it.isCompleted }
        }
        runBlocking { withTimeout(5000) { lifecycleJobs.joinAll() } }
        verify { appDao wasNot Called }
        verify { rethinkDao wasNot Called }
        // The initializer repository cannot be used for real data after object methods are mocked.
        coVerify(exactly = 0) { startupRepository.insert(any()) }
        coVerify(exactly = 0) { startupRepository.updateFirewallStatusByUid(any(), any(), any()) }
        verify(exactly = 0) { VpnController.start(any(), any()) }
        verify(exactly = 0) { VpnController.stop(any(), any(), any()) }
        unmockkObject(FirewallManager, ProxyManager, VpnController, DomainRulesManager, IpRulesManager, Utilities, UIUtils, FavIconDownloader.Companion)
        unmockkObject(Logger)
        stopKoin(); ShadowDialog.reset(); ShadowProcess.reset()
    }

    private fun syntheticApp(uid: Int) = AppInfo(null).apply {
        this.uid = uid; packageName = TEST_PACKAGE; appName = if (uid == TEST_UID) TEST_NAME else "Synthetic UID $uid"
        isSystemApp = uid == 1000; uploadBytes = 1200; downloadBytes = 3400
    }
    private fun page(uid: Int, ip: String, name: String, flag: String = "🇺🇸"): PagingSource<Int, AppConnection> =
        SyntheticConnectionPage(if (emptyLogs) emptyList() else listOf(AppConnection(uid, ip, 443, 3, flag, false, name)))
    private fun emptyPage(): PagingSource<Int, AppConnection> = SyntheticConnectionPage(emptyList())
    private class SyntheticConnectionPage(private val rows: List<AppConnection>) : PagingSource<Int, AppConnection>() {
        override val jumpingSupported = true
        override fun getRefreshKey(state: PagingState<Int, AppConnection>): Int? = null
        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, AppConnection> = LoadResult.Page(rows, null, null, 0, 0)
    }
    private fun launch(uid: Int? = TEST_UID, emptyExtras: Boolean = false): AppInfoActivity {
        val intent = Intent(RuntimeEnvironment.getApplication(), AppInfoActivity::class.java).apply {
            uid?.let { putExtra(AppInfoActivity.INTENT_UID, it) }
            if (emptyExtras) putExtras(android.os.Bundle())
        }
        controller = Robolectric.buildActivity(AppInfoActivity::class.java, intent)
        controllers.add(controller)
        controller.create().start().resume().visible()
        compose.waitForIdle()
        return controller.get()
    }
    private fun host() = controller.get()
    private fun state(activity: AppInfoActivity = host()): AppDetailsState = AppInfoActivity::class.java.getDeclaredField("b").let {
        it.isAccessible = true; it.get(activity) as AppDetailsState
    }
    private fun awaitMain(condition: () -> Boolean) {
        // waitUntil advances Compose frames, but does not drain Android's paused main looper.
        // Real Activity IO -> Main callbacks must run before inspecting their recorded results.
        compose.waitUntil(5000) { compose.runOnIdle(condition) }
    }
    private fun ready(uid: Int = TEST_UID) {
        awaitMain { state().aadAppDetailName.text.toString() == requireNotNull(apps[uid]).appName }
        compose.onAllNodesWithText(requireNotNull(apps[uid]).appName, useUnmergedTree = true).onLast().assertExists()
        assertFalse(host().isFinishing)
    }
    private fun dialogReady() {
        awaitMain { ShadowDialog.getLatestDialog()?.isShowing == true }
        compose.onNodeWithText(host().getString(R.string.ada_noapp_dialog_title)).assertIsDisplayed()
        assertFalse(shadowOf(requireNotNull(ShadowDialog.getLatestDialog())).isCancelable)
    }
    private fun noApp(uid: Int? = null, emptyExtras: Boolean = false) {
        launch(uid, emptyExtras); dialogReady()
        compose.onNodeWithText(host().getString(R.string.ada_noapp_dialog_positive)).performClick()
        assertTrue(host().isFinishing)
        assertTrue(firewallCommands.isEmpty())
    }
    private fun showAllSections() {
        listOf(R.string.top_active_conns, R.string.most_contacted_asn, R.string.ssv_most_contacted_domain_heading,
            R.string.ssv_most_contacted_ips_heading, R.string.ssv_most_contacted_countries_heading).forEach {
            compose.waitUntil(5000) { compose.onAllNodesWithText(host().getString(it)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(host().getString(it)).performScrollTo().assertIsDisplayed()
        }
    }
    private fun switchProxy() = compose.onNode(hasText(host().getString(R.string.exclude_apps_from_proxy)) and hasClickAction())
    private fun destroyCurrent() { controller.pause().stop().destroy(); assertTrue(host().isDestroyed) }
    private fun assertNoObserverLeaks() {
        assertFalse(ipCount.hasObservers()); assertFalse(domainCount.hasObservers())
        assertTrue(state().insightsSnapshots.isEmpty())
    }

    @Test fun testLaunchesSuccessfullyWithValidUid() {
        launch(); ready(); compose.onNodeWithTag("app_details").assertIsDisplayed()
        verify(exactly = 1) { ipDao.getAppWiseIpRulesCount(TEST_UID) }
        verify(exactly = 1) { domainDao.getAppWiseDomainRulesCount(TEST_UID) }
    }
    @Test fun testLaunchWithInvalidUid() { noApp(INVALID_UID) }
    @Test fun testUiElementsAreDisplayedWithValidData() {
        launch(); ready()
        compose.onNodeWithText(host().getString(R.string.app_id_package, TEST_UID, TEST_PACKAGE), useUnmergedTree = true).assertIsDisplayed()
        assertNotNull(state().aadAppDetailIcon.drawable)
        compose.onNodeWithContentDescription(host().getString(R.string.about_settings_app_info)).assertIsDisplayed()
        (0..5).forEach { compose.onNodeWithTag("app_rule_$it").performScrollTo().assertIsDisplayed() }
        compose.onNodeWithText(host().getString(R.string.lbl_ip_rules)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(host().getString(R.string.lbl_domain_rules)).performScrollTo().assertIsDisplayed()
        switchProxy().performScrollTo().assertIsOff()
        assertEquals("3", state().aadIpBlockHeader.text.toString()); assertEquals("5", state().aadDomainBlockHeader.text.toString())
    }
    @Test fun testUiElementsContentAndInteractions() {
        launch(); ready(); switchProxy().performScrollTo().performClick(); switchProxy().assertIsOn()
        verify(timeout = 2000, exactly = 1) { FirewallManager.updateIsProxyExcluded(TEST_UID, true) }
        compose.onNodeWithContentDescription(host().getString(R.string.about_settings_app_info)).performScrollTo().performClick()
        awaitMain { androidInfoPackages.size == 1 }
        assertEquals(listOf(TEST_PACKAGE), androidInfoPackages.toList())
        verify(exactly = 1) { UIUtils.openAndroidAppInfo(any(), TEST_PACKAGE) }
        showAllSections()
        compose.onNodeWithText(host().getString(R.string.close_all)).performScrollTo().performClick()
        compose.onNodeWithText(host().getString(R.string.close_conns_dialog_title)).assertIsDisplayed()
        compose.onNodeWithText(host().getString(R.string.lbl_cancel)).performClick()
        verify(exactly = 0) { VpnController.closeConnectionsIfNeeded(any(), any()) }
    }
    @Test fun testAppSettingsToggleStates() {
        val expected = listOf(
            FirewallManager.FirewallStatus.NONE to FirewallManager.ConnectionStatus.UNMETERED,
            FirewallManager.FirewallStatus.NONE to FirewallManager.ConnectionStatus.METERED,
            FirewallManager.FirewallStatus.ISOLATE to FirewallManager.ConnectionStatus.ALLOW,
            FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL to FirewallManager.ConnectionStatus.ALLOW,
            FirewallManager.FirewallStatus.BYPASS_UNIVERSAL to FirewallManager.ConnectionStatus.ALLOW,
            FirewallManager.FirewallStatus.EXCLUDE to FirewallManager.ConnectionStatus.ALLOW)
        expected.forEachIndexed { index, (firewall, connection) ->
            launch(); ready(); compose.onNodeWithTag("app_rule_$index").performScrollTo().assertIsEnabled().performClick()
            awaitMain { firewallCommands.size == index + 1 }
            assertEquals(Triple(TEST_UID, firewall, connection), firewallCommands[index])
            destroyCurrent(); assertNoObserverLeaks()
        }
    }
    @Test fun testRecyclerViewsAreInitialized() {
        // The original list obligation is now real Paging presenters and native populated sections.
        launch(); ready(); showAllSections()
        listOf(state().aadActiveConnsRv, state().aadAsnRv, state().aadMostContactedDomainRv, state().aadMostContactedIpsRv).forEach {
            assertEquals(1, requireNotNull(it.adapter).itemCount)
        }
        compose.onNode(hasText(DOMAIN, substring = true)).performScrollTo().assertIsDisplayed()
        verify { statsDao.getMostDomainsByUid(TEST_UID, any()) }
        verify { connectionDao.getAppIpLogsLimited(TEST_UID, any()) }
    }
    @Test fun testActivityHandlesConfigurationChanges() {
        launch(); ready(); controller.recreate(); ready()
        compose.onNodeWithTag("app_rule_0").assertExists()
        assertEquals(TEST_UID, host().intent.getIntExtra(AppInfoActivity.INTENT_UID, INVALID_UID))
        assertTrue(firewallCommands.isEmpty())
    }
    @Test fun testLaunchWithMissingUidExtra() { noApp() }
    @Test fun testLaunchWithNegativeUid() { noApp(-1) }
    @Test fun testLaunchWithZeroUid() { launch(0); ready(0); verify(exactly = 1) { ipDao.getAppWiseIpRulesCount(0) } }
    @Test fun testLaunchWithVeryLargeUid() { launch(Int.MAX_VALUE); ready(Int.MAX_VALUE); verify(exactly = 1) { domainDao.getAppWiseDomainRulesCount(Int.MAX_VALUE) } }
    @Test fun testLaunchWithSystemAppUid() { launch(1000); ready(1000); compose.onNodeWithTag("app_rule_0").assertIsEnabled() }
    @Test fun testActivityStateWithMultipleIntents() {
        launch(); ready()
        val replacement = Intent(host(), AppInfoActivity::class.java).putExtra(AppInfoActivity.INTENT_UID, INVALID_UID)
        compose.runOnIdle { host().intent = replacement }
        // Baseline only called Activity.setIntent: it did not deliver onNewIntent or recreate.
        // AppInfo reads UID in onCreate, so the existing mounted content must remain unchanged.
        assertSame(replacement, host().intent)
        assertEquals(INVALID_UID, host().intent.getIntExtra(AppInfoActivity.INTENT_UID, TEST_UID))
        compose.onNodeWithText(TEST_NAME, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(host().getString(R.string.app_id_package, TEST_UID, TEST_PACKAGE), useUnmergedTree = true).assertIsDisplayed()
        assertFalse(host().isFinishing)
        assertEquals(Lifecycle.State.RESUMED, host().lifecycle.currentState)
        // Robolectric 4.17 recreates from ComponentController's original launch Intent, not the
        // prior Activity's replacement. Verify the real recreated host with that exact UID.
        assertEquals(TEST_UID, controller.intent.getIntExtra(AppInfoActivity.INTENT_UID, INVALID_UID))
        controller.recreate(); ready()
        assertEquals(TEST_UID, host().intent.getIntExtra(AppInfoActivity.INTENT_UID, INVALID_UID))
        compose.onNodeWithText(host().getString(R.string.app_id_package, TEST_UID, TEST_PACKAGE), useUnmergedTree = true).assertIsDisplayed()
        assertNull(ShadowDialog.getShownDialogs().firstOrNull { it.isShowing })
        coVerify(exactly = 0) { FirewallManager.getAppInfoByUid(INVALID_UID) }
        assertTrue(firewallCommands.isEmpty())
    }
    @Test fun testActivityLifecycleStates() {
        launch(); ready(); controller.pause(); assertEquals(Lifecycle.State.STARTED, host().lifecycle.currentState)
        controller.resume(); assertEquals(Lifecycle.State.RESUMED, host().lifecycle.currentState)
        controller.pause(); assertEquals(Lifecycle.State.STARTED, host().lifecycle.currentState)
        controller.resume(); compose.onNodeWithText(TEST_NAME, useUnmergedTree = true).assertExists()
    }
    @Test fun testMemoryPressureHandling() {
        launch(); ready(); host().onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertFalse(host().isFinishing); compose.onNodeWithText(TEST_NAME, useUnmergedTree = true).assertExists()
    }
    @Test fun testActivityWithEmptyIntentExtras() { noApp(emptyExtras = true) }
    @Test fun testActivityErrorRecovery() {
        missing = true; noApp(TEST_UID); destroyCurrent()
        missing = false; launch(); ready(); assertNull(ShadowDialog.getShownDialogs().firstOrNull { it.isShowing })
    }

    // These execute the old workloads deterministically. They DO NOT certify device timings or
    // heap limits; the seven original numerical acceptance gates are retained in the linked doc.
    @Test fun testActivityLaunchPerformance() { launch(); ready(); assertEquals(Lifecycle.State.RESUMED, host().lifecycle.currentState) }
    @Test fun testUiRenderingPerformance() { launch(); ready(); showAllSections(); assertNotNull(state().aadAppDetailIcon.drawable) }
    @Test fun testMultipleActivityInstancesPerformance() {
        val owners = (1..3).map { launch(); ready(); host() }
        assertEquals(3, owners.toSet().size)
        owners.forEach { assertFalse(it.isDestroyed); assertEquals(TEST_NAME, state(it).aadAppDetailName.text.toString()) }
        controllers.asReversed().forEach { it.pause().stop().destroy() }
        assertFalse(ipCount.hasObservers()); assertFalse(domainCount.hasObservers())
    }
    @Test fun testConfigurationChangePerformance() {
        launch(); ready(); repeat(3) { controller.recreate(); ready() }
        assertTrue(firewallCommands.isEmpty())
        destroyCurrent(); assertNoObserverLeaks()
    }
    @Test fun testMemoryUsageStability() {
        repeat(3) { launch(); ready(); showAllSections(); destroyCurrent(); assertNoObserverLeaks() }
        assertTrue(firewallCommands.isEmpty())
    }
    @Test fun testScrollingPerformance() {
        launch(); ready(); repeat(3) {
            showAllSections(); compose.onNodeWithText(TEST_NAME, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        }
    }
    @Test fun testInteractionResponseTimes() {
        launch(); ready(); repeat(4) { switchProxy().performScrollTo().performClick() }
        switchProxy().assertIsOff()
        verify(timeout = 2000, exactly = 2) { FirewallManager.updateIsProxyExcluded(TEST_UID, true) }
        verify(timeout = 2000, exactly = 2) { FirewallManager.updateIsProxyExcluded(TEST_UID, false) }
    }

    @Test fun ownUid_warningDefersQueries_andBackFinishesWithoutCommands() {
        launch(Process.myUid())
        compose.onNodeWithTag("app_warning_proceed").assertIsDisplayed()
        compose.onNodeWithTag("app_rule_0").assertDoesNotExist()
        verify(exactly = 0) { ipDao.getAppWiseIpRulesCount(any()) }
        verify(exactly = 0) { domainDao.getAppWiseDomainRulesCount(any()) }
        host().onBackPressedDispatcher.onBackPressed()
        assertTrue(host().isFinishing); assertTrue(firewallCommands.isEmpty())
    }

    @Test fun unknownUid_excludeRuleIsDisabled_withoutIssuingCommands() {
        unknownPackage = true; launch(); ready()
        compose.onNodeWithTag("app_rule_5").performScrollTo().assertIsNotEnabled()
        assertTrue(firewallCommands.isEmpty())
    }
    @Test fun emptySyntheticLogs_hideSections_andViewModeToggle() {
        emptyLogs = true; launch(); ready()
        awaitMain { state().aadMostContactedIpsRv.adapter != null }
        compose.onNodeWithText(host().getString(R.string.top_active_conns)).assertDoesNotExist()
        compose.onNodeWithContentDescription(host().getString(R.string.cd_stats_switch_to_insights)).assertDoesNotExist()
    }
    @Test fun ruleCounts_reemitThroughActualLifecycleObservers() {
        launch(); ready(); compose.runOnIdle { ipCount.value = 8; domainCount.value = 13 }
        compose.onNodeWithText("8", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("13", useUnmergedTree = true).assertExists()
        destroyCurrent(); assertNoObserverLeaks()
    }
    @Test fun customRulesNavigation_preservesTabAndUidWithoutLaunchingTarget() {
        launch(); ready()
        compose.onNodeWithText(host().getString(R.string.lbl_ip_rules)).performScrollTo().performClick()
        val ip = requireNotNull(shadowOf(host()).nextStartedActivity)
        assertEquals(CustomRulesActivity::class.java.name, ip.component?.className)
        assertEquals(TEST_UID, ip.getIntExtra(Constants.INTENT_UID, INVALID_UID))
        assertEquals(CustomRulesActivity.Tabs.IP_RULES.screen, ip.getIntExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, -1))
        compose.onNodeWithText(host().getString(R.string.lbl_domain_rules)).performScrollTo().performClick()
        val domain = requireNotNull(shadowOf(host()).nextStartedActivity)
        assertEquals(CustomRulesActivity.Tabs.DOMAIN_RULES.screen, domain.getIntExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, -1))
        assertEquals(TEST_UID, domain.getIntExtra(Constants.INTENT_UID, INVALID_UID))
    }
}
