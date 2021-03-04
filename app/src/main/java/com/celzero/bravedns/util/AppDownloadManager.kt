package com.celzero.bravedns.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.util.Log
import com.celzero.bravedns.RethinkDnsApplication
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.localDownloadStatus
import com.celzero.bravedns.util.Constants.Companion.DOWNLOAD_URLS
import com.celzero.bravedns.util.Constants.Companion.FILE_NAMES
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_FILE_COUNT
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.DownloadHelper.Companion.deleteFromCanonicalPath
import com.celzero.bravedns.util.DownloadHelper.Companion.isLocalDownloadValid
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class AppDownloadManager(private val persistentState: PersistentState) {

    private lateinit var downloadManager: DownloadManager
    private var downloadReference : MutableList<Long> = mutableListOf()

    /**
     * The onCompleteReceiver - Broadcast receiver to receive the events from
     * download manager.
     *
     * Notifies once the download is completed/failed
     *
     */
    private val onCompleteReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (DEBUG) Log.d(LOG_TAG, "AppDownloadManager onReceive ACTION_DOWNLOAD_COMPLETE - $downloadId, ${downloadReference.size}")
                val query = DownloadManager.Query()
                val cursor = downloadManager.query(query)
                cursor?.let {
                    if (cursor.moveToFirst()) {
                        val columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        if (DEBUG) Log.d(LOG_TAG, "AppDownloadManager onReceive ACTION_DOWNLOAD_COMPLETE 1- ${cursor.getInt(columnIndex)}, ${downloadReference.size}")
                        if (DownloadManager.STATUS_SUCCESSFUL == cursor.getInt(columnIndex)) {
                            Log.i(LOG_TAG, "AppDownloadManager onReceive STATUS_SUCCESSFUL - ${downloadReference.size}")
                            downloadReference.remove(downloadId)
                            if(downloadReference.isEmpty()) {
                                if (context != null) {
                                    copyFilesToCanonicalPath(context)
                                }
                            }
                        } else if (DownloadManager.STATUS_FAILED == cursor.getInt(columnIndex)) {
                            if (DEBUG) Log.d(LOG_TAG, "AppDownloadManager STATUS_FAILED")
                            updateSharedPref(false)
                        }
                    }
                    cursor.close()
                }
            }
        }
    }

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
    fun downloadLocalBlocklist(timeStamp: Long, contextVal: Context) {
        val context = contextVal.applicationContext
        if (context != null) {
            initDownload(context)
            for(i in 0 until LOCAL_BLOCKLIST_FILE_COUNT){
                val url = DOWNLOAD_URLS[i]
                val fileName = FILE_NAMES[i]
                if (DEBUG) Log.d(LOG_TAG, "AppDownloadManager ($timeStamp) filename - $fileName, url - $url")
                download(context, url, fileName, timeStamp.toString())
            }
        }else{
            Log.i(LOG_TAG, "Context for download is null")
            updateSharedPref(isDownloadSuccess = false)
        }
    }

    /**
     * Once the files are downloaded, copyFilesToCanonicalPath() will copy the files
     * to the canonical path and initiate the delete method to clear the downloads by DownloadManager
     */
    fun copyFilesToCanonicalPath(contextVal : Context): Boolean {
        var context = RethinkDnsApplication.context
        try {
            if(context == null){
                context = contextVal
            }
            if(context != null) {
                val timeStamp = persistentState.localBlockListDownloadTime
                if (isLocalDownloadValid(context, timeStamp.toString())) {
                    deleteFromCanonicalPath(context)
                    if (DEBUG) Log.d(LOG_TAG, "AppDownloadManager Copy file Directory isLocalDownloadValid- true")
                    val dir = File(DownloadHelper.getExternalFilePath(context, timeStamp.toString()))
                    if (DEBUG) Log.d(LOG_TAG, "AppDownloadManager Copy file Directory- ${dir.path}")
                    if (dir.isDirectory) {
                        val children = dir.list()
                        if (children != null && children.isNotEmpty()) {
                            for (i in children.indices) {
                                if (DEBUG) Log.d(LOG_TAG, "AppDownloadManager Copy file - ${children[i]}")
                                val from = File(dir, children[i])
                                if (DEBUG) Log.d(LOG_TAG, "AppDownloadManager Copy file from - ${from.path}")
                                val to = File("${context.filesDir.canonicalPath}/$timeStamp/${children[i]}")
                                Log.i(LOG_TAG, "AppDownloadManager Copy file to - ${to.path}")
                                from.copyTo(to, true)
                            }
                            if (children.size == LOCAL_BLOCKLIST_FILE_COUNT) {
                                //Update the shared pref values
                                updateSharedPref(true)
                                cleanupDownloads(context)
                                context.unregisterReceiver(onCompleteReceiver)
                                return true
                            }
                        }
                    }
                    context.unregisterReceiver(onCompleteReceiver)
                    return false
                }
            }else{
                Log.e(LOG_TAG, "AppDownloadManager Context is null")
                updateSharedPref(false)
            }
        } catch (e: Exception) {
            updateSharedPref(false)
        }
        context?.unregisterReceiver(onCompleteReceiver)
        return false
    }



    /**
     * Updates the shared preference values based on the files downloaded
     */
    private fun updateSharedPref(isDownloadSuccess: Boolean) {
        if(isDownloadSuccess) {
            localDownloadStatus.postValue(2)
            persistentState.blockListFilesDownloaded = true
            persistentState.localBlocklistEnabled = true
        } else{
            localDownloadStatus.postValue(-1)
            persistentState.localBlockListDownloadTime = 0L
            persistentState.localBlocklistEnabled = false
            persistentState.blockListFilesDownloaded = false
            val context = RethinkDnsApplication.context
            context?.unregisterReceiver(onCompleteReceiver)
        }
    }

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



    private fun cleanupDownloads(context: Context) {
        DownloadHelper.deleteOldFiles(context)
    }

    /**
     * Init for downloads.
     * Delete all the old files which are available in the download path.
     * Handles are the preliminary check before initiating the download.
     */
    private fun initDownload(context: Context) {
        downloadReference.clear()
        localDownloadStatus.postValue(1)
        timeOutForDownload()
        DownloadHelper.deleteOldFiles(context)
    }

    private fun download(contextVal : Context, url: String, fileName: String, timeStamp: String) {
        var context = RethinkDnsApplication.context
        if(context == null){
            context = contextVal
        }
        downloadManager = context?.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadUri = Uri.parse(url)
        val request = DownloadManager.Request(downloadUri)
        request.apply {
            setTitle(fileName)
            setDescription(fileName)
            request.setDestinationInExternalFilesDir(context, DownloadHelper.getExternalFilePath(null, timeStamp), fileName)
            context.registerReceiver(onCompleteReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            downloadReference.add(downloadManager.enqueue(this))
        }
    }

    private fun timeOutForDownload(){
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
                val context = RethinkDnsApplication.context
                context?.unregisterReceiver(onCompleteReceiver)
            }
            backgroundExecutor.shutdown()
        }, 3, TimeUnit.MINUTES)

    }

}