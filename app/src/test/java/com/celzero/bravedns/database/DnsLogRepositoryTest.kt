package com.celzero.bravedns.database

import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class DnsLogRepositoryTest {

    private lateinit var repository: DnsLogRepository
    private val dnsLogDAO: DnsLogDAO = mockk(relaxed = true)

    @Before
    fun setup() {
        repository = DnsLogRepository(dnsLogDAO)
    }

    @Test
    fun `test insert calls DAO`() = runBlocking {
        val dnsLog = DnsLog().apply { queryStr = "test.com" }
        repository.insert(dnsLog)
        coVerify { dnsLogDAO.insert(dnsLog) }
    }

    @Test
    fun `test insertBatch calls DAO`() = runBlocking {
        val dnsLogs = listOf(DnsLog(), DnsLog())
        repository.insertBatch(dnsLogs)
        coVerify { dnsLogDAO.insertBatch(dnsLogs) }
    }

    @Test
    fun `test clearAllData calls DAO`() = runBlocking {
        repository.clearAllData()
        coVerify { dnsLogDAO.clearAllData() }
    }
}
