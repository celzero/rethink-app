package com.celzero.bravedns.scheduler

import Logger
import Logger.LOG_BATCH_LOGGER
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.database.ConsoleLogRepository
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.service.PersistentState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

class PurgeConsoleLogs(val context: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(context, workerParameters), KoinComponent {

    private val consoleLogRepository by inject<ConsoleLogRepository>()
    private val persistentState by inject<PersistentState>()
    companion object {
        const val MAX_TIME: Long = 3 // max time in hours to keep the console logs
    }
    override suspend fun doWork(): Result {
        // delete logs which are older than MAX_TIME hrs
        val threshold = TimeUnit.HOURS.toMillis(MAX_TIME)
        val currTime = System.currentTimeMillis()
        val time = currTime - threshold

        consoleLogRepository.deleteOldLogs(time)
        val startTime = consoleLogRepository.consoleLogStartTimestamp
        val lapsedTime = currTime - startTime
        // stop the console log if it exceeds max time and set the log level to ERROR
        // this is to avoid the console log from growing indefinitely
        // no need to reset the start timestamp/logger level if it is already set to ERROR or above
        if (lapsedTime > TimeUnit.MINUTES.toMillis(MAX_TIME) && Logger.uiLogLevel < Logger.LoggerLevel.ERROR.id) {
            consoleLogRepository.consoleLogStartTimestamp = 0
            Logger.uiLogLevel = Logger.LoggerLevel.ERROR.id
            GoVpnAdapter.setLogLevel(persistentState.goLoggerLevel.toInt(), Logger.uiLogLevel.toInt())
            Logger.i(LOG_BATCH_LOGGER, "console log purged, disabled as it exceeded max time of $MAX_TIME hrs")
        }
        Logger.v(LOG_BATCH_LOGGER, "purged console logs older than $MAX_TIME hrs, current time: $currTime, start time: $startTime, lapsed time: $lapsedTime")
        return Result.success()
    }

}
