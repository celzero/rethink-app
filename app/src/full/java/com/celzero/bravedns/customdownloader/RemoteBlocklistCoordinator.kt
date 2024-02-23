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

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.download.BlocklistDownloadHelper
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants
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
            Log.e(
                LoggerConstants.LOG_TAG_DOWNLOAD,
                "Local blocklist download, received cancellation exception: ${ex.message}",
                ex
            )
        }
        return Result.failure()
    }

    private suspend fun downloadRemoteBlocklist(timestamp: Long): Boolean {
        Log.i(LoggerConstants.LOG_TAG_DOWNLOAD, "Download remote blocklist: $timestamp")

        val retrofit =
            RetrofitManager.getBlocklistBaseBuilder()
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        val retrofitInterface = retrofit.create(IBlocklistDownload::class.java)
        val response =
            retrofitInterface.downloadRemoteBlocklistFile(
                Constants.FILETAG_TEMP_DOWNLOAD_URL,
                persistentState.appVersion,
                ""
            )

        Log.i(
            LoggerConstants.LOG_TAG_DOWNLOAD,
            "Response received on remote blocklist request: ${response?.isSuccessful}"
        )

        return if (response?.isSuccessful == true) {
            val isDownloadSuccess = saveRemoteFile(response.body(), timestamp)
            isDownloadSuccess
        } else {
            Log.i(
                LoggerConstants.LOG_TAG_DOWNLOAD,
                "Remote blocklist download failure, call? ${response?.body()}, response: $response "
            )
            false
        }
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
            Log.w(
                LoggerConstants.LOG_TAG_DOWNLOAD,
                "could not create filetag.json at version $timestamp",
                e
            )
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
            Log.e(
                LoggerConstants.LOG_TAG_DOWNLOAD,
                "Could not create remote blocklist folder/file: $timestamp" + e.message,
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
