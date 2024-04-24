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
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.celzero.bravedns.download.BlocklistDownloadHelper.Companion.deleteOldFiles
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.calculateMd5
import com.celzero.bravedns.util.Utilities.getTagValueFromJson
import com.celzero.bravedns.util.Utilities.hasLocalBlocklists
import com.celzero.bravedns.util.Utilities.localBlocklistFileDownloadPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/**
 * Class responsible for copying the files from External path to canonical path. The worker will be
 * triggered once the DownloadWatcher worker finishes its work.
 *
 * File handler should check if the files are available in the external file path. If yes, will copy
 * to the canonical path and will stop the worker.
 *
 * As of now the code is written only for the local block list copy.
 */
class FileHandleWorker(val context: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(context, workerParameters), KoinComponent {

    val persistentState by inject<PersistentState>()

    override suspend fun doWork(): Result {
        try {
            val timestamp = inputData.getLong("blocklistDownloadInitiatedTime", Long.MIN_VALUE)
            Logger.d(LOG_TAG_DOWNLOAD, "blocklistDownloadInitiatedTime - $timestamp")

            // invalid download initiated time
            if (timestamp <= INIT_TIME_MS) {
                Logger.w(LOG_TAG_DOWNLOAD, "timestamp version invalid $timestamp")
                return Result.failure()
            }

            // A file move from external file path to app data dir is preferred because it is an
            // atomic operation: but Android doesn't support move/rename across mount points.
            val response = copyFiles(context, timestamp)

            val outputData = workDataOf(DownloadConstants.OUTPUT_FILES to response)

            return if (response) Result.success(outputData) else Result.failure()
        } catch (e: Exception) {
            Logger.e(
                LOG_TAG_DOWNLOAD,
                "FileHandleWorker Error while moving files to canonical path ${e.message}",
                e
            )
        }
        return Result.failure()
    }

    private suspend fun copyFiles(context: Context, timestamp: Long): Boolean {
        try {
            if (!BlocklistDownloadHelper.isDownloadComplete(context, timestamp)) {
                return false
            }

            BlocklistDownloadHelper.deleteFromCanonicalPath(context)
            val dir =
                File(BlocklistDownloadHelper.getExternalFilePath(context, timestamp.toString()))
            if (!dir.isDirectory) {
                Logger.w(
                    LOG_TAG_DOWNLOAD,
                    "Abort: file download path ${dir.absolutePath} isn't a directory"
                )
                return false
            }

            val children = dir.list()
            if (children.isNullOrEmpty()) {
                Logger.w(LOG_TAG_DOWNLOAD, "Abort: ${dir.absolutePath} is empty directory")
                return false
            }

            // In case of failure, all files in
            // 1. "from" dir will be deleted by deleteFromExternalDir
            // 2. "to" dir will be delete by deleteFromCanonicalPath
            // during the next download downloadLocalBlocklist
            for (i in children.indices) {
                val from = dir.absolutePath + File.separator + children[i]
                val to = localBlocklistFileDownloadPath(context, children[i], timestamp)
                if (to.isEmpty()) {
                    Logger.w(LOG_TAG_DOWNLOAD, "Copy failed from $from, to: $to")
                    return false
                }
                val result = Utilities.copy(from, to)

                if (!result) {
                    Logger.w(LOG_TAG_DOWNLOAD, "Copy failed from: $from, to: $to")
                    return false
                }
            }
            val destinationDir =
                File(
                    "${context.filesDir.canonicalPath}${File.separator}$timestamp${File.separator}"
                )

            Logger.i(
                LOG_TAG_DOWNLOAD,
                "After copy, dest dir: $destinationDir, ${destinationDir.isDirectory}, ${destinationDir.list()?.count()}"
            )

            if (!hasLocalBlocklists(context, timestamp) || !isDownloadValid(timestamp)) {
                return false
            }

            val result = updateTagsToDb(timestamp)

            updatePersistenceOnCopySuccess(timestamp)
            deleteOldFiles(context, timestamp, RethinkBlocklistManager.DownloadType.LOCAL)
            return true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_DOWNLOAD, "AppDownloadManager Copy exception: ${e.message}", e)
        }
        return false
    }

    private suspend fun updateTagsToDb(timestamp: Long): Boolean {
        return RethinkBlocklistManager.readJson(
            context,
            RethinkBlocklistManager.DownloadType.LOCAL,
            timestamp
        )
    }

    private fun updatePersistenceOnCopySuccess(timestamp: Long) {
        ui {
            persistentState.localBlocklistTimestamp = timestamp
            persistentState.newestLocalBlocklistTimestamp = INIT_TIME_MS
            persistentState.blocklistEnabled = true
        }
    }

    /**
     * Post the check of number of files downloaded by the download manager, need to validate the
     * downloaded files. As of now there is no checksum validation. So validating the downloaded
     * files by create localBraveDNS object. If the object returned by the Dnsx is not null then
     * valid. Null/exception will be invalid.
     */
    private fun isDownloadValid(timestamp: Long): Boolean {
        try {
            val path: String =
                Utilities.blocklistDownloadBasePath(
                    context,
                    LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                    timestamp
                )
            val tdmd5 = calculateMd5(path + Constants.ONDEVICE_BLOCKLIST_FILE_TD)
            val rdmd5 = calculateMd5(path + Constants.ONDEVICE_BLOCKLIST_FILE_RD)
            val remoteTdmd5 =
                getTagValueFromJson(path + Constants.ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG, "tdmd5")
            val remoteRdmd5 =
                getTagValueFromJson(path + Constants.ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG, "rdmd5")
            Logger.d(
                LOG_TAG_DOWNLOAD,
                "tdmd5: $tdmd5, rdmd5: $rdmd5, remotetd: $remoteTdmd5, remoterd: $remoteRdmd5"
            )
            val isDownloadValid = tdmd5 == remoteTdmd5 && rdmd5 == remoteRdmd5
            Logger.i(LOG_TAG_DOWNLOAD, "AppDownloadManager, isDownloadValid? $isDownloadValid")
            return isDownloadValid
        } catch (e: Exception) {
            Logger.e(LOG_TAG_DOWNLOAD, "AppDownloadManager, isDownloadValid err: ${e.message}", e)
        }
        return false
    }

    private fun ui(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.Main).launch { f() }
    }
}
