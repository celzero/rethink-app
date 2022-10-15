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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.customdownloader.ConnectivityHelper.downloadIds
import com.celzero.bravedns.customdownloader.RetrofitManager.Companion.getBlocklistBaseBuilder
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.NotificationHandlerDialog
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.*
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.*
import kotlin.coroutines.CoroutineContext
import kotlin.math.pow

class CustomDownloadManager(private val context: Context) : CoroutineScope {

    private var job: Job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    companion object {
        private const val DOWNLOAD_NOTICATION_TAG = "DOWNLOAD_ALERTS"
        private const val DOWNLOAD_NOTICATION_ID = 110
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
            val retrofit = getBlocklistBaseBuilder(
                RetrofitManager.Companion.OkHttpDnsType.DEFAULT).build().create(
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

    private fun updateDownloadStatus(downloadId: Long, result: ConnectivityHelper.DownloadStatus) {
        // hide progress and show the download complete
        downloadIds[downloadId] = result
        var status = true
        downloadIds.forEach { (_, downloadStatus) ->
            if (downloadStatus != ConnectivityHelper.DownloadStatus.SUCCESSFUL) {
                status = false
            }
        }

        if (status) notifyDownloadSuccess()
    }

    private fun downloadFile(body: ResponseBody?,
                             fileName: String): ConnectivityHelper.DownloadStatus {

        if (body == null) {
            return ConnectivityHelper.DownloadStatus.FAILED
        }

        showNotification()
        var bis: InputStream? = null
        var output: OutputStream? = null
        try {
            // below code will download the code with additional calculation of
            // file size and download percentage
            var totalFileSize: Double
            var count: Int
            val data = ByteArray(1024 * 4)
            val fileSize = body.contentLength()
            bis = BufferedInputStream(body.byteStream(), 1024 * 8)
            val outputFile = File(fileName)
            output = FileOutputStream(outputFile)
            var total: Long = 0
            val startTime = System.currentTimeMillis()
            var timeCount = 1
            var prevProgress = 0
            while (bis.read(data).also { count = it } != -1) {
                total += count.toLong()
                totalFileSize = (fileSize / 1024.0.pow(2.0))
                val current = (total / 1024.0.pow(2.0))
                val progress = (total * 100 / fileSize).toInt()
                val currentTime = System.currentTimeMillis() - startTime
                val download = DownloadFile()
                download.totalFileSize = totalFileSize
                if (prevProgress - progress >= 5 || prevProgress - progress <= 5) {
                    prevProgress = progress
                }
                if (currentTime > 1000 * timeCount) {
                    download.currentFileSize = current
                    download.progress = progress
                    updateProgress(download.progress)
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
            Log.i(LoggerConstants.LOG_TAG_DOWNLOAD, "Download success for file $fileName")
            return ConnectivityHelper.DownloadStatus.SUCCESSFUL
        } catch (e: Exception) {
            Log.e(LoggerConstants.LOG_TAG_DOWNLOAD, "failure error: ${e.message}", e)
            notifyDownloadFailure()
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

    lateinit var builder: NotificationCompat.Builder

    private fun showNotification() {
        val notificationManager = context.getSystemService(
            VpnService.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, NotificationHandlerDialog::class.java)
        intent.putExtra(Constants.NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME,
                        Constants.NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE)


        val pendingIntent = Utilities.getActivityPendingIntent(context, Intent(context,
                                                                               HomeScreenActivity::class.java),
                                                               PendingIntent.FLAG_UPDATE_CURRENT,
                                                               mutable = false)

        if (Utilities.isAtleastO()) {
            val name: CharSequence = context.getString(R.string.notif_channel_download)
            val description = context.resources.getString(R.string.notif_channed_desc_download)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(DOWNLOAD_NOTICATION_TAG, name, importance)
            channel.description = description
            notificationManager.createNotificationChannel(channel)
            builder = NotificationCompat.Builder(context, DOWNLOAD_NOTICATION_TAG)
        } else {
            builder = NotificationCompat.Builder(context, DOWNLOAD_NOTICATION_TAG)
        }

        val contentTitle = context.getString(R.string.notif_download_content_title)
        val contentText = context.getString(R.string.notif_download_content_text)
        builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(contentTitle).setContentIntent(
            pendingIntent).setContentText(contentText)
        builder.setProgress(100, 0, false)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        builder.color = ContextCompat.getColor(context, Utilities.getThemeAccent(context))

        // Secret notifications are not shown on the lock screen.  No need for this app to show there.
        // Only available in API >= 21
        builder = builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)

        // If true, silences this instance of the notification, regardless of the sounds or
        // vibrations set on the notification or notification channel.
        builder.setSilent(true)
        builder.setAutoCancel(false)

        notificationManager.notify(DOWNLOAD_NOTICATION_TAG, DOWNLOAD_NOTICATION_ID, builder.build())

    }

    private fun updateProgress(progress: Int) {
        val notificationManager = context.getSystemService(
            VpnService.NOTIFICATION_SERVICE) as NotificationManager
        builder.setProgress(100, progress, false)
        notificationManager.notify(DOWNLOAD_NOTICATION_TAG, DOWNLOAD_NOTICATION_ID, builder.build())
    }

    private fun notifyDownloadFailure() {
        val notificationManager = context.getSystemService(
            VpnService.NOTIFICATION_SERVICE) as NotificationManager
        val contentText = context.getString(R.string.notif_download_failure_content)
        builder.setProgress(0, 0, false)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        notificationManager.notify(DOWNLOAD_NOTICATION_TAG, DOWNLOAD_NOTICATION_ID, builder.build())
    }

    private fun notifyDownloadSuccess() {
        val notificationManager = context.getSystemService(
            VpnService.NOTIFICATION_SERVICE) as NotificationManager
        val contentText = context.getString(R.string.notif_download_success_content)
        builder.setProgress(0, 0, false)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        notificationManager.notify(DOWNLOAD_NOTICATION_TAG, DOWNLOAD_NOTICATION_ID, builder.build())
    }
}
