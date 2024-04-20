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
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.celzero.bravedns.util.Utilities
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class WorkScheduler(val context: Context) {

    companion object {
        const val APP_EXIT_INFO_ONE_TIME_JOB_TAG = "OnDemandCollectAppExitInfoJob"
        const val APP_EXIT_INFO_JOB_TAG = "ScheduledCollectAppExitInfoJob"
        const val PURGE_CONNECTION_LOGS_JOB_TAG = "ScheduledPurgeConnectionLogsJob"
        const val BLOCKLIST_UPDATE_CHECK_JOB_TAG = "ScheduledBlocklistUpdateCheckJob"
        const val DATA_USAGE_JOB_TAG = "ScheduledDataUsageJob"

        const val APP_EXIT_INFO_JOB_TIME_INTERVAL_DAYS: Long = 7
        const val PURGE_LOGS_TIME_INTERVAL_HOURS: Long = 4
        const val BLOCKLIST_UPDATE_CHECK_INTERVAL_DAYS: Long = 3
        const val DATA_USAGE_TIME_INTERVAL_MINS: Long = 20

        fun isWorkRunning(context: Context, tag: String): Boolean {
            val instance = WorkManager.getInstance(context)
            val statuses: ListenableFuture<List<WorkInfo>> = instance.getWorkInfosByTag(tag)
            Logger.d(LOG_TAG_SCHEDULER, "Job $tag already running check")
            return try {
                var running = false
                val workInfos = statuses.get()

                if (workInfos.isNullOrEmpty()) return false

                for (workStatus in workInfos) {
                    running = workStatus.state == WorkInfo.State.RUNNING
                }
                Logger.i(LOG_TAG_SCHEDULER, "Job $tag already running? $running")
                running
            } catch (e: ExecutionException) {
                Logger.e(LOG_TAG_SCHEDULER, "error on status check ${e.message}", e)
                false
            } catch (e: InterruptedException) {
                Logger.e(LOG_TAG_SCHEDULER, "error on status check ${e.message}", e)
                false
            }
        }

        // Check if the job is already scheduled.
        // It will be in either running or enqueued state.
        fun isWorkScheduled(context: Context, tag: String): Boolean {
            val instance = WorkManager.getInstance(context)
            val statuses: ListenableFuture<List<WorkInfo>> = instance.getWorkInfosByTag(tag)
            Logger.d(LOG_TAG_SCHEDULER, "Job $tag already scheduled check")
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

    // Schedule AppExitInfo every APP_EXIT_INFO_JOB_TIME_INTERVAL_DAYS
    fun scheduleAppExitInfoCollectionJob() {
        // app exit info is supported from R+
        if (!Utilities.isAtleastR()) return

        Logger.d(LOG_TAG_SCHEDULER, "App exit info job scheduled")
        val bugReportCollector =
            PeriodicWorkRequest.Builder(
                    BugReportCollector::class.java,
                    APP_EXIT_INFO_JOB_TIME_INTERVAL_DAYS,
                    TimeUnit.DAYS
                )
                .addTag(APP_EXIT_INFO_JOB_TAG)
                .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                APP_EXIT_INFO_JOB_TAG,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                bugReportCollector
            )
    }

    fun schedulePurgeConnectionsLog() {
        val purgeLogs =
            PeriodicWorkRequest.Builder(
                    PurgeConnectionLogs::class.java,
                    PURGE_LOGS_TIME_INTERVAL_HOURS,
                    TimeUnit.HOURS
                )
                .addTag(PURGE_CONNECTION_LOGS_JOB_TAG)
                .build()

        Logger.d(LOG_TAG_SCHEDULER, "purge connection logs job scheduled")
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                PURGE_CONNECTION_LOGS_JOB_TAG,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                purgeLogs
            )
    }

    // Schedule AppExitInfo on demand
    fun scheduleOneTimeWorkForAppExitInfo() {
        val bugReportCollector =
            OneTimeWorkRequestBuilder<BugReportCollector>()
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(APP_EXIT_INFO_ONE_TIME_JOB_TAG)
                .build()
        WorkManager.getInstance(context.applicationContext)
            .beginUniqueWork(
                APP_EXIT_INFO_ONE_TIME_JOB_TAG,
                ExistingWorkPolicy.REPLACE,
                bugReportCollector
            )
            .enqueue()
    }

    // schedule blocklist update check (based on user settings)
    fun scheduleBlocklistUpdateCheckJob() {
        // if (isWorkScheduled(context.applicationContext, BLOCKLIST_UPDATE_CHECK_JOB_TAG)) return

        Logger.i(LOG_TAG_SCHEDULER, "Scheduled blocklist update check")
        val blocklistUpdateCheck =
            PeriodicWorkRequest.Builder(
                    BlocklistUpdateCheckJob::class.java,
                    BLOCKLIST_UPDATE_CHECK_INTERVAL_DAYS,
                    TimeUnit.DAYS
                )
                .addTag(BLOCKLIST_UPDATE_CHECK_JOB_TAG)
                .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                BLOCKLIST_UPDATE_CHECK_JOB_TAG,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                blocklistUpdateCheck
            )
    }

    fun scheduleDataUsageJob() {
        Logger.i(LOG_TAG_SCHEDULER, "Data usage job scheduled")
        val workRequest =
            PeriodicWorkRequest.Builder(
                    DataUsageUpdater::class.java,
                    DATA_USAGE_TIME_INTERVAL_MINS,
                    TimeUnit.MINUTES // Set the repeat interval for every 15 minutes
                )
                .addTag(DATA_USAGE_JOB_TAG)
                .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                DATA_USAGE_JOB_TAG,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                workRequest
            )
    }
}
