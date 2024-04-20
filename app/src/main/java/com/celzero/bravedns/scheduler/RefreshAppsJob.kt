/*
 * Copyright 2021 RethinkDNS and its authors
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
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class RefreshAppsJob(val context: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(context, workerParameters), KoinComponent {

    private val refreshDatabase by inject<RefreshDatabase>()

    override suspend fun doWork(): Result {
        Logger.d(LOG_TAG_SCHEDULER, "starting refresh-database job")
        refreshDatabase.refresh(RefreshDatabase.ACTION_REFRESH_AUTO)
        return Result.success()
    }
}
