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
package com.celzero.bravedns.customdownloader

import Logger
import Logger.LOG_TAG_DOWNLOAD
import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.download.BlocklistDownloadHelper
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.RemoteFileTagUtil
import com.celzero.bravedns.util.Utilities
import com.google.gson.JsonObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class RemoteBlocklistCoordinator(val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams), KoinComponent {

    private val persistentState by inject<PersistentState>()

    companion object {
        const val REMOTE_DOWNLOAD_WORKER = "CUSTOM_DOWNLOAD_WORKER_REMOTE"
        private val BLOCKLIST_DOWNLOAD_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(10)
    }

    override suspend fun doWork(): Result {
        Logger.i(LOG_TAG_DOWNLOAD, "Remote blocklist download worker started")
        try {
            val startTime = inputData.getLong("workerStartTime", 0)
            val timestamp = inputData.getLong("blocklistTimestamp", 0)

            if (SystemClock.elapsedRealtime() - startTime > BLOCKLIST_DOWNLOAD_TIMEOUT_MS) {
                return Result.failure()
            }

            val downloadStatus = downloadRemoteBlocklist(timestamp)
            // reset updatable time stamp
            if (downloadStatus) {
                // update the download related persistence status on download success
                updatePersistenceOnCopySuccess(timestamp)
                BlocklistDownloadHelper.deleteBlocklistResidue(
                    context,
                    Constants.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                    timestamp
                )
            } else {
                // reset the remote blocklist timestamp, a copy of remote blocklist is already
                // available in asset folder (go back to that version)
                RemoteFileTagUtil.moveFileToLocalDir(context.applicationContext, persistentState)
            }

            return when (downloadStatus) {
                false -> {
                    Result.failure()
                }
                true -> {
                    Result.success()
                }
            }
        } catch (ex: CancellationException) {
            Logger.e(
                LOG_TAG_DOWNLOAD,
                "Local blocklist download, received cancellation exception: ${ex.message}",
                ex
            )
        }
        return Result.failure()
    }

    private suspend fun downloadRemoteBlocklist(timestamp: Long, retryCount: Int = 0): Boolean {
        Logger.i(LOG_TAG_DOWNLOAD, "Download remote blocklist: $timestamp")
        try {
            val retrofit =
                RetrofitManager.getBlocklistBaseBuilder(retryCount)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
            val retrofitInterface = retrofit.create(IBlocklistDownload::class.java)
            val response =
                retrofitInterface.downloadRemoteBlocklistFile(
                    Constants.FILETAG_TEMP_DOWNLOAD_URL,
                    persistentState.appVersion,
                    ""
                )

            Logger.i(
                LOG_TAG_DOWNLOAD,
                "response rcvd for remote blocklist, res: ${response?.isSuccessful}"
            )

            if (response?.isSuccessful == true) {
                return saveRemoteFile(response.body(), timestamp)
            }
        } catch (ex: Exception) {
            Logger.e(LOG_TAG_DOWNLOAD, "err in downloadRemoteBlocklist: ${ex.message}", ex)
        }
        return if (isRetryRequired(retryCount)) {
            Logger.i(LOG_TAG_DOWNLOAD, "retrying the downloadRemoteBlocklist")
            downloadRemoteBlocklist(timestamp, retryCount + 1)
        } else {
            Logger.i(LOG_TAG_DOWNLOAD, "retry count exceeded, returning null")
            false
        }
    }

    private fun isRetryRequired(retryCount: Int): Boolean {
        return retryCount < RetrofitManager.Companion.OkHttpDnsType.entries.size - 1
    }

    private suspend fun saveRemoteFile(jsonObject: JsonObject?, timestamp: Long): Boolean {
        try {
            val filetag = makeFile(timestamp) ?: return false

            filetag.writeText(jsonObject.toString())

            // write the file tag json file into database
            return RethinkBlocklistManager.readJson(
                context,
                RethinkBlocklistManager.DownloadType.REMOTE,
                timestamp
            )
        } catch (e: IOException) {
            Logger.w(LOG_TAG_DOWNLOAD, "could not create filetag.json at version $timestamp", e)
        }
        return false
    }

    private fun makeFile(timestamp: Long): File? {
        try {
            val dir =
                Utilities.blocklistDir(
                    context,
                    Constants.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                    timestamp
                ) ?: return null

            if (!dir.exists()) {
                dir.mkdirs()
            }
            val filePath = File(dir.absolutePath + Constants.ONDEVICE_BLOCKLIST_FILE_TAG)
            if (!filePath.exists()) {
                filePath.createNewFile()
            }
            return filePath
        } catch (e: IOException) {
            Logger.e(
                LOG_TAG_DOWNLOAD,
                "err creating remote blocklist, ts: $timestamp" + e.message,
                e
            )
        }
        return null
    }

    private fun updatePersistenceOnCopySuccess(timestamp: Long) {
        persistentState.remoteBlocklistTimestamp = timestamp
        persistentState.newestRemoteBlocklistTimestamp = Constants.INIT_TIME_MS
    }
}
