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

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_SCHEDULER
import com.celzero.bravedns.util.Utilities
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

class AppExitInfoCollector(val context: Context, workerParameters: WorkerParameters) :
    Worker(context, workerParameters), KoinComponent {

    private val persistentState by inject<PersistentState>()

    @RequiresApi(Build.VERSION_CODES.R)
    override fun doWork(): Result {
        if (DEBUG) Log.d(LOG_TAG_SCHEDULER, "starting app-exit-info job")
        detectAppExitInfo()
        return Result.success()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun detectAppExitInfo() {

        if (!Utilities.isAtleastR()) return

        val am = context.getSystemService(AppCompatActivity.ACTIVITY_SERVICE) as ActivityManager

        val path = BugReportZipper.prepare(this.applicationContext)
        // gets all the historical process exit reasons.
        val appExitInfo = am.getHistoricalProcessExitReasons(null, 0, 0)

        if (appExitInfo.isEmpty()) return

        var maxTimestamp = appExitInfo[0].timestamp

        val file = File(path)
        run returnTag@{
            appExitInfo.forEach {
                maxTimestamp = maxTimestamp.coerceAtLeast(it.timestamp)

                // Write exit infos past the previously recorded checkpoint
                if (persistentState.lastAppExitInfoTimestamp >= it.timestamp) return@returnTag

                BugReportZipper.write(it, file)
            }
        }

        // Store the last exit reason time stamp
        persistentState.lastAppExitInfoTimestamp =
            persistentState.lastAppExitInfoTimestamp.coerceAtLeast(maxTimestamp)

        BugReportZipper.build(applicationContext, file)
    }
}
