package com.celzero.bravedns.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.service.FirewallManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class AppInfoDAOTest {
    private lateinit var appInfoDao: AppInfoDAO
    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        appInfoDao = db.appInfoDAO()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    @Throws(Exception::class)
    fun writeAppInfoAndReadInList() = runBlocking {
        val appInfo = AppInfo(
            packageName = "com.example.app",
            appName = "Example App",
            uid = 10001,
            isSystemApp = false,
            firewallStatus = FirewallManager.FirewallStatus.NONE.id,
            appCategory = "Internet",
            wifiDataUsed = 0,
            mobileDataUsed = 0,
            connectionStatus = FirewallManager.ConnectionStatus.ALLOW.id,
            isProxyExcluded = false,
            screenOffAllowed = false,
            backgroundAllowed = false
        )
        appInfoDao.insert(appInfo)
        val app = appInfoDao.getAppInfoByUid(10001)
        assertEquals(app?.packageName, "com.example.app")
    }

    @Test
    @Throws(Exception::class)
    fun updateFirewallStatus() = runBlocking {
        val appInfo = AppInfo(
            packageName = "com.example.app",
            appName = "Example App",
            uid = 10001,
            isSystemApp = false,
            firewallStatus = FirewallManager.FirewallStatus.NONE.id,
            appCategory = "Internet",
            wifiDataUsed = 0,
            mobileDataUsed = 0,
            connectionStatus = FirewallManager.ConnectionStatus.ALLOW.id,
            isProxyExcluded = false,
            screenOffAllowed = false,
            backgroundAllowed = false
        )
        appInfoDao.insert(appInfo)
        
        val newStatus = FirewallManager.FirewallStatus.ISOLATE.id
        val newConnStatus = FirewallManager.ConnectionStatus.BOTH.id
        appInfoDao.updateFirewallStatusByUid(10001, newStatus, newConnStatus, System.currentTimeMillis())
        
        val updatedApp = appInfoDao.getAppInfoByUid(10001)
        assertEquals(updatedApp?.firewallStatus, newStatus)
        assertEquals(updatedApp?.connectionStatus, newConnStatus)
    }

    @Test
    @Throws(Exception::class)
    fun deleteAppInfo() = runBlocking {
        val appInfo = AppInfo(
            packageName = "com.example.app",
            appName = "Example App",
            uid = 10001,
            isSystemApp = false,
            firewallStatus = FirewallManager.FirewallStatus.NONE.id,
            appCategory = "Internet",
            wifiDataUsed = 0,
            mobileDataUsed = 0,
            connectionStatus = FirewallManager.ConnectionStatus.ALLOW.id,
            isProxyExcluded = false,
            screenOffAllowed = false,
            backgroundAllowed = false
        )
        appInfoDao.insert(appInfo)
        appInfoDao.deleteByUid(10001)
        val app = appInfoDao.getAppInfoByUid(10001)
        assertNull(app)
    }
}
