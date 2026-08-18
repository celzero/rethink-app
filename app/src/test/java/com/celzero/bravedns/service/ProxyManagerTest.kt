/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.service

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.ProxyAppMappingRepository
import com.celzero.bravedns.database.ProxyApplicationMapping
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.ProxyManagerTest.Companion.mockDb
import com.celzero.bravedns.service.ProxyManagerTest.Companion.setUpClass
import com.celzero.bravedns.service.ProxyManagerTest.Companion.tearDownClass
import com.celzero.firestack.backend.Backend
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.unmockkObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Comprehensive unit tests for [ProxyManager].
 *
 * ### Key invariants under test
 * - Multi-proxy model: a single app can have multiple [ProxyApplicationMapping] rows, one per
 *   proxyId. The composite PK is (uid, packageName, proxyId).
 * - A "base" entry with proxyId="" exists for every tracked app so it appears in "All Apps"
 *   pager queries even when not assigned to any specific proxy.
 * - WireGuard proxyIds use prefix "wg" (e.g. "wg0", "wg1").
 * - RPN WIN proxyIds use prefix Backend.RpnWin (e.g. "wgyrpnsrv-us-ny-abc123").
 * - AUTO server (key="AUTO") must never be used as a per-app proxy ID.
 * - Deleting a WG config removes only the WG proxyId rows, leaving RPN rows intact.
 * - Stale RPN servers clean up ProxyApplicationMapping rows via removeProxyId.
 *
 * ### Documented anomalies (each has a dedicated test)
 * 1. [ProxyApplicationMapping.equals]/[hashCode] ignore proxyId -- a plain
 *    Set<ProxyApplicationMapping> silently de-duplicates multi-proxy rows for the same app.
 * 2. [ProxyManager.addNewApp] swaps proxyId and proxyName in the PAM entity constructor.
 *    The in-memory pamSet is correct; the persisted DB entity is not.
 * 3. [ProxyManager.isRpnProxy] uses endsWith(Backend.RPN) which does NOT match WIN proxyIds.
 *    WIN-proxied connections appear as non-RPN in connection-log adapters.
 * 4. [ProxyManager.isAnyUserSetProxy] matches WIN proxyIds via startsWith("wg") -- correct
 *    result but for the wrong structural reason (WG prefix instead of RPN prefix).
 * 5. Bare Backend.RpnWin passes isValidProxyPrefix and would be stored in PAM. The Go engine
 *    treats bare Backend.RpnWin as the AUTO sentinel, not a specific server.
 *
 * ### Koin / singleton injection note
 * ProxyManager is a Kotlin `object` whose `db` property is a Koin lazy delegate
 * (`by inject()`). Once the lazy is first evaluated it is cached for the JVM lifetime of the
 * singleton. To keep all tests targeting the same mock instance, Koin is started once in
 * [setUpClass] (and stopped once in [tearDownClass]). Per-test setUp only resets mock state.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProxyManagerTest : KoinTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    // ---- Proxy ID constants ------------------------------------------------
    private val wgProxyId0   = ProxyManager.ID_WG_BASE + "0"
    private val wgProxyId1   = ProxyManager.ID_WG_BASE + "1"
    private val rpnServerKey = "srv-us-ny-abc123"
    private val rpnProxyId   = Backend.RpnWin + rpnServerKey
    private val rpnServerKey2 = "srv-de-fra-xyz789"
    private val rpnProxyId2  = Backend.RpnWin + rpnServerKey2
    private val autoProxyId  = Backend.RpnWin + RpnProxyManager.AUTO_SERVER_ID
    private val orbotProxyId = ProxyManager.ID_ORBOT_BASE + ":5555"
    private val tcpProxyId   = ProxyManager.ID_TCP_BASE
    private val s5ProxyId    = ProxyManager.ID_S5_BASE + ":1080"
    private val httpProxyId  = ProxyManager.ID_HTTP_BASE + ":8080"

    // ---- Test app data -----------------------------------------------------
    private val uid1  = 10001; private val pkg1  = "com.test.app1"; private val name1 = "App1"
    private val uid2  = 10002; private val pkg2  = "com.test.app2"; private val name2 = "App2"
    private val uid1b = 10201  // work-profile copy (same pkg1, different uid)

    private lateinit var appInfo1: AppInfo
    private lateinit var appInfo2: AppInfo

    companion object {
        /**
         * Single shared mock for the whole test class.
         *
         * ProxyManager.db is a Koin lazy (`by inject()`) that is cached after its first
         * resolution. Keeping Koin alive (and using the same mock instance) across all tests
         * guarantees that every call to ProxyManager.db.insert/delete/etc. goes to THIS mock
         * and can be verified per-test via clearMocks + re-stubbing.
         */
        private val mockDb: ProxyAppMappingRepository = mockk(relaxed = true)

        @BeforeClass
        @JvmStatic
        fun setUpClass() {
            // Start Koin once so that the first test to access ProxyManager.db resolves
            // to mockDb and all subsequent tests continue to use the same cached instance.
            try { stopKoin() } catch (_: Exception) {}
            startKoin {
                modules(module { single<ProxyAppMappingRepository> { mockDb } })
            }
        }

        @AfterClass
        @JvmStatic
        fun tearDownClass() {
            stopKoin()
            unmockkAll()
        }
    }

    // ========================================================================
    // setUp / tearDown (per-test)
    // ========================================================================

    @Before
    fun setUp() {
        // Reset recorded calls (keep relaxed default answers), then re-stub specifics.
        clearMocks(mockDb, answers = false, recordedCalls = true, childMocks = false)
        coEvery { mockDb.getApps() } returns emptyList()
        coEvery { mockDb.insert(any()) } returns 1L
        coJustRun { mockDb.deleteMapping(any(), any(), any()) }
        coJustRun { mockDb.deleteApp(any(), any()) }
        coJustRun { mockDb.deleteAppByPkgName(any()) }
        coJustRun { mockDb.updateUidForApp(any(), any(), any()) }
        coJustRun { mockDb.tombstoneApp(any(), any()) }
        coJustRun { mockDb.deleteAll() }
        coJustRun { mockDb.updateProxyNameForProxyId(any(), any()) }

        // FirewallManager is a Kotlin object -- mockkObject is idempotent.
        mockkObject(FirewallManager)
        coEvery { FirewallManager.getAppInfoByPackage(pkg1)  } returns buildAppInfo(uid1,  pkg1,  name1)
        coEvery { FirewallManager.getAppInfoByPackage(pkg2)  } returns buildAppInfo(uid2,  pkg2,  name2)
        coEvery { FirewallManager.getAppInfoByPackage(null)  } returns null
        coEvery { FirewallManager.getAppInfoByPackage("")    } returns null
        // bulk ops (setProxyIdForAllApps / setProxyIdForUnselectedApps) and updateApp now consult
        // FirewallManager as the source of truth; default to empty/null, individual tests override.
        coEvery { FirewallManager.getAllApps() } returns emptySet()
        coEvery { FirewallManager.getAppInfoByUidAndPackage(any(), any()) } returns null

        appInfo1 = buildAppInfo(uid1, pkg1, name1)
        appInfo2 = buildAppInfo(uid2, pkg2, name2)
        resetProxyManagerState()
    }

    @After
    fun tearDown() {
        resetProxyManagerState()
        clearMocks(mockDb, answers = false, recordedCalls = true, childMocks = false)
        unmockkObject(FirewallManager)
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun buildAppInfo(uid: Int, pkg: String, name: String) = AppInfo(
        packageName = pkg, appName = name, uid = uid, isSystemApp = false,
        firewallStatus = FirewallManager.FirewallStatus.NONE.id, appCategory = "Test",
        wifiDataUsed = 0L, mobileDataUsed = 0L,
        connectionStatus = FirewallManager.ConnectionStatus.ALLOW.id,
        isProxyExcluded = false, screenOffAllowed = true, backgroundAllowed = true, tombstoneTs = 0L
    )

    /** Convenience factory for ProxyApplicationMapping test rows. */
    private fun pam(uid: Int, pkg: String, proxyId: String, proxyName: String = "") =
        ProxyApplicationMapping(uid, pkg, "", proxyName, true, proxyId)

    /**
     * Stub FirewallManager (the source of truth for bulk proxy ops) to expose the given apps.
     * setProxyIdForAllApps / setProxyIdForUnselectedApps / updateApp iterate FirewallManager, so
     * tests exercising them must declare which apps FirewallManager knows about.
     */
    private fun stubFwApps(vararg apps: FirewallManager.AppInfoTuple) {
        coEvery { FirewallManager.getAllApps() } returns apps.toSet()
        apps.forEach { t ->
            coEvery { FirewallManager.getAppInfoByUidAndPackage(t.uid, t.packageName) } returns buildAppInfo(t.uid, t.packageName, "name${t.uid}")
        }
    }

    /**
     * Reset ProxyManager's in-memory CopyOnWriteArraySet<ProxyAppMapTuple> to an empty state.
     * This gives each test a clean slate without touching the DB.
     */
    private fun resetProxyManagerState() {
        val field = ProxyManager::class.java.getDeclaredField("pamSet")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(ProxyManager) as CopyOnWriteArraySet<*>).clear()
    }

    /**
     * Populate pamSet directly (bypassing load()) and also stub [mockDb.getApps].
     * Direct pamSet population avoids any potential Koin-lazy timing issues and lets
     * tests start with a precise known state.
     */
    private fun loadMappings(vararg rows: ProxyApplicationMapping) {
        coEvery { mockDb.getApps() } returns rows.toList()
        // Also pre-populate pamSet directly so tests don't need ProxyManager.load().
        val field = ProxyManager::class.java.getDeclaredField("pamSet")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val pamSet = field.get(ProxyManager) as CopyOnWriteArraySet<ProxyManager.ProxyAppMapTuple>
        rows.forEach { row ->
            pamSet.add(ProxyManager.ProxyAppMapTuple(row.uid, row.packageName, row.proxyId))
        }
    }

    private fun pamSetSize(): Int {
        val field = ProxyManager::class.java.getDeclaredField("pamSet")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return (field.get(ProxyManager) as CopyOnWriteArraySet<*>).size
    }

    // ========================================================================
    // 1. addNewApp
    // ========================================================================

    @Test
    fun `addNewApp creates base entry with empty proxyId`() = runBlocking {
        ProxyManager.addNewApp(appInfo1)
        assertTrue(ProxyManager.trackedApps().any { it.uid == uid1 && it.packageName == pkg1 })
        coVerify(exactly = 1) { mockDb.insert(any()) }
    }

    @Test
    fun `addNewApp skips null AppInfo`() = runBlocking {
        ProxyManager.addNewApp(null)
        assertTrue(ProxyManager.trackedApps().isEmpty())
        coVerify(exactly = 0) { mockDb.insert(any()) }
    }

    @Test
    fun `addNewApp does not duplicate same uid-pkg-proxyId tuple`() = runBlocking {
        ProxyManager.addNewApp(appInfo1)
        ProxyManager.addNewApp(appInfo1)
        coVerify(exactly = 1) { mockDb.insert(any()) }
    }

    @Test
    fun `addNewApp allows base entry when app already has a WG-specific entry`() = runBlocking {
        ProxyManager.addNewApp(appInfo1, proxyId = wgProxyId0, proxyName = "T0")
        ProxyManager.addNewApp(appInfo1, proxyId = "",          proxyName = "")
        assertTrue(ProxyManager.getProxyIdsForApp(uid1).contains(wgProxyId0))
        assertTrue(ProxyManager.trackedApps().any { it.uid == uid1 })
        coVerify(exactly = 2) { mockDb.insert(any()) }
    }

    @Test
    fun `addNewApp allows base entry when app already has an RPN-specific entry`() = runBlocking {
        ProxyManager.addNewApp(appInfo1, proxyId = rpnProxyId, proxyName = rpnServerKey)
        ProxyManager.addNewApp(appInfo1, proxyId = "",          proxyName = "")
        assertTrue(ProxyManager.getProxyIdsForApp(uid1).contains(rpnProxyId))
        coVerify(exactly = 2) { mockDb.insert(any()) }
    }

    /**
     * addNewApp stores proxyId and proxyName in the correct PAM constructor slots
     * (uid, pkg, appName, proxyName, isActive, proxyId). Previously these two were swapped, which
     * stored proxyName in the proxyId column -- breaking the (proxyId = :proxyId OR proxyId = '')
     * pager-query match and risking PK collisions, making apps vanish from WgIncludeAppsAdapter.
     * The in-memory pamSet (ProxyAppMapTuple) was always correct.
     */
    @Test
    fun `addNewApp stores proxyId and proxyName in correct columns`() = runBlocking {
        val capturedPam = slot<ProxyApplicationMapping>()
        coEvery { mockDb.insert(capture(capturedPam)) } returns 1L

        ProxyManager.addNewApp(appInfo1, proxyId = wgProxyId0, proxyName = "Tunnel0")

        assertTrue("In-memory cache must be correct", ProxyManager.getProxyIdsForApp(uid1).contains(wgProxyId0))

        val stored = capturedPam.captured
        assertEquals("proxyId must be stored in pam.proxyId", wgProxyId0, stored.proxyId)
        assertEquals("proxyName must be stored in pam.proxyName", "Tunnel0", stored.proxyName)
    }

    @Test
    fun `addNewApp base entry swap is harmless (both fields empty)`() = runBlocking {
        val capturedPam = slot<ProxyApplicationMapping>()
        coEvery { mockDb.insert(capture(capturedPam)) } returns 1L
        ProxyManager.addNewApp(appInfo1)
        assertTrue(capturedPam.isCaptured)
        assertEquals("", capturedPam.captured.proxyId)
        assertEquals("", capturedPam.captured.proxyName)
    }

    // ========================================================================
    // 2. addProxyToApp
    // ========================================================================

    @Test
    fun `addProxyToApp accepts valid WG proxyId and persists`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""))
        ProxyManager.addProxyToApp(uid1, pkg1, wgProxyId0, "Tunnel0")
        assertTrue(ProxyManager.getProxyIdsForApp(uid1).contains(wgProxyId0))
        coVerify(exactly = 1) { mockDb.insert(match { it.proxyId == wgProxyId0 }) }
    }

    @Test
    fun `addProxyToApp accepts valid RPN proxyId`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""))
        ProxyManager.addProxyToApp(uid1, pkg1, rpnProxyId, rpnServerKey)
        assertTrue(ProxyManager.getProxyIdsForApp(uid1).contains(rpnProxyId))
        coVerify(exactly = 1) { mockDb.insert(match { it.proxyId == rpnProxyId && it.proxyName == rpnServerKey }) }
    }

    @Test
    fun `addProxyToApp stores proxyId and proxyName correctly (no swap unlike addNewApp)`() = runBlocking {
        val capturedPam = slot<ProxyApplicationMapping>()
        coEvery { mockDb.insert(capture(capturedPam)) } returns 1L
        loadMappings(pam(uid1, pkg1, ""))
        ProxyManager.addProxyToApp(uid1, pkg1, wgProxyId0, "Tunnel0")
        assertTrue(capturedPam.isCaptured)
        assertEquals(wgProxyId0, capturedPam.captured.proxyId)
        assertEquals("Tunnel0",  capturedPam.captured.proxyName)
    }

    @Test
    fun `addProxyToApp accepts all supported proxy type prefixes`() = runBlocking {
        val validIds = listOf(wgProxyId0, rpnProxyId, orbotProxyId, tcpProxyId, s5ProxyId, httpProxyId)
        validIds.forEach { id ->
            resetProxyManagerState()
            loadMappings(pam(uid1, pkg1, ""))
            ProxyManager.addProxyToApp(uid1, pkg1, id, "name")
            assertTrue("$id should be accepted", ProxyManager.getProxyIdsForApp(uid1).contains(id))
        }
    }

    @Test
    fun `addProxyToApp rejects empty proxyId`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""))
        ProxyManager.addProxyToApp(uid1, pkg1, "", "")
        coVerify(exactly = 0) { mockDb.insert(any()) }
    }

    @Test
    fun `addProxyToApp rejects SYSTEM proxyId`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""))
        ProxyManager.addProxyToApp(uid1, pkg1, ProxyManager.ID_NONE, "system")
        coVerify(exactly = 0) { mockDb.insert(any()) }
    }

    @Test
    fun `addProxyToApp rejects unknown prefix`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""))
        ProxyManager.addProxyToApp(uid1, pkg1, "UNKNOWN:1234", "bad")
        coVerify(exactly = 0) { mockDb.insert(any()) }
        assertTrue(ProxyManager.getProxyIdsForApp(uid1).isEmpty())
    }

    @Test
    fun `addProxyToApp is idempotent for same uid-pkg-proxyId`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""))
        ProxyManager.addProxyToApp(uid1, pkg1, wgProxyId0, "T0")
        ProxyManager.addProxyToApp(uid1, pkg1, wgProxyId0, "T0")
        coVerify(exactly = 1) { mockDb.insert(match { it.proxyId == wgProxyId0 }) }
    }

    /**
     * ANOMALY: bare Backend.RpnWin ("wgyrpn") passes isValidProxyPrefix because it starts with
     * itself. The Go engine treats bare Backend.RpnWin as the AUTO sentinel, so any app
     * assigned to it gets AUTO routing rather than a specific server.
     */
    @Test
    fun `ANOMALY bare Backend_RpnWin passes isValidProxyPrefix and gets stored`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""))
        ProxyManager.addProxyToApp(uid1, pkg1, Backend.RpnWin, "auto")
        assertTrue(
            "ANOMALY: bare '${Backend.RpnWin}' is accepted but Go engine treats it as AUTO",
            ProxyManager.getProxyIdsForApp(uid1).contains(Backend.RpnWin)
        )
    }

    // ========================================================================
    // 3. Multi-proxy model
    // ========================================================================

    @Test
    fun `app can belong to multiple WG tunnels simultaneously`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""))
        ProxyManager.addProxyToApp(uid1, pkg1, wgProxyId0, "T0")
        ProxyManager.addProxyToApp(uid1, pkg1, wgProxyId1, "T1")
        assertTrue(ProxyManager.getProxyIdsForApp(uid1).containsAll(listOf(wgProxyId0, wgProxyId1)))
    }

    @Test
    fun `app can belong to both WG and RPN simultaneously`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""))
        ProxyManager.addProxyToApp(uid1, pkg1, wgProxyId0, "T0")
        ProxyManager.addProxyToApp(uid1, pkg1, rpnProxyId, rpnServerKey)
        assertTrue(ProxyManager.getProxyIdsForApp(uid1).containsAll(listOf(wgProxyId0, rpnProxyId)))
    }

    @Test
    fun `app can belong to two different RPN servers simultaneously`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""))
        ProxyManager.addProxyToApp(uid1, pkg1, rpnProxyId,  rpnServerKey)
        ProxyManager.addProxyToApp(uid1, pkg1, rpnProxyId2, rpnServerKey2)
        assertTrue(ProxyManager.getProxyIdsForApp(uid1).containsAll(listOf(rpnProxyId, rpnProxyId2)))
    }

    @Test
    fun `trackedApps returns unique uid-pkg pairs ignoring proxyId multiplicity`() = runBlocking {
        loadMappings(
            pam(uid1, pkg1, ""), pam(uid1, pkg1, wgProxyId0), pam(uid1, pkg1, rpnProxyId),
            pam(uid2, pkg2, ""),
        )
        val tracked = ProxyManager.trackedApps()
        assertEquals(2, tracked.size)
        assertTrue(tracked.any { it.uid == uid1 && it.packageName == pkg1 })
        assertTrue(tracked.any { it.uid == uid2 && it.packageName == pkg2 })
    }

    // ========================================================================
    // 4. removeProxyFromApp
    // ========================================================================

    @Test
    fun `removeProxyFromApp removes only the targeted proxyId row`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""), pam(uid1, pkg1, wgProxyId0), pam(uid1, pkg1, rpnProxyId))
        ProxyManager.removeProxyFromApp(uid1, pkg1, wgProxyId0)
        assertFalse(ProxyManager.getProxyIdsForApp(uid1).contains(wgProxyId0))
        assertTrue(ProxyManager.getProxyIdsForApp(uid1).contains(rpnProxyId))
        coVerify(exactly = 1) { mockDb.deleteMapping(uid1, pkg1, wgProxyId0) }
        coVerify(exactly = 0) { mockDb.deleteMapping(uid1, pkg1, rpnProxyId) }
    }

    @Test
    fun `removeProxyFromApp for non-existent entry is no-op`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""))
        ProxyManager.removeProxyFromApp(uid1, pkg1, wgProxyId0)
        coVerify(exactly = 0) { mockDb.deleteMapping(any(), any(), any()) }
    }

    @Test
    fun `removeProxyFromApp does not affect other apps`() = runBlocking {
        loadMappings(pam(uid1, pkg1, wgProxyId0), pam(uid2, pkg2, wgProxyId0))
        ProxyManager.removeProxyFromApp(uid1, pkg1, wgProxyId0)
        assertTrue(ProxyManager.getProxyIdsForApp(uid2).contains(wgProxyId0))
        coVerify(exactly = 0) { mockDb.deleteMapping(uid2, pkg2, wgProxyId0) }
    }

    @Test
    fun `removing all proxy-specific entries leaves base entry tracked`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""), pam(uid1, pkg1, wgProxyId0), pam(uid1, pkg1, rpnProxyId))
        ProxyManager.removeProxyFromApp(uid1, pkg1, wgProxyId0)
        ProxyManager.removeProxyFromApp(uid1, pkg1, rpnProxyId)
        assertTrue("Base entry should keep app tracked", ProxyManager.trackedApps().any { it.uid == uid1 })
        assertTrue(ProxyManager.getProxyIdsForApp(uid1).isEmpty())
    }

    // ========================================================================
    // 5. setNoProxyForAllAppsForProxy / removeProxyId
    // ========================================================================

    @Test
    fun `removeProxyId (WG delete) removes WG rows and leaves RPN rows`() = runBlocking {
        loadMappings(
            pam(uid1, pkg1, ""), pam(uid1, pkg1, wgProxyId0), pam(uid1, pkg1, rpnProxyId),
            pam(uid2, pkg2, wgProxyId0),
        )
        ProxyManager.removeProxyId(wgProxyId0)
        assertFalse(ProxyManager.getProxyIdsForApp(uid1).contains(wgProxyId0))
        assertFalse(ProxyManager.getProxyIdsForApp(uid2).contains(wgProxyId0))
        assertTrue(ProxyManager.getProxyIdsForApp(uid1).contains(rpnProxyId))
        coVerify { mockDb.deleteMapping(uid1, pkg1, wgProxyId0) }
        coVerify { mockDb.deleteMapping(uid2, pkg2, wgProxyId0) }
        coVerify(exactly = 0) { mockDb.deleteMapping(any(), any(), rpnProxyId) }
    }

    @Test
    fun `removeProxyId (RPN stale server) removes only that server rows`() = runBlocking {
        loadMappings(
            pam(uid1, pkg1, ""), pam(uid1, pkg1, rpnProxyId), pam(uid1, pkg1, rpnProxyId2),
            pam(uid2, pkg2, rpnProxyId),
        )
        ProxyManager.removeProxyId(rpnProxyId)
        assertFalse(ProxyManager.getProxyIdsForApp(uid1).contains(rpnProxyId))
        assertTrue(ProxyManager.getProxyIdsForApp(uid1).contains(rpnProxyId2))
        assertFalse(ProxyManager.getProxyIdsForApp(uid2).contains(rpnProxyId))
        coVerify(exactly = 0) { mockDb.deleteMapping(any(), any(), rpnProxyId2) }
    }

    @Test
    fun `removeProxyId for non-existent proxyId is a no-op`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""))
        ProxyManager.removeProxyId(wgProxyId0)
        coVerify(exactly = 0) { mockDb.deleteMapping(any(), any(), any()) }
    }

    @Test
    fun `setNoProxyForAllAppsForProxy removes proxy from every app`() = runBlocking {
        loadMappings(
            pam(uid1, pkg1, ""), pam(uid1, pkg1, wgProxyId0),
            pam(uid2, pkg2, ""), pam(uid2, pkg2, wgProxyId0),
        )
        ProxyManager.setNoProxyForAllAppsForProxy(wgProxyId0)
        assertFalse(ProxyManager.getProxyIdsForApp(uid1).contains(wgProxyId0))
        assertFalse(ProxyManager.getProxyIdsForApp(uid2).contains(wgProxyId0))
        assertTrue("uid1 should still be tracked via base entry", ProxyManager.trackedApps().any { it.uid == uid1 })
        assertTrue("uid2 should still be tracked via base entry", ProxyManager.trackedApps().any { it.uid == uid2 })
    }

    // ========================================================================
    // 6. setProxyIdForAllApps
    // ========================================================================

    @Test
    fun `setProxyIdForAllApps adds proxy to every tracked app`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""), pam(uid2, pkg2, ""))
        stubFwApps(
            FirewallManager.AppInfoTuple(uid1, pkg1),
            FirewallManager.AppInfoTuple(uid2, pkg2)
        )
        ProxyManager.setProxyIdForAllApps(wgProxyId0, "T0")
        assertTrue(ProxyManager.getProxyIdsForApp(uid1).contains(wgProxyId0))
        assertTrue(ProxyManager.getProxyIdsForApp(uid2).contains(wgProxyId0))
        coVerify(exactly = 2) { mockDb.insert(match { it.proxyId == wgProxyId0 }) }
    }

    @Test
    fun `setProxyIdForAllApps skips apps already assigned to that proxy`() = runBlocking {
        loadMappings(pam(uid1, pkg1, wgProxyId0), pam(uid2, pkg2, ""))
        stubFwApps(
            FirewallManager.AppInfoTuple(uid1, pkg1),
            FirewallManager.AppInfoTuple(uid2, pkg2)
        )
        ProxyManager.setProxyIdForAllApps(wgProxyId0, "T0")
        coVerify(exactly = 1) { mockDb.insert(match { it.uid == uid2 && it.proxyId == wgProxyId0 }) }
        coVerify(exactly = 0) { mockDb.insert(match { it.uid == uid1 && it.proxyId == wgProxyId0 }) }
    }

    @Test
    fun `setProxyIdForAllApps rejects invalid proxyId`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""))
        stubFwApps(FirewallManager.AppInfoTuple(uid1, pkg1))
        ProxyManager.setProxyIdForAllApps("INVALID_PREFIX", "bad")
        coVerify(exactly = 0) { mockDb.insert(any()) }
    }

    @Test
    fun `setProxyIdForAllApps with RPN proxyId adds entry for every app`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""), pam(uid2, pkg2, ""))
        stubFwApps(
            FirewallManager.AppInfoTuple(uid1, pkg1),
            FirewallManager.AppInfoTuple(uid2, pkg2)
        )
        ProxyManager.setProxyIdForAllApps(rpnProxyId, rpnServerKey)
        coVerify(exactly = 2) { mockDb.insert(match { it.proxyId == rpnProxyId }) }
    }

    // ========================================================================
    // 7. setProxyIdForUnselectedApps
    // ========================================================================

    @Test
    fun `setProxyIdForUnselectedApps only adds to apps not yet in that proxy`() = runBlocking {
        loadMappings(pam(uid1, pkg1, wgProxyId0), pam(uid2, pkg2, ""))
        stubFwApps(
            FirewallManager.AppInfoTuple(uid1, pkg1),
            FirewallManager.AppInfoTuple(uid2, pkg2)
        )
        ProxyManager.setProxyIdForUnselectedApps(wgProxyId0, "T0")
        coVerify(exactly = 0) { mockDb.insert(match { it.uid == uid1 && it.proxyId == wgProxyId0 }) }
        coVerify(exactly = 1) { mockDb.insert(match { it.uid == uid2 && it.proxyId == wgProxyId0 }) }
        assertTrue(ProxyManager.getProxyIdsForApp(uid2).contains(wgProxyId0))
    }

    // ========================================================================
    // 8. deleteApp / deleteApps
    // ========================================================================

    @Test
    fun `deleteApp removes all rows for that uid and packageName`() = runBlocking {
        loadMappings(
            pam(uid1, pkg1, ""), pam(uid1, pkg1, wgProxyId0), pam(uid1, pkg1, rpnProxyId),
            pam(uid2, pkg2, ""),
        )
        ProxyManager.deleteApp(uid1, pkg1)
        assertFalse(ProxyManager.trackedApps().any { it.uid == uid1 })
        assertTrue(ProxyManager.trackedApps().any { it.uid == uid2 })
        coVerify(exactly = 1) { mockDb.deleteApp(uid1, pkg1) }
    }

    @Test
    fun `deleteApps bulk removes multiple apps`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""), pam(uid2, pkg2, ""))
        ProxyManager.deleteApps(listOf(
            FirewallManager.AppInfoTuple(uid1, pkg1),
            FirewallManager.AppInfoTuple(uid2, pkg2),
        ))
        assertTrue(ProxyManager.trackedApps().isEmpty())
    }

    // ========================================================================
    // 9. tombstoneApp
    // ========================================================================

    @Test
    fun `tombstoneApp negates the uid and calls DB tombstone`() = runBlocking {
        val tombstoneUid = -uid1
        loadMappings(pam(uid1, pkg1, ""), pam(uid1, pkg1, wgProxyId0))
        // After tombstoneApp calls load(), return tombstoned rows
        val tombstonedRows = listOf(pam(tombstoneUid, pkg1, ""), pam(tombstoneUid, pkg1, wgProxyId0))
        coEvery { mockDb.getApps() } returns tombstonedRows

        ProxyManager.tombstoneApp(uid1)

        coVerify(exactly = 1) { mockDb.tombstoneApp(uid1, tombstoneUid) }
        assertTrue(ProxyManager.trackedApps().any { it.uid == tombstoneUid })
        assertFalse(ProxyManager.trackedApps().any { it.uid == uid1 })
    }

    @Test
    fun `tombstoneApp with already-negative uid is a no-op`() = runBlocking {
        loadMappings(pam(-uid1, pkg1, ""))
        ProxyManager.tombstoneApp(-uid1)
        coVerify(exactly = 0) { mockDb.tombstoneApp(any(), any()) }
    }

    // ========================================================================
    // 10. updateApp
    // ========================================================================

    @Test
    fun `updateApp reassigns all rows to new uid when uid changes`() = runBlocking {
        val newUid = 20001
        loadMappings(pam(uid1, pkg1, ""), pam(uid1, pkg1, wgProxyId0))
        coEvery { FirewallManager.getAppInfoByPackage(pkg1) } returns buildAppInfo(newUid, pkg1, name1)

        ProxyManager.updateApp(newUid, pkg1)

        assertTrue(ProxyManager.trackedApps().any { it.uid == newUid && it.packageName == pkg1 })
        assertFalse(ProxyManager.trackedApps().any { it.uid == uid1 })
        coVerify(exactly = 1) { mockDb.updateUidForApp(uid1, newUid, pkg1) }
    }

    @Test
    fun `updateApp is no-op when uid has not changed`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""))
        ProxyManager.updateApp(uid1, pkg1)
        coVerify(exactly = 0) { mockDb.updateUidForApp(any(), any(), any()) }
    }

    // Regression: when an app exists in FirewallManager but has NO ProxyApplicationMapping row at all
    // (base row lost earlier, e.g. by a refresh race), updateApp must self-heal by creating the
    // proxyId="" base row instead of silently returning. Previously it logged "map not found" and
    // left the app permanently invisible in WgIncludeAppsAdapter.
    @Test
    fun `updateApp self-heals base row when no mapping exists`() = runBlocking {
        // no loadMappings(...) -> pamSet has nothing for (uid1, pkg1)
        stubFwApps(FirewallManager.AppInfoTuple(uid1, pkg1))

        ProxyManager.updateApp(uid1, pkg1)

        // a proxyId="" base row must now exist for the app
        assertTrue(
            ProxyManager.trackedApps().any { it.uid == uid1 && it.packageName == pkg1 }
        )
        coVerify(exactly = 1) { mockDb.insert(match { it.uid == uid1 && it.packageName == pkg1 && it.proxyId == "" }) }
        // no uid update should be issued (there was nothing to reassign)
        coVerify(exactly = 0) { mockDb.updateUidForApp(any(), any(), any()) }
    }

    @Test
    fun `updateApp does not self-heal when FirewallManager has no such app`() = runBlocking {
        // no mapping AND FirewallManager doesn't know the app -> skip (avoid orphan rows)
        ProxyManager.updateApp(uid1, pkg1)
        coVerify(exactly = 0) { mockDb.insert(any()) }
        coVerify(exactly = 0) { mockDb.updateUidForApp(any(), any(), any()) }
    }

    // Regression: setProxyIdForAllApps must backfill the proxyId="" base row for apps that lost it,
    // so they reappear in the "All Apps" pager query.
    @Test
    fun `setProxyIdForAllApps backfills missing base row`() = runBlocking {
        // uid1 has only a wg-specific row, NO base row; uid2 has only a base row
        loadMappings(pam(uid1, pkg1, wgProxyId0), pam(uid2, pkg2, ""))
        stubFwApps(
            FirewallManager.AppInfoTuple(uid1, pkg1),
            FirewallManager.AppInfoTuple(uid2, pkg2)
        )

        ProxyManager.setProxyIdForAllApps(wgProxyId0, "T0")

        // uid1 must now also have a proxyId="" base row
        val baseRowInserted = mutableListOf<ProxyApplicationMapping>()
        coVerify { mockDb.insert(capture(baseRowInserted)) }
        assertTrue(
            "uid1 base row must be backfilled",
            baseRowInserted.any { it.uid == uid1 && it.proxyId == "" }
        )
    }

    // ========================================================================
    // 11. isValidProxyPrefix
    // ========================================================================

    @Test
    fun `isValidProxyPrefix accepts all recognised prefixes`() = runBlocking {
        val validIds = mapOf(
            wgProxyId0              to "WG",
            "wg99"                  to "WG large id",
            Backend.RpnWin + "abc"  to "RPN WIN server",
            Backend.RpnWin + "AUTO" to "RPN WIN AUTO suffix (anomaly)",
            orbotProxyId            to "Orbot",
            tcpProxyId              to "TCP",
            s5ProxyId               to "SOCKS5",
            httpProxyId             to "HTTP",
        )
        validIds.forEach { (id, label) ->
            resetProxyManagerState()
            loadMappings(pam(uid1, pkg1, ""))
            ProxyManager.addProxyToApp(uid1, pkg1, id, "n")
            assertTrue("$label ($id) should be accepted", ProxyManager.getProxyIdsForApp(uid1).contains(id))
        }
    }

    @Test
    fun `isValidProxyPrefix rejects unrecognised prefixes`() = runBlocking {
        val invalidIds = listOf("", "SYSTEM", "unknown123", "rpnwin-abc", "wireguard0")
        invalidIds.forEach { id ->
            resetProxyManagerState()
            loadMappings(pam(uid1, pkg1, ""))
            ProxyManager.addProxyToApp(uid1, pkg1, id, "n")
            assertFalse("$id should be rejected", ProxyManager.getProxyIdsForApp(uid1).contains(id))
        }
    }

    // ========================================================================
    // 12. ID_RPN_WIN must equal Backend.RpnWin
    // ========================================================================

    @Test
    fun `ID_RPN_WIN equals Backend_RpnWin at runtime`() {
        assertEquals("ID_RPN_WIN must stay in sync with Backend.RpnWin", Backend.RpnWin, ProxyManager.ID_RPN_WIN)
    }

    @Test
    fun `RPN proxyId format is Backend_RpnWin + configKey`() {
        val configKey = "srv-us-ny-abc123"
        val expected  = Backend.RpnWin + configKey
        assertTrue(expected.startsWith(Backend.RpnWin))
        assertEquals(configKey, expected.removePrefix(Backend.RpnWin))
    }

    // ========================================================================
    // 13. AUTO server guard
    // ========================================================================

    @Test
    fun `AUTO proxyId startsWith Backend_RpnWin and passes prefix validation`() {
        assertTrue(autoProxyId.startsWith(Backend.RpnWin))
        assertNotEquals(autoProxyId, Backend.RpnWin)
    }

    @Test
    fun `AUTO proxyId is accepted by addProxyToApp - only UI guard prevents this`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""))
        ProxyManager.addProxyToApp(uid1, pkg1, autoProxyId, "AUTO")
        assertTrue(
            "ANOMALY: '$autoProxyId' stored in PAM; Go engine only guards id=='AUTO' and id==Backend.RpnWin",
            ProxyManager.getProxyIdsForApp(uid1).contains(autoProxyId)
        )
    }

    // ========================================================================
    // 14. isAnyAppSelected / getAppCountForProxy
    // ========================================================================

    @Test
    fun `isAnyAppSelected returns true when at least one app is in that proxy`() {
        loadMappings(pam(uid1, pkg1, wgProxyId0))
        assertTrue(ProxyManager.isAnyAppSelected(wgProxyId0))
    }

    @Test
    fun `isAnyAppSelected returns false when no app is in that proxy`() {
        loadMappings(pam(uid1, pkg1, ""))
        assertFalse(ProxyManager.isAnyAppSelected(wgProxyId0))
    }

    @Test
    fun `isAnyAppSelected is independent per proxyId`() {
        loadMappings(pam(uid1, pkg1, wgProxyId0))
        assertTrue(ProxyManager.isAnyAppSelected(wgProxyId0))
        assertFalse(ProxyManager.isAnyAppSelected(rpnProxyId))
    }

    @Test
    fun `getAppCountForProxy counts correctly across multi-proxy cache`() {
        loadMappings(
            pam(uid1, pkg1, wgProxyId0), pam(uid2, pkg2, wgProxyId0),
            pam(uid1, pkg1, rpnProxyId),
        )
        assertEquals(2, ProxyManager.getAppCountForProxy(wgProxyId0))
        assertEquals(1, ProxyManager.getAppCountForProxy(rpnProxyId))
        assertEquals(0, ProxyManager.getAppCountForProxy(wgProxyId1))
    }

    // ========================================================================
    // 15. getProxyIdsForApp
    // ========================================================================

    @Test
    fun `getProxyIdsForApp excludes base empty proxyId`() {
        loadMappings(pam(uid1, pkg1, ""), pam(uid1, pkg1, wgProxyId0), pam(uid1, pkg1, rpnProxyId))
        val ids = ProxyManager.getProxyIdsForApp(uid1)
        assertTrue(ids.contains(wgProxyId0))
        assertTrue(ids.contains(rpnProxyId))
        assertFalse("Base empty proxyId should be excluded", ids.contains(""))
    }

    @Test
    fun `getProxyIdsForApp by uid and pkg returns only for that specific package`() {
        loadMappings(pam(uid1, pkg1, wgProxyId0), pam(uid1b, pkg1, wgProxyId1))
        val ids = ProxyManager.getProxyIdsForApp(uid1, pkg1)
        assertTrue(ids.contains(wgProxyId0))
        assertFalse(ids.contains(wgProxyId1))
    }

    // ========================================================================
    // 16. getProxyIdForApp (legacy single-proxy lookup)
    // ========================================================================

    @Test
    fun `getProxyIdForApp returns ID_NONE when app not tracked`() {
        assertEquals(listOf(ProxyManager.ID_NONE), ProxyManager.getProxyIdForApp(uid1))
    }

    @Test
    fun `getProxyIdForApp returns first matching proxyId from pamSet`() {
        loadMappings(pam(uid1, pkg1, wgProxyId0))
        assertEquals(listOf(wgProxyId0), ProxyManager.getProxyIdForApp(uid1))
    }

    // ========================================================================
    // 17. load
    // ========================================================================

    @Test
    fun `load replaces entire pamSet with fresh DB data`() = runBlocking {
        coEvery { mockDb.getApps() } returns listOf(pam(uid1, pkg1, ""), pam(uid1, pkg1, wgProxyId0))
        val count1 = ProxyManager.load()
        assertEquals(2, count1)

        coEvery { mockDb.getApps() } returns listOf(pam(uid2, pkg2, ""))
        val count2 = ProxyManager.load()
        assertEquals(1, count2)
        assertFalse(ProxyManager.trackedApps().any { it.uid == uid1 })
        assertTrue(ProxyManager.trackedApps().any { it.uid == uid2 })
    }

    // ========================================================================
    // 18. isAnyUserSetProxy - ANOMALY: RPN matches via WG prefix
    // ========================================================================

    @Test
    fun `isAnyUserSetProxy returns true for WG proxyId`() {
        assertTrue(ProxyManager.isAnyUserSetProxy(wgProxyId0))
    }

    @Test
    fun `ANOMALY isAnyUserSetProxy returns true for RPN via WG prefix`() {
        // Backend.RpnWin = "wgyrpn" starts with ID_WG_BASE = "wg"
        // RPN proxyIds match via the WG prefix check, not via endsWith(Backend.RPN)
        assertTrue("RPN WIN proxyId should be a user-set proxy", ProxyManager.isAnyUserSetProxy(rpnProxyId))
        assertTrue("ANOMALY: matched via WG prefix", rpnProxyId.startsWith(ProxyManager.ID_WG_BASE))
    }

    @Test
    fun `isAnyUserSetProxy returns false for empty and SYSTEM`() {
        assertFalse(ProxyManager.isAnyUserSetProxy(""))
        assertFalse(ProxyManager.isAnyUserSetProxy(ProxyManager.ID_NONE))
    }

    // ========================================================================
    // 19. isRpnProxy - ANOMALY: WIN proxyIds misclassified
    // ========================================================================

    @Test
    fun `ANOMALY isRpnProxy returns false for WIN proxyId`() {
        // WIN proxyIds are Backend.RpnWin + configKey (e.g. "wgyrpnsrv-us-ny-abc123").
        // isRpnProxy uses endsWith(Backend.RPN) -- unless configKey ends with Backend.RPN
        // this returns false, causing WIN traffic to miss the RPN badge in connection-log.
        assertFalse(
            "ANOMALY: WIN proxyId '$rpnProxyId' does not endWith '${Backend.RPN}'",
            ProxyManager.isRpnProxy(rpnProxyId)
        )
    }

    @Test
    fun `isRpnProxy returns true for Backend_Auto`() {
        assertTrue(ProxyManager.isRpnProxy(Backend.Auto))
    }

    @Test
    fun `isRpnProxy returns false for empty string`() {
        assertFalse(ProxyManager.isRpnProxy(""))
    }

    // ========================================================================
    // 20. isNotLocalAndRpnProxy
    // ========================================================================

    @Test
    fun `isNotLocalAndRpnProxy returns false for Go engine sentinel values`() {
        listOf("", Backend.Base, Backend.Block, Backend.Exit, Backend.Auto, Backend.Ingress).forEach {
            assertFalse("Sentinel '$it' should return false", ProxyManager.isNotLocalAndRpnProxy(it))
        }
    }

    @Test
    fun `isNotLocalAndRpnProxy returns true for a WG proxyId`() {
        assertTrue(ProxyManager.isNotLocalAndRpnProxy(wgProxyId0))
    }

    @Test
    fun `isNotLocalAndRpnProxy returns true for a RPN WIN proxyId`() {
        assertTrue(ProxyManager.isNotLocalAndRpnProxy(rpnProxyId))
    }

    // ========================================================================
    // 21. ProxyApplicationMapping entity equality anomaly
    // ========================================================================

    @Test
    fun `ANOMALY ProxyApplicationMapping equals ignores proxyId`() {
        val base  = ProxyApplicationMapping(uid1, pkg1, name1, "",    true, "")
        val wgRow = ProxyApplicationMapping(uid1, pkg1, name1, "T0", true, wgProxyId0)
        assertEquals("ANOMALY: PAM.equals ignores proxyId", base, wgRow)
        assertEquals("ANOMALY: PAM.hashCode ignores proxyId", base.hashCode(), wgRow.hashCode())
        val set = mutableSetOf(base, wgRow)
        assertEquals("ANOMALY: Set<PAM> collapses multi-proxy rows to one entry", 1, set.size)
    }

    // ========================================================================
    // 22. ProxyAppMapTuple -- correct equality
    // ========================================================================

    @Test
    fun `ProxyAppMapTuple equality includes proxyId unlike ProxyApplicationMapping`() {
        val t1 = ProxyManager.ProxyAppMapTuple(uid1, pkg1, "")
        val t2 = ProxyManager.ProxyAppMapTuple(uid1, pkg1, wgProxyId0)
        val t3 = ProxyManager.ProxyAppMapTuple(uid1, pkg1, "")
        assertNotEquals("Different proxyId means different tuple", t1, t2)
        assertEquals("Same all-fields means equal tuple", t1, t3)
        val set = mutableSetOf(t1, t2, t3)
        assertEquals("Set<ProxyAppMapTuple> keeps both distinct entries", 2, set.size)
    }

    // ========================================================================
    // 23. Multi-user / shared-package scenario
    // ========================================================================

    @Test
    fun `two uids for same package are tracked and operated on independently`() = runBlocking {
        loadMappings(
            pam(uid1,  pkg1, ""), pam(uid1,  pkg1, wgProxyId0),
            pam(uid1b, pkg1, ""), pam(uid1b, pkg1, rpnProxyId),
        )
        assertTrue(ProxyManager.getProxyIdsForApp(uid1,  pkg1).contains(wgProxyId0))
        assertFalse(ProxyManager.getProxyIdsForApp(uid1,  pkg1).contains(rpnProxyId))
        assertTrue(ProxyManager.getProxyIdsForApp(uid1b, pkg1).contains(rpnProxyId))
        assertFalse(ProxyManager.getProxyIdsForApp(uid1b, pkg1).contains(wgProxyId0))

        ProxyManager.removeProxyFromApp(uid1, pkg1, wgProxyId0)
        assertFalse(ProxyManager.getProxyIdsForApp(uid1, pkg1).contains(wgProxyId0))
        assertTrue(ProxyManager.getProxyIdsForApp(uid1b, pkg1).contains(rpnProxyId))
    }

    // ========================================================================
    // 24. clear
    // ========================================================================

    @Test
    fun `clear removes all in-memory and DB data`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""), pam(uid2, pkg2, ""))
        ProxyManager.clear()
        assertEquals(0, pamSetSize())
        coVerify(exactly = 1) { mockDb.deleteAll() }
    }

    // ========================================================================
    // 25. updateProxyNameForProxyId
    // ========================================================================

    @Test
    fun `updateProxyNameForProxyId delegates to DB only`() = runBlocking {
        loadMappings(pam(uid1, pkg1, wgProxyId0, "OldName"))
        ProxyManager.updateProxyNameForProxyId(wgProxyId0, "NewName")
        coVerify(exactly = 1) { mockDb.updateProxyNameForProxyId(wgProxyId0, "NewName") }
    }

    // ========================================================================
    // 26. addApps
    // ========================================================================

    @Test
    fun `addApps calls addNewApp for each AppInfo`() = runBlocking {
        ProxyManager.addApps(listOf(appInfo1, appInfo2))
        assertTrue(ProxyManager.trackedApps().any { it.uid == uid1 })
        assertTrue(ProxyManager.trackedApps().any { it.uid == uid2 })
        coVerify(exactly = 2) { mockDb.insert(any()) }
    }

    @Test
    fun `addApps silently skips null entries`() = runBlocking {
        ProxyManager.addApps(listOf(appInfo1, null, appInfo2))
        coVerify(exactly = 2) { mockDb.insert(any()) }
    }

    // ========================================================================
    // 27. Sync invariant: AppInfo (FirewallManager) <-> ProxyApplicationMapping
    //     Every app that FirewallManager tracks must end up with at least a
    //     proxyId="" base row in the proxy mapping so it is visible in the
    //     WgIncludeAppsAdapter "All Apps" pager query
    //     (proxyId = :proxyId OR proxyId = '').
    // ========================================================================

    /**
     * Capture every PAM written to the mock DB so assertions can inspect column-level correctness
     * (in particular that proxyId/proxyName are NOT swapped, and that the base row proxyId == "").
     */
    private fun captureInserted(): MutableList<ProxyApplicationMapping> {
        val captured = mutableListOf<ProxyApplicationMapping>()
        coEvery { mockDb.insert(capture(captured)) } returns 1L
        return captured
    }

    @Test
    fun `addNewApp creates exactly one base row with proxyId empty`() = runBlocking {
        val captured = captureInserted()
        ProxyManager.addNewApp(appInfo1)
        assertEquals(1, captured.size)
        val row = captured[0]
        assertEquals(uid1, row.uid)
        assertEquals(pkg1, row.packageName)
        assertEquals("base row proxyId must be empty", "", row.proxyId)
        assertEquals(name1, row.appName)
    }

    @Test
    fun `addNewApp with explicit proxyId stores it in the proxyId column (not swapped)`() = runBlocking {
        val captured = captureInserted()
        ProxyManager.addNewApp(appInfo1, proxyId = wgProxyId0, proxyName = "T0")
        assertEquals(1, captured.size)
        assertEquals("proxyId must be in proxyId column", wgProxyId0, captured[0].proxyId)
        assertEquals("proxyName must be in proxyName column", "T0", captured[0].proxyName)
    }

    @Test
    fun `trackedApps reflects every uid-pkg that has any proxy row`() = runBlocking {
        loadMappings(
            pam(uid1, pkg1, ""), pam(uid1, pkg1, wgProxyId0),
            pam(uid2, pkg2, "")
        )
        val tracked = ProxyManager.trackedApps().map { it.uid to it.packageName }.toSet()
        // uid1 and uid2 each appear once even though uid1 has 2 rows
        assertEquals(setOf(uid1 to pkg1, uid2 to pkg2), tracked)
    }

    @Test
    fun `getProxyIdForApp returns SYSTEM when app has only the base row`() {
        loadMappings(pam(uid1, pkg1, ""))
        val ids = ProxyManager.getProxyIdForApp(uid1)
        assertEquals(listOf(ProxyManager.ID_NONE), ids)
    }

    @Test
    fun `addProxyToApp creates only the proxy-specific row, leaving any base row intact`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""))
        ProxyManager.addProxyToApp(uid1, pkg1, wgProxyId0, "T0")
        assertTrue(ProxyManager.getProxyIdsForApp(uid1).contains(wgProxyId0))
        // base row tuple still present
        assertTrue(pamSetContains(uid1, pkg1, ""))
    }

    @Test
    fun `removeProxyFromApp removes only the targeted proxy, not the base row`() = runBlocking {
        loadMappings(pam(uid1, pkg1, ""), pam(uid1, pkg1, wgProxyId0))
        ProxyManager.removeProxyFromApp(uid1, pkg1, wgProxyId0)
        assertFalse(ProxyManager.getProxyIdsForApp(uid1).contains(wgProxyId0))
        assertTrue("base row must survive proxy removal", pamSetContains(uid1, pkg1, ""))
        coVerify(exactly = 1) { mockDb.deleteMapping(uid1, pkg1, wgProxyId0) }
        coVerify(exactly = 0) { mockDb.deleteMapping(uid1, pkg1, "") }
    }

    // --- bulk ops backfill missing apps from FirewallManager -----------------------------

    @Test
    fun `setProxyIdForAllApps backfills apps known to FirewallManager but missing from proxy cache`() = runBlocking {
        // uid1 has a base row in proxy cache; uid2 is known to FirewallManager but has NO proxy row
        loadMappings(pam(uid1, pkg1, ""))
        stubFwApps(
            FirewallManager.AppInfoTuple(uid1, pkg1),
            FirewallManager.AppInfoTuple(uid2, pkg2)
        )
        ProxyManager.setProxyIdForAllApps(wgProxyId0, "T0")
        // uid2 must now have both a base row AND the wg0 row
        assertTrue("missing app backfilled with wg0", ProxyManager.getProxyIdsForApp(uid2).contains(wgProxyId0))
        assertTrue("missing app also gets base row", pamSetContains(uid2, pkg2, ""))
    }

    @Test
    fun `setProxyIdForUnselectedApps backfills apps known to FirewallManager but missing from proxy cache`() = runBlocking {
        loadMappings(pam(uid1, pkg1, wgProxyId0))
        stubFwApps(
            FirewallManager.AppInfoTuple(uid1, pkg1),
            FirewallManager.AppInfoTuple(uid2, pkg2)
        )
        ProxyManager.setProxyIdForUnselectedApps(wgProxyId0, "T0")
        assertTrue(ProxyManager.getProxyIdsForApp(uid2).contains(wgProxyId0))
        assertTrue("base row backfilled too", pamSetContains(uid2, pkg2, ""))
    }

    @Test
    fun `setProxyIdForAllApps ignores apps that FirewallManager does not track`() = runBlocking {
        // proxy cache has a stale uid, but FirewallManager only knows uid1 -> stale uid gets no
        // base row fixup and no spurious wg0 row from this op (it iterates FW, not the cache).
        val staleUid = 10999
        loadMappings(pam(staleUid, "com.stale", ""))
        stubFwApps(FirewallManager.AppInfoTuple(uid1, pkg1))
        ProxyManager.setProxyIdForAllApps(wgProxyId0, "T0")
        assertFalse("stale cache-only app must not gain wg0", ProxyManager.getProxyIdsForApp(staleUid).contains(wgProxyId0))
    }

    // --- updateApp self-healing (see also Section 10 for the core self-heal tests) -------

    @Test
    fun `updateApp self-heal uses uid-aware lookup so work-profile apps are handled`() = runBlocking {
        // same package under two uids; only one is being updated
        stubFwApps(
            FirewallManager.AppInfoTuple(uid1, pkg1),
            FirewallManager.AppInfoTuple(uid1b, pkg1)
        )
        ProxyManager.updateApp(uid1b, pkg1)
        assertTrue("work-profile uid got its own base row", pamSetContains(uid1b, pkg1, ""))
    }

    // --- full sync comparison after compound operations ---------------------------------

    @Test
    fun `sync after addNewApp then addProxyToApp - base and proxy rows coexist`() = runBlocking {
        ProxyManager.addNewApp(appInfo1)                                  // base row
        ProxyManager.addProxyToApp(uid1, pkg1, wgProxyId0, "T0")         // proxy row
        val ids = ProxyManager.getProxyIdsForApp(uid1)
        assertTrue(ids.contains(wgProxyId0))
        assertTrue("base row preserved", pamSetContains(uid1, pkg1, ""))
    }

    @Test
    fun `sync after assign-all then remove-all leaves only base rows`() = runBlocking {
        stubFwApps(
            FirewallManager.AppInfoTuple(uid1, pkg1),
            FirewallManager.AppInfoTuple(uid2, pkg2)
        )
        ProxyManager.setProxyIdForAllApps(wgProxyId0, "T0")
        ProxyManager.setNoProxyForAllAppsForProxy(wgProxyId0)
        assertFalse(ProxyManager.getProxyIdsForApp(uid1).contains(wgProxyId0))
        assertFalse(ProxyManager.getProxyIdsForApp(uid2).contains(wgProxyId0))
        // base rows survive so apps stay visible in the include list
        assertTrue("uid1 base row intact", pamSetContains(uid1, pkg1, ""))
        assertTrue("uid2 base row intact", pamSetContains(uid2, pkg2, ""))
    }

    @Test
    fun `sync after tombstone - rows move to negative uid and DB tombstone is invoked`() = runBlocking {
        ProxyManager.addNewApp(appInfo1)
        ProxyManager.addProxyToApp(uid1, pkg1, wgProxyId0, "T0")
        // tombstoneApp calls load() at the end; stub getApps to return the tombstoned entries so
        // the in-memory cache reflects post-tombstone state.
        val neg = -uid1
        coEvery { mockDb.getApps() } returns listOf(
            ProxyApplicationMapping(neg, pkg1, name1, "", true, ""),
            ProxyApplicationMapping(neg, pkg1, name1, "T0", true, wgProxyId0)
        )
        ProxyManager.tombstoneApp(uid1)
        // DB tombstone was invoked
        coVerify(exactly = 1) { mockDb.tombstoneApp(uid1, neg) }
        // positive uid gone from reloaded cache
        assertFalse(pamSetContains(uid1, pkg1, ""))
        // negative uid has both base and proxy rows in reloaded cache
        assertTrue("base row survives tombstone under negative uid", pamSetContains(neg, pkg1, ""))
        assertTrue("proxy row survives tombstone under negative uid", pamSetContains(neg, pkg1, wgProxyId0))
    }

    @Test
    fun `sync after delete removes all rows for that uid and package`() = runBlocking {
        ProxyManager.addNewApp(appInfo1)
        ProxyManager.addProxyToApp(uid1, pkg1, wgProxyId0, "T0")
        ProxyManager.deleteApp(uid1, pkg1)
        assertTrue("no rows remain", ProxyManager.trackedApps().none { it.uid == uid1 })
        coVerify(exactly = 1) { mockDb.deleteApp(uid1, pkg1) }
    }

    @Test
    fun `proxyId and proxyName are never swapped across operations`() = runBlocking {
        val captured = captureInserted()
        ProxyManager.addNewApp(appInfo1, proxyId = wgProxyId0, proxyName = "Tunnel0")
        ProxyManager.addProxyToApp(uid1, pkg1, rpnProxyId, rpnServerKey)
        // every captured row must have proxyId in proxyId and proxyName in proxyName
        captured.forEach { row ->
            assertTrue("proxyId column must not hold a name: ${row.proxyId}", row.proxyId != "Tunnel0" && row.proxyId != rpnServerKey)
        }
        assertEquals(wgProxyId0, captured[0].proxyId)
        assertEquals("Tunnel0", captured[0].proxyName)
        assertEquals(rpnProxyId, captured[1].proxyId)
        assertEquals(rpnServerKey, captured[1].proxyName)
    }

    // ========================================================================
    // 28. purgeGhostMappings — remove entries referencing deleted WG/RPN proxies
    // ========================================================================

    @Test
    fun `purgeGhostMappings removes stale WG proxy entries from cache and DB`() = runBlocking {
        loadMappings(
            pam(uid1, pkg1, ""),            // base row
            pam(uid1, pkg1, wgProxyId0),    // valid WG
            pam(uid1, pkg1, wgProxyId1),    // ghost WG (config deleted)
            pam(uid2, pkg2, "")             // another app base row
        )
        val purged = ProxyManager.purgeGhostMappings(
            validWgProxyIds = setOf(wgProxyId0),   // only wg0 still exists
            validRpnProxyIds = emptySet()
        )
        assertEquals(1, purged)
        assertTrue("valid WG survives", pamSetContains(uid1, pkg1, wgProxyId0))
        assertFalse("ghost WG removed from cache", pamSetContains(uid1, pkg1, wgProxyId1))
        assertTrue("base rows untouched", pamSetContains(uid1, pkg1, ""))
        assertTrue("base rows untouched", pamSetContains(uid2, pkg2, ""))
        coVerify(exactly = 1) { mockDb.deleteMapping(uid1, pkg1, wgProxyId1) }
    }

    @Test
    fun `purgeGhostMappings removes stale RPN proxy entries from cache and DB`() = runBlocking {
        loadMappings(
            pam(uid1, pkg1, ""),
            pam(uid1, pkg1, rpnProxyId),     // valid RPN
            pam(uid1, pkg1, rpnProxyId2),    // ghost RPN (server removed)
            pam(uid2, pkg2, rpnProxyId2)     // ghost RPN on another app
        )
        val purged = ProxyManager.purgeGhostMappings(
            validWgProxyIds = emptySet(),
            validRpnProxyIds = setOf(rpnProxyId)  // only first server still exists
        )
        assertEquals(2, purged)
        assertTrue("valid RPN survives", pamSetContains(uid1, pkg1, rpnProxyId))
        assertFalse("ghost RPN removed", pamSetContains(uid1, pkg1, rpnProxyId2))
        assertFalse("ghost RPN removed on uid2", pamSetContains(uid2, pkg2, rpnProxyId2))
        coVerify(exactly = 1) { mockDb.deleteMapping(uid1, pkg1, rpnProxyId2) }
        coVerify(exactly = 1) { mockDb.deleteMapping(uid2, pkg2, rpnProxyId2) }
    }

    @Test
    fun `purgeGhostMappings does NOT misclassify RPN ids as WG despite shared prefix`() = runBlocking {
        // Backend.RpnWin = "wgyrpn" starts with ID_WG_BASE = "wg"; the purge must check RPN
        // before WG so a valid RPN id is never evaluated against the WG valid-set.
        loadMappings(
            pam(uid1, pkg1, ""),
            pam(uid1, pkg1, rpnProxyId),    // valid RPN, must survive
            pam(uid1, pkg1, wgProxyId0)     // valid WG, must survive
        )
        val purged = ProxyManager.purgeGhostMappings(
            validWgProxyIds = setOf(wgProxyId0),
            validRpnProxyIds = setOf(rpnProxyId)
        )
        assertEquals(0, purged)
        assertTrue(pamSetContains(uid1, pkg1, rpnProxyId))
        assertTrue(pamSetContains(uid1, pkg1, wgProxyId0))
    }

    @Test
    fun `purgeGhostMappings retains Orbot SOCKS5 HTTP TCP assignments regardless of valid sets`() = runBlocking {
        loadMappings(
            pam(uid1, pkg1, ""),
            pam(uid1, pkg1, orbotProxyId),
            pam(uid1, pkg1, s5ProxyId),
            pam(uid1, pkg1, httpProxyId),
            pam(uid1, pkg1, tcpProxyId)
        )
        // pass EMPTY valid sets — none of these proxy types should be purged
        val purged = ProxyManager.purgeGhostMappings(
            validWgProxyIds = emptySet(),
            validRpnProxyIds = emptySet()
        )
        assertEquals(0, purged)
        assertTrue(pamSetContains(uid1, pkg1, orbotProxyId))
        assertTrue(pamSetContains(uid1, pkg1, s5ProxyId))
        assertTrue(pamSetContains(uid1, pkg1, httpProxyId))
        assertTrue(pamSetContains(uid1, pkg1, tcpProxyId))
    }

    @Test
    fun `purgeGhostMappings never removes base rows`() = runBlocking {
        loadMappings(
            pam(uid1, pkg1, ""),
            pam(uid2, pkg2, ""),
            pam(uid2, pkg2, wgProxyId0)     // ghost WG
        )
        val purged = ProxyManager.purgeGhostMappings(
            validWgProxyIds = emptySet(),
            validRpnProxyIds = emptySet()
        )
        assertEquals(1, purged)
        assertTrue("base row kept", pamSetContains(uid1, pkg1, ""))
        assertTrue("base row kept", pamSetContains(uid2, pkg2, ""))
        assertFalse("ghost WG purged", pamSetContains(uid2, pkg2, wgProxyId0))
    }

    @Test
    fun `purgeGhostMappings returns 0 and is a no-op when there are no ghosts`() = runBlocking {
        loadMappings(
            pam(uid1, pkg1, ""),
            pam(uid1, pkg1, wgProxyId0),
            pam(uid1, pkg1, rpnProxyId)
        )
        val purged = ProxyManager.purgeGhostMappings(
            validWgProxyIds = setOf(wgProxyId0),
            validRpnProxyIds = setOf(rpnProxyId)
        )
        assertEquals(0, purged)
        coVerify(exactly = 0) { mockDb.deleteMapping(any(), any(), any()) }
    }

    @Test
    fun `purgeGhostMappings handles empty pamSet`() = runBlocking {
        val purged = ProxyManager.purgeGhostMappings(
            validWgProxyIds = setOf(wgProxyId0),
            validRpnProxyIds = setOf(rpnProxyId)
        )
        assertEquals(0, purged)
    }

    @Test
    fun `purgeGhostMappings clears ghost from getProxyIdForApp so AppInfoActivity shows correct proxies`() = runBlocking {
        // simulates the user-reported bug: AppInfoActivity.displayProxyStatus shows a deleted WG
        loadMappings(
            pam(uid1, pkg1, ""),
            pam(uid1, pkg1, wgProxyId0),    // live
            pam(uid1, pkg1, wgProxyId1)     // deleted tunnel, still in DB
        )
        // before purge, getProxyIdForApp returns the ghost
        assertTrue(ProxyManager.getProxyIdForApp(uid1).contains(wgProxyId1))

        ProxyManager.purgeGhostMappings(setOf(wgProxyId0), emptySet())

        // after purge, only the live proxy remains
        val proxies = ProxyManager.getProxyIdForApp(uid1)
        assertTrue(proxies.contains(wgProxyId0))
        assertFalse("ghost proxy no longer reported", proxies.contains(wgProxyId1))
    }

    // --- helper -------------------------------------------------------------------------

    /** True if the in-memory pamSet contains the given (uid, packageName, proxyId) tuple. */
    private fun pamSetContains(uid: Int, pkg: String, proxyId: String): Boolean {
        val field = ProxyManager::class.java.getDeclaredField("pamSet")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val set = field.get(ProxyManager) as CopyOnWriteArraySet<ProxyManager.ProxyAppMapTuple>
        return set.any { it.uid == uid && it.packageName == pkg && it.proxyId == proxyId }
    }
}

