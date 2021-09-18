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
import com.celzero.bravedns.util.Constants.Companion.UPDATE_CHECK_RESPONSE_VERSION
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_APP_UPDATE
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.dnsoverhttps.DnsOverHttps
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress

class NonStoreAppUpdater(private val baseUrl: String,
                         private val persistentState: PersistentState) : AppUpdater {

    override fun checkForAppUpdate(isInteractive: AppUpdater.UserPresent, activity: Activity,
                                   listener: AppUpdater.InstallStateListener) {
        Log.i(LOG_TAG_APP_UPDATE, "Beginning update check")
        val url = baseUrl + BuildConfig.VERSION_CODE

        val bootstrapClient = OkHttpClient()
        // FIXME: Use user set doh provider
        // using quad9 doh provider
        val dns = DnsOverHttps.Builder().client(bootstrapClient).url(
            "https://dns.quad9.net/dns-query".toHttpUrl()).bootstrapDnsHosts(
            InetAddress.getByName("9.9.9.9"), InetAddress.getByName("149.112.112.112"),
            InetAddress.getByName("2620:fe::9"), InetAddress.getByName("2620:fe::fe")).build()

        val client = bootstrapClient.newBuilder().dns(dns).build()
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i(LOG_TAG_APP_UPDATE, "onFailure -  ${call.isCanceled()}, ${call.isExecuted()}")
                listener.onUpdateCheckFailed(AppUpdater.InstallSource.OTHER, isInteractive)
                call.cancel()
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val res = response.body?.string()
                    if (res.isNullOrBlank()) {
                        listener.onUpdateCheckFailed(AppUpdater.InstallSource.OTHER, isInteractive)
                        return
                    }

                    val json = JSONObject(res)
                    val version = json.optInt(JSON_VERSION, 0)
                    val shouldUpdate = json.optBoolean(JSON_UPDATE, false)
                    val latest = json.optLong(JSON_LATEST, INIT_TIME_MS)
                    persistentState.lastAppUpdateCheck = System.currentTimeMillis() // FIXME move to NTP

                    response.close()
                    client.connectionPool.evictAll()
                    Log.i(LOG_TAG_APP_UPDATE,
                          "Server response for the new version download is $shouldUpdate (json version: $version), version number:  $latest")

                    if (version != UPDATE_CHECK_RESPONSE_VERSION) {
                        listener.onUpdateCheckFailed(AppUpdater.InstallSource.OTHER, isInteractive)
                        return
                    } else {
                        /* no-op - If the response version is correct, proceed with further checks. */
                    }

                    if (!shouldUpdate) {
                        listener.onUpToDate(AppUpdater.InstallSource.OTHER, isInteractive)
                    } else {
                        listener.onUpdateAvailable(AppUpdater.InstallSource.OTHER)
                    }

                } catch (e: Exception) {
                    listener.onUpdateCheckFailed(AppUpdater.InstallSource.OTHER, isInteractive)
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
