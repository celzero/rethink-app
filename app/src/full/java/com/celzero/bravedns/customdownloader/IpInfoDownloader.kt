/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.customdownloader

import Logger
import Logger.LOG_TAG_DOWNLOAD
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.IpInfo
import com.celzero.bravedns.database.IpInfoRepository
import com.celzero.bravedns.service.PersistentState
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import retrofit2.converter.gson.GsonConverterFactory
import kotlin.math.pow

object IpInfoDownloader: KoinComponent {

    private val persistentState by inject<PersistentState>()
    private val db by inject<IpInfoRepository>()

    private const val TAG = "IpInfoDownloader"
    private var retryAfterTimestamp = 0L
    private var retryAttemptCount = 0
    private const val HTTP_TOO_MANY_REQUEST_CODE = 429

    suspend fun fetchIpInfoIfRequired(ipToLookup: String) {
        if (!persistentState.downloadIpInfo) {
            Logger.vv(LOG_TAG_DOWNLOAD, "$TAG; download is disabled")
            return
        }

        // check in database whether the ip info is already downloaded
        val ipInfo = db.getIpInfo(ipToLookup)
        if (ipInfo != null) {
            Logger.vv(LOG_TAG_DOWNLOAD, "$TAG; already available, skip download")
            return
        }

        Logger.vv(LOG_TAG_DOWNLOAD, "$TAG; not present, proceed download...")
        val downloadSuccessful = performIpInfoDownload(ipToLookup)
        Logger.d(LOG_TAG_DOWNLOAD, "$TAG; download complete, success? $downloadSuccessful")
    }

    private suspend fun performIpInfoDownload(ipToLookup: String): Boolean {
        if (DEBUG) OkHttpDebugLogging.enableHttp2()
        if (DEBUG) OkHttpDebugLogging.enableTaskRunner()

        if (System.currentTimeMillis() < retryAfterTimestamp) {
            val remainingTime = retryAfterTimestamp - System.currentTimeMillis()
            Logger.i(LOG_TAG_DOWNLOAD, "$TAG; too many req, no attempt to download for $remainingTime")
            return false
        }

        val retrofitInstance = RetrofitManager.getIpInfoBaseBuilder()
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val ipInfoDownloadApi = retrofitInstance.create(IIpInfoDownload::class.java)

        return try {
            val downloadResponse = ipInfoDownloadApi.downloadIpInfo(ipToLookup)
            // in case if the response error is 429 do not send requests for next 5 minutes
            // if the response is 429 for second time, then exponentially increase the break time
            if (downloadResponse == null) {
                Logger.i(LOG_TAG_DOWNLOAD, "$TAG; download failed: response is null")
                return false
            }

            if (downloadResponse.isSuccessful) {
                val jsonResponse = downloadResponse.body()
                if (jsonResponse == null) {
                    Logger.i(LOG_TAG_DOWNLOAD, "$TAG; download failed: response body is null")
                    return false
                }
                val ipInfo = parseIpInfoFromJson(jsonResponse)
                if (ipInfo == null) {
                    Logger.i(LOG_TAG_DOWNLOAD, "$TAG; download failed: ip is null")
                    return false
                }
                db.insertAsync(ipInfo)
                Logger.i(LOG_TAG_DOWNLOAD, "$TAG; download successful: $jsonResponse")
                true
            } else {
                if (downloadResponse.code() == HTTP_TOO_MANY_REQUEST_CODE) {
                    Logger.i(LOG_TAG_DOWNLOAD, "$TAG; download failed: ${downloadResponse.code()} ${downloadResponse.message()}")
                    var coolDownPeriodMillis: Long = 5 * 60 * 1000
                    if (retryAttemptCount > 1) {
                        Logger.i(LOG_TAG_DOWNLOAD, "$TAG; download failed: too many attempts")
                        // increase the break time exponentially
                        coolDownPeriodMillis = (2.0.pow(retryAttemptCount.toDouble()) * 60 * 1000).toLong()
                    }
                    retryAfterTimestamp = System.currentTimeMillis() + coolDownPeriodMillis
                    retryAttemptCount++
                }

                Logger.i(LOG_TAG_DOWNLOAD, "$TAG; download failed: ${downloadResponse.code()} ${downloadResponse.message()}")
                false
            }
        } catch (e: Exception) {
            Logger.i(LOG_TAG_DOWNLOAD, "$TAG; err while download: ${e.localizedMessage}")
            false
        }
    }

    private fun parseIpInfoFromJson(ipInfoJson: JsonObject): IpInfo? {
        try {
            val ipInfoJsonString = ipInfoJson.toString()
            val ipInfo = Gson().fromJson(ipInfoJsonString, IpInfo::class.java)
            ipInfo.createdTs = System.currentTimeMillis()
            return ipInfo
        } catch (e: JsonSyntaxException) {
            Logger.w(LOG_TAG_DOWNLOAD, "$TAG; err parsing ip info: ${e.message}")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_DOWNLOAD, "$TAG; err parsing ip info: ${e.message}")
        }
        return null
    }
}
