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

package com.celzero.bravedns.scheduler

import android.content.Context
import android.util.Log
import androidx.work.*
import com.celzero.bravedns.BuildConfig.DEBUG
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_SCHEDULER
import com.celzero.bravedns.util.Utilities
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class WorkScheduler(val context: Context) {

    companion object {
        const val APP_EXIT_INFO_ONE_TIME_JOB_TAG = "OnDemandCollectAppExitInfoJob"
        const val APP_EXIT_INFO_JOB_TAG = "ScheduledCollectAppExitInfoJob"
        const val REFRESH_APPS_JOB_TAG = "ScheduledRefreshAppsJob"
        const val BLOCKLIST_UPDATE_CHECK_JOB_TAG = "ScheduledBlocklistUpdateCheckJob"

        const val APP_EXIT_INFO_JOB_TIME_INTERVAL_DAYS: Long = 7
        const val REFRESH_TIME_INTERVAL_HOURS: Long = 3
        const val BLOCKLIST_UPDATE_CHECK_INTERVAL_DAYS: Long = 3

        fun isWorkRunning(context: Context, tag: String): Boolean {
            val instance = WorkManager.getInstance(context)
            val statuses: ListenableFuture<List<WorkInfo>> = instance.getWorkInfosByTag(tag)
            if (DEBUG) Log.d(LOG_TAG_SCHEDULER, "Job $tag already running check")
            return try {
                var running = false
                val workInfos = statuses.get()

                if (workInfos.isNullOrEmpty()) return false

                for (workStatus in workInfos) {
                    running = workStatus.state == WorkInfo.State.RUNNING
                }
                Log.i(LOG_TAG_SCHEDULER, "Job $tag already running? $running")
                running
            } catch (e: ExecutionException) {
                Log.e(LOG_TAG_SCHEDULER, "error on status check ${e.message}", e)
                false
            } catch (e: InterruptedException) {
                Log.e(LOG_TAG_SCHEDULER, "error on status check ${e.message}", e)
                false
            }
        }

        // Check if the job is already scheduled.
        // It will be in either running or enqueued state.
        fun isWorkScheduled(context: Context, tag: String): Boolean {
            val instance = WorkManager.getInstance(context)
            val statuses: ListenableFuture<List<WorkInfo>> = instance.getWorkInfosByTag(tag)
            if (DEBUG) Log.d(LOG_TAG_SCHEDULER, "Job $tag already scheduled check")
            return try {
                var running = false
                val workInfos = statuses.get()

                if (workInfos.isNullOrEmpty()) return false

                for (workStatus in workInfos) {
                    running = workStatus.state == WorkInfo.State.RUNNING || workStatus.state == WorkInfo.State.ENQUEUED
                }
                Log.i(LOG_TAG_SCHEDULER, "Job $tag already scheduled? $running")
                running
            } catch (e: ExecutionException) {
                Log.e(LOG_TAG_SCHEDULER, "error on status check ${e.message}", e)
                false
            } catch (e: InterruptedException) {
                Log.e(LOG_TAG_SCHEDULER, "error on status check ${e.message}", e)
                false
            }
        }
    }

    // Schedule AppExitInfo every APP_EXIT_INFO_JOB_TIME_INTERVAL_DAYS
    fun scheduleAppExitInfoCollectionJob() {
        if (isWorkScheduled(context, APP_EXIT_INFO_JOB_TAG) || isWorkRunning(context,
                                                                             APP_EXIT_INFO_ONE_TIME_JOB_TAG)) return

        // app exit info is supported from R+
        if (!Utilities.isAtleastR()) return

        if (DEBUG) Log.d(LOG_TAG_SCHEDULER, "App exit info job scheduled")
        val appExitInfoCollector = PeriodicWorkRequest.Builder(AppExitInfoCollector::class.java,
                                                               APP_EXIT_INFO_JOB_TIME_INTERVAL_DAYS,
                                                               TimeUnit.DAYS).addTag(
            APP_EXIT_INFO_JOB_TAG).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(APP_EXIT_INFO_JOB_TAG,
                                                                   ExistingPeriodicWorkPolicy.KEEP,
                                                                   appExitInfoCollector)
    }

    // schedule refresh-apps every REFRESH_TIME_INTERVAL_HOURS
    fun scheduleDatabaseRefreshJob() {
        if (isWorkScheduled(context, REFRESH_APPS_JOB_TAG)) return

        if (DEBUG) Log.d(LOG_TAG_SCHEDULER, "Refresh database job scheduled")
        val refreshAppsJob = PeriodicWorkRequest.Builder(RefreshAppsJob::class.java,
                                                         REFRESH_TIME_INTERVAL_HOURS,
                                                         TimeUnit.HOURS).addTag(
            REFRESH_APPS_JOB_TAG).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(REFRESH_APPS_JOB_TAG,
                                                                   ExistingPeriodicWorkPolicy.KEEP,
                                                                   refreshAppsJob)
    }

    // Schedule AppExitInfo on demand
    fun scheduleOneTimeWorkForAppExitInfo() {
        if (isWorkRunning(context, APP_EXIT_INFO_JOB_TAG)) return

        val appExitInfoCollector = OneTimeWorkRequestBuilder<AppExitInfoCollector>().setBackoffCriteria(
            BackoffPolicy.LINEAR, OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
            TimeUnit.MILLISECONDS).addTag(APP_EXIT_INFO_ONE_TIME_JOB_TAG).build()
        WorkManager.getInstance(context).beginUniqueWork(APP_EXIT_INFO_ONE_TIME_JOB_TAG,
                                                         ExistingWorkPolicy.REPLACE,
                                                         appExitInfoCollector).enqueue()
    }

    // schedule blocklist update check (based on user settings)
    fun scheduleBlocklistUpdateCheckJob() {
        if (isWorkScheduled(context, BLOCKLIST_UPDATE_CHECK_JOB_TAG) || isWorkRunning(context,
                                                                                      BLOCKLIST_UPDATE_CHECK_JOB_TAG)) return

        Log.i(LOG_TAG_SCHEDULER, "Scheduled blocklist update check")
        val blocklistUpdateCheck = PeriodicWorkRequest.Builder(BlocklistUpdateCheckJob::class.java,
                                                               BLOCKLIST_UPDATE_CHECK_INTERVAL_DAYS,
                                                               TimeUnit.DAYS).addTag(
            BLOCKLIST_UPDATE_CHECK_JOB_TAG).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(BLOCKLIST_UPDATE_CHECK_JOB_TAG,
                                                                   ExistingPeriodicWorkPolicy.REPLACE,
                                                                   blocklistUpdateCheck)
    }

}
