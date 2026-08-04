package com.celzero.bravedns.database

import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class ProxyEndpointRepositoryTest {

    private lateinit var repository: ProxyEndpointRepository
    private val proxyEndpointDAO: ProxyEndpointDAO = mockk(relaxed = true)

    @Before
    fun setup() {
        repository = ProxyEndpointRepository(proxyEndpointDAO)
    }

    @Test
    fun `test insert calls DAO`() = runBlocking {
        val proxy = ProxyEndpoint(
            id = 0,
            proxyName = "test",
            proxyMode = 0,
            proxyType = "NONE",
            proxyAppName = null,
            proxyIP = "1.2.3.4",
            proxyPort = 8080,
            userName = null,
            password = null,
            isSelected = true,
            isCustom = true,
            isUDP = false,
            modifiedDataTime = 0L,
            latency = 0
        )
        repository.insert(proxy)
        coVerify { proxyEndpointDAO.insert(proxy) }
    }

    @Test
    fun `test removeConnectionStatus calls DAO`() = runBlocking {
        repository.removeConnectionStatus()
        coVerify { proxyEndpointDAO.removeConnectionStatus() }
    }
}
