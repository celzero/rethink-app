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

import Logger
import Logger.LOG_TAG_DOWNLOAD
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.customdownloader.RetrofitManager.Companion.getBlocklistBaseBuilder
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.download.BlocklistDownloadHelper
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.ui.activity.AppLockActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.blocklistDownloadBasePath
import com.celzero.bravedns.util.Utilities.calculateMd5
import com.celzero.bravedns.util.Utilities.getTagValueFromJson
import com.celzero.bravedns.util.Utilities.tempDownloadBasePath
import okhttp3.ResponseBody
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LocalBlocklistCoordinator(val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams), KoinComponent {

    val persistentState by inject<PersistentState>()
    val appConfig by inject<AppConfig>()
    private var downloadStatuses: ConcurrentHashMap<Long, DownloadStatus> = ConcurrentHashMap()

    // download request status
    enum class DownloadStatus {
        FAILED,
        RUNNING,
        SUCCESSFUL
    }

    private lateinit var builder: NotificationCompat.Builder

    companion object {
        private val BLOCKLIST_DOWNLOAD_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(40)
        const val CUSTOM_DOWNLOAD = "CUSTOM_DOWNLOAD_WORKER_LOCAL"

        private const val DOWNLOAD_NOTIFICATION_TAG = "DOWNLOAD_ALERTS"
        private const val DOWNLOAD_NOTIFICATION_ID = 110
        private const val MAX_RETRY_COUNT = 3

        // Buffer sizes for file download
        private const val BUFFERED_INPUT_STREAM_SIZE = 8192 // 8KB
        private const val BYTE_BUFFER_SIZE = 4096 // 4KB

        // Progress update intervals
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1000 // 1 second

        // Byte conversion constants
        private const val BYTES_PER_MB = 1048576.0 // 1024 * 1024

        // Notification progress constants
        private const val NOTIFICATION_PROGRESS_MAX = 100
    }

    override suspend fun doWork(): Result {
        Logger.d(LOG_TAG_DOWNLOAD, "Local blocklist download started")
        try {
            val startTime = inputData.getLong("workerStartTime", 0)
            val timestamp = inputData.getLong("blocklistTimestamp", 0)

            if (runAttemptCount > MAX_RETRY_COUNT) {
                Logger.w(LOG_TAG_DOWNLOAD, "Local blocklist download failed after $MAX_RETRY_COUNT attempts")
                return Result.failure()
            }

            if (SystemClock.elapsedRealtime() - startTime > BLOCKLIST_DOWNLOAD_TIMEOUT_MS) {
                Logger.w(LOG_TAG_DOWNLOAD, "Local blocklist download timeout")
                return Result.failure()
            }

            return when (processDownload(timestamp)) {
                false -> {
                    if (isDownloadCancelled()) {
                        Logger.i(LOG_TAG_DOWNLOAD, "Local blocklist download cancelled")
                        notifyDownloadCancelled(context)
                    }
                    Logger.i(LOG_TAG_DOWNLOAD, "Local blocklist download failed")
                    Result.failure()
                }
                true -> {
                    // update the download related persistence status on download success
                    Logger.i(LOG_TAG_DOWNLOAD, "Local blocklist download success, updating ts: $timestamp")
                    updatePersistenceOnCopySuccess(timestamp)
                    Result.success()
                }
            }
        } catch (ex: CancellationException) {
            Logger.e(
                LOG_TAG_DOWNLOAD,
                "Local blocklist download, received cancellation exception: ${ex.message}",
                ex
            )
            notifyDownloadCancelled(context)
        } catch (ex: Exception) {
            Logger.e(
                LOG_TAG_DOWNLOAD,
                "Local blocklist download, received cancellation exception: ${ex.message}",
                ex
            )
            notifyDownloadFailure(context)
        } finally {
            clear()
        }
        return Result.failure()
    }

    private suspend fun processDownload(timestamp: Long): Boolean {
        // create a temp folder to download, format (timestamp ==> -timestamp)
        val file = makeTempDownloadDir(timestamp)

        if (file == null) {
            Logger.e(LOG_TAG_DOWNLOAD, "Error creating temp folder for download")
            return false
        }

        Constants.ONDEVICE_BLOCKLISTS_IN_APP.forEachIndexed { _, onDeviceBlocklistsMetadata ->
            val id = generateCustomDownloadId()

            downloadStatuses[id] = DownloadStatus.RUNNING
            val filePath = file.absolutePath + onDeviceBlocklistsMetadata.filename

            Logger.i(
                LOG_TAG_DOWNLOAD,
                "Downloading file: ${onDeviceBlocklistsMetadata.filename}, url: ${onDeviceBlocklistsMetadata.url}, id: $id"
            )

            if (isDownloadCancelled()) {
                Logger.i(LOG_TAG_DOWNLOAD, "Download cancelled, id: $id")
                return false
            }

            when (startFileDownload(context, onDeviceBlocklistsMetadata.url, filePath)) {
                true -> {
                    Logger.i(LOG_TAG_DOWNLOAD, "Download successful for id: $id")
                    downloadStatuses[id] = DownloadStatus.SUCCESSFUL
                }
                false -> {
                    Logger.e(LOG_TAG_DOWNLOAD, "Download failed for id: $id")
                    downloadStatuses[id] = DownloadStatus.FAILED
                    return false
                }
            }
        }
        // check if all the files are downloaded, as of now the check if for only number of files
        // downloaded. TODO: Later add checksum matching as well
        if (!isDownloadComplete(file)) {
            Logger.e(
                LOG_TAG_DOWNLOAD,
                "Local blocklist validation failed for timestamp: $timestamp"
            )
            notifyDownloadFailure(context)
            return false
        }

        if (isDownloadCancelled()) return false

        if (!moveLocalBlocklistFiles(context, timestamp)) {
            Logger.e(LOG_TAG_DOWNLOAD, "Issue while moving the downloaded files: $timestamp")
            notifyDownloadFailure(context)
            return false
        }

        if (isDownloadCancelled()) return false

        if (!isLocalBlocklistDownloadValid(context, timestamp)) {
            Logger.e(LOG_TAG_DOWNLOAD, "Invalid download for local blocklist files: $timestamp")
            notifyDownloadFailure(context)
            return false
        }

        if (isDownloadCancelled()) return false

        val result = updateTagsToDb(timestamp)
        if (!result) {
            Logger.e(LOG_TAG_DOWNLOAD, "Invalid download for local blocklist files: $timestamp")
            notifyDownloadFailure(context)
            return false
        }

        notifyDownloadSuccess(context)
        return true
    }

    private fun isDownloadCancelled(): Boolean {
        // return if the download is cancelled by the user
        // sometimes the worker cancellation is not received as exception
        Logger.i(LOG_TAG_DOWNLOAD, "Download cancel check, isStopped? $isStopped")
        return isStopped
    }

    private fun generateCustomDownloadId(): Long {
        val id = persistentState.customDownloaderLastGeneratedId + 1
        persistentState.customDownloaderLastGeneratedId = id
        return id
    }

    private fun makeTempDownloadDir(timestamp: Long): File? {
        try {
            val file =
                File(tempDownloadBasePath(context, LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME, timestamp))
            if (!file.exists()) {
                file.mkdirs()
            }
            return file
        } catch (ex: IOException) {
            Logger.e(LOG_TAG_DOWNLOAD, "Error creating temp folder $timestamp, ${ex.message}", ex)
        }
        return null
    }

    private suspend fun startFileDownload(
        context: Context,
        url: String,
        fileName: String,
        retryCount: Int = 0
    ): Boolean {
        // enable the OkHttp's logging only in debug mode for testing
        if (DEBUG) OkHttpDebugLogging.enableHttp2()
        if (DEBUG) OkHttpDebugLogging.enableTaskRunner()

        try {
            // create okhttp client with base url
            val retrofit =
                getBlocklistBaseBuilder(persistentState.routeRethinkInRethink).build().create(IBlocklistDownload::class.java)
            Logger.i(LOG_TAG_DOWNLOAD, "Downloading file: $fileName, url: $url")
            val response = retrofit.downloadLocalBlocklistFile(url, persistentState.appVersion, "")
            if (response?.isSuccessful == true) {
                return downloadFile(context, response.body(), fileName)
            } else {
                Logger.e(
                    LOG_TAG_DOWNLOAD,
                    "Error in startFileDownload: ${response?.message()}, code: ${response?.code()}"
                )
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_DOWNLOAD, "Error in startFileDownload: ${e.message}", e)
        }
        return if (isRetryRequired(retryCount)) {
            Logger.i(LOG_TAG_DOWNLOAD, "retrying download($url) $fileName, count: $retryCount")
            startFileDownload(context, url, fileName, retryCount + 1)
        } else {
            Logger.i(LOG_TAG_DOWNLOAD, "download failed for $fileName, retry: $retryCount")
            false
        }
    }

    private fun isRetryRequired(retryCount: Int): Boolean {
        Logger.i(LOG_TAG_DOWNLOAD, "Retry count: $retryCount")
        return retryCount < MAX_RETRY_COUNT
    }

    private fun downloadFile(context: Context, body: ResponseBody?, fileName: String): Boolean {
        if (body == null) {
            return false
        }

        showNotification(context)
        var input: InputStream? = null
        var output: OutputStream? = null
        try {
            val outputFile = File(fileName)
            output = FileOutputStream(outputFile)
            // below code will download the code with additional calculation of
            // file size and download percentage
            var bytesRead: Int
            val contentLength = body.contentLength()
            val expectedMB: Double = contentLength / BYTES_PER_MB
            var downloadedMB = 0.0
            input = BufferedInputStream(body.byteStream(), BUFFERED_INPUT_STREAM_SIZE)
            val startMs = SystemClock.elapsedRealtime()
            var progressJumpsMs = PROGRESS_UPDATE_INTERVAL_MS
            val buf = ByteArray(BYTE_BUFFER_SIZE)
            while (input.read(buf).also { bytesRead = it } != -1) {
                val elapsedMs = SystemClock.elapsedRealtime() - startMs
                downloadedMB += bytesToMB(bytesRead)
                val progress =
                    if (contentLength == Long.MAX_VALUE || expectedMB == 0.0) 0
                    else (downloadedMB * NOTIFICATION_PROGRESS_MAX / expectedMB).toInt()
                if (elapsedMs >= progressJumpsMs) {
                    updateProgress(context, progress)
                    // increase the next update duration linearly by another sec; ie,
                    // update in the intervals of once every [1, 2, 3, 4, ...] secs
                    progressJumpsMs += PROGRESS_UPDATE_INTERVAL_MS
                }
                output.write(buf, 0, bytesRead)
            }
            output.flush()
            Logger.i(LOG_TAG_DOWNLOAD, "$fileName > ${downloadedMB}MB downloaded")
            return true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_DOWNLOAD, "$fileName download err: ${e.message}", e)
        } finally {
            output?.close()
            input?.close()
        }
        return false
    }

    private fun bytesToMB(sz: Int): Double {
        return sz.toDouble() / BYTES_PER_MB
    }

    private fun isDownloadComplete(dir: File): Boolean {
        var result = false
        var total: Int? = 0
        try {
            Logger.d(LOG_TAG_DOWNLOAD, "Local block list validation: ${dir.absolutePath}")

            total =
                if (dir.isDirectory) {
                    dir.list()?.count()
                } else {
                    0
                }
            result = Constants.ONDEVICE_BLOCKLISTS_IN_APP.count() == total
        } catch (e: Exception) {
            Logger.w(LOG_TAG_DOWNLOAD, "Local block list validation failed: ${e.message}", e)
        }

        Logger.d(
            LOG_TAG_DOWNLOAD,
            "Valid on-device blocklist in folder (${dir.name}) download? $result, files: $total, dir? ${dir.isDirectory}"
        )
        return result
    }

    // move the files from temp location to actual location (folder name with timestamp)
    private fun moveLocalBlocklistFiles(context: Context, timestamp: Long): Boolean {
        try {
            val from =
                File(tempDownloadBasePath(context, LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME, timestamp))

            if (!from.isDirectory) {
                Logger.i(LOG_TAG_DOWNLOAD, "Invalid from: ${from.name} dir")
                return false
            }

            val to =
                File(
                    blocklistDownloadBasePath(
                        context,
                        LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                        timestamp
                    )
                )
            if (!to.exists()) {
                to.mkdir()
            }

            from.listFiles()?.forEach {
                val dest = File(to.absolutePath + File.separator + it.name)
                val result = it.copyTo(dest, true)
                if (!result.isFile) {
                    Logger.d(LOG_TAG_DOWNLOAD, "Copy failed from ${it.path} to ${dest.path}")
                    return false
                }
            }

            Logger.d(LOG_TAG_DOWNLOAD, "Copied file from ${from.path} to ${to.path}")
            return true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_DOWNLOAD, "err copying files to local blocklist folder", e)
        }
        return false
    }

    private fun isLocalBlocklistDownloadValid(context: Context, timestamp: Long): Boolean {
        try {
            val path: String =
                blocklistDownloadBasePath(context, LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME, timestamp)
            val tdmd5 = calculateMd5(path + Constants.ONDEVICE_BLOCKLIST_FILE_TD)
            val rdmd5 = calculateMd5(path + Constants.ONDEVICE_BLOCKLIST_FILE_RD)
            val remoteTdmd5 =
                getTagValueFromJson(path + Constants.ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG, "tdmd5")
            val remoteRdmd5 =
                getTagValueFromJson(path + Constants.ONDEVICE_BLOCKLIST_FILE_BASIC_CONFIG, "rdmd5")
            Logger.d(
                LOG_TAG_DOWNLOAD,
                "tdmd5: $tdmd5, rdmd5: $rdmd5, remotetd: $remoteTdmd5, remoterd: $remoteRdmd5"
            )
            val isDownloadValid = tdmd5 == remoteTdmd5 && rdmd5 == remoteRdmd5
            Logger.i(LOG_TAG_DOWNLOAD, "AppDownloadManager isDownloadValid? $isDownloadValid")
            return isDownloadValid
        } catch (e: Exception) {
            Logger.e(
                LOG_TAG_DOWNLOAD,
                "AppDownloadManager isDownloadValid exception: ${e.message}",
                e
            )
        }
        return false
    }

    private suspend fun updateTagsToDb(timestamp: Long): Boolean {
        // write the file tag json file into database
        return RethinkBlocklistManager.readJson(
            context,
            RethinkBlocklistManager.DownloadType.LOCAL,
            timestamp
        )
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

            builder
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(contentTitle)
                .setContentIntent(getPendingIntent(context))
                .setContentText(contentText)
            builder.setProgress(NOTIFICATION_PROGRESS_MAX, 0, false)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            builder.color =
                ContextCompat.getColor(context, UIUtils.getAccentColor(persistentState.theme))

            // Secret notifications are not shown on the lock screen.  No need for this app to show
            // there.
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
        return Utilities.getActivityPendingIntent(
            context,
            Intent(context, AppLockActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            mutable = false
        )
    }

    private fun showNotification(context: Context) {
        getNotificationManager(context)
            .notify(
                DOWNLOAD_NOTIFICATION_TAG,
                DOWNLOAD_NOTIFICATION_ID,
                getBuilder(context).build()
            )
    }

    private fun updateProgress(context: Context, progress: Int) {
        val builder = getBuilder(context)
        val cur = if (progress <= 0) 0 else progress
        val max = if (cur <= 0) 0 else NOTIFICATION_PROGRESS_MAX
        val forever = cur <= 0
        builder.setProgress(max, cur, forever)
        getNotificationManager(context)
            .notify(DOWNLOAD_NOTIFICATION_TAG, DOWNLOAD_NOTIFICATION_ID, builder.build())
    }

    private fun notifyDownloadFailure(context: Context) {
        val builder = getBuilder(context)
        val contentText = context.getString(R.string.notif_download_failure_content)
        val contentTitle = context.getString(R.string.notif_download_content_title)

        builder
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(contentTitle)
            .setContentIntent(getPendingIntent(context))
            .setContentText(contentText)
        builder.setProgress(0, 0, false)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        getNotificationManager(context)
            .notify(DOWNLOAD_NOTIFICATION_TAG, DOWNLOAD_NOTIFICATION_ID, builder.build())
    }

    private fun notifyDownloadSuccess(context: Context) {
        val builder = getBuilder(context)
        val contentText = context.getString(R.string.notif_download_success_content)
        val contentTitle = context.getString(R.string.notif_download_content_title)

        builder
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(contentTitle)
            .setContentIntent(getPendingIntent(context))
            .setContentText(contentText)
        builder.setProgress(0, 0, false)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        getNotificationManager(context)
            .notify(DOWNLOAD_NOTIFICATION_TAG, DOWNLOAD_NOTIFICATION_ID, builder.build())
    }

    private fun notifyDownloadCancelled(context: Context) {
        val builder = getBuilder(context)
        val contentText = context.getString(R.string.notif_download_cancel_content)
        val contentTitle = context.getString(R.string.notif_download_content_title)

        builder
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(contentTitle)
            .setContentIntent(getPendingIntent(context))
            .setContentText(contentText)
        builder.setProgress(0, 0, false)
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
        getNotificationManager(context)
            .notify(DOWNLOAD_NOTIFICATION_TAG, DOWNLOAD_NOTIFICATION_ID, builder.build())
    }

    private fun clear() {
        downloadStatuses.clear()
        BlocklistDownloadHelper.deleteBlocklistResidue(
            context,
            LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
            persistentState.localBlocklistTimestamp
        )
    }

    private fun updatePersistenceOnCopySuccess(timestamp: Long) {
        persistentState.localBlocklistTimestamp = timestamp
        persistentState.blocklistEnabled = true
        // reset updatable time stamp
        persistentState.newestLocalBlocklistTimestamp = INIT_TIME_MS
    }
}
