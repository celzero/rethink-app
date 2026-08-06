/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.database

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Real in-memory Room DB integration tests for the [ProxyApplicationMapping] table and its sync
 * invariant with the [AppInfo] table.
 *
 * These tests validate the exact SQL that [com.celzero.bravedns.adapter.WgIncludeAppsAdapter]
 * (via [com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel]) depends on, plus every DAO
 * transaction that mutates the mapping, to ensure no app can silently disappear from the WireGuard
 * "include apps" list.
 *
 * ## The invariant under test
 * An app `(uid, packageName)` is **visible** in proxy P's include-list iff it has a row in
 * `ProxyApplicationMapping` with `proxyId = P` **or** a row with `proxyId = ''` (the "base" row).
 * Every app present in `AppInfo` must therefore have at least that base row, otherwise it vanishes
 * from every proxy's include-list even though it shows up in the firewall app list.
 *
 * Uses a real in-memory [AppDatabase] (schema built fresh from entities, no migrations) so that the
 * actual SQL (`GROUP BY`, `IN`, `rowid MIN`, `LIKE`) is exercised verbatim.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class ProxyAppMappingIntegrityTest {

    private lateinit var db: AppDatabase
    private lateinit var appInfoDao: AppInfoDAO
    private lateinit var proxyDao: ProxyApplicationMappingDAO
    private lateinit var proxyRepo: ProxyAppMappingRepository

    // test proxy ids
    private val wg0 = "wg0"
    private val wg1 = "wg1"
    private val rpn = "wgyrpnsrv-us-ny-abc"

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        appInfoDao = db.appInfoDAO()
        proxyDao = db.wgApplicationMappingDao()
        proxyRepo = ProxyAppMappingRepository(proxyDao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ----------------------------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------------------------

    private fun appInfo(uid: Int, pkg: String, name: String = "App$uid"): AppInfo = AppInfo(
        packageName = pkg, appName = name, uid = uid, isSystemApp = false,
        firewallStatus = 5, appCategory = "Test", wifiDataUsed = 0L, mobileDataUsed = 0L,
        connectionStatus = 3, isProxyExcluded = false, screenOffAllowed = true,
        backgroundAllowed = true, tombstoneTs = 0L
    )

    private fun pam(uid: Int, pkg: String, proxyId: String, appName: String = "App$uid"): ProxyApplicationMapping =
        ProxyApplicationMapping(uid, pkg, appName, "pname", true, proxyId)

    /** Insert into ProxyApplicationMapping directly via DAO. */
    private fun insertProxy(uid: Int, pkg: String, proxyId: String, appName: String = "App$uid") {
        proxyDao.insert(pam(uid, pkg, proxyId, appName))
    }

    /** Insert into AppInfo directly via DAO. */
    private fun insertApp(uid: Int, pkg: String, name: String = "App$uid") {
        appInfoDao.insert(appInfo(uid, pkg, name))
    }

    /**
     * Synchronously load the first page from a [PagingSource] (the same mechanism the real adapter
     * uses) and return the data list. Used to assert visibility behaviour of the pager queries.
     * [PagingSource.load] is a suspend function; wrap it in [runBlocking] so the test helpers stay
     * synchronous for use inside assertions.
     */
    private fun loadPage(source: PagingSource<Int, ProxyApplicationMapping>): List<ProxyApplicationMapping> =
        runBlocking {
            val result = source.load(PagingSource.LoadParams.Refresh(null, 200, false))
            assertTrue("expected LoadResult.Page, got $result", result is PagingSource.LoadResult.Page)
            (result as PagingSource.LoadResult.Page).data
        }

    /** Set of (uid, packageName) currently visible in the "All apps" pager for a given proxy. */
    private fun visibleAllApps(proxyId: String): Set<Pair<Int, String>> =
        loadPage(proxyDao.getAllAppsMapping("%%", proxyId))
            .map { it.uid to it.packageName }
            .toSet()

    /** Set of (uid, packageName) currently visible in the "Selected apps" pager. */
    private fun visibleSelected(proxyId: String): Set<Pair<Int, String>> =
        loadPage(proxyDao.getSelectedAppsMapping("%%", proxyId))
            .map { it.uid to it.packageName }
            .toSet()

    /** Set of (uid, packageName) currently visible in the "Unselected apps" pager. */
    private fun visibleUnselected(proxyId: String): Set<Pair<Int, String>> =
        loadPage(proxyDao.getUnSelectedAppsMapping("%%", proxyId))
            .map { it.uid to it.packageName }
            .toSet()

    // ========================================================================================
    // SECTION 1 - Visibility rule (the exact query WgIncludeAppsAdapter depends on)
    // ========================================================================================

    @Test
    fun `getAllAppsMapping shows app that has only the base row`() {
        insertProxy(10001, "com.a", "")
        val visible = visibleAllApps(wg0)
        assertTrue("app with base row must be visible", (10001 to "com.a") in visible)
    }

    @Test
    fun `getAllAppsMapping shows app that has only the specific proxyId row`() {
        insertProxy(10001, "com.a", wg0)
        val visible = visibleAllApps(wg0)
        assertTrue("app assigned to wg0 must be visible for wg0", (10001 to "com.a") in visible)
    }

    @Test
    fun `getAllAppsMapping hides app that has no row at all`() {
        // app exists in AppInfo but has ZERO rows in ProxyApplicationMapping -> the bug scenario
        insertApp(10001, "com.a")
        val visible = visibleAllApps(wg0)
        assertFalse("app with no proxy row must NOT be visible (regression)", (10001 to "com.a") in visible)
    }

    @Test
    fun `getAllAppsMapping shows app with base row even when querying a different proxyId`() {
        insertProxy(10001, "com.a", "") // base only, not assigned to any proxy
        val visible = visibleAllApps(wg0)
        assertTrue("base row makes app visible for every proxy dialog", (10001 to "com.a") in visible)
    }

    @Test
    fun `getAllAppsMapping shows app once even with both base and specific rows`() {
        insertProxy(10001, "com.a", "")     // base
        insertProxy(10001, "com.a", wg0)    // assigned
        insertProxy(10001, "com.a", wg1)    // assigned to another proxy
        val visible = visibleAllApps(wg0)
        assertEquals("must be de-duplicated to one row", 1, visible.size)
        assertTrue((10001 to "com.a") in visible)
    }

    @Test
    fun `getAllAppsMapping returns empty when table is empty`() {
        assertTrue(visibleAllApps(wg0).isEmpty())
    }

    @Test
    fun `getSelectedAppsMapping only shows apps assigned to that proxyId`() {
        insertProxy(10001, "com.a", wg0)
        insertProxy(10002, "com.b", "")     // base only
        insertProxy(10003, "com.c", wg1)    // different proxy
        val sel = visibleSelected(wg0)
        assertTrue((10001 to "com.a") in sel)
        assertFalse("base-only app not in selected", (10002 to "com.b") in sel)
        assertFalse("different-proxy app not in selected", (10003 to "com.c") in sel)
    }

    @Test
    fun `getUnSelectedAppsMapping shows apps not assigned to that proxyId`() {
        insertProxy(10001, "com.a", wg0)    // assigned -> excluded
        insertProxy(10002, "com.b", "")     // not assigned -> included
        insertProxy(10003, "com.c", wg1)    // different -> included
        val unsel = visibleUnselected(wg0)
        assertFalse((10001 to "com.a") in unsel)
        assertTrue((10002 to "com.b") in unsel)
        assertTrue((10003 to "com.c") in unsel)
    }

    @Test
    fun `LIKE search filter works on appName`() {
        insertProxy(10001, "com.a", "", appName = "WhatsApp")
        insertProxy(10002, "com.b", "", appName = "Telegram")
        val res = loadPage(proxyDao.getAllAppsMapping("%whats%", wg0))
        assertEquals(1, res.size)
        assertEquals("com.a", res[0].packageName)
    }

    // ========================================================================================
    // SECTION 2 - Multi-uid per package (work-profile / cloned apps)
    // ========================================================================================

    @Test
    fun `same package under two uids both appear when both have base rows`() {
        insertProxy(10042, "com.dual", "")
        insertProxy(1010042, "com.dual", "") // work-profile copy
        val visible = visibleAllApps(wg0)
        assertEquals(2, visible.size)
        assertTrue((10042 to "com.dual") in visible)
        assertTrue((1010042 to "com.dual") in visible)
    }

    @Test
    fun `multi-uid package where only one uid has a row shows only that uid`() {
        // This is the SQL-level signature of the previously-reported anomaly: only one uid gets
        // a base row, the other is invisible.
        insertProxy(10042, "com.dual", "")
        // 1010042 has NO row
        val visible = visibleAllApps(wg0)
        assertEquals(1, visible.size)
        assertTrue((10042 to "com.dual") in visible)
        assertFalse((1010042 to "com.dual") in visible)
    }

    @Test
    fun `multi-uid package with proxy assigned to one uid shows both in all-apps`() {
        insertProxy(10042, "com.dual", "")        // base
        insertProxy(1010042, "com.dual", "")      // base
        insertProxy(10042, "com.dual", wg0)       // assigned user-0 only
        val all = visibleAllApps(wg0)
        assertEquals("both uids visible in all-apps (work-profile has base row)", 2, all.size)
        val sel = visibleSelected(wg0)
        assertEquals("only user-0 selected for wg0", 1, sel.size)
        assertTrue((10042 to "com.dual") in sel)
    }

    // ========================================================================================
    // SECTION 3 - Sync comparison: AppInfo vs ProxyApplicationMapping base rows
    // ========================================================================================

    /**
     * Core comparison invariant: the set of distinct (uid, pkg) that are VISIBLE via the pager
     * query must equal the set that has a base row, and (in a healthy system) equal AppInfo rows.
     */
    @Test
    fun `visible apps equal base-row apps`() {
        insertProxy(10001, "com.a", "")
        insertProxy(10002, "com.b", "")
        insertProxy(10003, "com.c", wg0)   // only specific, no base
        val visible = visibleAllApps(wg0)
        assertEquals(3, visible.size)
        // the proxy-specific-only app is still visible
        assertTrue((10003 to "com.c") in visible)
    }

    @Test
    fun `healthy sync - every AppInfo app has a base row so all are visible`() {
        // seed 5 apps in AppInfo, each with a matching base row in ProxyApplicationMapping
        val apps = (1..5).map { appInfo(10000 + it, "com.app$it") }
        apps.forEach {
            appInfoDao.insert(it)
            insertProxy(it.uid, it.packageName, "")
        }
        val visible = visibleAllApps(wg0)
        val appInfoApps = appInfoDao.getAllAppDetails().map { it.uid to it.packageName }.toSet()
        assertEquals("all AppInfo apps must be visible", appInfoApps, visible)
    }

    @Test
    fun `broken sync - missing base row makes app invisible (documents the bug)`() {
        // 3 apps in AppInfo, but the 2nd has no proxy row -> invisible (the anomaly)
        insertApp(10001, "com.a")
        insertApp(10002, "com.b")
        insertApp(10003, "com.c")
        insertProxy(10001, "com.a", "")
        // 10002 intentionally missing
        insertProxy(10003, "com.c", "")
        val visible = visibleAllApps(wg0)
        assertTrue((10001 to "com.a") in visible)
        assertFalse("com.b is the anomaly - missing base row", (10002 to "com.b") in visible)
        assertTrue((10003 to "com.c") in visible)
    }

    @Test
    fun `adding the missing base row restores visibility (the fix)`() {
        insertApp(10002, "com.b")
        assertFalse((10002 to "com.b") in visibleAllApps(wg0))
        // fix: create the base row
        insertProxy(10002, "com.b", "")
        assertTrue("after backfill, app is visible again", (10002 to "com.b") in visibleAllApps(wg0))
    }

    @Test
    fun `tombstoned app with negative uid still has its rows but is a distinct identity`() = runBlocking {
        // tombstone moves uid 10001 -> -10001; the row should still exist under the negative uid
        insertProxy(10001, "com.a", "")
        proxyRepo.tombstoneApp(10001, -10001)
        // positive uid row gone
        assertTrue(proxyDao.getProxiesForApp(10001, "com.a").none { it.proxyId == "" })
        // negative uid row present
        val rows = proxyDao.getProxiesForApp(-10001, "com.a")
        assertTrue("base row must survive tombstone under negative uid", rows.any { it.proxyId == "" })
    }

    // ========================================================================================
    // SECTION 4 - Transaction integrity: updateUidForApp / tombstoneApp / deleteApp
    // ========================================================================================

    @Test
    fun `updateUidForApp moves base and proxy-specific rows to new uid without loss`() = runBlocking {
        insertProxy(10001, "com.a", "")
        insertProxy(10001, "com.a", wg0)
        insertProxy(10001, "com.a", wg1)
        proxyRepo.updateUidForApp(10001, 20001, "com.a")
        // old uid has nothing
        assertTrue(proxyDao.getProxiesForApp(10001, "com.a").isEmpty())
        // new uid has all three rows
        val rows = proxyDao.getProxiesForApp(20001, "com.a").map { it.proxyId }.toSet()
        assertEquals(setOf("", wg0, wg1), rows)
    }

    @Test
    fun `updateUidForApp handles conflicting existing row at new uid via delete+insert fallback`() = runBlocking {
        // old uid has base + wg0; new uid already has a base row (conflict on PK)
        insertProxy(10001, "com.a", "")
        insertProxy(10001, "com.a", wg0)
        insertProxy(20001, "com.a", "")
        proxyRepo.updateUidForApp(10001, 20001, "com.a")
        // after resolution, 20001 should have base + wg0
        val rows = proxyDao.getProxiesForApp(20001, "com.a").map { it.proxyId }.toSet()
        assertTrue("base row must exist after conflict resolution", "" in rows)
        assertTrue("wg0 row must be migrated", wg0 in rows)
    }

    @Test
    fun `tombstoneApp moves all rows (base and proxy-specific) to negative uid`() = runBlocking {
        insertProxy(10001, "com.a", "")
        insertProxy(10001, "com.a", wg0)
        proxyRepo.tombstoneApp(10001, -10001)
        assertEquals(0, proxyDao.getProxiesForApp(10001, "com.a").size)
        val rows = proxyDao.getProxiesForApp(-10001, "com.a").map { it.proxyId }.toSet()
        assertEquals(setOf("", wg0), rows)
    }

    @Test
    fun `deleteApp removes every row for that uid and packageName`() {
        insertProxy(10001, "com.a", "")
        insertProxy(10001, "com.a", wg0)
        insertProxy(10001, "com.a", rpn)
        insertProxy(10002, "com.b", "") // other app, must survive
        proxyDao.deleteApp(10001, "com.a")
        assertTrue(proxyDao.getProxiesForApp(10001, "com.a").isEmpty())
        assertTrue(proxyDao.getProxiesForApp(10002, "com.b").isNotEmpty())
    }

    @Test
    fun `deleteAppByPkgName removes all rows across all uids for that package`() {
        insertProxy(10042, "com.dual", "")
        insertProxy(1010042, "com.dual", "")  // work-profile
        insertProxy(10099, "com.other", "")
        proxyDao.deleteAppByPkgName("com.dual")
        assertTrue(proxyDao.getProxiesForApp(10042, "com.dual").isEmpty())
        assertTrue(proxyDao.getProxiesForApp(1010042, "com.dual").isEmpty())
        assertTrue("unrelated app survives", proxyDao.getProxiesForApp(10099, "com.other").isNotEmpty())
    }

    @Test
    fun `deleteMapping removes only the targeted proxyId row`() {
        insertProxy(10001, "com.a", "")
        insertProxy(10001, "com.a", wg0)
        proxyDao.deleteMapping(10001, "com.a", wg0)
        val rows = proxyDao.getProxiesForApp(10001, "com.a").map { it.proxyId }
        assertEquals("base row must survive targeted proxy delete", listOf(""), rows)
    }

    @Test
    fun `insert with REPLACE upserts existing row`() {
        proxyDao.insert(pam(10001, "com.a", wg0, appName = "Old"))
        proxyDao.insert(pam(10001, "com.a", wg0, appName = "New"))
        val rows = proxyDao.getProxiesForApp(10001, "com.a")
        assertEquals(1, rows.size)
        assertEquals("New", rows[0].appName)
    }

    @Test
    fun `PK constraint allows same uid-pkg with different proxyId`() {
        proxyDao.insert(pam(10001, "com.a", ""))
        proxyDao.insert(pam(10001, "com.a", wg0))
        proxyDao.insert(pam(10001, "com.a", rpn))
        assertEquals(3, proxyDao.getProxiesForApp(10001, "com.a").size)
    }

    @Test
    fun `deleteAll clears the table`() {
        insertProxy(10001, "com.a", "")
        insertProxy(10002, "com.b", wg0)
        proxyDao.deleteAll()
        assertTrue(proxyDao.getWgAppMapping().isEmpty())
    }

    // ========================================================================================
    // SECTION 5 - Count queries (used by WgIncludeAppsDialog heading)
    // ========================================================================================

    @Test
    fun `getAppCountById counts only rows for that proxyId`() {
        insertProxy(10001, "com.a", wg0)
        insertProxy(10002, "com.b", wg0)
        insertProxy(10003, "com.c", wg1)
        insertProxy(10004, "com.d", "")
        assertEquals(2, proxyDao.getAppCountById(wg0))
        assertEquals(1, proxyDao.getAppCountById(wg1))
        assertEquals(0, proxyDao.getAppCountById(rpn))
    }

    @Test
    fun `getProxyIdsForApp returns all proxyIds for that uid+pkg`() {
        insertProxy(10001, "com.a", "")
        insertProxy(10001, "com.a", wg0)
        insertProxy(10001, "com.a", rpn)
        val ids = proxyDao.getProxyIdsForApp(10001, "com.a").toSet()
        assertEquals(setOf("", wg0, rpn), ids)
    }

    @Test
    fun `getAppsForProxy returns all apps assigned to a proxy`() {
        insertProxy(10001, "com.a", wg0)
        insertProxy(10002, "com.b", wg0)
        insertProxy(10003, "com.c", "")
        val apps = proxyDao.getAppsForProxy(wg0)
        assertEquals(2, apps.size)
    }

    @Test
    fun `updateProxyNameForProxyId updates name across all rows`() {
        insertProxy(10001, "com.a", wg0)
        insertProxy(10002, "com.b", wg0)
        proxyDao.updateProxyNameForProxyId(wg0, "RenamedTunnel")
        val apps = proxyDao.getAppsForProxy(wg0)
        assertTrue(apps.all { it.proxyName == "RenamedTunnel" })
    }

    // ========================================================================================
    // SECTION 6 - Full sync reconciliation simulation (mirrors refreshProxyMapping add-path)
    // ========================================================================================

    /**
     * Simulate the "add missing base rows" reconciliation that [RefreshDatabase.refreshProxyMapping]
     * performs, but at the DAO level. After running it, every AppInfo app must be visible.
     */
    @Test
    fun `reconciliation backfills base rows so all AppInfo apps become visible`() {
        // seed AppInfo with 4 apps
        listOf(
            appInfo(10001, "com.a"),
            appInfo(10002, "com.b"),
            appInfo(10003, "com.c"),
            appInfo(10042, "com.dual")
        ).forEach { appInfoDao.insert(it) }

        // proxy mapping has base rows for only 2 of them (com.a, com.c)
        insertProxy(10001, "com.a", "")
        insertProxy(10003, "com.c", "")

        // before reconciliation: com.b and com.dual are invisible
        val before = visibleAllApps(wg0)
        assertFalse((10002 to "com.b") in before)
        assertFalse((10042 to "com.dual") in before)

        // reconciliation: for every AppInfo app without a base row, insert one
        val existing = proxyDao.getWgAppMapping()
            .filter { it.proxyId == "" }
            .map { it.uid to it.packageName }
            .toSet()
        appInfoDao.getAllAppDetails().forEach { ai ->
            if ((ai.uid to ai.packageName) !in existing) {
                insertProxy(ai.uid, ai.packageName, "", ai.appName)
            }
        }

        // after reconciliation: ALL AppInfo apps are visible
        val after = visibleAllApps(wg0)
        val allAppInfo = appInfoDao.getAllAppDetails().map { it.uid to it.packageName }.toSet()
        assertEquals("every AppInfo app must now be visible", allAppInfo, after)
    }

    @Test
    fun `reconciliation handles multi-uid package correctly (both uids get base rows)`() {
        // same package under two uids in AppInfo
        insertApp(10042, "com.dual")
        insertApp(1010042, "com.dual")
        // proxy mapping empty

        // reconcile
        val existing = proxyDao.getWgAppMapping().filter { it.proxyId == "" }
            .map { it.uid to it.packageName }.toSet()
        appInfoDao.getAllAppDetails().forEach { ai ->
            if ((ai.uid to ai.packageName) !in existing) insertProxy(ai.uid, ai.packageName, "", ai.appName)
        }

        val visible = visibleAllApps(wg0)
        assertTrue((10042 to "com.dual") in visible)
        assertTrue((1010042 to "com.dual") in visible)
    }

    @Test
    fun `full lifecycle - install, assign, uid-change, uninstall keeps visibility consistent`() = runBlocking {
        // 1. fresh install: AppInfo has app, mapping has base row -> visible
        insertApp(10001, "com.a")
        insertProxy(10001, "com.a", "")
        assertTrue((10001 to "com.a") in visibleAllApps(wg0))

        // 2. user assigns to wg0 -> visible + selected
        insertProxy(10001, "com.a", wg0)
        assertTrue((10001 to "com.a") in visibleSelected(wg0))

        // 3. app uid changes (reinstall) -> updateUidForApp moves rows
        proxyRepo.updateUidForApp(10001, 20001, "com.a")
        assertTrue((20001 to "com.a") in visibleAllApps(wg0))
        assertTrue((20001 to "com.a") in visibleSelected(wg0))
        assertFalse((10001 to "com.a") in visibleAllApps(wg0))

        // 4. uninstall -> deleteApp removes rows -> invisible
        proxyDao.deleteApp(20001, "com.a")
        assertFalse((20001 to "com.a") in visibleAllApps(wg0))
    }

    @Test
    fun `hasInternetPermission no_package prefix round-trips through DAO`() {
        val m = pam(9999, "${AppInfoRepository.NO_PACKAGE_PREFIX}9999", "")
        // PackageManager not available in this test context; just verify the prefix short-circuit
        // path doesn't throw and the entity round-trips
        proxyDao.insert(m)
        assertNotNull(proxyDao.getProxiesForApp(9999, m.packageName).firstOrNull())
    }
}
