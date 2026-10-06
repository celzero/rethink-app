/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.scheduler

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_BATCH_LOGGER
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.database.ConsoleLogRepository
import com.celzero.bravedns.database.RpnLogRepository
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.PcapMode
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

class PurgeConsoleLogsAndPcap(val context: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(context, workerParameters), KoinComponent {

    private val consoleLogRepository by inject<ConsoleLogRepository>()
    private val rpnLogRepository by inject<RpnLogRepository>()
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    companion object {
        const val MAX_TIME: Long = 3 // max time in hours to keep the console logs
    }
    override suspend fun doWork(): Result {
        // delete logs which are older than MAX_TIME hrs
        val threshold = TimeUnit.HOURS.toMillis(MAX_TIME)
        val currTime = System.currentTimeMillis()
        val time = currTime - threshold

        consoleLogRepository.deleteOldLogs(time)
        // rpn logs share the same retention policy as console logs
        rpnLogRepository.deleteOldLogs(time)
        val startTime = consoleLogRepository.consoleLogStartTimestamp
        val lapsedTime = currTime - startTime
        // stop the console log if it exceeds max time and set the log level to ERROR
        // this is to avoid the console log from growing indefinitely
        if (lapsedTime > threshold && Logger.uiLogLevel < Logger.LoggerLevel.ERROR.id) {
            consoleLogRepository.consoleLogStartTimestamp = 0
            Logger.uiLogLevel = Logger.LoggerLevel.ERROR.id
            GoVpnAdapter.setLogLevel(persistentState.goLoggerLevel.toInt(), Logger.uiLogLevel.toInt(), persistentState.includeFileTrace)
            Logger.i(LOG_BATCH_LOGGER, "console log purged, disabled as it exceeded max time of $MAX_TIME hrs")
        }
        stopPcapIfThresholdMet(currTime, threshold)
        Logger.v(LOG_BATCH_LOGGER, "purged console logs older than $MAX_TIME hrs, current time: $currTime, start time: $startTime, lapsed time: $lapsedTime")
        return Result.success()
    }

    private suspend fun stopPcapIfThresholdMet(currTime: Long, threshold: Long) {
        if (PcapMode.getPcapType(persistentState.pcapMode) != PcapMode.EXTERNAL_FILE) {
            return
        }
        val pcapStartTime = persistentState.pcapStartTime
        if (pcapStartTime <= 0 || currTime - pcapStartTime <= threshold) {
            return
        }
        // resetting the pref triggers the vpn service to disable pcap on the tunnel
        appConfig.setPcap(PcapMode.NONE.id)
        Logger.i(LOG_BATCH_LOGGER, "pcap purged, disabled as it exceeded max time of $MAX_TIME hrs")
    }

}
