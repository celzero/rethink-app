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
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLISTS_IN_APP
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.InputStreamReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LocalBlocklistUtil(val context: Context) {

    fun init() {
        CoroutineScope(Dispatchers.IO).launch {
            val jsonObject: JsonObject =
                JsonParser.parseReader(
                    InputStreamReader(
                        context.assets.open(
                            ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG.removePrefix(File.separator)
                        )
                    )
                ) as JsonObject

            val localBlocklistTimestamp =
                jsonObject.get("timestamp").asString.split("/").last().toLong()

            val assets = context.assets.list("") ?: return@launch
            for (asset in assets) {
                val file =
                    ONDEVICE_BLOCKLISTS_IN_APP.firstOrNull {
                        asset.contains(it.filename.removePrefix(File.separator))
                    }
                        ?: continue
                context.assets
                    .open(file.filename.removePrefix(File.separator))
                    .copyTo(
                        File(
                                Utilities.blocklistDir(
                                        context,
                                        Constants.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                                        localBlocklistTimestamp
                                    )
                                    ?.apply { mkdirs() }
                                    ?: return@launch,
                                file.filename.removePrefix(File.separator)
                            )
                            .outputStream()
                    )
            }
        }
    }
}
