package com.celzero.bravedns.database

import com.celzero.bravedns.data.ConnectionSummary
import com.celzero.bravedns.service.PersistentState
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class ConnectionTrackerRepositoryTest {

    private lateinit var repository: ConnectionTrackerRepository
    private val connectionTrackerDAO: ConnectionTrackerDAO = mockk(relaxed = true)
    private val persistentState: PersistentState = mockk(relaxed = true)

    @Before
    fun setup() {
        stopKoin()
        startKoin {
            modules(module {
                single { persistentState }
            })
        }
        repository = ConnectionTrackerRepository(connectionTrackerDAO)
    }

    @Test
    fun `test insert calls DAO`() = runBlocking {
        val ct = ConnectionTracker()
        repository.insert(ct)
        coVerify { connectionTrackerDAO.insert(ct) }
    }

    @Test
    fun `test updateBatch with targetIp calls detailed updateSummary`() = runBlocking {
        val summary = ConnectionSummary(
            uid = "1000",
            connId = "1",
            pid = "p1",
            rpid = "r1",
            downloadBytes = 100,
            uploadBytes = 50,
            duration = 10,
            rtt = 5,
            message = "msg",
            targetIp = "1.2.3.4",
            flag = "flag"
        )
        
        repository.updateBatch(listOf(summary))
        
        coVerify {
            connectionTrackerDAO.updateSummary("1", "p1", "r1", 100, 50, 10, 5, "msg", "1.2.3.4", "flag")
        }
    }

    @Test
    fun `test updateBatch without targetIp calls basic updateSummary`() = runBlocking {
        val summary = ConnectionSummary(
            uid = "1000",
            connId = "1",
            pid = "p1",
            rpid = "r1",
            downloadBytes = 100,
            uploadBytes = 50,
            duration = 10,
            rtt = 5,
            message = "msg",
            targetIp = null,
            flag = null
        )
        
        repository.updateBatch(listOf(summary))
        
        coVerify {
            connectionTrackerDAO.updateSummary("1", "p1", "r1", 100, 50, 10, 5, "msg")
        }
    }
}
