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

import Logger
import Logger.LOG_TAG_SCHEDULER
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class ScheduleManager(val context: Context) {

    companion object {
        const val REFRESH_APPS_JOB_TAG = "ScheduledRefreshAppsJob"

        const val REFRESH_TIME_INTERVAL_HOURS: Long = 3

        // Check if the job is already scheduled.
        // It will be in either running or enqueued state.
        fun isWorkScheduled(context: Context, tag: String): Boolean {
            val instance = WorkManager.getInstance(context)
            val statuses: ListenableFuture<List<WorkInfo>> = instance.getWorkInfosByTag(tag)
            Logger.i(LOG_TAG_SCHEDULER, "Job $tag already scheduled check")
            return try {
                var running = false
                val workInfos = statuses.get()

                if (workInfos.isNullOrEmpty()) return false

                for (workStatus in workInfos) {
                    running =
                        workStatus.state == WorkInfo.State.RUNNING ||
                            workStatus.state == WorkInfo.State.ENQUEUED
                }
                Logger.i(LOG_TAG_SCHEDULER, "Job $tag already scheduled? $running")
                running
            } catch (e: ExecutionException) {
                Logger.e(LOG_TAG_SCHEDULER, "error on status check ${e.message}", e)
                false
            } catch (e: InterruptedException) {
                Logger.e(LOG_TAG_SCHEDULER, "error on status check ${e.message}", e)
                false
            }
        }
    }

    // schedule refresh-apps every REFRESH_TIME_INTERVAL_HOURS
    fun scheduleDatabaseRefreshJob() {
        if (isWorkScheduled(context, REFRESH_APPS_JOB_TAG)) return

        Logger.i(LOG_TAG_SCHEDULER, "Refresh database job scheduled")
        val refreshAppsJob =
            PeriodicWorkRequest.Builder(
                    RefreshAppsJob::class.java,
                    REFRESH_TIME_INTERVAL_HOURS,
                    TimeUnit.HOURS
                )
                .addTag(REFRESH_APPS_JOB_TAG)
                .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                REFRESH_APPS_JOB_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                refreshAppsJob
            )
    }
}
