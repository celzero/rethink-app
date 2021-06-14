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

import android.app.DownloadManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG_DOWNLOAD
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The download watcher  - Worker initiated from AppDownloadManager class.
 * The worker will be listening for the status of the download for the download ID's
 * stored in shared preference.
 * Once the download is completed, the Worker will send a Result.success().
 * Else, the Result.retry() will be triggered to check again.
 */
class DownloadWatcher(val context: Context, workerParameters: WorkerParameters) : Worker(context, workerParameters), KoinComponent {

    val persistentState by inject<PersistentState>()

    override fun doWork(): Result {

        val startTime = persistentState.workManagerStartTime
        val currentTime = SystemClock.elapsedRealtime()
        Log.d(LOG_TAG_DOWNLOAD, "AppDownloadManager - $startTime, $currentTime")
        if (currentTime - startTime > Constants.WORK_MANAGER_TIMEOUT) {
            persistentState.workManagerStartTime = 0
            return Result.failure()
        }

        when (checkForDownload(context)) {
            0 -> {
                return Result.retry()
            }
            -1 -> {
                return Result.failure()
            }
            1 -> {
                return Result.success()
            }
        }

        return Result.failure()
    }

    private fun checkForDownload(context: Context): Int {
        //Check for the download success from the receiver
        persistentState.downloadIDs.forEach { downloadID ->
            val query = DownloadManager.Query()
            query.setFilterById(downloadID.toLong())
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val cursor = downloadManager.query(query)
            if (cursor == null) {
                Log.i(LOG_TAG_DOWNLOAD, "status is $downloadID cursor null")
                return -1
            }
            if (cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS))
                if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "onReceive status $status $downloadID")
                if (status == DownloadManager.STATUS_RUNNING) {
                } else if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    if (persistentState.downloadIDs.contains(downloadID)) {
                        persistentState.downloadIDs = persistentState.downloadIDs.minusElement(downloadID)
                    }
                    if (persistentState.downloadIDs.isEmpty()) {
                        cursor.close()
                        return 1
                    }
                } else if (status == DownloadManager.STATUS_FAILED) {
                    cursor.close()
                    return -1
                }
            } else {
                if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "moveToFirst is false")
                cursor.close()
                return -1
            }
            cursor.close()
        }
        return 0
    }
}