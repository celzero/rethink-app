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

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.work.*
import com.celzero.bravedns.automaton.RethinkBlocklistManager
import com.celzero.bravedns.customdownloader.*
import com.celzero.bravedns.download.DownloadConstants.Companion.DOWNLOAD_TAG
import com.celzero.bravedns.download.DownloadConstants.Companion.FILE_TAG
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLISTS
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DOWNLOAD
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.localBlocklistDownloadBasePath
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Generic class responsible for downloading the block list for both remote and local.
 * As of now, the download manager will download the local blocklist and initiate thw workers
 * to listen for the download complete and for copying the files from external to canonical path.
 * TODO remote blocklist - implementation pending.
 */
class AppDownloadManager(private val context: Context,
                         private val persistentState: PersistentState) {

    private lateinit var downloadManager: DownloadManager
    private lateinit var downloadIds: LongArray

    // live data to initiate the download, contains time stamp if the download is required,
    // else will have
    val timeStampToDownload: MutableLiveData<Long> = MutableLiveData()

    // live data to update remote download status
    val remoteDownloadStatus: MutableLiveData<Long> = MutableLiveData()

    // various download status used as part of Work manager.
    enum class DownloadManagerStatus(val id: Long) {
        NOT_STARTED(-4L), FAILURE(-3L), NOT_REQUIRED(-2L), IN_PROGRESS(-1L), SUCCESS(0L);
    }

    enum class DownloadType(val id: Int) {
        LOCAL(0), REMOTE(1);

        fun isLocal(): Boolean {
            return this == LOCAL
        }

        fun isRemote(): Boolean {
            return this == REMOTE
        }
    }

    fun isDownloadRequired(type: DownloadType, retryCount: Int) {
        timeStampToDownload.postValue(DownloadManagerStatus.IN_PROGRESS.id)
        val url = constructDownloadCheckUrl(type)
        val request = Request.Builder().url(url).build()

        RetrofitManager.okHttpClient(getDnsTypeOnRetryCount(retryCount)).newCall(
            request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i(LOG_TAG_DOWNLOAD,
                      "onFailure attempt to retry($retryCount), cancelled? ${call.isCanceled()}, exec? ${call.isExecuted()} with exception: ${e.message}")

                if (retryCount > 3) {
                    timeStampToDownload.postValue(DownloadManagerStatus.FAILURE.id)
                    return
                }

                isDownloadRequired(type, retryCount + 1)
            }

            override fun onResponse(call: Call, response: Response) {
                processCheckDownloadResponse(type, response)
            }
        })
    }

    private fun getDnsTypeOnRetryCount(retryCount: Int): RetrofitManager.Companion.OkHttpDnsType {
        return when (retryCount) {
            0 -> RetrofitManager.Companion.OkHttpDnsType.SYSTEM_DNS
            1 -> RetrofitManager.Companion.OkHttpDnsType.CLOUDFLARE
            2 -> RetrofitManager.Companion.OkHttpDnsType.GOOGLE
            else -> RetrofitManager.Companion.OkHttpDnsType.SYSTEM_DNS
        }
    }

    private fun processCheckDownloadResponse(type: DownloadType, response: Response) {
        val stringResponse = response.body?.string() ?: return
        response.body?.close()
        try {
            val json = JSONObject(stringResponse)
            val version = json.optInt(Constants.JSON_VERSION, 0)
            if (DEBUG) Log.d(LOG_TAG_DOWNLOAD,
                             "client onResponse for refresh blocklist files:  $version")
            if (version != Constants.UPDATE_CHECK_RESPONSE_VERSION) {
                timeStampToDownload.postValue(DownloadManagerStatus.NOT_REQUIRED.id)
                return
            }

            val shouldUpdate = json.optBoolean(Constants.JSON_UPDATE, false)
            val timestamp = json.optLong(Constants.JSON_LATEST, INIT_TIME_MS)
            if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "onResponse:  update? $shouldUpdate")

            // post timestamp if there is update available or re-download request
            if (shouldUpdate) {
                setUpdatableTimestamp(timestamp, type)
                timeStampToDownload.postValue(timestamp)
                return
            }

            timeStampToDownload.postValue(DownloadManagerStatus.NOT_REQUIRED.id)
        } catch (e: JSONException) {
            timeStampToDownload.postValue(DownloadManagerStatus.FAILURE.id)
        }
    }

    private fun setUpdatableTimestamp(timestamp: Long, type: DownloadType) {
        if (type.isLocal()) {
            persistentState.newestLocalBlocklistTimestamp = timestamp
        } else {
            persistentState.newestRemoteBlocklistTimestamp = timestamp
        }
    }

    private fun constructDownloadCheckUrl(type: DownloadType): String {
        val timestamp = if (type == DownloadType.LOCAL) {
            persistentState.localBlocklistTimestamp
        } else {
            persistentState.remoteBlocklistTimestamp
        }
        val appVersionCode = persistentState.appVersion
        val url = "${Constants.ONDEVICE_BLOCKLIST_UPDATE_CHECK_URL}$timestamp&${Constants.ONDEVICE_BLOCKLIST_UPDATE_CHECK_PARAMETER_VCODE}$appVersionCode"
        Log.i(LOG_TAG_DOWNLOAD, "Check for download, download type ${type.name} url: $url")
        return url
    }

    /**
     * Responsible for downloading the local blocklist files.
     * For local blocklist, we need filetag.json, basicconfig.json, rd.txt and td.txt
     */
    fun downloadLocalBlocklist(timestamp: Long) {
        if (persistentState.useCustomDownloadManager) {
            initiateCustomDownloadManager(timestamp)
            return
        }

        initiateAndroidDownloadManager(timestamp)
    }

    fun downloadRemoteBlocklist(timestamp: Long) {
        remoteDownloadStatus.postValue(DownloadManagerStatus.IN_PROGRESS.id)
        purge(context, timestamp, DownloadType.REMOTE)

        val retrofit = RetrofitManager.getBlocklistBaseBuilder(
            RetrofitManager.Companion.OkHttpDnsType.DEFAULT).addConverterFactory(
            GsonConverterFactory.create()).build()
        val retrofitInterface = retrofit.create(IBlocklistDownload::class.java)
        val request = retrofitInterface.downloadRemoteBlocklistFile(
            Constants.FILETAG_TEMP_DOWNLOAD_URL)

        Log.i(LOG_TAG_DOWNLOAD,
              "Remote blocklist download request with $request with url? ${request.request().url} ")

        request.enqueue(object : retrofit2.Callback<JsonObject?> {
            override fun onResponse(call: retrofit2.Call<JsonObject?>,
                                    response: retrofit2.Response<JsonObject?>) {
                if (response.isSuccessful) {
                    saveFileTag(response.body(), timestamp)
                    // reset updatable time stamp
                    setUpdatableTimestamp(INIT_TIME_MS, DownloadType.REMOTE)
                    remoteDownloadStatus.postValue(DownloadManagerStatus.SUCCESS.id)
                } else {
                    Log.i(LOG_TAG_DOWNLOAD,
                          "Remote blocklist download failure, call? ${call.isExecuted}, response: $response ")
                    remoteDownloadStatus.postValue(DownloadManagerStatus.FAILURE.id)
                }
            }

            override fun onFailure(call: retrofit2.Call<JsonObject?>, t: Throwable) {
                Log.i(LOG_TAG_DOWNLOAD,
                      "Remote blocklist download failure with error: ${t.message}")
                remoteDownloadStatus.postValue(DownloadManagerStatus.FAILURE.id)
            }
        })
    }

    private fun saveFileTag(jsonObject: JsonObject?, timestamp: Long) {
        try {
            val filetag = makeFile(timestamp) ?: return

            filetag.writeText(jsonObject.toString())
            persistentState.remoteBlocklistTimestamp = timestamp
            // write the file tag json file into database
            io {
                RethinkBlocklistManager.readJson(context, DownloadType.REMOTE, timestamp)
            }
        } catch (e: IOException) {
            persistentState.remoteBlocklistTimestamp = INIT_TIME_MS
            Log.w(LOG_TAG_DOWNLOAD, "could not create filetag.json at version $timestamp", e)
        }
    }

    private fun makeFile(timestamp: Long): File? {
        val dir = Utilities.remoteBlocklistFile(context,
                                                Constants.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                                                timestamp) ?: return null

        if (!dir.exists()) {
            dir.mkdirs()
        }
        val filePath = File(dir.absolutePath + Constants.ONDEVICE_BLOCKLIST_FILE_TAG)
        if (!filePath.exists()) {
            filePath.createNewFile()
        }
        return filePath
    }

    private fun initiateAndroidDownloadManager(timestamp: Long) {
        purge(context, timestamp, DownloadType.LOCAL)

        downloadIds = LongArray(ONDEVICE_BLOCKLISTS.count())
        ONDEVICE_BLOCKLISTS.forEachIndexed { i, it ->
            val fileName = it.filename
            if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "v: ($timestamp), f: $fileName, u: $it.url")
            downloadIds[i] = enqueueDownload(it.url, fileName, timestamp.toString())
        }
        initiateDownloadStatusCheck(timestamp)
    }

    private fun initiateCustomDownloadManager(timestamp: Long) {
        io {
            val customDownloadManager = CustomDownloadManager(context)
            ConnectivityHelper.downloadIds.clear()
            Constants.ONDEVICE_BLOCKLISTS_TEMP.forEachIndexed { _, onDeviceBlocklistsMetadata ->
                val id = generateCustomDownloadId()
                val file = File(
                    localBlocklistDownloadBasePath(context, LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                                                   timestamp))
                file.mkdirs()
                val filename = file.absolutePath + onDeviceBlocklistsMetadata.filename
                customDownloadManager.download(id, filename, onDeviceBlocklistsMetadata.url)
            }

            startCustomDownloadWorker(timestamp)
        }
    }

    private fun startCustomDownloadWorker(timestamp: Long) {
        val data = Data.Builder()
        data.putLong("workerStartTime", SystemClock.elapsedRealtime())
        data.putLong("blocklistTimestamp", timestamp)
        val downloadWatcher = OneTimeWorkRequestBuilder<LocalBlocklistDownloader>().setInputData(
            data.build()).setBackoffCriteria(BackoffPolicy.LINEAR,
                                             OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                                             TimeUnit.MILLISECONDS).addTag(
            LocalBlocklistDownloader.CUSTOM_DOWNLOAD).build()

        WorkManager.getInstance(context).beginWith(downloadWatcher).enqueue()
    }

    private fun generateCustomDownloadId(): Long {
        val id = persistentState.customDownloaderLastGeneratedId + 1
        persistentState.customDownloaderLastGeneratedId = id
        return id
    }

    private fun initiateDownloadStatusCheck(timestamp: Long) {
        val data = Data.Builder()
        data.putLong("workerStartTime", SystemClock.elapsedRealtime())
        data.putLongArray("downloadIds", downloadIds)

        val downloadWatcher = OneTimeWorkRequestBuilder<DownloadWatcher>().setInputData(
            data.build()).setBackoffCriteria(BackoffPolicy.LINEAR,
                                             OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                                             TimeUnit.MILLISECONDS).addTag(
            DOWNLOAD_TAG).setInitialDelay(10, TimeUnit.SECONDS).build()

        val timestampWorkerData = workDataOf("blocklistDownloadInitiatedTime" to timestamp)

        val fileHandler = OneTimeWorkRequestBuilder<FileHandleWorker>().setInputData(
            timestampWorkerData).setBackoffCriteria(BackoffPolicy.LINEAR,
                                                    OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                                                    TimeUnit.MILLISECONDS).addTag(FILE_TAG).build()

        WorkManager.getInstance(context).beginWith(downloadWatcher).then(fileHandler).enqueue()

    }

    /**
     * Init for downloads.
     * Delete all the old files which are available in the download path.
     * Handles are the preliminary check before initiating the download.
     */
    private fun purge(context: Context, timestamp: Long, type: DownloadType) {
        if (type == DownloadType.LOCAL) downloadIds = LongArray(ONDEVICE_BLOCKLISTS.count())
        BlocklistDownloadHelper.deleteOldFiles(context, timestamp, type)
    }

    private fun enqueueDownload(url: String, fileName: String, timestamp: String): Long {
        downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadUri = Uri.parse(url)
        val request = DownloadManager.Request(downloadUri)
        request.apply {
            setTitle(fileName)
            setDescription(fileName)
            request.setDestinationInExternalFilesDir(context,
                                                     BlocklistDownloadHelper.getExternalFilePath(
                                                         timestamp), fileName)
            val downloadId = downloadManager.enqueue(this)
            if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "filename - $fileName, downloadID - $downloadId")
            return downloadId
        }
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            f()
        }
    }

}
