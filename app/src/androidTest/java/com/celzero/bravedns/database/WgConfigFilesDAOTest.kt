package com.celzero.bravedns.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class WgConfigFilesDAOTest {
    private lateinit var wgConfigFilesDao: WgConfigFilesDAO
    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        wgConfigFilesDao = db.wgConfigFilesDAO()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    @Throws(Exception::class)
    fun insertAndGetConfig() = runBlocking {
        val config = WgConfigFiles(
            name = "Test Config",
            configPath = "/path/to/config",
            serverResponse = "{}",
            isActive = false,
            isCatchAll = false,
            isLockdown = false,
            oneWireGuard = false,
            useOnlyOnMetered = false,
            isDeletable = true
        )
        val id = wgConfigFilesDao.insert(config).toInt()
        
        val fetched = wgConfigFilesDao.isConfigAdded(id)
        assertNotNull(fetched)
        assertEquals(fetched?.name, "Test Config")
    }

    @Test
    @Throws(Exception::class)
    fun updateConfig() = runBlocking {
        val config = WgConfigFiles(
            name = "Test Config",
            configPath = "/path/to/config",
            serverResponse = "{}",
            isActive = false,
            isCatchAll = false,
            isLockdown = false,
            oneWireGuard = false,
            useOnlyOnMetered = false,
            isDeletable = true
        )
        val id = wgConfigFilesDao.insert(config).toInt()
        
        wgConfigFilesDao.updateLockdownConfig(id, true)
        val updated = wgConfigFilesDao.isConfigAdded(id)
        assertEquals(updated?.isLockdown, true)
    }

    @Test
    @Throws(Exception::class)
    fun deleteConfig() = runBlocking {
        val config = WgConfigFiles(
            name = "Test Config",
            configPath = "/path/to/config",
            serverResponse = "{}",
            isActive = false,
            isCatchAll = false,
            isLockdown = false,
            oneWireGuard = false,
            useOnlyOnMetered = false,
            isDeletable = true
        )
        val id = wgConfigFilesDao.insert(config).toInt()
        wgConfigFilesDao.deleteConfig(id)
        
        val fetched = wgConfigFilesDao.isConfigAdded(id)
        assertNull(fetched)
    }
}
