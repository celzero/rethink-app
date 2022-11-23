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

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.download.BlocklistDownloadHelper
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_SCHEDULER
import com.celzero.bravedns.util.Utilities
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class BlocklistUpdateCheckJob(val context: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(context, workerParameters), KoinComponent {

    private val persistentState by inject<PersistentState>()

    override suspend fun doWork(): Result {
        Log.i(LOG_TAG_SCHEDULER, "starting blocklist update check job")

        if (!Utilities.isPlayStoreFlavour()) {
            isDownloadRequired(
                persistentState.localBlocklistTimestamp,
                AppDownloadManager.DownloadType.LOCAL
            )
        }
        isDownloadRequired(
            persistentState.remoteBlocklistTimestamp,
            AppDownloadManager.DownloadType.REMOTE
        )
        return Result.success()
    }

    private suspend fun isDownloadRequired(timestamp: Long, type: AppDownloadManager.DownloadType) {
        val response =
            BlocklistDownloadHelper.checkBlocklistUpdate(
                timestamp,
                persistentState.appVersion,
                retryCount = 0
            )
                ?: return

        val updatableTs = BlocklistDownloadHelper.getDownloadableTimestamp(response)
        if (response.update && updatableTs > timestamp) {
            setUpdatableTimestamp(updatableTs, type)
        } else {
            // no-op
        }
    }

    private fun setUpdatableTimestamp(timestamp: Long, type: AppDownloadManager.DownloadType) {
        if (type.isLocal()) {
            persistentState.newestLocalBlocklistTimestamp = timestamp
        } else {
            persistentState.newestRemoteBlocklistTimestamp = timestamp
        }
    }
}
