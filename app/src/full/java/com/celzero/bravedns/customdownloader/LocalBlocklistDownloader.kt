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
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.celzero.bravedns.customdownloader.ConnectivityHelper.downloadIds
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

class LocalBlocklistDownloader(val context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams), KoinComponent {

    val persistentState by inject<PersistentState>()

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
            AppDownloadManager.DownloadManagerStatus.FAILURE -> {
                Result.failure()
            }
            AppDownloadManager.DownloadManagerStatus.SUCCESS -> {
                // update the download related persistence status on download success
                updatePersistenceOnCopySuccess(timestamp)
                clearDownloadList()
                Result.success()
            }
            else -> {
                Result.retry()
            }
        }
    }

    private fun clearDownloadList() {
        downloadIds.clear()
    }

    private fun checkForDownload(): AppDownloadManager.DownloadManagerStatus {

        downloadIds.forEach {
            when (it.value) {
                ConnectivityHelper.DownloadStatus.PAUSED -> {
                    return AppDownloadManager.DownloadManagerStatus.IN_PROGRESS
                }
                ConnectivityHelper.DownloadStatus.RUNNING -> {
                    return AppDownloadManager.DownloadManagerStatus.IN_PROGRESS
                }
                ConnectivityHelper.DownloadStatus.FAILED -> {
                    return AppDownloadManager.DownloadManagerStatus.FAILURE
                }
                ConnectivityHelper.DownloadStatus.SUCCESSFUL -> {
                    // no-op
                }
            }
        }

        return AppDownloadManager.DownloadManagerStatus.SUCCESS
    }

    private fun updatePersistenceOnCopySuccess(timestamp: Long) {
        // issue fix: #575, chosen blocklists are not updating for first time
        // the below operations need to be completed before returning the value
        // from the worker
        ui {
            persistentState.localBlocklistTimestamp = timestamp
            persistentState.blocklistEnabled = true
            // reset updatable time stamp
            persistentState.newestLocalBlocklistTimestamp = INIT_TIME_MS
            // write the file tag json file into database
            io {
                RethinkBlocklistManager.readJson(
                    context, RethinkBlocklistManager.DownloadType.LOCAL,
                    timestamp
                )
            }
        }
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            f()
        }
    }

    private fun ui(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            f()
        }
    }

}
