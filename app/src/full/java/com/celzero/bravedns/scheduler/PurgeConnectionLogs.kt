/*
 * Copyright 2022 RethinkDNS and its authors
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

import Logger
import Logger.LOG_TAG_SCHEDULER
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.service.EventLogger
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Calendar

class PurgeConnectionLogs(val context: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(context, workerParameters), KoinComponent {

    private val refreshDatabase by inject<RefreshDatabase>()
    private val eventLogger by inject<EventLogger>()

    companion object {
        const val NUMBER_OF_DAYS_TO_PURGE = -7
        const val NUMBER_OF_DAYS_TO_PURGE_EVENTS = 4
    }

    override suspend fun doWork(): Result {
        Logger.d(LOG_TAG_SCHEDULER, "starting purge-database job")
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, NUMBER_OF_DAYS_TO_PURGE)
        val date = calendar.time.time
        Logger.i(LOG_TAG_SCHEDULER, "purging logs older than 7 days, date: $date")

        /**
         * purge logs older than 7 days (on version v053l, subject to change in later versions based
         * on user configuration) come up with user configuration to delete the user logs.(both
         * ConnectionTracker and DNSLogs.
         */
        refreshDatabase.purgeConnectionLogs(date)
        /**
         * purge event logs older than 4 days, can be changed based on user configuration in later
         * versions.
         */
         eventLogger.scheduleAutoPurge(NUMBER_OF_DAYS_TO_PURGE_EVENTS)

        return Result.success()
    }
}
