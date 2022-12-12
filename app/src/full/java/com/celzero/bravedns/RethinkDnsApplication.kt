package com.celzero.bravedns

import android.app.Application
import android.os.StrictMode
import android.util.Log
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.BuildConfig.DEBUG
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_SCHEDULER
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

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
class RethinkDnsApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        turnOnStrictMode()

        startKoin {
            if (DEBUG) androidLogger()
            androidContext(this@RethinkDnsApplication)
            koin.loadModules(AppModules)
        }

        if (DEBUG) Log.d(LOG_TAG_SCHEDULER, "Schedule job")
        get<WorkScheduler>().scheduleAppExitInfoCollectionJob()
        get<WorkScheduler>().scheduleDatabaseRefreshJob()
        get<WorkScheduler>().schedulePurgeConnectionsLog()
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
}
