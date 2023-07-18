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
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_SCHEDULER
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastO
import java.io.File
import java.io.InputStream
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AppExitInfoCollector(val context: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(context, workerParameters), KoinComponent {

    private val persistentState by inject<PersistentState>()
    private lateinit var file: File

    companion object {
        private const val LOGCAT_CMD = "logcat"
        private const val LOGCAT_OPTIONS = "printable uid year descriptive"
        private const val LOGCAT_OPTIONS_D = "-d"
        private const val LOGCAT_OPTIONS_B = "-b"
        private const val LOGCAT_OPTIONS_V = "-v"
        private const val LOGCAT_OPTIONS_TIME = "threadtime"
        private const val EVENT_TYPES = "main,crash,events,system"
    }

    override suspend fun doWork(): Result {
        if (DEBUG) Log.d(LOG_TAG_SCHEDULER, "starting app-exit-info job")
        if (!isAtleastO()) {
            // support for zip file creation for devices below Oreo is not straightforward
            // hence, we are not supporting it for now
            return Result.success()
        }

        // prepare the bugreport directory and file
        prepare()
        storePrefs()
        // app exit info is available only on Android R and above, normal process builder
        // for logcat is used for all the other versions
        if (Utilities.isAtleastR()) {
            detectAppExitInfo()
        } else {
            Log.i(LOG_TAG_SCHEDULER, "app-exit-info job not supported on this device")
            dumpLogcat()
        }
        build()
        return Result.success()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun prepare() {
        val path = BugReportZipper.prepare(this.applicationContext)
        Log.i(LOG_TAG_SCHEDULER, "app-exit-info job path: $path")
        file = File(path)
        Log.i(LOG_TAG_SCHEDULER, "app-exit-info job file: ${file.name}, ${file.absolutePath}")
    }

    private fun storePrefs() {
        // write all the shared preferences values into the file
        BugReportZipper.writePrefs(applicationContext, file)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun detectAppExitInfo() {
        // use the logcat dumper to get the default info
        dumpLogcat()

        val am = context.getSystemService(AppCompatActivity.ACTIVITY_SERVICE) as ActivityManager

        // gets all the historical process exit reasons.
        val appExitInfo = am.getHistoricalProcessExitReasons(null, 0, 0)

        if (appExitInfo.isEmpty()) return

        var maxTimestamp = appExitInfo[0].timestamp

        run returnTag@{
            appExitInfo.forEach {
                maxTimestamp = maxTimestamp.coerceAtLeast(it.timestamp)

                // Write exit infos past the previously recorded checkpoint
                if (persistentState.lastAppExitInfoTimestamp >= it.timestamp) return@returnTag

                // appends the exit info to the file
                BugReportZipper.write(it, file)
            }
        }

        // Store the last exit reason time stamp
        persistentState.lastAppExitInfoTimestamp =
            persistentState.lastAppExitInfoTimestamp.coerceAtLeast(maxTimestamp)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun build() {
        BugReportZipper.build(applicationContext, file)
    }

    private fun dumpLogcat() {
        try {} catch (e: Exception) {
            Log.e(LOG_TAG_SCHEDULER, "Error while dumping logs", e)
        }
        val s1 =
            ProcessBuilder(
                LOGCAT_CMD,
                LOGCAT_OPTIONS_D,
                LOGCAT_OPTIONS_V,
                LOGCAT_OPTIONS_TIME,
                " ",
                LOGCAT_OPTIONS,
                LOGCAT_OPTIONS_B,
                EVENT_TYPES
            )
        val pd = s1.redirectErrorStream(true).start()
        val ips: InputStream = pd.inputStream
        BugReportZipper.writeTrace(file, ips)
        val exit = pd.waitFor()
        if (exit != 0) {
            Log.e(LOG_TAG_SCHEDULER, "logcat process exited with $exit")
        }
    }
}
