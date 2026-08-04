package com.celzero.bravedns.database

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AppInfoRepositoryTest {

    private lateinit var repository: AppInfoRepository
    private val appInfoDAO: AppInfoDAO = mockk(relaxed = true)

    @Before
    fun setup() {
        repository = AppInfoRepository(appInfoDAO)
    }

    @Test
    fun `test insert app info calls DAO`() = runBlocking {
        val appInfo = AppInfo(null).apply {
            packageName = "com.test"
            appName = "Test App"
        }
        
        coEvery { appInfoDAO.insert(any()) } returns 1L
        
        val result = repository.insert(appInfo)
        
        assertEquals(1L, result)
        coVerify { appInfoDAO.insert(match { it.packageName == "com.test" }) }
    }

    @Test
    fun `test updateUid handles existing UID`() = runBlocking {
        coEvery { appInfoDAO.isUidPkgExist(2000, "com.test") } returns AppInfo(null)
        
        val result = repository.updateUid(1000, 2000, "com.test")
        
        assertEquals(0, result)
        coVerify { appInfoDAO.deletePackage(1000, "com.test") }
        coVerify(exactly = 0) { appInfoDAO.updateUid(any(), any(), any(), any()) }
    }

    @Test
    fun `test updateUid handles non-existing UID`() = runBlocking {
        coEvery { appInfoDAO.isUidPkgExist(2000, "com.test") } returns null
        coEvery { appInfoDAO.updateUid(1000, "com.test", 2000, any()) } returns 1
        
        val result = repository.updateUid(1000, 2000, "com.test")
        
        assertEquals(1, result)
        coVerify { appInfoDAO.updateUid(1000, "com.test", 2000, any()) }
    }

    @Test
    fun `test tombstoneApp handles null package name`() = runBlocking {
        coEvery { appInfoDAO.tombstoneAppByUid(1000, 2000, 12345L, any()) } returns Unit
        
        repository.tombstoneApp(1000, 2000, null, 12345L)
        
        coVerify { appInfoDAO.tombstoneAppByUid(1000, 2000, 12345L, any()) }
    }

    @Test
    fun `test tombstoneApp handles package name`() = runBlocking {
        coEvery { appInfoDAO.tombstoneAppWithPkg(2000, 1000, "com.test", 12345L, any()) } returns Unit
        
        repository.tombstoneApp(1000, 2000, "com.test", 12345L)
        
        coVerify { appInfoDAO.tombstoneAppWithPkg(2000, 1000, "com.test", 12345L, any()) }
    }

    @Test
    fun `test getAllTempAllowedApps filters by time`() = runBlocking {
        val app1 = AppInfo(null).apply { tempAllowExpiryTime = 1000 }
        val app2 = AppInfo(null).apply { tempAllowExpiryTime = 3000 }
        
        coEvery { appInfoDAO.getTempAllowedApps() } returns listOf(app1, app2)
        
        val result = repository.getAllTempAllowedApps(2000)
        
        assertEquals(1, result.size)
        assertEquals(3000L, result[0].tempAllowExpiryTime)
    }
}
