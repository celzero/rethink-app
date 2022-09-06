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
package com.celzero.bravedns.scheduler

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.customdownloader.RetrofitManager
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_SCHEDULER
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.IOException

class BlocklistUpdateCheckJob(val context: Context, workerParameters: WorkerParameters) :
        CoroutineWorker(context, workerParameters), KoinComponent {

    private val persistentState by inject<PersistentState>()

    override suspend fun doWork(): Result {
        Log.i(LOG_TAG_SCHEDULER, "starting blocklist update check job")

        isDownloadRequired(AppDownloadManager.DownloadType.LOCAL)
        isDownloadRequired(AppDownloadManager.DownloadType.REMOTE)
        return Result.success()
    }

    private fun isDownloadRequired(type: AppDownloadManager.DownloadType) {
        val url = constructDownloadCheckUrl(type)
        val request = Request.Builder().url(url).build()
        val client = RetrofitManager.okHttpClient(RetrofitManager.Companion.OkHttpDnsType.DEFAULT)

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i(LOG_TAG_SCHEDULER,
                      "onFailure, cancelled? ${call.isCanceled()}, exec? ${call.isExecuted()}")

            }

            override fun onResponse(call: Call, response: Response) {
                val stringResponse = response.body?.string() ?: return
                response.body?.close()
                client.connectionPool.evictAll()

                val json = JSONObject(stringResponse)
                val version = json.optInt(Constants.JSON_VERSION, 0)
                Log.i(LOG_TAG_SCHEDULER, "Response for update check for blocklist:  $version")
                if (version != Constants.UPDATE_CHECK_RESPONSE_VERSION) {
                    return
                }

                val shouldUpdate = json.optBoolean(Constants.JSON_UPDATE, false)
                val timestamp = json.optLong(Constants.JSON_LATEST, Constants.INIT_TIME_MS)
                Log.i(LOG_TAG_SCHEDULER,
                      "Response for update check for blocklist: version? $version, update? $shouldUpdate, download type: ${type.name}")
                if (type == AppDownloadManager.DownloadType.LOCAL) {
                    persistentState.isLocalBlocklistUpdateAvailable = shouldUpdate
                    if (shouldUpdate) persistentState.updatableTimestampLocal = timestamp
                    return
                }

                persistentState.isRemoteBlocklistUpdateAvailable = shouldUpdate
                if (shouldUpdate) persistentState.updatableTimestampRemote = timestamp
            }
        })
    }

    private fun constructDownloadCheckUrl(type: AppDownloadManager.DownloadType): String {
        val timestamp = if (type == AppDownloadManager.DownloadType.LOCAL) {
            persistentState.localBlocklistTimestamp
        } else {
            persistentState.remoteBlocklistTimestamp
        }
        val appVersionCode = persistentState.appVersion
        val url = "${Constants.ONDEVICE_BLOCKLIST_UPDATE_CHECK_URL}$timestamp&${Constants.ONDEVICE_BLOCKLIST_UPDATE_CHECK_PARAMETER_VCODE}$appVersionCode"
        Log.d(LOG_TAG_SCHEDULER, "Check for download, download type ${type.name} url: $url")
        return url
    }

}
