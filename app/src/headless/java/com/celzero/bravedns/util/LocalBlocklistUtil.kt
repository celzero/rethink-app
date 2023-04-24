/*
 * Copyright 2023 RethinkDNS and its authors
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
package com.celzero.bravedns.util

import android.content.Context
import android.util.Log
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLISTS_IN_APP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream

class LocalBlocklistUtil(val context: Context) {

    fun init() {
        // check if the files are present in the internal storage
        // if not present, move the files from assets to internal storage
        if (!isFilesPresentInInternalStorage()) {
            moveFilesFromAssetsToInternalStorage()
        }
    }

    // create a function to check if the files are present in the internal storage
    private fun isFilesPresentInInternalStorage(): Boolean {
        // check if the files are present in the internal storage
        // folder name: Constants.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
        // file name: Constants.ONDEVICE_BLOCKLIST_FILE_TAG (just check for this file)
        val dir =
            Utilities.blocklistDir(
                context,
                Constants.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                Constants.INIT_TIME_MS
            )
                ?: return false
        val file = File(dir.absolutePath + File.separator + Constants.ONDEVICE_BLOCKLIST_FILE_TAG)
        return file.exists()
    }

    // create a function to move files from assets to internal storage
    private fun moveFilesFromAssetsToInternalStorage() {
        // get the list of files from assets
        // loop through the list of files
        // copy the files to internal storage
        val files = context.assets.list("") ?: return

        CoroutineScope(Dispatchers.IO).launch {
            for (file in files) {
                // check if the file contains in ONDEVICE_BLOCKLISTS_IN_APP
                // values contains file name with file separator, remove the file separator
                val t = ONDEVICE_BLOCKLISTS_IN_APP.firstOrNull { file.contains(it.filename.removePrefix(File.separator)) } ?: continue
                moveFileToLocalDir(t.filename.removePrefix(File.separator))
            }
        }
    }

    private suspend fun moveFileToLocalDir(fileName: String) {
        try {
            val assetMgr = context.assets
            val readStream: InputStream = assetMgr.open(fileName)
            val to = makeFile(context, fileName) ?: throw IOException()

            Utilities.copyWithStream(readStream, to.outputStream())
            if (fileName.contains(Constants.ONDEVICE_BLOCKLIST_FILE_TAG)) {
                // write the file tag json file into database
                RethinkBlocklistManager.readJson(
                    context,
                    RethinkBlocklistManager.DownloadType.LOCAL,
                    Constants.INIT_TIME_MS
                )
            }
        } catch (e: IOException) {
            Log.e(
                LoggerConstants.LOG_TAG_DOWNLOAD,
                "Issue moving file from asset folder: ${e.message}",
                e
            )
        }
    }

    private fun makeFile(context: Context, fileName: String): File? {
        val dir =
            Utilities.blocklistDir(
                context,
                Constants.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                Constants.INIT_TIME_MS
            )
                ?: return null
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir.absolutePath + File.separator + fileName)
        return if (file.exists()) {
            // no need to move the file from asset if the file is already available
            file
        } else {
            file.createNewFile()
            file
        }
    }

}