package com.celzero.bravedns.util

import android.content.Context
import android.util.Log
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import java.io.File

class DownloadHelper {

    companion object{
        fun isLocalDownloadValid(context : Context, timeStamp : String) : Boolean{
            try {
                val dir = File(AppDownloadManager.getExternalFilePath(context, timeStamp))
                if (dir.isDirectory) {
                    val children = dir.list()
                    if (children != null && children.size == Constants.LOCAL_BLOCKLIST_FILE_COUNT) return true
                }

            }catch (e : Exception){
                Log.w(LOG_TAG, "Local block list validation failed - ${e.message}")
            }
            return false
        }

        fun validateRemoteBlocklistDownload(){

        }
    }

}