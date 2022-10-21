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
import com.celzero.bravedns.BuildConfig.DEBUG
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DOWNLOAD
import com.celzero.bravedns.util.Utilities.Companion.deleteRecursive
import com.celzero.bravedns.util.Utilities.Companion.localBlocklistCanonicalPath
import java.io.File

class BlocklistDownloadHelper {

    companion object {
        fun isDownloadComplete(context: Context, timestamp: String): Boolean {
            var result = false
            var total: Int? = 0
            var dir: File? = null
            try {
                if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "Local block list validation: $timestamp")
                dir = File(getExternalFilePath(context, timestamp))
                total = if (dir.isDirectory) {
                    dir.list()?.count()
                } else {
                    0
                }
                result = Constants.ONDEVICE_BLOCKLISTS.count() == total
            } catch (e: Exception) {
                Log.w(LOG_TAG_DOWNLOAD, "Local block list validation failed: ${e.message}", e)
            }

            if (DEBUG) Log.d(
                LOG_TAG_DOWNLOAD,
                "Valid on-device blocklist ($timestamp) download? $result, files: $total, dir? ${dir?.isDirectory}"
            )
            return result
        }

        /**
         * Clean up the folder which had the old download files.
         * This was introduced in v053, before that the files downloaded as part of blocklists
         * are stored in external files dir by the DownloadManager and moved to canonicalPath.
         * Now in v053 we are moving the files from external dir to canonical path.
         * So deleting the old files in the external directory.
         */
        fun deleteOldFiles(
            context: Context, timestamp: Long,
            type: RethinkBlocklistManager.DownloadType
        ) {
            val path = if (type == RethinkBlocklistManager.DownloadType.LOCAL) {
                Constants.ONDEVICE_BLOCKLIST_DOWNLOAD_PATH
            } else {
                Constants.ONDEVICE_BLOCKLIST_DOWNLOAD_PATH
            }
            val dir = File(context.getExternalFilesDir(null).toString() + path + timestamp)
            if (DEBUG) Log.d(
                LOG_TAG_DOWNLOAD,
                "deleteOldFiles, File : ${dir.path}, ${dir.isDirectory}"
            )
            deleteRecursive(dir)
        }

        fun deleteFromCanonicalPath(context: Context) {
            val canonicalPath = File(
                localBlocklistCanonicalPath(
                    context,
                    Constants.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
                )
            )
            deleteRecursive(canonicalPath)
        }

        fun getExternalFilePath(context: Context, timestamp: String): String {
            return context.getExternalFilesDir(
                null
            )
                .toString() + Constants.ONDEVICE_BLOCKLIST_DOWNLOAD_PATH + File.separator + timestamp + File.separator
        }

        // getExternalFilePath is similar to the above function without use of default external files dir
        // case: with usage of default android download manager, api requires path without
        // external files dir (api: setDestinationInExternalFilesDir)
        fun getExternalFilePath(timestamp: String): String {
            return Constants.ONDEVICE_BLOCKLIST_DOWNLOAD_PATH + File.separator + timestamp + File.separator
        }
    }

}
