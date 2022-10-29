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
package com.celzero.bravedns.customdownloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.RethinkBlocklistManager
import com.celzero.bravedns.customdownloader.RetrofitManager.Companion.getBlocklistBaseBuilder
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.download.BlocklistDownloadHelper
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.NotificationHandlerDialog
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DOWNLOAD
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.blocklistDownloadBasePath
import com.celzero.bravedns.util.Utilities.Companion.tempDownloadBasePath
import dnsx.Dnsx
import okhttp3.ResponseBody
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.*
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class LocalBlocklistCoordinator(val context: Context, workerParams: WorkerParameters) :
        CoroutineWorker(context, workerParams), KoinComponent {

    val persistentState by inject<PersistentState>()
    val appConfig by inject<AppConfig>()
    private var downloadStatuses: MutableMap<Long, DownloadStatus> = hashMapOf()

    // download request status
    enum class DownloadStatus {
        FAILED, RUNNING, SUCCESSFUL
    }

    lateinit var builder: NotificationCompat.Builder

    companion object {
        private val BLOCKLIST_DOWNLOAD_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(40)
        const val CUSTOM_DOWNLOAD = "CUSTOM_DOWNLOAD_WORKER_LOCAL"

        private const val DOWNLOAD_NOTIFICATION_TAG = "DOWNLOAD_ALERTS"
        private const val DOWNLOAD_NOTIFICATION_ID = 110
    }

    override suspend fun doWork(): Result {
        try {
            val startTime = inputData.getLong("workerStartTime", 0)
            val timestamp = inputData.getLong("blocklistTimestamp", 0)

            if (SystemClock.elapsedRealtime() - startTime > BLOCKLIST_DOWNLOAD_TIMEOUT_MS) {
                return Result.failure()
            }

            return when (processDownload(timestamp)) {
                false -> {
                    Result.failure()
                }
                true -> {
                    // update the download related persistence status on download success
                    updatePersistenceOnCopySuccess(timestamp)
                    Result.success()
                }
            }
        } catch (ex: CancellationException) {
            Log.e(LOG_TAG_DOWNLOAD,
                  "Local blocklist download, received cancellation exception: ${ex.message}", ex)
            notifyDownloadCancelled(context)
        } finally {
            clearDownloadList()
        }
        return Result.failure()
    }

    private suspend fun processDownload(timestamp: Long): Boolean {
        val file = makeTempDownloadDir(timestamp) ?: return false

        Constants.ONDEVICE_BLOCKLISTS_TEMP.forEachIndexed { _, onDeviceBlocklistsMetadata ->
            val id = generateCustomDownloadId()

            downloadStatuses[id] = DownloadStatus.RUNNING
            val filePath = file.absolutePath + onDeviceBlocklistsMetadata.filename

            when (startFileDownload(context, onDeviceBlocklistsMetadata.url, filePath)) {
                true -> {
                    downloadStatuses[id] = DownloadStatus.SUCCESSFUL
                }
                false -> {
                    downloadStatuses[id] = DownloadStatus.FAILED
                    return false
                }
            }
        }

        if (!isDownloadComplete(file)) {
            Log.e(LOG_TAG_DOWNLOAD, "Local blocklist validation failed for timestamp: $timestamp")
            notifyDownloadFailure(context)
            return false
        }
        if (!moveLocalBlocklistFiles(context, timestamp)) {
            Log.e(LOG_TAG_DOWNLOAD, "Issue while moving the downloaded files: $timestamp")
            notifyDownloadFailure(context)
            return false
        }
        if (!isLocalBlocklistDownloadValid(context, timestamp)) {
            Log.e(LOG_TAG_DOWNLOAD, "Invalid download for local blocklist files: $timestamp")
            notifyDownloadFailure(context)
            return false
        }

        BlocklistDownloadHelper.deleteBlocklistResidue(context,
                                                       LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                                                       timestamp)
        notifyDownloadSuccess(context)
        return true
    }

    private fun generateCustomDownloadId(): Long {
        val id = persistentState.customDownloaderLastGeneratedId + 1
        persistentState.customDownloaderLastGeneratedId = id
        return id
    }

    private fun makeTempDownloadDir(timestamp: Long): File? {
        try {
            val file = File(
                tempDownloadBasePath(context, LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME, timestamp))
            if (!file.exists()) {
                file.mkdirs()
            }
            return file
        } catch (ex: IOException) {
            Log.e(LOG_TAG_DOWNLOAD, "Error creating temp folder $timestamp, ${ex.message}", ex)
        }
        return null
    }

    private suspend fun startFileDownload(context: Context, url: String,
                                          fileName: String): Boolean {
        // enable the OkHttp's logging only in debug mode for testing
        if (DEBUG) OkHttpDebugLogging.enableHttp2()
        if (DEBUG) OkHttpDebugLogging.enableTaskRunner()

        // create okhttp client with base url as https://download.rethinkdns.com
        val retrofit = getBlocklistBaseBuilder(
            RetrofitManager.Companion.OkHttpDnsType.DEFAULT).build().create(
            IBlocklistDownload::class.java)
        val response = retrofit.downloadLocalBlocklistFile(url)

        return if (response?.isSuccessful == true) {
            downloadFile(context, response.body(), fileName)
        } else {
            false
        }
    }

    private fun downloadFile(context: Context, body: ResponseBody?, fileName: String): Boolean {

        if (body == null) {
            return false
        }

        showNotification(context)
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
                    updateProgress(context, download.progress)
                    timeCount++
                }
                output.write(data, 0, count)
            }
            output.flush()
            Log.i(LOG_TAG_DOWNLOAD, "$fileName downloaded")
            return true
        } catch (e: Exception) {
            Log.e(LOG_TAG_DOWNLOAD, "$fileName download err: ${e.message}", e)
        } finally {
            output?.close()
            bis?.close()
        }
        return false
    }

    private fun isDownloadComplete(dir: File): Boolean {
        var result = false
        var total: Int? = 0
        try {
            if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "Local block list validation: ${dir.absolutePath}")

            total = if (dir.isDirectory) {
                dir.list()?.count()
            } else {
                0
            }
            result = Constants.ONDEVICE_BLOCKLISTS.count() == total
        } catch (e: Exception) {
            Log.w(LOG_TAG_DOWNLOAD, "Local block list validation failed: ${e.message}", e)
        }

        if (DEBUG) Log.d(LOG_TAG_DOWNLOAD,
                         "Valid on-device blocklist in folder (${dir.name}) download? $result, files: $total, dir? ${dir?.isDirectory}")
        return result
    }

    private fun moveLocalBlocklistFiles(context: Context, timestamp: Long): Boolean {
        try {
            val from = File(
                tempDownloadBasePath(context, LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME, timestamp))

            if (!from.isDirectory) {
                if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "Invalid from: ${from.name} dir")
                return false
            }

            val to = File(
                blocklistDownloadBasePath(context, LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME, timestamp))
            if (!to.exists()) {
                to.mkdir()
            }

            from.listFiles()?.forEach {
                val dest = File(to.absolutePath + File.separator + it.name)
                val result = it.copyTo(dest, true)
                if (!result.isFile) {
                    if (DEBUG) Log.d(LOG_TAG_DOWNLOAD,
                                     "Copy failed from ${it.path} to ${dest.path}")
                    return false
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(LOG_TAG_DOWNLOAD, "Error copying files to local blocklist folder", e)
        }
        return false
    }

    private fun isLocalBlocklistDownloadValid(context: Context, timestamp: Long): Boolean {
        try {
            val path: String = blocklistDownloadBasePath(context,
                                                         LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                                                         timestamp)
            val braveDNS = Dnsx.newBraveDNSLocal(path + Constants.ONDEVICE_BLOCKLIST_FILE_TD,
                                                 path + Constants.ONDEVICE_BLOCKLIST_FILE_RD,
                                                 path + Constants.ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG,
                                                 path + Constants.ONDEVICE_BLOCKLIST_FILE_TAG)
            if (DEBUG) Log.d(LOG_TAG_DOWNLOAD,
                             "AppDownloadManager isDownloadValid? ${braveDNS != null}")
            return braveDNS != null
        } catch (e: Exception) {
            Log.e(LOG_TAG_DOWNLOAD, "AppDownloadManager isDownloadValid exception: ${e.message}", e)
        }
        return false

    }

    private fun getBuilder(context: Context): NotificationCompat.Builder {
        if (this::builder.isInitialized) {
            return builder
        }

        if (Utilities.isAtleastO()) {
            val name: CharSequence = context.getString(R.string.notif_channel_download)
            val description = context.resources.getString(R.string.notif_channed_desc_download)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(DOWNLOAD_NOTIFICATION_TAG, name, importance)
            channel.description = description
            getNotificationManager(context).createNotificationChannel(channel)
            builder = NotificationCompat.Builder(context, DOWNLOAD_NOTIFICATION_TAG)
            val contentText = context.getString(R.string.notif_download_content_text)
            val contentTitle = context.getString(R.string.notif_download_content_title)

            builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(
                contentTitle).setContentIntent(getPendingIntent(context)).setContentText(
                contentText)
            builder.setProgress(100, 0, false)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            builder.color = ContextCompat.getColor(context, Utilities.getThemeAccent(context))

            // Secret notifications are not shown on the lock screen.  No need for this app to show there.
            // Only available in API >= 21
            builder.setVisibility(NotificationCompat.VISIBILITY_SECRET)

            // If true, silences this instance of the notification, regardless of the sounds or
            // vibrations set on the notification or notification channel.
            builder.setSilent(true)
            builder.setAutoCancel(false)
        } else {
            builder = NotificationCompat.Builder(context, DOWNLOAD_NOTIFICATION_TAG)
        }
        return builder
    }

    private fun getNotificationManager(context: Context): NotificationManager {
        return context.getSystemService(VpnService.NOTIFICATION_SERVICE) as NotificationManager

    }

    private fun getPendingIntent(context: Context): PendingIntent {
        return Utilities.getActivityPendingIntent(context,
                                                  Intent(context, HomeScreenActivity::class.java),
                                                  PendingIntent.FLAG_UPDATE_CURRENT,
                                                  mutable = false)
    }

    private fun showNotification(context: Context) {
        val intent = Intent(context, NotificationHandlerDialog::class.java)
        intent.putExtra(Constants.NOTIF_INTENT_EXTRA_ACCESSIBILITY_NAME,
                        Constants.NOTIF_INTENT_EXTRA_ACCESSIBILITY_VALUE)

        val builder = getBuilder(context)


        getNotificationManager(context).notify(DOWNLOAD_NOTIFICATION_TAG, DOWNLOAD_NOTIFICATION_ID,
                                               builder.build())
    }

    private fun updateProgress(context: Context, progress: Int) {
        val builder = getBuilder(context)
        builder.setProgress(100, progress, false)
        getNotificationManager(context).notify(DOWNLOAD_NOTIFICATION_TAG, DOWNLOAD_NOTIFICATION_ID,
                                               builder.build())
    }

    private fun notifyDownloadFailure(context: Context) {
        val builder = getBuilder(context)
        val contentText = context.getString(R.string.notif_download_failure_content)
        val contentTitle = context.getString(R.string.notif_download_content_title)

        builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(contentTitle).setContentIntent(
            getPendingIntent(context)).setContentText(contentText)
        builder.setProgress(0, 0, false)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        getNotificationManager(context).notify(DOWNLOAD_NOTIFICATION_TAG, DOWNLOAD_NOTIFICATION_ID,
                                               builder.build())
    }

    private fun notifyDownloadSuccess(context: Context) {
        val builder = getBuilder(context)
        val contentText = context.getString(R.string.notif_download_success_content)
        val contentTitle = context.getString(R.string.notif_download_content_title)

        builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(contentTitle).setContentIntent(
            getPendingIntent(context)).setContentText(contentText)
        builder.setProgress(0, 0, false)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        getNotificationManager(context).notify(DOWNLOAD_NOTIFICATION_TAG, DOWNLOAD_NOTIFICATION_ID,
                                               builder.build())
    }

    private fun notifyDownloadCancelled(context: Context) {
        val builder = getBuilder(context)
        val contentText = context.getString(R.string.notif_download_cancel_content)
        val contentTitle = context.getString(R.string.notif_download_content_title)

        builder.setSmallIcon(R.drawable.dns_icon).setContentTitle(contentTitle).setContentIntent(
            getPendingIntent(context)).setContentText(contentText)
        builder.setProgress(0, 0, false)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        getNotificationManager(context).notify(DOWNLOAD_NOTIFICATION_TAG, DOWNLOAD_NOTIFICATION_ID,
                                               builder.build())
    }

    private fun clearDownloadList() {
        downloadStatuses.clear()
    }

    private suspend fun updatePersistenceOnCopySuccess(timestamp: Long) {
        persistentState.localBlocklistTimestamp = timestamp
        persistentState.blocklistEnabled = true
        // reset updatable time stamp
        persistentState.newestLocalBlocklistTimestamp = INIT_TIME_MS
        // write the file tag json file into database
        RethinkBlocklistManager.readJson(context, AppDownloadManager.DownloadType.LOCAL, timestamp)
        // recreate bravedns object ()
        appConfig.recreateBraveDnsObj()
    }

}
