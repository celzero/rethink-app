package com.celzero.bravedns

import android.app.Application
import android.content.pm.ApplicationInfo
import com.celzero.bravedns.scheduler.ScheduleManager
import com.celzero.bravedns.service.ServiceModule
import com.celzero.bravedns.util.GlobalExceptionHandler
import com.celzero.bravedns.util.LocalBlocklistUtil
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
        DEBUG =
            applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == ApplicationInfo.FLAG_DEBUGGABLE

        startKoin {
            if (DEBUG) androidLogger()
            androidContext(this@RethinkDnsApplication)
            koin.loadModules(ServiceModule.modules)
            LocalBlocklistUtil(this@RethinkDnsApplication, get()).init()
            koin.loadModules(AppModules)
        }

        // Initialize global exception handler
        GlobalExceptionHandler.initialize(this)
        FirebaseErrorReporting.initialize()
        get<ScheduleManager>().scheduleDatabaseRefreshJob()
    }
}
