package com.celzero.bravedns.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.database.ConsoleLogRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

class PurgeConsoleLogs(val context: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(context, workerParameters), KoinComponent {

    private val consoleLogRepository by inject<ConsoleLogRepository>()

    companion object {
        const val MAX_TIME: Long = 4
    }
    override suspend fun doWork(): Result {
        // delete logs which are older than MAX_TIME hrs
        val threshold = TimeUnit.HOURS.toMillis(MAX_TIME)
        val currTime = System.currentTimeMillis()
        val time = currTime - threshold

        consoleLogRepository.deleteOldLogs(time)
        return Result.success()
    }

}
