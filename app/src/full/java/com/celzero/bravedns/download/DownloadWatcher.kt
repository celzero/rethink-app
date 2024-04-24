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
package com.celzero.bravedns.download

import Logger
import Logger.LOG_TAG_DOWNLOAD
import android.app.DownloadManager
import android.content.Context
import android.os.SystemClock
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.koin.core.component.KoinComponent
import java.util.concurrent.TimeUnit

/**
 * The download watcher - Worker initiated from AppDownloadManager class. The worker will be
 * listening for the status of the download for the download ID's stored in shared preference. Once
 * the download is completed, the Worker will send a Result.success(). Else, the Result.retry() will
 * be triggered to check again.
 */
class DownloadWatcher(val context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters), KoinComponent {

    companion object {
        // Maximum time out for the DownloadManager to wait for download of local blocklist.
        // The time out value is set as 40 minutes.
        val ONDEVICE_BLOCKLIST_DOWNLOAD_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(40)

        // various download status used as part of Work manager. see
        // DownloadWatcher#checkForDownload()
        const val DOWNLOAD_FAILURE = -1
        const val DOWNLOAD_SUCCESS = 1
        const val DOWNLOAD_RETRY = 0
    }

    private var downloadIds: MutableList<Long>? = mutableListOf()

    override fun doWork(): Result {
        Logger.i(LOG_TAG_DOWNLOAD, "start download watcher, checking for download status")
        val startTime = inputData.getLong("workerStartTime", 0)
        downloadIds = inputData.getLongArray("downloadIds")?.toMutableList()
        Logger.d(LOG_TAG_DOWNLOAD, "AppDownloadManager: $startTime, $downloadIds")

        if (downloadIds == null || downloadIds?.isEmpty() == true) return Result.failure()

        if (SystemClock.elapsedRealtime() - startTime > ONDEVICE_BLOCKLIST_DOWNLOAD_TIMEOUT_MS) {
            return Result.failure()
        }

        when (checkForDownload(context, downloadIds)) {
            DOWNLOAD_RETRY -> {
                return Result.retry()
            }
            DOWNLOAD_FAILURE -> {
                return Result.failure()
            }
            DOWNLOAD_SUCCESS -> {
                return Result.success()
            }
        }

        return Result.failure()
    }

    private fun checkForDownload(context: Context, downloadIds: MutableList<Long>?): Int {
        // check for the download success from the receiver
        val downloadIdsIterator = downloadIds?.iterator()

        while (downloadIdsIterator?.hasNext() == true) {
            val downloadID = downloadIdsIterator.next()
            val query = DownloadManager.Query()
            query.setFilterById(downloadID)
            val downloadManager =
                context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val cursor = downloadManager.query(query)
            if (cursor == null) {
                Logger.i(LOG_TAG_DOWNLOAD, "status is $downloadID cursor null")
                return DOWNLOAD_FAILURE
            }

            try {
                val columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                if (columnIndex == -1) {
                    Logger.i(LOG_TAG_DOWNLOAD, "status is $downloadID column index -1")
                    return DOWNLOAD_FAILURE
                }
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(columnIndex)

                    Logger.d(LOG_TAG_DOWNLOAD, "onReceive status $status $downloadID")

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        downloadIdsIterator.remove()
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        val reason = cursor.getInt(columnIndex)
                        Logger.d(
                            LOG_TAG_DOWNLOAD,
                            "download status failure for $downloadID, $reason"
                        )
                        return DOWNLOAD_FAILURE
                    }
                } else {
                    Logger.d(LOG_TAG_DOWNLOAD, "cursor empty")
                    return DOWNLOAD_FAILURE
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_DOWNLOAD, "failure download: ${e.message}", e)
            } finally {
                cursor.close()
            }
        }

        // send the status as success when the download ids are cleared
        if (downloadIds?.isEmpty() == true) {
            Logger.i(LOG_TAG_DOWNLOAD, "files downloaded successfully")
            return DOWNLOAD_SUCCESS
        }

        // occasionally, the download-manager observer fires without a download having
        // been enqueued and download-ids populated into persistent-state, which keep in
        // mind, is also eventually consistent with its state propagation. In this case,
        // count(download-ids) is zero. So: Ask for a retry regardless of the download-status
        return DOWNLOAD_RETRY
    }
}
