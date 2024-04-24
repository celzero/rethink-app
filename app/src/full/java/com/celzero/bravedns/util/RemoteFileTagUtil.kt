/*
 * Copyright 2022 RethinkDNS and its authors
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

import Logger
import Logger.LOG_TAG_DOWNLOAD
import android.content.Context
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import org.koin.core.component.KoinComponent
import java.io.File
import java.io.IOException
import java.io.InputStream

class RemoteFileTagUtil : KoinComponent {
    companion object {

        // find a cleaner way to implement this
        suspend fun moveFileToLocalDir(context: Context, persistentState: PersistentState) {
            try {
                val assetMgr = context.assets
                val readStream: InputStream = assetMgr.open(Constants.FILE_TAG)

                val to =
                    makeFile(context, Constants.PACKAGED_REMOTE_FILETAG_TIMESTAMP)
                        ?: throw IOException()

                Utilities.copyWithStream(readStream, to.outputStream())
                RethinkBlocklistManager.readJson(
                    context,
                    RethinkBlocklistManager.DownloadType.REMOTE,
                    Constants.PACKAGED_REMOTE_FILETAG_TIMESTAMP
                )
                persistentState.remoteBlocklistTimestamp =
                    Constants.PACKAGED_REMOTE_FILETAG_TIMESTAMP
            } catch (e: IOException) {
                Logger.e(LOG_TAG_DOWNLOAD, "err moving file from asset folder: ${e.message}", e)
                persistentState.remoteBlocklistTimestamp = Constants.INIT_TIME_MS
            }
        }

        private fun makeFile(context: Context, timestamp: Long): File? {
            val dir =
                Utilities.blocklistDir(
                    context,
                    Constants.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                    timestamp
                ) ?: return null
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir.absolutePath + Constants.ONDEVICE_BLOCKLIST_FILE_TAG)

            return if (file.exists()) {
                // no need to move the file from asset if the file is already available
                file
            } else {
                file.createNewFile()
                file
            }
        }
    }
}
