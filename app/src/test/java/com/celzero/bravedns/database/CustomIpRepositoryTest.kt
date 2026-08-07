package com.celzero.bravedns.database

import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class CustomIpRepositoryTest {

    private lateinit var repository: CustomIpRepository
    private val customIpDao: CustomIpDao = mockk(relaxed = true)

    @Before
    fun setup() {
        repository = CustomIpRepository(customIpDao)
    }

    @Test
    fun `test insert calls DAO`() = runBlocking {
        val customIp = CustomIp()
        repository.insert(customIp)
        coVerify { customIpDao.insert(customIp) }
    }

    @Test
    fun `test deleteRule calls DAO`() = runBlocking {
        repository.deleteRule(1000, "1.2.3.4", 80)
        coVerify { customIpDao.deleteRule(1000, "1.2.3.4", 80) }
    }
}
