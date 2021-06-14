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
import android.util.Log
import androidx.work.*
import com.celzero.bravedns.download.DownloadConstants.Companion.DOWNLOAD_TAG
import com.celzero.bravedns.download.DownloadConstants.Companion.FILE_TAG
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.DOWNLOAD_URLS
import com.celzero.bravedns.util.Constants.Companion.FILE_NAMES
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_FILE_COUNT
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG_DOWNLOAD
import java.util.concurrent.TimeUnit

/**
 * Generic class responsible for downloading the block list for both remote and local.
 * As of now, the download manager will download the local blocklist and initiate thw workers
 * to listen for the download complete and for copying the files from external to canonical path.
 * TODO remote blocklist - implementation pending.
 */
class AppDownloadManager(private val persistentState: PersistentState, private val context: Context?) {

    private lateinit var downloadManager: DownloadManager
    private var downloadReference : MutableList<Long> = mutableListOf()

    /**
     * Takes care of number of files need to be downloaded for the remote blocklist.
     * For remote blocklist, we require only filetag.json file.
     */
    /*private fun downloadRemoteBlocklist() {

    }*/

    /**
     * Responsible for downloading the local blocklist files.
     * For local blocklist, we need filetag.json, basicconfig.json, rd.txt and td.txt
     * Once the download of all the files are completed.
     * Calls the copy method.
     */
    fun downloadLocalBlocklist(timeStamp: Long) {
        if (context != null) {
            initDownload(context)
            for(i in 0 until LOCAL_BLOCKLIST_FILE_COUNT){
                val url = DOWNLOAD_URLS[i]
                val fileName = FILE_NAMES[i]
                if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "Timestamp - ($timeStamp) filename - $fileName, url - $url")
                download(url, fileName, timeStamp.toString())
            }
            initiateDownloadStatusCheck()
        }else{
            Log.i(LOG_TAG_DOWNLOAD, "Context for download is null")
            persistentState.localBlocklistEnabled = false
            persistentState.tempBlocklistDownloadTime = 0
            persistentState.workManagerStartTime = 0
            persistentState.blockListFilesDownloaded = false
        }
    }

    private fun initiateDownloadStatusCheck() {
        WorkManager.getInstance().pruneWork()

        val downloadWatcher = OneTimeWorkRequestBuilder<DownloadWatcher>()
            .setBackoffCriteria(BackoffPolicy.LINEAR,
                                OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                                TimeUnit.MILLISECONDS)
            .addTag(DOWNLOAD_TAG)
            .setInitialDelay(10, TimeUnit.SECONDS)
            .build()

        //FileHandleWorker manager
        val timeStampLong = persistentState.tempBlocklistDownloadTime
        val timeStamp = workDataOf("timeStamp" to timeStampLong)

        val fileHandler = OneTimeWorkRequestBuilder<FileHandleWorker>().setInputData(timeStamp)
            .setBackoffCriteria(BackoffPolicy.LINEAR,
                                OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                                TimeUnit.MILLISECONDS)
            .addTag(FILE_TAG)
            .build()

        WorkManager.getInstance()
                    .beginWith(downloadWatcher)
                    .then(fileHandler)
                    .enqueue()

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
    private fun initDownload(context: Context) {
        downloadReference.clear()
        persistentState.downloadIDs = emptySet()
        DownloadHelper.deleteOldFiles(context)
    }

    private fun download(url: String, fileName: String, timeStamp: String) {
        downloadManager = context?.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadUri = Uri.parse(url)
        val request = DownloadManager.Request(downloadUri)
        request.apply {
            setTitle(fileName)
            setDescription(fileName)
            request.setDestinationInExternalFilesDir(context, DownloadHelper.getExternalFilePath(null, timeStamp), fileName)
            val downloadID = downloadManager.enqueue(this)
            if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "filename - $fileName, downloadID - $downloadID")
            persistentState.downloadIDs =  persistentState.downloadIDs + downloadID.toString()
        }
    }

}