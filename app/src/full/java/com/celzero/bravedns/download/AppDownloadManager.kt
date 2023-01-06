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
import com.celzero.bravedns.customdownloader.LocalBlocklistCoordinator
import com.celzero.bravedns.customdownloader.RemoteBlocklistCoordinator
import com.celzero.bravedns.download.BlocklistDownloadHelper.Companion.checkBlocklistUpdate
import com.celzero.bravedns.download.BlocklistDownloadHelper.Companion.getDownloadableTimestamp
import com.celzero.bravedns.download.DownloadConstants.Companion.DOWNLOAD_TAG
import com.celzero.bravedns.download.DownloadConstants.Companion.FILE_TAG
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager.DownloadType
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.ONDEVICE_BLOCKLISTS_ADM
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DOWNLOAD
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val scope = CoroutineScope(Job() + Dispatchers.IO)

    // various download status used as part of Work manager.
    enum class DownloadManagerStatus(val id: Int) {
        NOT_AVAILABLE(-5),
        NOT_STARTED(-4),
        FAILURE(-3),
        NOT_REQUIRED(-2),
        IN_PROGRESS(-1),
        STARTED(0),
        SUCCESS(1)
    }

    suspend fun isDownloadRequired(type: DownloadType) {
        downloadRequired.postValue(DownloadManagerStatus.IN_PROGRESS)
        val ts = getCurrentBlocklistTimestamp(type)
        val response = checkBlocklistUpdate(ts, persistentState.appVersion, retryCount = 0)
        // if received response for update is null
        if (response == null) {
            Log.w(LOG_TAG_DNS, "blocklist update is check response is null for ${type.name}")
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
        Log.i(
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
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(DOWNLOAD_TAG)
            WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(FILE_TAG)
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

        val response = checkBlocklistUpdate(currentTs, persistentState.appVersion, retryCount = 0)
        // if received response for update is null
        if (response == null) {
            Log.w(LOG_TAG_DNS, "local blocklist update check is null")
            return DownloadManagerStatus.FAILURE
        }

        val updatableTs = getDownloadableTimestamp(response)

        // no need to proceed if the current and received timestamp is same
        if (updatableTs <= currentTs && !isRedownload) {
            return DownloadManagerStatus.NOT_REQUIRED
        } else {
            // no-op
        }

        if (persistentState.useCustomDownloadManager) {
            return initiateCustomDownloadManager(updatableTs)
        }

        return initiateAndroidDownloadManager(updatableTs)
    }

    private fun initiateAndroidDownloadManager(timestamp: Long): DownloadManagerStatus {

        if (
            WorkScheduler.isWorkScheduled(context, DOWNLOAD_TAG) ||
                WorkScheduler.isWorkScheduled(context, FILE_TAG)
        )
            return DownloadManagerStatus.FAILURE

        purge(context, timestamp, DownloadType.LOCAL)
        val downloadIds = LongArray(ONDEVICE_BLOCKLISTS_ADM.count())
        ONDEVICE_BLOCKLISTS_ADM.forEachIndexed { i, it ->
            val fileName = it.filename
            if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "v: ($timestamp), f: $fileName, u: $it.url")
            downloadIds[i] = enqueueDownload(it.url, fileName, timestamp.toString())
        }
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

        val response = checkBlocklistUpdate(currentTs, persistentState.appVersion, retryCount = 0)
        // if received response for update is null
        if (response == null) {
            Log.w(LOG_TAG_DNS, "remote blocklist update check is null")
            downloadRequired.postValue(DownloadManagerStatus.FAILURE)
            return false
        }

        val updatableTs = getDownloadableTimestamp(response)

        if (updatableTs <= currentTs && !isRedownload) {
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
        )
            return false

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
                    OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
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
                    OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
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
                    OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(DOWNLOAD_TAG)
                .setInitialDelay(10, TimeUnit.SECONDS)
                .build()

        val timestampWorkerData = workDataOf("blocklistDownloadInitiatedTime" to timestamp)

        val fileHandler =
            OneTimeWorkRequestBuilder<FileHandleWorker>()
                .setInputData(timestampWorkerData)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
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
        downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadUri = Uri.parse(url)
        val request = DownloadManager.Request(downloadUri)
        request.apply {
            setTitle(fileName)
            setDescription(fileName)
            request.setDestinationInExternalFilesDir(
                context,
                BlocklistDownloadHelper.getExternalFilePath(timestamp),
                fileName
            )
            val downloadId = downloadManager.enqueue(this)
            if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "filename: $fileName, downloadID: $downloadId")
            return downloadId
        }
    }
}
