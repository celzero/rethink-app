package com.celzero.bravedns.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.*
import com.celzero.bravedns.download.DownloadConstants.Companion.DOWNLOAD_IDS
import com.celzero.bravedns.download.DownloadConstants.Companion.DOWNLOAD_TAG
import com.celzero.bravedns.download.DownloadConstants.Companion.FILE_TAG
import com.celzero.bravedns.receiver.ReceiverHelper
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.DOWNLOAD_URLS
import com.celzero.bravedns.util.Constants.Companion.FILE_NAMES
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_FILE_COUNT
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import java.util.concurrent.TimeUnit

class AppDownloadManager(private val persistentState: PersistentState, private val context: Context?) {

    private lateinit var downloadManager: DownloadManager
    private var downloadReference : MutableList<Long> = mutableListOf()

    /**
     * Takes care of number of files need to be downloaded for the remote blocklist.
     * For remote blocklist, we require only filetag.json file.
     */
    private fun downloadRemoteBlocklist() {

    }

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
                if (DEBUG) Log.d(LOG_TAG, "AppDownloadManager ($timeStamp) filename - $fileName, url - $url")
                download(url, fileName, timeStamp.toString())
            }
            initiateDownloadStatusCheck()
        }else{
            Log.i(LOG_TAG, "Context for download is null")
            persistentState.localBlocklistEnabled = false
            persistentState.tempBlocklistDownloadTime = 0
            persistentState.blockListFilesDownloaded = false
        }
    }

    private fun initiateDownloadStatusCheck() {
        WorkManager.getInstance().pruneWork()

        val downloadIDs = ReceiverHelper.persistentState.downloadIDs.toTypedArray()
        val downloadWorkerData = workDataOf(DOWNLOAD_IDS to downloadIDs)
        val downloadWatcher = OneTimeWorkRequestBuilder<DownloadWatcher>()
            .setInputData(downloadWorkerData)
            .setBackoffCriteria(BackoffPolicy.LINEAR,
                                OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                                TimeUnit.MILLISECONDS)
            .addTag(DOWNLOAD_TAG)
            .setInitialDelay(10, TimeUnit.SECONDS)
            .build()

        //FileHandleWorker manager
        val timeStampLong = ReceiverHelper.persistentState.tempBlocklistDownloadTime
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
     * Updates the shared preference values based on the files downloaded

    private fun updateSharedPref(isDownloadSuccess: Boolean) {
        if (isDownloadSuccess) {
            localDownloadStatus.postValue(2)
            persistentState.blockListFilesDownloaded = true
            persistentState.localBlocklistEnabled = true
        } else {
            localDownloadStatus.postValue(-1)
            persistentState.localBlockListDownloadTime = 0L
            persistentState.localBlocklistEnabled = false
            persistentState.blockListFilesDownloaded = false
        }
    }*/


    /**
     * Updates the values for the local download.
     * The observers in the UI will reflect the download status.
     */
    private fun updateLocalUIValues(isDownloadSuccess: Boolean) {

    }

    /**
     * Updates the values for the Remote download.
     * The observers in the UI will reflect the download status.
     */
    private fun updateRemoteUIValues(isDownloadSuccess: Boolean) {

    }



    /**
     * Init for downloads.
     * Delete all the old files which are available in the download path.
     * Handles are the preliminary check before initiating the download.
     */
    private fun initDownload(context: Context) {
        downloadReference.clear()
        //timeOutForDownload()
        DownloadHelper.deleteOldFiles(context)
        persistentState.downloadIDs = emptySet()
    }

    private fun download(url: String, fileName: String, timeStamp: String) {
        downloadManager = context?.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadUri = Uri.parse(url)
        val request = DownloadManager.Request(downloadUri)
        request.apply {
            setTitle(fileName)
            setDescription(fileName)
            request.setDestinationInExternalFilesDir(context, DownloadHelper.getExternalFilePath(null, timeStamp), fileName)
            persistentState.downloadIDs =  persistentState.downloadIDs + downloadManager.enqueue(this).toString()
        }
    }

    /*private fun timeOutForDownload(){
        // Create an executor that executes tasks in a background thread.
        val backgroundExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

        // Execute a task in the background thread after 3 minutes.
        // This task will be timeout execution for the download.
        // After 3 minutes of init download, The failure will be updated,
        backgroundExecutor.schedule({
            Log.i(LOG_TAG, "timeOutForDownload executor triggered - ${localDownloadStatus.value}")
            if (localDownloadStatus.value != 2) {
                localDownloadStatus.postValue(-1)
                persistentState.localBlockListDownloadTime = 0L
                persistentState.localBlocklistEnabled = false
                persistentState.blockListFilesDownloaded = false
                //context?.unregisterReceiver(onCompleteReceiver)
            }
            backgroundExecutor.shutdown()
        },40, TimeUnit.MINUTES)
    }*/

}