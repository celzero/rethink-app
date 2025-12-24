/*
 * Copyright 2023 RethinkDNS and its authors
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
package com.celzero.bravedns.scheduler

import Logger
import Logger.LOG_TAG_SCHEDULER
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.RethinkLogRepository
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DataUsageUpdater(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams), KoinComponent {
    private val connTrackRepository by inject<ConnectionTrackerRepository>()
    private val rethinkDb by inject<RethinkLogRepository>()
    private val appInfoRepository by inject<AppInfoRepository>()
    private val persistentState by inject<PersistentState>()

    override suspend fun doWork(): Result {
        val curr = System.currentTimeMillis()
        val prev = persistentState.prevDataUsageCheck
        updateDataUsage(prev, curr)
        updateRethinkDataUsage(prev, curr)
        return Result.success()
    }

    private suspend fun updateDataUsage(prev: Long, curr: Long) {
        // fetch the data usage from connection tracker and update the app info database
        // with the data usage.

        val dataUsageList = connTrackRepository.getDataUsage(prev, curr)

        if (dataUsageList == null) {
            Logger.w(LOG_TAG_SCHEDULER, "Data usage list is null, skipping update")
            persistentState.prevDataUsageCheck = curr
            return
        }

        dataUsageList.forEach {
            if (it.uid == Constants.INVALID_UID) return@forEach

            try {
                val currentDataUsage = appInfoRepository.getDataUsageByUid(it.uid)
                if (currentDataUsage == null) {
                    Logger.w(LOG_TAG_SCHEDULER, "No data usage found for uid: ${it.uid}")
                    return@forEach
                }

                val upload = currentDataUsage.uploadBytes + it.uploadBytes
                val download = currentDataUsage.downloadBytes + it.downloadBytes
                Logger.d(LOG_TAG_SCHEDULER, "Data usage for ${it.uid}, $upload, $download")
                appInfoRepository.updateDataUsageByUid(it.uid, upload, download)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_SCHEDULER, "err in data usage updater: ${e.message}")
            }
        }

        persistentState.prevDataUsageCheck = curr
        Logger.i(LOG_TAG_SCHEDULER, "Data usage updated for all apps at $curr")
    }

    private suspend fun updateRethinkDataUsage(prev: Long, curr: Long) {
        try {
            // get rethink's uid from the database
            val uid =
                appInfoRepository.getAppInfoUidForPackageName(Constants.RETHINK_PACKAGE)

            val currDataUsage = rethinkDb.getDataUsage(prev, curr) ?: return
            val prevDataUsage = appInfoRepository.getDataUsageByUid(uid) ?: return

            if (currDataUsage.uploadBytes == 0L && currDataUsage.downloadBytes == 0L) {
                // if the data usage is 0, then no need to update the database
                Logger.d(LOG_TAG_SCHEDULER, "rinr, data usage is 0 for $uid")
                return
            }

            val upload = currDataUsage.uploadBytes + prevDataUsage.uploadBytes
            val download = currDataUsage.downloadBytes + prevDataUsage.downloadBytes

            Logger.d(LOG_TAG_SCHEDULER, "rinr, data usage:($uid), $upload, $download")
            appInfoRepository.updateDataUsageByUid(uid, upload, download)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_SCHEDULER, "err in rinr data usage updater: ${e.message}", e)
        }
    }
}
