package com.celzero.bravedns.database

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
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
                }
            )
        }
        mockkObject(IpRulesManager)
        mockkObject(FirewallManager)
        mockkObject(ProxyManager)
        mockkObject(Utilities)
        every { context.packageManager } returns packageManager
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
        unmockkObject(IpRulesManager)
        unmockkObject(FirewallManager)
        unmockkObject(ProxyManager)
        unmockkObject(Utilities)
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
        
        coEvery { FirewallManager.tombstoneApp(any(), any(), any()) } just Runs
        
        val action = RefreshDatabase.Action(RefreshDatabase.ACTION_REFRESH_AUTO)
        refreshDatabase.process(action)
        
        coVerify { FirewallManager.tombstoneApp(trackedUid, trackedPackage, any()) }
    }
}
