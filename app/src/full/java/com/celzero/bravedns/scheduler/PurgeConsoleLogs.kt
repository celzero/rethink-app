package com.celzero.bravedns.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.database.ConsoleLogRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class PurgeConsoleLogs(val context: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(context, workerParameters), KoinComponent {

    companion object {
        const val LOG_LIMIT = 20000
    }

    private val consoleLogRepository by inject<ConsoleLogRepository>()

    override suspend fun doWork(): Result {
        // delete old logs will limit the logs count to 20000
        consoleLogRepository.deleteOldLogs(LOG_LIMIT)
        Logger.v(Logger.LOG_TAG_APP_DB, "Purging console logs")
        return Result.success()
    }

}
