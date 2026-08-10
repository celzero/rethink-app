package com.celzero.bravedns.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class DnsLogDAOTest {
    private lateinit var dnsLogDao: DnsLogDAO
    private lateinit var db: LogDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, LogDatabase::class.java).build()
        dnsLogDao = db.dnsLogDAO()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    @Throws(Exception::class)
    fun insertAndCountLogs() = runBlocking {
        val dnsLog = DnsLog().apply {
            queryStr = "google.com"
            time = System.currentTimeMillis()
            isBlocked = false
            resolver = "Cloudflare"
        }
        dnsLogDao.insert(dnsLog)
        
        // logsCount() returns LiveData, but we can't easily test LiveData in runBlocking without observers.
        // However, we can use other methods or just check if insert works by fetching if there was a list method.
        // DnsLogDAO has recentlyBlockedDnsApps which is suspend.
        
        val blockedDnsLog = DnsLog().apply {
            uid = 10001
            queryStr = "malware.com"
            time = System.currentTimeMillis()
            isBlocked = true
        }
        dnsLogDao.insert(blockedDnsLog)
        
        val blockedApps = dnsLogDao.getRecentlyBlockedDnsApps(0)
        assertEquals(blockedApps.size, 1)
        assertEquals(blockedApps[0].uid, 10001)
    }

    @Test
    @Throws(Exception::class)
    fun clearLogs() = runBlocking {
        val dnsLog = DnsLog().apply {
            queryStr = "google.com"
            time = System.currentTimeMillis()
        }
        dnsLogDao.insert(dnsLog)
        dnsLogDao.clearAllData()
        
        val blockedApps = dnsLogDao.getRecentlyBlockedDnsApps(0)
        assertEquals(blockedApps.size, 0)
    }
}
