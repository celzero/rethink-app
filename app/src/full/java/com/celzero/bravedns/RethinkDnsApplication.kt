/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns

import Logger
import Logger.LOG_TAG_SCHEDULER
import android.app.Activity
import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.StrictMode
import com.celzero.bravedns.battery.BatteryStatsLogger
import com.celzero.bravedns.battery.BatteryStatsProvider.logMetrics
import com.celzero.bravedns.scheduler.ScheduleManager
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.util.FirebaseErrorReporting
import com.celzero.bravedns.util.GlobalExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class RethinkDnsApplication : Application(), Application.ActivityLifecycleCallbacks {
    companion object {
        var DEBUG: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        DEBUG =
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE ==
                    ApplicationInfo.FLAG_DEBUGGABLE

        startKoin {
            if (DEBUG) androidLogger()
            androidContext(this@RethinkDnsApplication)
            koin.loadModules(AppModules)
        }

        // Initialize global exception handler
        GlobalExceptionHandler.initialize(this)
        FirebaseErrorReporting.initialize()

        // initialize battery stats provider (facebook battery-metrics)
        try {
            BatteryStatsLogger.start(this)
        } catch (e: Exception) {
            Logger.w(LOG_TAG_SCHEDULER, "battery-stats-init-failed: ${e.message}", e)
        }

        turnOnStrictMode()

        CoroutineScope(SupervisorJob()).launch {
            scheduleJobs()
        }

        registerActivityLifecycleCallbacks(this)
    }

    private suspend fun scheduleJobs() {
        Logger.d(LOG_TAG_SCHEDULER, "Schedule job")
        get<WorkScheduler>().scheduleAppExitInfoCollectionJob()
        // database refresh is used in both headless and main project
        get<ScheduleManager>().scheduleDatabaseRefreshJob()
        get<WorkScheduler>().scheduleDataUsageJob()
        get<WorkScheduler>().schedulePurgeConnectionsLog()
        get<WorkScheduler>().schedulePurgeConsoleLogs()
    }

    private fun turnOnStrictMode() {
        if (!DEBUG) return
        // Uncomment the code below to enable the StrictModes.
        // To test the apps disk read/writes, network usages.
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .permitDiskReads()
                .permitDiskWrites()
                .permitNetwork()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .detectLeakedSqlLiteObjects()
                .penaltyLog()
                .build()
        )
    }

    override fun onActivityCreated(p0: Activity, p1: Bundle?) {}

    override fun onActivityDestroyed(p0: Activity) {}

    override fun onActivityPaused(p0: Activity) {
        logMetrics("foreground")
    }

    override fun onActivityResumed(p0: Activity) {
        logMetrics("background")
    }

    override fun onActivitySaveInstanceState(p0: Activity, p1: Bundle) {}

    override fun onActivityStarted(p0: Activity) {}

    override fun onActivityStopped(p0: Activity) {}
}
