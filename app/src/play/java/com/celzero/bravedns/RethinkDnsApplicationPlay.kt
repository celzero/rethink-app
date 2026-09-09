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

import android.app.Application
import android.content.pm.ApplicationInfo
import com.celzero.bravedns.scheduler.EnhancedBugReport
import com.celzero.bravedns.scheduler.ScheduleManager
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.service.InAppMessageProvider
import com.celzero.bravedns.service.PlayInAppMessageProvider
import com.celzero.bravedns.util.FirebaseErrorReporting
import com.celzero.bravedns.util.GlobalExceptionHandler
import com.celzero.bravedns.util.GoReportingHandler
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_APP
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
                        // Play Billing in-app messaging
                        single<InAppMessageProvider> { PlayInAppMessageProvider() }
                    }
                )
            )
        }

        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Initialize exception handlers
        GlobalExceptionHandler.initialize(this)
        appScope.launch(Dispatchers.IO) {
            try {
                FirebaseApp.initializeApp(this@RethinkDnsApplicationPlay)
            } catch (e: Exception) {
                Logger.w(LOG_TAG_APP, "firebase app init failed: ${e.message}")
            }
            FirebaseErrorReporting.initialize()
            // On every app start, report any tombstone files from the previous session
            EnhancedBugReport.reportTombstonesToFirebaseOnStartup(
                this@RethinkDnsApplicationPlay)
        }
        GoReportingHandler.initialize(appScope, this)

        appScope.launch {
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
