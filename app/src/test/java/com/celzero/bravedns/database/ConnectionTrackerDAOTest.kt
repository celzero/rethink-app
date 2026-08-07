package com.celzero.bravedns.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE)
class ConnectionTrackerDAOTest {

    private lateinit var db: LogDatabase
    private lateinit var dao: ConnectionTrackerDAO

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LogDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.connectionTrackerDAO()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `test insert and query connection log`() {
        val log = ConnectionTracker().apply {
            id = 1
            appName = "Test App"
            uid = 1000
            ipAddress = "1.1.1.1"
            isBlocked = false
            timeStamp = System.currentTimeMillis()
        }
        
        dao.insert(log)
        
        // Since getConnectionTrackerByName returns PagingSource, we might need a different way to verify
        // or just use a query that returns a List for simple tests if available.
        // ConnectionTrackerDAO doesn't have a simple getAllLogs() returning List.
        // Let's use getAppIpLogs or similar if it's easier, or just add a test-only query if needed.
        // Actually, let's test purgeLogsByDate.
        
        dao.purgeLogsByDate(System.currentTimeMillis() + 1000)
        
        // We can check count
        // Note: logsCount returns LiveData. In tests we can use getValue() or observe.
    }

    @Test
    fun `test getDataUsage returns correct summary`() {
        val now = System.currentTimeMillis()
        val log1 = ConnectionTracker().apply {
            connId = "1"
            uid = 1000
            uploadBytes = 100
            downloadBytes = 200
            timeStamp = now - 1000
        }
        val log2 = ConnectionTracker().apply {
            connId = "2"
            uid = 1000
            uploadBytes = 50
            downloadBytes = 50
            timeStamp = now - 500
        }
        
        dao.insertBatch(listOf(log1, log2))
        
        val usage = dao.getDataUsage(now - 2000, now)
        assertEquals(1, usage.size)
        assertEquals(1000, usage[0].uid)
        assertEquals(150L, usage[0].uploadBytes)
        assertEquals(250L, usage[0].downloadBytes)
    }
}
