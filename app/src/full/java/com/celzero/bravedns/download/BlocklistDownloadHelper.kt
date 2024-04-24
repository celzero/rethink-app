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

import Logger
import Logger.LOG_TAG_DOWNLOAD
import android.content.Context
import com.celzero.bravedns.customdownloader.IBlocklistDownload
import com.celzero.bravedns.customdownloader.RetrofitManager
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Utilities.blocklistCanonicalPath
import com.celzero.bravedns.util.Utilities.deleteRecursive
import org.json.JSONException
import org.json.JSONObject
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File

class BlocklistDownloadHelper {

    data class BlocklistUpdateServerResponse(
        val version: Int,
        val update: Boolean,
        val timestamp: Long
    )

    companion object {
        fun isDownloadComplete(context: Context, timestamp: Long): Boolean {
            var result = false
            var total: Int? = 0
            var dir: File? = null
            try {
                Logger.d(LOG_TAG_DOWNLOAD, "Local block list validation: $timestamp")
                dir = File(getExternalFilePath(context, timestamp.toString()))
                total =
                    if (dir.isDirectory) {
                        dir.list()?.count()
                    } else {
                        0
                    }
                result = Constants.ONDEVICE_BLOCKLISTS_ADM.count() == total
            } catch (ignored: Exception) {
                Logger.w(
                    LOG_TAG_DOWNLOAD,
                    "Local block list validation failed: ${ignored.message}",
                    ignored
                )
            }

            Logger.d(
                LOG_TAG_DOWNLOAD,
                "Valid on-device blocklist ($timestamp) download? $result, files: $total, dir? ${dir?.isDirectory}"
            )
            return result
        }

        /**
         * Clean up the folder which had the old download files. This was introduced in v053, before
         * that the files downloaded as part of blocklists are stored in external files dir by the
         * DownloadManager and moved to canonicalPath. Now in v053 we are moving the files from
         * external dir to canonical path. So deleting the old files in the external directory.
         */
        fun deleteOldFiles(
            context: Context,
            timestamp: Long,
            type: RethinkBlocklistManager.DownloadType
        ) {
            val path =
                if (type == RethinkBlocklistManager.DownloadType.LOCAL) {
                    Constants.ONDEVICE_BLOCKLIST_DOWNLOAD_PATH
                } else {
                    Constants.ONDEVICE_BLOCKLIST_DOWNLOAD_PATH
                }
            val dir = File(context.getExternalFilesDir(null).toString() + path + timestamp)
            Logger.d(LOG_TAG_DOWNLOAD, "deleteOldFiles, File : ${dir.path}, ${dir.isDirectory}")
            deleteRecursive(dir)
        }

        fun deleteBlocklistResidue(context: Context, which: String, timestamp: Long) {
            val dir = File(blocklistCanonicalPath(context, which))
            if (!dir.exists()) return

            dir.listFiles()?.forEach {
                Logger.d(
                    LOG_TAG_DOWNLOAD,
                    "Delete blocklist list residue for $which, dir: ${it.name}"
                )
                // delete all the dir other than current timestamp dir
                if (it.name != timestamp.toString()) {
                    deleteRecursive(it)
                }
            }
        }

        fun deleteFromCanonicalPath(context: Context) {
            val canonicalPath =
                File(
                    blocklistCanonicalPath(context, Constants.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME)
                )
            deleteRecursive(canonicalPath)
        }

        fun getExternalFilePath(context: Context, timestamp: String): String {
            return context.getExternalFilesDir(null).toString() +
                Constants.ONDEVICE_BLOCKLIST_DOWNLOAD_PATH +
                File.separator +
                timestamp +
                File.separator
        }

        // getExternalFilePath is similar to the above function without use of default external
        // files dir
        // case: with usage of default android download manager, api requires path without
        // external files dir (api: setDestinationInExternalFilesDir)
        fun getExternalFilePath(timestamp: String): String {
            return Constants.ONDEVICE_BLOCKLIST_DOWNLOAD_PATH +
                File.separator +
                timestamp +
                File.separator
        }

        suspend fun checkBlocklistUpdate(
            timestamp: Long,
            vcode: Int,
            retryCount: Int
        ): BlocklistUpdateServerResponse? {
            try {
                val retrofit =
                    RetrofitManager.getBlocklistBaseBuilder(retryCount)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()
                val retrofitInterface = retrofit.create(IBlocklistDownload::class.java)
                Logger.i(
                    LOG_TAG_DOWNLOAD,
                    "downloadAvailabilityCheck: ${Constants.ONDEVICE_BLOCKLIST_UPDATE_CHECK_QUERYPART_1}, ${Constants.ONDEVICE_BLOCKLIST_UPDATE_CHECK_QUERYPART_2}, $vcode, $timestamp"
                )
                val response =
                    retrofitInterface.downloadAvailabilityCheck(
                        Constants.ONDEVICE_BLOCKLIST_UPDATE_CHECK_QUERYPART_1,
                        Constants.ONDEVICE_BLOCKLIST_UPDATE_CHECK_QUERYPART_2,
                        timestamp,
                        vcode
                    )
                Logger.i(
                    LOG_TAG_DOWNLOAD,
                    "downloadAvailabilityCheck: $response, $retryCount, $vcode, $timestamp"
                )
                if (response?.isSuccessful == true) {
                    val r = response.body()?.toString()?.let { JSONObject(it) }
                    return processCheckDownloadResponse(r)
                }
            } catch (ex: Exception) {
                Logger.e(LOG_TAG_DOWNLOAD, "exception in checkBlocklistUpdate: ${ex.message}", ex)
            }
            Logger.i(
                LOG_TAG_DOWNLOAD,
                "downloadAvailabilityCheck: failed, returning null, $retryCount"
            )
            return if (isRetryRequired(retryCount)) {
                Logger.i(LOG_TAG_DOWNLOAD, "retrying the downloadAvailabilityCheck")
                checkBlocklistUpdate(timestamp, vcode, retryCount + 1)
            } else {
                Logger.i(LOG_TAG_DOWNLOAD, "retry count exceeded, returning null")
                null
            }
        }

        private fun isRetryRequired(retryCount: Int): Boolean {
            return retryCount < RetrofitManager.Companion.OkHttpDnsType.entries.size - 1
        }

        private fun processCheckDownloadResponse(
            response: JSONObject?
        ): BlocklistUpdateServerResponse? {
            if (response == null) return null

            try {
                val version = response.optInt(Constants.JSON_VERSION, 0)
                Logger.d(
                    LOG_TAG_DOWNLOAD,
                    "client onResponse for refresh blocklist files:  $version"
                )

                val shouldUpdate = response.optBoolean(Constants.JSON_UPDATE, false)
                val timestamp = response.optLong(Constants.JSON_LATEST, INIT_TIME_MS)
                Logger.i(
                    LOG_TAG_DOWNLOAD,
                    "response for blocklist update check: version: $version, update? $shouldUpdate, timestamp: $timestamp"
                )

                return BlocklistUpdateServerResponse(version, shouldUpdate, timestamp)
            } catch (e: JSONException) {
                Logger.e(LOG_TAG_DOWNLOAD, "Error in parsing the response: ${e.message}", e)
            }
            return null
        }

        fun getDownloadableTimestamp(response: BlocklistUpdateServerResponse): Long {
            if (response.version != Constants.UPDATE_CHECK_RESPONSE_VERSION) {
                return INIT_TIME_MS
            }

            return response.timestamp
        }
    }
}
