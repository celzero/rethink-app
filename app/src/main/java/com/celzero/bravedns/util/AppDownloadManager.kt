package com.celzero.bravedns.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.DownloadHelper.Companion.isLocalDownloadValid
import java.io.File

object AppDownloadManager {

    /**
    * The onCompleteReceiver - Broadcast receiver to receive the events from
    * download manager.
    *
    * Notifies once the download is completed/failed
    *
    */
    private val onCompleteReceiver = object : BroadcastReceiver(){
        override fun onReceive(p0: Context?, p1: Intent?) {

        }
    }

    /**
     * Takes care of number of files need to be downloaded for the remote blocklist.
     * For remote blocklist, we require only filetag.json file.
     */
    private fun downloadRemoteBlocklist(){

    }

    /**
     * Responsible for downloading the local blocklist files.
     * For local blocklist, we need filetag.json, basicconfig.json, rd.txt and td.txt
     * Once the download of all the files are completed.
     * Calls the copy method.
     */
    private fun downloadLocalBlocklist(){

    }

    /**
     * Once the files are downloaded, copyFilesToCanonicalPath() will copy the files
     * to the canonical path and initiate the delete method to clear the downloads by DownloadManager
     */
    fun copyFilesToCanonicalPath(context: Context, timeStamp: String): Boolean {
        try {
            if (isLocalDownloadValid(context, timeStamp)) {
                Log.d(LOG_TAG, "Copy file Directory isLocalDownloadValid- true")
                val dir = File(getExternalFilePath(context, timeStamp))
                Log.d(LOG_TAG, "Copy file Directory- ${dir.path}")
                if (dir.isDirectory) {
                    val children = dir.list()
                    if (children != null && children.isNotEmpty()) {
                        for (i in children.indices) {
                            Log.d(LOG_TAG, "Copy file - ${children[i]}")
                            val from = File(dir, children[i])
                            Log.d(LOG_TAG, "Copy file from - ${from.path}")
                            val to = File(context.filesDir.canonicalPath +"/"+ children[i])
                            Log.d(LOG_TAG, "Copy file to - ${to.path}")
                            from.copyTo(to, true)
                        }
                        if(children.size == Constants.LOCAL_BLOCKLIST_FILE_COUNT){
                            return true
                        }
                    }
                }
                return false
            }
        } catch (e: Exception) {
            return false
        }
        return false
    }

    fun getExternalFilePath(context: Context, timeStamp: String): String {
        return context.getExternalFilesDir(null).toString() + Constants.DOWNLOAD_PATH + "/"+timeStamp +"/"
    }

    /**
     * Updates the shared preference values based on the files downloaded
     */
    fun updateSharedPref(){

    }

    /**
     * Updates the values so that the observers in the UI will reflect the download status.
     */
    fun updateUIValues(){

    }


    fun cleanupDownloads(context: Context){
        Utilities.deleteOldFiles(context)
    }

    /**
     * Init for downloads.
     * Delete all the old files which are available in the download path.
     * Handles are the preliminary check before initiating the download.
     */
     fun initDownload(context: Context){
        Utilities.deleteOldFiles(context)
     }
}