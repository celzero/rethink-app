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
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastO
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.InputStream

class BugReportCollector(val context: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(context, workerParameters), KoinComponent {

    private val persistentState by inject<PersistentState>()

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
        Logger.d(LOG_TAG_SCHEDULER, "starting app-exit-info job")
        if (!isAtleastO()) {
            // support for zip file creation for devices below Oreo is not straightforward
            // hence, we are not supporting it for now
            return Result.success()
        }

        // prepare the bugreport directory and file
        val fout = prepare()
        storePrefs(fout)
        val ts = dumpLogsAndAppExits(fout)
        addToZip(fout)
        // Store the last exit reason time stamp
        persistentState.lastAppExitInfoTimestamp =
            persistentState.lastAppExitInfoTimestamp.coerceAtLeast(ts)
        return Result.success()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun prepare(): File {
        val path = BugReportZipper.prepare(applicationContext.filesDir)
        Logger.i(LOG_TAG_SCHEDULER, "app-exit-info job path: $path")
        val file = File(path)
        Logger.i(LOG_TAG_SCHEDULER, "app-exit-info job file: ${file.name}, ${file.absolutePath}")
        return file
    }

    private fun storePrefs(file: File) {
        // write all the shared preferences values into the file
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        BugReportZipper.dumpPrefs(prefs, file)
    }

    private fun dumpLogsAndAppExits(file: File): Long {
        // use the logcat dumper to get the default info
        dumpLogcat(file)

        // app exit info is available only on Android R and above, normal process builder
        // for logcat is used for all the other versions
        if (!Utilities.isAtleastR()) {
            Logger.i(LOG_TAG_SCHEDULER, "app-exit-info job not supported on this device")
            return -1L
        }

        val am = context.getSystemService(AppCompatActivity.ACTIVITY_SERVICE) as ActivityManager

        // gets all the historical process exit reasons.
        val appExitInfo = am.getHistoricalProcessExitReasons(null, 0, 0)

        if (appExitInfo.isEmpty()) return -1L

        var maxTimestamp = appExitInfo[0].timestamp

        run returnTag@{
            appExitInfo.forEach {
                maxTimestamp = maxTimestamp.coerceAtLeast(it.timestamp)

                // Write exit infos past the previously recorded checkpoint
                if (persistentState.lastAppExitInfoTimestamp >= it.timestamp) return@returnTag

                // appends the exit info to the file
                BugReportZipper.dumpAppExit(it, file)
            }
        }

        return maxTimestamp
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun addToZip(file: File) {
        val dir = applicationContext.filesDir
        BugReportZipper.rezipAll(dir, file)
        BugReportZipper.deleteAll(dir)
    }

    private fun dumpLogcat(file: File) {
        try {
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
            BugReportZipper.fileWrite(ips, file)
            val exitcode = pd.waitFor()
            if (exitcode != 0) {
                Logger.e(LOG_TAG_SCHEDULER, "logcat process exited with $exitcode")
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_SCHEDULER, "err while dumping logs", e)
        }
    }
}
