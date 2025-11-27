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
package com.celzero.bravedns

import Logger.LOG_TAG_SCHEDULER
import android.app.Application
import android.content.pm.ApplicationInfo
import com.celzero.bravedns.battery.BatteryStatsLogger
import com.celzero.bravedns.scheduler.ScheduleManager
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.util.FirebaseErrorReporting
import com.celzero.bravedns.util.GlobalExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.dsl.module

class RethinkDnsApplicationPlay : Application() {

    override fun onCreate() {
        super.onCreate()

        RethinkDnsApplication.DEBUG =
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE ==
                    ApplicationInfo.FLAG_DEBUGGABLE


        startKoin {
            if (BuildConfig.DEBUG) androidLogger()
            androidContext(this@RethinkDnsApplicationPlay)
            koin.loadModules(AppModules)
            koin.loadModules(
                listOf(
                    module {
                        // New Koin override strategy allow to override any definition by default.
                        // don't need to specify override = true anymore in module.
                        single<AppUpdater> { StoreAppUpdater(androidContext()) }
                    }
                )
            )
        }

        // Initialize global exception handler
        GlobalExceptionHandler.initialize(this)
        FirebaseErrorReporting.initialize()

        CoroutineScope(SupervisorJob()).launch {
            scheduleJobs()
        }
    }

    private suspend fun scheduleJobs() {
        get<WorkScheduler>().scheduleAppExitInfoCollectionJob()
        get<ScheduleManager>().scheduleDatabaseRefreshJob()
        get<WorkScheduler>().scheduleDataUsageJob()
        get<WorkScheduler>().schedulePurgeConnectionsLog()
        get<WorkScheduler>().schedulePurgeConsoleLogs()
    }
}
