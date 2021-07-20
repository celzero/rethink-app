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
package com.celzero.bravedns

import android.app.Activity
import android.util.Log
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.JSON_LATEST
import com.celzero.bravedns.util.Constants.Companion.JSON_UPDATE
import com.celzero.bravedns.util.Constants.Companion.JSON_VERSION
import com.celzero.bravedns.util.Constants.Companion.RESPONSE_VERSION
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_APP_UPDATE
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class NonStoreAppUpdater(private val baseUrl: String,
                         private val persistentState: PersistentState) : AppUpdater {

    override fun checkForAppUpdate(isUserInitiated: Boolean, activity: Activity,
                                   listener: AppUpdater.InstallStateListener) {
        Log.i(LOG_TAG_APP_UPDATE, "Beginning update check")
        val url = baseUrl + BuildConfig.VERSION_CODE

        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i(LOG_TAG_APP_UPDATE, "onFailure -  ${call.isCanceled()}, ${call.isExecuted()}")
                if (isUserInitiated) {
                    listener.onUpdateCheckFailed(AppUpdater.InstallSource.OTHER)
                }
                call.cancel()
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val stringResponse = response.body!!.string()
                    val json = JSONObject(stringResponse)
                    val version = json.optInt(JSON_VERSION, 0)
                    val shouldUpdate = json.optBoolean(JSON_UPDATE, false)
                    val latest = json.optLong(JSON_LATEST, INIT_TIME_MS)
                    persistentState.lastAppUpdateCheck = System.currentTimeMillis() // FIXME move to NTP

                    response.close()
                    client.connectionPool.evictAll()
                    Log.i(LOG_TAG_APP_UPDATE,
                          "Server response for the new version download is $shouldUpdate (json version: $version), version number:  $latest")

                    if (version != RESPONSE_VERSION) {
                        if (isUserInitiated) {
                            listener.onUpdateCheckFailed(AppUpdater.InstallSource.OTHER)
                        }
                        return
                    } else {
                        /* No Op */
                        // If the response version is correct, proceed with further checks.
                    }

                    if (!shouldUpdate) {
                        if (isUserInitiated) {
                            listener.onUpToDate(AppUpdater.InstallSource.OTHER)
                        }
                    } else {
                        listener.onUpdateAvailable(AppUpdater.InstallSource.OTHER)
                    }

                } catch (e: Exception) {
                    if (isUserInitiated) {
                        listener.onUpdateCheckFailed(AppUpdater.InstallSource.OTHER)
                    }
                }
            }
        })
    }

    override fun completeUpdate() {
        /* no-op */
    }

    override fun unregisterListener(listener: AppUpdater.InstallStateListener) {
        /* no-op */
    }
}
