package com.celzero.bravedns.scheduler

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.celzero.bravedns.database.ConsoleLogRepository
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class LogExportWorkerTest {

    private lateinit var context: Context
    private val logDb: ConsoleLogRepository = mockk(relaxed = true)

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        stopKoin()
        startKoin {
            modules(module {
                single { logDb }
            })
        }
    }

    @Test
    fun `test doWork returns failure when filePath is missing`() = runBlocking {
        val worker = TestListenableWorkerBuilder<LogExportWorker>(context).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.failure(), result)
    }
}
