package com.celzero.bravedns.scheduler

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.database.ConsoleLogRepository
import com.celzero.bravedns.service.PersistentState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

class PurgeConsoleLogs(val context: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(context, workerParameters), KoinComponent {

    private val consoleLogRepository by inject<ConsoleLogRepository>()
    private val persistentState by inject<PersistentState>()

    companion object {
        const val MAX_TIME: Long = 3
    }
    override suspend fun doWork(): Result {
        // delete logs which are older than MAX_TIME hrs
        val threshold = TimeUnit.HOURS.toMillis(MAX_TIME)
        val currTime = System.currentTimeMillis()
        val time = currTime - threshold

        consoleLogRepository.deleteOldLogs(time)
        if (persistentState.consoleLogEnabled) {
            val startTime = consoleLogRepository.consoleLogStartTimestamp
            // stop the console log if it exceeds max time
            if (currTime - startTime > TimeUnit.HOURS.toMillis(MAX_TIME)) {
                consoleLogRepository.consoleLogStartTimestamp = 0
                persistentState.consoleLogEnabled = false
            }
        }
        return Result.success()
    }

}
