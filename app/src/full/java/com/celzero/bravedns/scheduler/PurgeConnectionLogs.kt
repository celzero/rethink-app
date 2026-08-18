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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_SCHEDULER
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.R
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.LogLifespan
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Calendar

class PurgeConnectionLogs(val context: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(context, workerParameters), KoinComponent {

    private val refreshDatabase by inject<RefreshDatabase>()
    private val eventLogger by inject<EventLogger>()

    private val persistentState by inject<PersistentState>()

    override suspend fun doWork(): Result {
        val logLifespan = persistentState.logLifespan
        val hoursToPurge = LogLifespan.getLifespanHours(logLifespan)

        Logger.d(LOG_TAG_SCHEDULER, "starting purge-database job")
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.HOUR_OF_DAY, -hoursToPurge)
        val date = calendar.time.time
        Logger.i(LOG_TAG_SCHEDULER, "purging logs older than $logLifespan, date: $date")

        /**
         * Purge logs older than log lifespan.
         * In the future, come up with user configuration to delete DNSLogs as well.
         */
        refreshDatabase.purgeConnectionLogs(date)
        /**
         * Purge event logs older than user-configured log lifespan.
         */
         eventLogger.scheduleAutoPurge(hoursToPurge)

        return Result.success()
    }
}
