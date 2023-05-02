package com.celzero.bravedns

import android.app.Application
import android.content.pm.ApplicationInfo
import android.net.VpnService
import android.os.StrictMode
import android.util.Log
import com.celzero.bravedns.scheduler.ScheduleManager
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.LoggerConstants
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
    companion object {
        var DEBUG: Boolean = false
    }

    override fun onCreate() {
        super.onCreate()

        DEBUG = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == ApplicationInfo.FLAG_DEBUGGABLE

        turnOnStrictMode()

        startKoin {
            if (DEBUG) androidLogger()
            androidContext(this@RethinkDnsApplication)
            koin.loadModules(AppModules)
        }

        if (DEBUG) Log.d(LOG_TAG_SCHEDULER, "Schedule job")
        get<WorkScheduler>().scheduleAppExitInfoCollectionJob()
        // database refresh is used in both headless and main project
        get<ScheduleManager>().scheduleDatabaseRefreshJob()
        get<WorkScheduler>().schedulePurgeConnectionsLog()

        /**
         * Issue fix - https://github.com/celzero/rethink-app/issues/57 When the application
         * crashes/updates it goes into red waiting state. This causes confusion to the users also
         * requires click of START button twice to start the app. FIX : The check for the controller
         * state. If persistence state has vpn enabled and the VPN is not connected then the start
         * will be initiated.
         */
        val state = VpnController.state()
        if (state.activationRequested && !state.on) {
            Log.i(LoggerConstants.LOG_TAG_VPN, "start VPN (previous state)")
            if (VpnService.prepare(this) == null) VpnController.start(this)
        } else VpnController.stop(this)
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
