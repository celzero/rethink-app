package com.celzero.bravedns.database

import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class WgConfigFilesRepositoryTest {

    private lateinit var repository: WgConfigFilesRepository
    private val wgConfigFilesDAO: WgConfigFilesDAO = mockk(relaxed = true)

    @Before
    fun setup() {
        repository = WgConfigFilesRepository(wgConfigFilesDAO)
    }

    @Test
    fun `test insert calls DAO`() = runBlocking {
        val config = WgConfigFiles(
            name = "Test",
            configPath = "/path",
            serverResponse = "{}",
            isActive = false,
            isCatchAll = false,
            isLockdown = false,
            oneWireGuard = false,
            useOnlyOnMetered = false,
            isDeletable = true
        )
        repository.insert(config)
        coVerify { wgConfigFilesDAO.insert(config) }
    }

    @Test
    fun `test updateLockdownConfig calls DAO`() = runBlocking {
        repository.updateLockdownConfig(1, true)
        coVerify { wgConfigFilesDAO.updateLockdownConfig(1, true) }
    }
}
