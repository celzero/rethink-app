package com.celzero.bravedns.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class CustomIpDaoTest {
    private lateinit var customIpDao: CustomIpDao
    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        customIpDao = db.customIpEndpointDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    @Throws(Exception::class)
    fun insertAndGetIpRule() = runBlocking {
        val customIp = CustomIp().apply {
            uid = 10001
            ipAddress = "1.1.1.1"
            port = 443
            protocol = "TCP"
            status = 0 // BLOCK
            isActive = true
        }
        customIpDao.insert(customIp)
        
        val rule = customIpDao.getCustomIpDetail(10001, "1.1.1.1", 443)
        assertNotNull(rule)
        assertEquals(rule?.ipAddress, "1.1.1.1")
        assertEquals(rule?.uid, 10001)
    }

    @Test
    @Throws(Exception::class)
    fun deleteIpRule() = runBlocking {
        val customIp = CustomIp().apply {
            uid = 10001
            ipAddress = "1.1.1.1"
            port = 443
            protocol = "TCP"
        }
        customIpDao.insert(customIp)
        customIpDao.deleteRule(10001, "1.1.1.1", 443)
        
        val rule = customIpDao.getCustomIpDetail(10001, "1.1.1.1", 443)
        assertNull(rule)
    }

    @Test
    @Throws(Exception::class)
    fun insertUniversalRule() = runBlocking {
        val customIp = CustomIp().apply {
            uid = UID_EVERYBODY
            ipAddress = "8.8.8.8"
            port = 53
            protocol = "UDP"
            status = 0 // BLOCK
        }
        customIpDao.insert(customIp)
        
        val count = customIpDao.getBlockedConnectionsCount()
        assertEquals(count, 1)
    }
}
