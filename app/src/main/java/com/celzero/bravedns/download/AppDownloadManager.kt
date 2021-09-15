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
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.work.*
import com.celzero.bravedns.download.DownloadConstants.Companion.DOWNLOAD_TAG
import com.celzero.bravedns.download.DownloadConstants.Companion.FILE_TAG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLISTS
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DOWNLOAD
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Generic class responsible for downloading the block list for both remote and local.
 * As of now, the download manager will download the local blocklist and initiate thw workers
 * to listen for the download complete and for copying the files from external to canonical path.
 * TODO remote blocklist - implementation pending.
 */
class AppDownloadManager(private val context: Context) {

    private lateinit var downloadManager: DownloadManager
    private lateinit var downloadReference: LongArray

    /**
     * Responsible for downloading the local blocklist files.
     * For local blocklist, we need filetag.json, basicconfig.json, rd.txt and td.txt
     * Once the download of all the files are completed.
     * Calls the copy method.
     */
    fun downloadLocalBlocklist(timestamp: Long) {
        purge(context, timestamp)

        downloadReference = LongArray(Constants.ONDEVICE_BLOCKLISTS.size)
        ONDEVICE_BLOCKLISTS.forEachIndexed { i, it ->
            val fileName = it.filename
            if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "v: ($timestamp), f: $fileName, u: $it.url")
            downloadReference[i] = enqueueDownload(it.url, fileName, timestamp.toString())
        }
        initiateDownloadStatusCheck(timestamp)
    }

    private fun initiateDownloadStatusCheck(timestamp: Long) {
        WorkManager.getInstance(context).pruneWork()

        val data = Data.Builder()
        data.putLong("workerStartTime", SystemClock.elapsedRealtime())
        data.putLongArray("downloadIds", downloadReference)

        val downloadWatcher = OneTimeWorkRequestBuilder<DownloadWatcher>().setInputData(
            data.build()).setBackoffCriteria(BackoffPolicy.LINEAR,
                                             OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                                             TimeUnit.MILLISECONDS).addTag(
            DOWNLOAD_TAG).setInitialDelay(10, TimeUnit.SECONDS).build()

        val timestampWorkerData = workDataOf("blocklistDownloadInitiatedTime" to timestamp)

        val fileHandler = OneTimeWorkRequestBuilder<FileHandleWorker>().setInputData(
            timestampWorkerData).setBackoffCriteria(BackoffPolicy.LINEAR,
                                                    OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                                                    TimeUnit.MILLISECONDS).addTag(FILE_TAG).build()

        WorkManager.getInstance(context).beginWith(downloadWatcher).then(fileHandler).enqueue()

    }

    /**
     * TODO
     * Updates the values for the local download.
     * The observers in the UI will reflect the download status.
     */
    /*private fun updateLocalUIValues(isDownloadSuccess: Boolean) {

    }*/

    /**
     * TODO
     * Updates the values for the Remote download.
     * The observers in the UI will reflect the download status.
     */
    /*private fun updateRemoteUIValues(isDownloadSuccess: Boolean) {

    }*/

    /**
     * Init for downloads.
     * Delete all the old files which are available in the download path.
     * Handles are the preliminary check before initiating the download.
     */
    private fun purge(context: Context, timestamp: Long) {
        downloadReference = LongArray(ONDEVICE_BLOCKLISTS.size)
        BlocklistDownloadHelper.deleteOldFiles(context, timestamp)
    }

    private fun enqueueDownload(url: String, fileName: String, timestamp: String): Long {
        downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadUri = Uri.parse(url)
        val request = DownloadManager.Request(downloadUri)
        request.apply {
            setTitle(fileName)
            setDescription(fileName)
            request.setDestinationInExternalFilesDir(context,
                                                     BlocklistDownloadHelper.getExternalFilePath(
                                                         timestamp), fileName)
            val downloadID = downloadManager.enqueue(this)
            if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "filename - $fileName, downloadID - $downloadID")
            return downloadID
        }
    }

}
