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
import android.os.Build
import android.util.Log
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG_DOWNLOAD
import java.io.File

class DownloadHelper {

    companion object {
        fun isLocalDownloadValid(context: Context, timeStamp: String): Boolean {
            try {
                if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "Local block list validation: $timeStamp")
                val dir = File(getExternalFilePath(context, timeStamp))
                if (dir.isDirectory) {
                    val children = dir.list()
                    if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "Local block list validation isDirectory: true, children : ${children?.size}, ${dir.path}")
                    if (children != null && children.size == Constants.LOCAL_BLOCKLIST_FILE_COUNT) return true
                }

            } catch (e: Exception) {
                Log.w(LOG_TAG_DOWNLOAD, "Local block list validation failed - ${e.message}")
            }
            return false
        }

        fun validateRemoteBlocklistDownload() {

        }


        /**
         * Clean up the folder which had the old download files.
         * This was introduced in v053, before that the files downloaded as part of blocklists
         * are stored in external files dir by the DownloadManager and moved to canonicalPath.
         * Now in v053 we are moving the files from external dir to canonical path.
         * So deleting the old files in the external directory.
         */
        fun deleteOldFiles(context: Context) {
            val dir = File(context.getExternalFilesDir(null).toString() + Constants.DOWNLOAD_PATH)
            if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "deleteOldFiles -- File : ${dir.path}, ${dir.isDirectory}")
            deleteRecursive(dir)
        }

        private fun deleteRecursive(fileOrDirectory: File) {
            try {
                if (fileOrDirectory.isDirectory) for (child in fileOrDirectory.listFiles()!!) deleteRecursive(child)
                val isDeleted : Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    fileOrDirectory.deleteRecursively()
                } else {
                    fileOrDirectory.delete()
                }
                if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "deleteRecursive -- File : ${fileOrDirectory.path}, $isDeleted")
            } catch (e: Exception) {
                Log.w(LOG_TAG_DOWNLOAD, "File delete exception: ${e.message}", e)
            }
        }

        fun deleteFromCanonicalPath(context: Context) {
            val canonicalPath = File("${context.filesDir.canonicalPath}/")
            deleteRecursive(canonicalPath)
        }

        fun getExternalFilePath(context: Context?, timeStamp: String): String {
            if (context == null) {
                return Constants.DOWNLOAD_PATH + "/" + timeStamp + "/"
            }
            return context.getExternalFilesDir(null).toString() + Constants.DOWNLOAD_PATH + "/" + timeStamp + "/"
        }
    }

}