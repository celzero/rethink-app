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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_APP_DB
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_SCHEDULER
import com.celzero.bravedns.util.Utilities
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit


class WorkScheduler(val context: Context) {

    companion object {
        const val APP_EXIT_INFO_JOB_TAG = "CollectAppExitInfo"
        const val REFRESH_APPS_JOB_TAG = "RefreshApps"
        const val APP_EXIT_INFO_JOB_TIME_INTERVAL_HOURS: Long = 1
        const val REFRESH_TIME_INTERVAL_HOURS: Long = 1
    }

    // Schedule work manager to collect the app exit info after a particular interval.
    // interval time - 6 hours
    fun scheduleAppExitInfoCollectionJob() {
        if (isWorkScheduled(context, APP_EXIT_INFO_JOB_TAG)) return

        // app exit info is supported from R+
        if (!Utilities.isAtleastR()) return

        if(DEBUG) Log.d(LOG_TAG_SCHEDULER, "App exit info job scheduled")
        val appExitInfoCollector: PeriodicWorkRequest.Builder = PeriodicWorkRequest.Builder(
            AppExitInfoCollector::class.java, APP_EXIT_INFO_JOB_TIME_INTERVAL_HOURS, TimeUnit.HOURS)
        val appExitInfoWork = appExitInfoCollector.build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(APP_EXIT_INFO_JOB_TAG,
                                                            ExistingPeriodicWorkPolicy.KEEP,
                                                            appExitInfoWork)
    }

    // schedule work manager to refresh apps from database after an interval
    // interval time - 3 hours
    fun scheduleDatabaseRefreshJob() {
        if (isWorkScheduled(context, REFRESH_APPS_JOB_TAG)) return

        if(DEBUG) Log.d(LOG_TAG_SCHEDULER, "Refresh database job scheduled")
        val refreshAppsJob: PeriodicWorkRequest.Builder = PeriodicWorkRequest.Builder(
            RefreshAppsJob::class.java, REFRESH_TIME_INTERVAL_HOURS,
            TimeUnit.HOURS)
        val refreshAppsWork = refreshAppsJob.build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(REFRESH_APPS_JOB_TAG,
                                                                   ExistingPeriodicWorkPolicy.KEEP,
                                                                   refreshAppsWork)
    }

    // Check if the job is already scheduled.
    // It will be in either running or enqueued state.
    private fun isWorkScheduled(context: Context, tag: String): Boolean {
        val instance = WorkManager.getInstance(context)
        val statuses: ListenableFuture<List<WorkInfo>> = instance.getWorkInfosByTag(tag)
        if(DEBUG) Log.d(LOG_TAG_SCHEDULER, "Job $tag already scheduled check")
        return try {
            var running = false
            val workInfos = statuses.get()

            if (workInfos == null || workInfos.isEmpty()) return false

            for (workStatus in workInfos) {
                running = workStatus.state == WorkInfo.State.RUNNING || workStatus.state == WorkInfo.State.ENQUEUED
            }
            if(DEBUG) Log.d(LOG_TAG_SCHEDULER, "Job $tag already scheduled? $running")
            running
        } catch (e: ExecutionException) {
            Log.e(LOG_TAG_SCHEDULER,"error on status check ${e.message}", e)
            false
        } catch (e: InterruptedException) {
            Log.e(LOG_TAG_SCHEDULER,"error on status check ${e.message}", e)
            false
        }
    }

}