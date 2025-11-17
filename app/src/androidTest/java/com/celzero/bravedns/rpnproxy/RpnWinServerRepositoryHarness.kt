package com.celzero.bravedns.rpnproxy

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.database.RpnWinServer
import com.celzero.bravedns.database.RpnWinServerDAO
import com.celzero.bravedns.database.RpnWinServerRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Small instrumentation test harness to simulate:
 *  fetch (mock API) → sync (DB) → read (repo) → update metrics (DB+repo)
 * Uses an in-memory Room database to avoid touching the real AppDatabase.
 */
@RunWith(AndroidJUnit4::class)
class RpnWinServerRepositoryHarness {

    // Minimal Room database for testing just RpnWinServerDAO
    @Database(entities = [RpnWinServer::class], version = 1, exportSchema = false)
    abstract class TestRpnDb : RoomDatabase() {
        abstract fun rpnWinServerDAO(): RpnWinServerDAO
    }

    private lateinit var db: TestRpnDb
    private lateinit var dao: RpnWinServerDAO
    private lateinit var repo: RpnWinServerRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, TestRpnDb::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.rpnWinServerDAO()
        repo = RpnWinServerRepository(dao)
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun makeServer(
        name: String,
        cc: String,
        city: String,
        key: String,
        load: Int,
        link: Int,
        count: Int,
        active: Boolean = true
    ): RpnWinServer = RpnWinServer(
        id = "$cc-$city-$key",
        name = name,
        countryCode = cc,
        address = "1.1.1.1", // dummy
        city = city,
        key = key,
        load = load,
        link = link,
        count = count,
        isActive = active,
        lastUpdated = System.currentTimeMillis()
    )

    @Test
    fun test_fetch_sync_read_update_flow() = runBlocking {
        // 0) Initial state
        assertEquals(0, repo.getServerCount())

        // 1) Fetch (mock) and sync
        val s1 = makeServer(name = "New York", cc = "US", city = "NYC", key = "k1", load = 25, link = 80, count = 2)
        val s2 = makeServer(name = "San Jose", cc = "US", city = "SJC", key = "k2", load = 40, link = 120, count = 1)
        val added = repo.syncServers(listOf(s1, s2))
        assertEquals(2, added)
        assertEquals(2, repo.getServerCount())

        // 2) Read all and verify
        val all1 = repo.getAllServers()
        assertEquals(2, all1.size)
        val first = all1.firstOrNull { it.id == s1.id }
        assertNotNull(first)
        assertEquals(80, first!!.link)
        assertEquals(25, first.load)

        // 3) Update metrics of one server
        repo.updateServerMetrics(s1.id, load = 10, link = 45)
        val updated = repo.getServerById(s1.id)
        assertNotNull(updated)
        assertEquals(10, updated!!.load)
        assertEquals(45, updated.link)

        // 4) Sync again with removal and insertion
        val s3 = makeServer(name = "Berlin", cc = "DE", city = "BER", key = "k3", load = 20, link = 60, count = 3)
        val total = repo.syncServers(listOf(s2, s3)) // s1 removed, s3 added
        assertEquals(2, total)

        val all2 = repo.getAllServers()
        assertEquals(2, all2.size)
        // s1 should be removed
        assertEquals(null, all2.firstOrNull { it.id == s1.id })
        // s3 should be present
        assertNotNull(all2.firstOrNull { it.id == s3.id })

        // 5) Query by country
        val usServers = repo.getServersByCountryCode("US")
        assertEquals(1, usServers.size) // only s2 remains in US

        // 6) Clear all
        repo.deleteAllServers()
        assertEquals(0, repo.getServerCount())
    }
}

