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
import Logger.LOG_TAG_DNS
import Logger.LOG_TAG_DOWNLOAD
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.MutableLiveData
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import com.celzero.bravedns.customdownloader.LocalBlocklistCoordinator
import com.celzero.bravedns.customdownloader.RemoteBlocklistCoordinator
import com.celzero.bravedns.download.BlocklistDownloadHelper.Companion.checkBlocklistUpdate
import com.celzero.bravedns.download.BlocklistDownloadHelper.Companion.getDownloadableTimestamp
import com.celzero.bravedns.download.DownloadConstants.Companion.DOWNLOAD_TAG
import com.celzero.bravedns.download.DownloadConstants.Companion.FILE_TAG
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager.DownloadType
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLISTS_ADM
import com.celzero.bravedns.util.Utilities
import java.util.concurrent.TimeUnit

/**
 * Generic class responsible for downloading the block list for both remote and local. As of now,
 * the download manager will download the local blocklist and initiate thw workers to listen for the
 * download complete and for copying the files from external to canonical path. TODO remote
 * blocklist - implementation pending.
 */
class AppDownloadManager(
    private val context: Context,
    private val persistentState: PersistentState
) {

    private lateinit var downloadManager: DownloadManager

    // live data to initiate the download, contains time stamp if the download is required,
    // else will have
    val downloadRequired: MutableLiveData<DownloadManagerStatus> = MutableLiveData()

    // various download status used as part of Work manager.
    enum class DownloadManagerStatus(val id: Int) {
        NOT_AVAILABLE(STATUS_NOT_AVAILABLE),
        NOT_STARTED(STATUS_NOT_STARTED),
        FAILURE(STATUS_FAILURE),
        NOT_REQUIRED(STATUS_NOT_REQUIRED),
        IN_PROGRESS(STATUS_IN_PROGRESS),
        STARTED(STATUS_STARTED),
        SUCCESS(STATUS_SUCCESS)
    }

    companion object {
        private const val INVALID_DOWNLOAD_ID = -1L

        // Download status constants
        private const val STATUS_NOT_AVAILABLE = -5
        private const val STATUS_NOT_STARTED = -4
        private const val STATUS_FAILURE = -3
        private const val STATUS_NOT_REQUIRED = -2
        private const val STATUS_IN_PROGRESS = -1
        private const val STATUS_STARTED = 0
        private const val STATUS_SUCCESS = 1

        // WorkManager delay constant
        private const val WORK_INITIAL_DELAY_SECONDS = 10L
    }

    suspend fun isDownloadRequired(type: DownloadType) {
        downloadRequired.postValue(DownloadManagerStatus.IN_PROGRESS)
        val ts = getCurrentBlocklistTimestamp(type)
        val response = checkBlocklistUpdate(ts, persistentState.appVersion, retryCount = 0, persistentState.routeRethinkInRethink)
        // if received response for update is null
        if (response == null) {
            Logger.w(
                LOG_TAG_DNS,
                "blocklist update is check response is null for ${type.name}, ts: $ts, app version: ${persistentState.appVersion}"
            )
            downloadRequired.postValue(DownloadManagerStatus.FAILURE)
            return
        }

        // new case: timestamp value is greater than current & update is set to false
        // in this case, we need to prompt user stating that the update for blocklist
        // is available but not suitable for the current version of the app
        if (!response.update && ts < response.timestamp) {
            downloadRequired.postValue(DownloadManagerStatus.NOT_AVAILABLE)
            return
        }

        val updatableTs = getDownloadableTimestamp(response)
        Logger.i(
            LOG_TAG_DNS,
            "Updatable ts: $updatableTs, current ts: $ts, blocklist type: ${type.name}"
        )

        if (updatableTs == INIT_TIME_MS) {
            downloadRequired.postValue(DownloadManagerStatus.FAILURE)
        } else if (updatableTs > ts) {
            setUpdatableTimestamp(updatableTs, type)
            downloadRequired.postValue(DownloadManagerStatus.SUCCESS)
        } else {
            downloadRequired.postValue(DownloadManagerStatus.NOT_REQUIRED)
        }
    }

    fun cancelDownload(type: DownloadType) {
        if (type.isLocal()) {
            cancelLocalBlocklistDownload()
        } else {
            cancelRemoteBlocklistDownload()
        }
    }

    private fun cancelLocalBlocklistDownload() {
        if (persistentState.useCustomDownloadManager) {
            WorkManager.getInstance(context.applicationContext)
                .cancelAllWorkByTag(LocalBlocklistCoordinator.CUSTOM_DOWNLOAD)
        } else {
            // can android download manager downloads
            cancelAndroidDownloadManagerDownloads()
            // cancel the download check workers
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(DOWNLOAD_TAG)
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(FILE_TAG)
        }
    }

    private fun cancelAndroidDownloadManagerDownloads() {
        try {
            val downloadIdsStr = persistentState.androidDownloadManagerIds
            if (downloadIdsStr.isEmpty()) {
                Logger.i(LOG_TAG_DOWNLOAD, "no andr-down-mgr downloads to cancel")
                return
            }

            val downloadIds = downloadIdsStr.split(",").mapNotNull { it.toLongOrNull() }
            if (downloadIds.isEmpty()) {
                Logger.i(LOG_TAG_DOWNLOAD, "no valid download IDs found to cancel")
                return
            }

            downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            var cancelledCount = 0
            downloadIds.forEach { downloadId ->
                try {
                    val removed = downloadManager.remove(downloadId)
                    if (removed > 0) {
                        cancelledCount++
                        Logger.i(LOG_TAG_DOWNLOAD, "cancelled download with id: $downloadId")
                    }
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_DOWNLOAD, "failed to cancel download id: $downloadId", e)
                }
            }

            persistentState.androidDownloadManagerIds = ""
            Logger.i(LOG_TAG_DOWNLOAD, "cancelled $cancelledCount andr-down-mgr downloads")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_DOWNLOAD, "err cancelling andr-down-mgr downloads", e)
        }
    }

    private fun cancelRemoteBlocklistDownload() {
        WorkManager.getInstance(context.applicationContext)
            .cancelAllWorkByTag(RemoteBlocklistCoordinator.REMOTE_DOWNLOAD_WORKER)
    }

    private fun setUpdatableTimestamp(timestamp: Long, type: DownloadType) {
        if (type.isLocal()) {
            persistentState.newestLocalBlocklistTimestamp = timestamp
        } else {
            persistentState.newestRemoteBlocklistTimestamp = timestamp
        }
    }

    private fun getCurrentBlocklistTimestamp(type: DownloadType): Long {
        return if (type == DownloadType.LOCAL) {
            persistentState.localBlocklistTimestamp
        } else {
            persistentState.remoteBlocklistTimestamp
        }
    }

    /**
     * Responsible for downloading the local blocklist files. For local blocklist, we need
     * filetag.json, basicconfig.json, rd.txt and td.txt
     */
    suspend fun downloadLocalBlocklist(
        currentTs: Long,
        isRedownload: Boolean
    ): DownloadManagerStatus {
        // local blocklist available only in fdroid and website version
        if (!Utilities.isWebsiteFlavour() && !Utilities.isFdroidFlavour()) {
            return DownloadManagerStatus.FAILURE
        }

        val response = checkBlocklistUpdate(currentTs, persistentState.appVersion, retryCount = 0, persistentState.routeRethinkInRethink)
        // if received response for update is null
        if (response == null) {
            Logger.w(
                LOG_TAG_DNS,
                "local blocklist update check is null, ts: $currentTs, app version: ${persistentState.appVersion}"
            )
            return DownloadManagerStatus.FAILURE
        }

        val updatableTs = getDownloadableTimestamp(response)

        // no need to proceed if the current and received timestamp is same
        if (updatableTs <= currentTs && !isRedownload) {
            Logger.i(
                LOG_TAG_DNS,
                "local blocklist update not required, current ts: $currentTs, updatable ts: $updatableTs"
            )
            return DownloadManagerStatus.NOT_REQUIRED
        } else {
            // no-op
        }

        if (persistentState.useCustomDownloadManager) {
            Logger.i(LOG_TAG_DNS, "initiating local blocklist download with custom download mgr")
            return initiateCustomDownloadManager(updatableTs)
        }

        Logger.i(LOG_TAG_DNS, "initiating local blocklist download with Android download mgr")
        return initiateAndroidDownloadManager(updatableTs)
    }

    private fun initiateAndroidDownloadManager(timestamp: Long): DownloadManagerStatus {

        if (
            WorkScheduler.isWorkScheduled(context, DOWNLOAD_TAG) ||
                WorkScheduler.isWorkScheduled(context, FILE_TAG)
        ) {
            Logger.i(LOG_TAG_DNS, "local blocklist download is already in progress, returning")
            return DownloadManagerStatus.FAILURE
        }

        Logger.i(LOG_TAG_DNS, "local blocklist download is not in progress, starting the download")
        purge(context, timestamp, DownloadType.LOCAL)
        val downloadIds = LongArray(ONDEVICE_BLOCKLISTS_ADM.count())
        ONDEVICE_BLOCKLISTS_ADM.forEachIndexed { i, it ->
            val fileName = it.filename
            // url: https://dl.rethinkdns.com/update/blocklists?tstamp=1696197375609&vcode=33
            Logger.d(LOG_TAG_DOWNLOAD, "v: ($timestamp), f: $fileName, u: $it.url")
            downloadIds[i] = enqueueDownload(it.url, fileName, timestamp.toString())
            if (downloadIds[i] == INVALID_DOWNLOAD_ID) {
                return DownloadManagerStatus.FAILURE
            }
        }
        // Store download IDs for later cancellation
        persistentState.androidDownloadManagerIds = downloadIds.joinToString(",")
        initiateDownloadStatusCheck(downloadIds, timestamp)
        return DownloadManagerStatus.STARTED
    }

    private fun initiateCustomDownloadManager(timestamp: Long): DownloadManagerStatus {
        if (
            WorkScheduler.isWorkScheduled(context, LocalBlocklistCoordinator.CUSTOM_DOWNLOAD) ||
                WorkScheduler.isWorkRunning(context, LocalBlocklistCoordinator.CUSTOM_DOWNLOAD)
        )
            return DownloadManagerStatus.FAILURE

        startLocalBlocklistCoordinator(timestamp)
        return DownloadManagerStatus.STARTED
    }

    suspend fun downloadRemoteBlocklist(currentTs: Long, isRedownload: Boolean): Boolean {

        val response = checkBlocklistUpdate(currentTs, persistentState.appVersion, retryCount = 0, persistentState.routeRethinkInRethink)
        // if received response for update is null
        if (response == null) {
            Logger.w(LOG_TAG_DNS, "remote blocklist update check is null")
            downloadRequired.postValue(DownloadManagerStatus.FAILURE)
            return false
        }

        val updatableTs = getDownloadableTimestamp(response)

        if (updatableTs <= currentTs && !isRedownload) {
            Logger.i(
                LOG_TAG_DNS,
                "remote blocklist update not required, current ts: $currentTs, updatable ts: $updatableTs"
            )
            return false
        } else {
            // no-op
        }

        return initiateRemoteBlocklistDownload(updatableTs)
    }

    private fun initiateRemoteBlocklistDownload(timestamp: Long): Boolean {
        if (
            WorkScheduler.isWorkScheduled(
                context,
                RemoteBlocklistCoordinator.REMOTE_DOWNLOAD_WORKER
            ) ||
                WorkScheduler.isWorkRunning(
                    context,
                    RemoteBlocklistCoordinator.REMOTE_DOWNLOAD_WORKER
                )
        ) {
            Logger.i(LOG_TAG_DNS, "remote blocklist download is already in progress, returning")
            return false
        }

        startRemoteBlocklistCoordinator(timestamp)
        return true
    }

    private fun startRemoteBlocklistCoordinator(timestamp: Long) {
        val data = Data.Builder()
        data.putLong("workerStartTime", SystemClock.elapsedRealtime())
        data.putLong("blocklistTimestamp", timestamp)
        val downloadWatcher =
            OneTimeWorkRequestBuilder<RemoteBlocklistCoordinator>()
                .setInputData(data.build())
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(RemoteBlocklistCoordinator.REMOTE_DOWNLOAD_WORKER)
                .build()

        WorkManager.getInstance(context).beginWith(downloadWatcher).enqueue()
    }

    private fun startLocalBlocklistCoordinator(timestamp: Long) {
        val data = Data.Builder()
        data.putLong("workerStartTime", SystemClock.elapsedRealtime())
        data.putLong("blocklistTimestamp", timestamp)
        val downloadWatcher =
            OneTimeWorkRequestBuilder<LocalBlocklistCoordinator>()
                .setInputData(data.build())
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(LocalBlocklistCoordinator.CUSTOM_DOWNLOAD)
                .build()

        WorkManager.getInstance(context).beginWith(downloadWatcher).enqueue()
    }

    private fun initiateDownloadStatusCheck(downloadIds: LongArray, timestamp: Long) {
        val data = Data.Builder()
        data.putLong("workerStartTime", SystemClock.elapsedRealtime())
        data.putLongArray("downloadIds", downloadIds)

        val downloadWatcher =
            OneTimeWorkRequestBuilder<DownloadWatcher>()
                .setInputData(data.build())
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(DOWNLOAD_TAG)
                .setInitialDelay(WORK_INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
                .build()

        val timestampWorkerData = workDataOf("blocklistDownloadInitiatedTime" to timestamp)

        val fileHandler =
            OneTimeWorkRequestBuilder<FileHandleWorker>()
                .setInputData(timestampWorkerData)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(FILE_TAG)
                .build()

        WorkManager.getInstance(context).beginWith(downloadWatcher).then(fileHandler).enqueue()
    }

    /**
     * delete all the old files which are available in the download path (android download manager's
     * default download path).
     */
    private fun purge(context: Context, timestamp: Long, type: DownloadType) {
        BlocklistDownloadHelper.deleteOldFiles(context, timestamp, type)
    }

    private fun enqueueDownload(url: String, fileName: String, timestamp: String): Long {
        try {
            downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadUri = Uri.parse(url)
            val request = DownloadManager.Request(downloadUri)
            request.apply {
                setTitle(fileName)
                setDescription(fileName)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalFilesDir(
                    context,
                    BlocklistDownloadHelper.getExternalFilePath(timestamp),
                    fileName
                )
                val downloadId = downloadManager.enqueue(this)
                Logger.d(LOG_TAG_DOWNLOAD, "filename: $fileName, downloadID: $downloadId")
                return downloadId
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_DOWNLOAD, "Exception while downloading the file: $fileName", e)
        }
        return INVALID_DOWNLOAD_ID
    }
}
