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

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_FILE_COUNT
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DOWNLOAD
import com.celzero.bravedns.util.Utilities
import dnsx.Dnsx
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

/**
 * Class responsible for copying the files from External path to canonical path.
 * The worker will be triggered once the DownloadWatcher worker finishes its work.
 *
 * File handler should check if the files are available in the external file path.
 * If yes, will copy to the canonical path and will stop the worker.
 *
 * As of now the code is written only for the local block list copy.
 */

class FileHandleWorker(val context: Context, workerParameters: WorkerParameters) :
        Worker(context, workerParameters), KoinComponent {

    val persistentState by inject<PersistentState>()

    override fun doWork(): Result {
        try {
            val timestamp = inputData.getLong("blocklistDownloadInitiatedTime", 0L)
            if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "blocklistDownloadInitiatedTime - $timestamp")

            // invalid download initiated time
            if(timestamp == 0L) return Result.failure()

            // A file move from external file path to app data dir is preferred because it is an
            // atomic operation: but Android doesn't support move/rename across mount points.
            val response = copyFiles(context, timestamp)

            val outputData = workDataOf(DownloadConstants.OUTPUT_FILES to response)

            return if (response) Result.success(outputData)
            else Result.failure()

        } catch (e: Exception) {
            Log.e(LOG_TAG_DOWNLOAD,
                  "FileHandleWorker Error while moving files to canonical path ${e.message}", e)
        }
        return Result.failure()
    }

    private fun copyFiles(context: Context, timestamp: Long): Boolean {
        try {
            if (!BlocklistDownloadHelper.isDownloadComplete(context, timestamp.toString())) {
                return false
            }

            BlocklistDownloadHelper.deleteFromCanonicalPath(context)
            val dir = File(
                BlocklistDownloadHelper.getExternalFilePath(context, timestamp.toString()))
            if (!dir.isDirectory) {
                Log.w(LOG_TAG_DOWNLOAD,
                      "Abort: file download path ${dir.absolutePath} isn't a directory")
                return false
            }

            val children = dir.list()
            if (children.isNullOrEmpty()) {
                Log.w(LOG_TAG_DOWNLOAD, "Abort: ${dir.absolutePath} is empty directory")
                return false
            }

            for (i in children.indices) {
                val from = dir.absolutePath + File.separator + children[i]
                val to = context.filesDir.canonicalPath + File.separator + timestamp + File.separator + children[i]
                val result = Utilities.copy(from, to)

                if (!result) {
                    Log.w(LOG_TAG_DOWNLOAD, "Copy failed from: $from, to: $to")
                    return false
                }
            }
            val destinationDir = File("${context.filesDir.canonicalPath}/$timestamp")

            Log.i(LOG_TAG_DOWNLOAD,
                  "After copy, dest dir: $destinationDir, ${destinationDir.isDirectory}, ${destinationDir.list()?.size}")

            if (!destinationDir.isDirectory || destinationDir.list()?.size != LOCAL_BLOCKLIST_FILE_COUNT || !isDownloadValid(timestamp)) {
                return false
            }

            updatePersistenceOnCopySuccess(timestamp)
            BlocklistDownloadHelper.deleteOldFiles(context)
            return true

        } catch (e: Exception) {
            Log.e(LOG_TAG_DOWNLOAD, "AppDownloadManager Copy exception - ${e.message}", e)
        }
        return false
    }

    private fun updatePersistenceOnCopySuccess(timestamp: Long) {
        persistentState.blocklistDownloadTime = timestamp
        persistentState.blocklistEnabled = true
        persistentState.blocklistFilesDownloaded = true
    }

    /**
     * Post the check of number of files downloaded by the
     * download manager, need to validate the downloaded files.
     * As of now there is no checksum validation.
     * So validating the downloaded files by
     * create localBraveDNS object. If the object returned by the
     * Dnsx is not null then valid. Null/exception will be invalid.
     */
    private fun isDownloadValid(timestamp: Long): Boolean {
        try {
            val path: String = context.filesDir.canonicalPath + File.separator + timestamp
            val braveDNS = Dnsx.newBraveDNSLocal(path + Constants.FILE_TD_FILE,
                                                 path + Constants.FILE_RD_FILE,
                                                 path + Constants.FILE_BASIC_CONFIG,
                                                 path + Constants.FILE_TAG_NAME)
            if (DEBUG) Log.d(LOG_TAG_DOWNLOAD,
                             "AppDownloadManager isDownloadValid? ${braveDNS != null}")
            return braveDNS != null
        } catch (e: Exception) {
            Log.e(LOG_TAG_DOWNLOAD, "AppDownloadManager isDownloadValid exception - ${e.message}",
                  e)
        }
        return false
    }

}
