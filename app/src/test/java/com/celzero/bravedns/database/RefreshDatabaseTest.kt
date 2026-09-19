package com.celzero.bravedns.database

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.wireguard.WgHopManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.shadows.ShadowBackend
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowBackend::class])
class RefreshDatabaseTest {

    private lateinit var refreshDatabase: RefreshDatabase
    private val context = mockk<Context>(relaxed = true)
    private val connTrackerRepository = mockk<ConnectionTrackerRepository>(relaxed = true)
    private val dnsLogRepository = mockk<DnsLogRepository>(relaxed = true)
    private val rethinkLogRepository = mockk<RethinkLogRepository>(relaxed = true)
    private val persistentState = mockk<PersistentState>(relaxed = true)
    private val eventLogger = mockk<EventLogger>(relaxed = true)
    private val packageManager = mockk<PackageManager>(relaxed = true)

    @Before
    fun setup() {
        try {
            org.koin.core.context.stopKoin()
        } catch (_: Exception) {
        }
        org.koin.core.context.startKoin {
            modules(
                org.koin.dsl.module {
                    single { context }
                    single { mockk<CustomIpRepository>(relaxed = true) }
                    // ProxyManager.db resolves this lazily on first access
                    single { mockk<ProxyAppMappingRepository>(relaxed = true) }
                }
            )
        }
        mockkObject(IpRulesManager)
        mockkObject(FirewallManager)
        mockkObject(ProxyManager)
        mockkObject(DomainRulesManager)
        mockkObject(WireguardManager)
        mockkObject(WgHopManager)
        mockkObject(RpnProxyManager)
        mockkObject(Utilities)
        every { context.packageManager } returns packageManager
        // RefreshDatabase posts notifications for batch new-app detection; the
        // relaxed Context mock would fail the cast to NotificationManager
        every {
            context.getSystemService(Context.NOTIFICATION_SERVICE)
        } returns mockk<android.app.NotificationManager>(relaxed = true)
        // Domain rules go through Koin-injected repositories not present here
        coEvery { DomainRulesManager.load() } returns 0L
        coEvery { DomainRulesManager.tombstoneRulesByUid(any()) } just Runs
        coEvery { DomainRulesManager.deleteRulesByUid(any()) } just Runs
        coEvery { DomainRulesManager.updateUids(any(), any()) } just Runs
        // RefreshDatabase.process() reloads every manager; stub the loads so the
        // objects' original Koin-backed implementations never run
        coEvery { FirewallManager.load() } returns 0
        coEvery { IpRulesManager.load() } returns 0
        coEvery { ProxyManager.load() } returns 0
        coEvery { WireguardManager.load(any()) } returns 0
        coEvery { WgHopManager.load(any()) } returns 0
        coEvery { RpnProxyManager.load() } returns 0
        refreshDatabase = RefreshDatabase(
            context,
            connTrackerRepository,
            dnsLogRepository,
            rethinkLogRepository,
            persistentState,
            eventLogger
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
        org.koin.core.context.stopKoin()
    }

    @Test
    fun `process ACTION_INSERT_NEW_APP should insert app into database when detected`() = runTest {
        val uid = 10123
        val packageName = "com.example.app"
        val applicationInfo = ApplicationInfo().apply {
            this.uid = uid
            this.packageName = packageName
        }

        coEvery { Utilities.isMissingOrInvalidUid(uid) } returns false
        coEvery { FirewallManager.hasUid(uid) } returns false
        coEvery { FirewallManager.isTombstone(packageName) } returns false
        every { packageManager.getPackagesForUid(uid) } returns arrayOf(packageName)
        every { packageManager.getApplicationInfo(packageName, any<Int>()) } returns applicationInfo
        every { packageManager.getApplicationLabel(any()) } returns "Example App"
        every { Utilities.getApplicationInfo(any(), any()) } returns applicationInfo
        
        coEvery { FirewallManager.persistAppInfo(any()) } just Runs
        coEvery { ProxyManager.addNewApp(any()) } just Runs
        // Non-empty so the finally-block's "empty firewall rules" notification
        // (which builds a real Notification, unsupported here) is skipped
        coEvery { FirewallManager.getAllApps() } returns
            setOf(FirewallManager.AppInfoTuple(uid, packageName))

        val action = RefreshDatabase.Action(RefreshDatabase.ACTION_INSERT_NEW_APP, uid)
        refreshDatabase.process(action)
        
        coVerify { FirewallManager.persistAppInfo(match { it.packageName == packageName && it.uid == uid }) }
        coVerify { ProxyManager.addNewApp(any()) }
    }

    @Test
    fun `process ACTION_REFRESH_AUTO should tombstone uninstalled apps when tombstone is enabled`() = runTest {
        val trackedUid = 10555
        val trackedPackage = "com.uninstalled.app"
        val trackedApps = setOf(FirewallManager.AppInfoTuple(trackedUid, trackedPackage))
        
        every { persistentState.tombstoneApps } returns true
        coEvery { FirewallManager.getAllApps() } returns trackedApps
        // Simulate no apps installed via Package Manager
        every { packageManager.getInstalledPackages(any<Int>()) } returns emptyList()

        // The tombstone path skips apps without a tracked AppInfo row
        val trackedAppInfo = mockk<AppInfo>(relaxed = true)
        every { trackedAppInfo.tombstoneTs } returns 0L
        every { trackedAppInfo.uid } returns trackedUid
        coEvery { FirewallManager.getAppInfoByPackage(trackedPackage) } returns trackedAppInfo

        coEvery { FirewallManager.tombstoneApp(any(), any(), any()) } just Runs
        coEvery { IpRulesManager.tombstoneRulesByUid(any()) } just Runs
        coEvery { ProxyManager.tombstoneApp(any()) } just Runs
        
        val action = RefreshDatabase.Action(RefreshDatabase.ACTION_REFRESH_AUTO)
        refreshDatabase.process(action)
        
        coVerify { FirewallManager.tombstoneApp(trackedUid, trackedPackage, any()) }
    }
}
