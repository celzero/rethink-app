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
package com.celzero.bravedns.customdownloader

import android.util.Log
import com.celzero.bravedns.customdownloader.ConnectivityHelper.downloadIds
import com.celzero.bravedns.customdownloader.RetrofitManager.Companion.getBlocklistBaseBuilder
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.LoggerConstants
import kotlinx.coroutines.*
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.*
import kotlin.coroutines.CoroutineContext
import kotlin.math.pow

class CustomDownloadManager : CoroutineScope {

    private var job: Job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    // call this method to cancel a coroutine
    // e.g. when user clicks on cancel on the download dialog
    fun cancelDownload() {
        downloadIds.clear()
        job.cancel()
    }

    fun download(downloadId: Long, fileName: String, url: String) = launch {
        withContext(coroutineContext) {
            initDownload(downloadId)
            downloadFiles(downloadId, url, fileName)
        }
    }

    private fun downloadFiles(downloadId: Long, url: String, fileName: String) = launch {
        withContext(coroutineContext) {
            // enable the OkHttp's logging only in debug mode for testing
            if (DEBUG) OkHttpDebugLogging.enableHttp2()
            if (DEBUG) OkHttpDebugLogging.enableTaskRunner()

            // create okhttp client with base url as https://download.rethinkdns.com
            val retrofit = getBlocklistBaseBuilder().build().create(
                IBlocklistDownload::class.java)
            val request = retrofit.downloadLocalBlocklistFile(url)

            var status: ConnectivityHelper.DownloadStatus

            request?.enqueue(object : Callback<ResponseBody?> {
                override fun onResponse(call: Call<ResponseBody?>,
                                        response: Response<ResponseBody?>) {
                    io {
                        if (response.isSuccessful) {
                            status = downloadFile(response.body(), fileName)
                            updateDownloadStatus(downloadId, status)
                            return@io
                        }

                        status = ConnectivityHelper.DownloadStatus.FAILED
                        updateDownloadStatus(downloadId, status)
                    }
                }

                override fun onFailure(call: Call<ResponseBody?>, t: Throwable) {
                    status = ConnectivityHelper.DownloadStatus.FAILED
                    updateDownloadStatus(downloadId, status)
                }
            })
        }
    }

    private fun initDownload(downloadId: Long) {
        // initiate the download and show the dialog with progress bar
        downloadIds[downloadId] = ConnectivityHelper.DownloadStatus.RUNNING
    }

    private fun updateDownloadStatus(downloadId: Long,
                                     result: ConnectivityHelper.DownloadStatus) {
        // hide progress and show the download complete
        downloadIds[downloadId] = result
    }

    private fun downloadFile(body: ResponseBody?,
                             fileName: String): ConnectivityHelper.DownloadStatus {

        if (body == null) {
            return ConnectivityHelper.DownloadStatus.FAILED
        }

        var bis: InputStream? = null
        var output: OutputStream? = null
        try {
            // below code will download the code with additional calculation of
            // file size and download percentage
            var totalFileSize = 0
            var count: Int
            val data = ByteArray(1024 * 4)
            val fileSize = body!!.contentLength()
            bis = BufferedInputStream(body.byteStream(), 1024 * 8)
            val outputFile = File(fileName)
            output = FileOutputStream(outputFile)
            var total: Long = 0
            val startTime = System.currentTimeMillis()
            var timeCount = 1
            var prevProgress = 0
            while (bis.read(data).also { count = it } != -1) {
                total += count.toLong()
                totalFileSize = (fileSize / 1024.0.pow(2.0)).toInt()
                val current = (total / 1024.0.pow(2.0))
                val progress = (total * 100 / fileSize).toInt()
                val currentTime = System.currentTimeMillis() - startTime
                val download = DownloadFile()
                download.totalFileSize = totalFileSize
                if (prevProgress - progress >= 5 || prevProgress - progress <= 5) {
                    prevProgress = progress
                }
                if (currentTime > 1000 * timeCount) {
                    download.currentFileSize = current.toInt()
                    download.progress = progress
                    timeCount++
                }
                output.write(data, 0, count)
            }

            // the code to download the file without the calculations (download percentage)
            /*var count: Int
            bis = BufferedInputStream(body?.byteStream(), 1024 * 8)
            val data = ByteArray(1024 * 4)
            val outputFile = File(fileName)
            output = FileOutputStream(outputFile)
            while (true) {
                count = bis.read(data)
                if (count == -1) {
                    break
                }
                output.write(data, 0, count)
            }*/
            output.flush()
            return ConnectivityHelper.DownloadStatus.SUCCESSFUL
        } catch (e: Exception) {
            Log.e(LoggerConstants.LOG_TAG_DOWNLOAD, "failure error: ${e.message}", e)
            return ConnectivityHelper.DownloadStatus.FAILED
        } finally {
            output?.close()
            bis?.close()
        }
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(coroutineContext).launch {
            f()
        }
    }
}
