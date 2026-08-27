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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_DOWNLOAD
import android.app.DownloadManager
import android.content.Context
import android.os.SystemClock
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
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
    private val persistentState by inject<PersistentState>()

    /**
     * Pure interpretation of a DownloadManager row into a terminal/continue outcome.
     * Kept as an explicit, testable function so unknown statuses fail fast instead of
     * being silently retried forever (see [classify]).
     */
    internal object Interpreter {
        const val CONTINUE = 0
        const val SUCCESS = 1
        const val FAILURE = 2

        fun classify(status: Int, reason: Int): Int {
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> SUCCESS
                DownloadManager.STATUS_FAILED -> FAILURE
                // Still in flight (or paused); the Worker will retry and re-check.
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED -> CONTINUE
                // Any other value (0, -1, or a value not recognised by this build) is
                // treated as a terminal failure so the Worker cannot loop forever.
                else -> FAILURE
            }
        }

        fun reasonToResId(reason: Int): Int {
            return when (reason) {
                DownloadManager.ERROR_CANNOT_RESUME,
                DownloadManager.ERROR_HTTP_DATA_ERROR,
                DownloadManager.ERROR_TOO_MANY_REDIRECTS,
                DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> R.string.download_err_network
                DownloadManager.ERROR_DEVICE_NOT_FOUND -> R.string.download_err_system_manager
                DownloadManager.ERROR_FILE_ALREADY_EXISTS,
                DownloadManager.ERROR_FILE_ERROR -> R.string.download_err_storage
                DownloadManager.ERROR_INSUFFICIENT_SPACE -> R.string.download_err_storage
                DownloadManager.ERROR_UNKNOWN -> R.string.download_err_internal
                else -> R.string.download_err_internal
            }
        }
    }

    override fun doWork(): Result {
        Logger.i(LOG_TAG_DOWNLOAD, "start download watcher, checking for download status")
        val startTime = inputData.getLong("workerStartTime", 0)
        downloadIds = inputData.getLongArray("downloadIds")?.toMutableList()
        Logger.d(LOG_TAG_DOWNLOAD, "AppDownloadManager: $startTime, $downloadIds")

        if (downloadIds == null || downloadIds?.isEmpty() == true) {
            persistentState.lastDownloadFailureReason = context.getString(R.string.download_err_system_manager)
            return Result.failure()
        }

        if (SystemClock.elapsedRealtime() - startTime > ONDEVICE_BLOCKLIST_DOWNLOAD_TIMEOUT_MS) {
            persistentState.lastDownloadFailureReason = context.getString(R.string.download_err_network)
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
                // Clear the stored download IDs on successful completion and reset any
                // previously-recorded failure reason so it cannot leak into a later Error.
                clearStoredDownloadIds()
                persistentState.lastDownloadFailureReason = ""
                return Result.success()
            }
        }

        return Result.failure()
    }

    private fun clearStoredDownloadIds() {
        try {
            persistentState.androidDownloadManagerIds = ""
            Logger.i(LOG_TAG_DOWNLOAD, "cleared stored andr-down-mgr ids")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_DOWNLOAD, "err clearing stored download ids", e)
        }
    }

    private fun checkForDownload(context: Context, downloadIds: MutableList<Long>?): Int {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        var totalBytes = 0L
        var downloadedBytes = 0L
        var anyFailed = false
        var failureReason = ""

        val downloadIdsIterator = downloadIds?.iterator()

        while (downloadIdsIterator?.hasNext() == true) {
            val downloadID = downloadIdsIterator.next()
            val query = DownloadManager.Query()
            query.setFilterById(downloadID)
            val cursor = downloadManager.query(query)
            if (cursor == null) {
                Logger.i(LOG_TAG_DOWNLOAD, "status is $downloadID cursor null")
                anyFailed = true
                failureReason = context.getString(R.string.download_err_system_manager)
                break
            }

            try {
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                val downloadedIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)

                if (statusIdx == -1) {
                    Logger.i(LOG_TAG_DOWNLOAD, "status is $downloadID column index -1")
                    anyFailed = true
                    failureReason = context.getString(R.string.download_err_system_manager)
                    break
                }

                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(statusIdx)
                    val reason = if (reasonIdx != -1) cursor.getInt(reasonIdx) else -1
                    val downloaded = if (downloadedIdx != -1) cursor.getLong(downloadedIdx) else 0L
                    val total = if (totalIdx != -1) cursor.getLong(totalIdx) else -1L

                    Logger.d(LOG_TAG_DOWNLOAD, "onReceive status $status $downloadID, reason $reason, progress $downloaded/$total")

                    if (total > 0) {
                        totalBytes += total
                        downloadedBytes += downloaded
                    }

                    when (Interpreter.classify(status, reason)) {
                        Interpreter.SUCCESS -> {
                            downloadIdsIterator.remove()
                        }
                        Interpreter.FAILURE -> {
                            Logger.d(
                                LOG_TAG_DOWNLOAD,
                                "download status failure for $downloadID, $reason"
                            )
                            anyFailed = true
                            failureReason = context.getString(Interpreter.reasonToResId(reason))
                            break
                        }
                        Interpreter.CONTINUE -> {
                            // still downloading / paused / pending; keep waiting
                        }
                    }
                } else {
                    Logger.d(LOG_TAG_DOWNLOAD, "cursor empty")
                    anyFailed = true
                    failureReason = context.getString(R.string.download_err_system_manager)
                    break
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_DOWNLOAD, "failure download: ${e.message}", e)
                anyFailed = true
                failureReason = context.getString(R.string.download_err_internal)
                break
            } finally {
                cursor.close()
            }
        }

        if (anyFailed) {
            persistentState.lastDownloadFailureReason = failureReason
            return DOWNLOAD_FAILURE
        }

        // send the status as success when the download ids are cleared
        if (downloadIds?.isEmpty() == true) {
            Logger.i(LOG_TAG_DOWNLOAD, "files downloaded successfully")
            return DOWNLOAD_SUCCESS
        }

        // Update progress if possible
        if (totalBytes > 0) {
            val progress = (downloadedBytes * 100 / totalBytes).toInt()
            setProgressAsync(workDataOf("progress" to progress))
        }

        // occasionally, the download-manager observer fires without a download having
        // been enqueued and download-ids populated into persistent-state, which keep in
        // mind, is also eventually consistent with its state propagation. In this case,
        // count(download-ids) is zero. So: Ask for a retry regardless of the download-status
        return DOWNLOAD_RETRY
    }
}
