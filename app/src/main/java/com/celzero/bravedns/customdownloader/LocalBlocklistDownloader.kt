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
package com.celzero.bravedns.customdownloader

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.celzero.bravedns.customdownloader.ConnectionCheckHelper.downloadIds
import com.celzero.bravedns.service.PersistentState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

class LocalBlocklistDownloader(val context: Context, workerParams: WorkerParameters) :
        Worker(context, workerParams), KoinComponent {

    val persistentState by inject<PersistentState>()

    // various download status used as part of Work manager.
    enum class DownloadManagerStatus {
        FAILURE, SUCCESS, IN_PROGRESS
    }

    companion object {
        val BLOCKLIST_DOWNLOAD_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(40)
        const val CUSTOM_DOWNLOAD = "CUSTOM_DOWNLOAD_WORKER"
    }

    override fun doWork(): Result {
        // start the download based number of files in blocklist download
        val startTime = inputData.getLong("workerStartTime", 0)
        val timestamp = inputData.getLong("blocklistTimestamp", 0)

        if (SystemClock.elapsedRealtime() - startTime > BLOCKLIST_DOWNLOAD_TIMEOUT_MS) {
            return Result.failure()
        }

        return when (checkForDownload()) {
            DownloadManagerStatus.IN_PROGRESS -> {
                Result.retry()
            }
            DownloadManagerStatus.FAILURE -> {
                Result.failure()
            }
            DownloadManagerStatus.SUCCESS -> {
                // update the download related persistence status on download success
                updatePersistenceOnCopySuccess(timestamp)
                clearDownloadList()
                Result.success()
            }
        }
    }

    private fun clearDownloadList() {
        downloadIds.clear()
    }

    private fun checkForDownload(): DownloadManagerStatus {

        downloadIds.forEach {
            when (it.value) {
                ConnectionCheckHelper.DownloadStatus.PAUSED -> {
                    return DownloadManagerStatus.IN_PROGRESS
                }
                ConnectionCheckHelper.DownloadStatus.RUNNING -> {
                    return DownloadManagerStatus.IN_PROGRESS
                }
                ConnectionCheckHelper.DownloadStatus.FAILED -> {
                    return DownloadManagerStatus.FAILURE
                }
                ConnectionCheckHelper.DownloadStatus.SUCCESSFUL -> {
                    // no-op
                }
            }
        }

        return DownloadManagerStatus.SUCCESS
    }

    private fun updatePersistenceOnCopySuccess(timestamp: Long) {
        persistentState.localBlocklistTimestamp = timestamp
        persistentState.blocklistEnabled = true
    }

}
