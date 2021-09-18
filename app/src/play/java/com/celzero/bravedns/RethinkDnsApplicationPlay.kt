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
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.util.Constants
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.dsl.module

class RethinkDnsApplicationPlay : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            if (BuildConfig.DEBUG) androidLogger()
            androidContext(this@RethinkDnsApplicationPlay)
            koin.loadModules(AppModules)
            koin.loadModules(listOf(module {
                // New Koin override strategy allow to override any definition by default.
                // don't need to specify override = true anymore in module.
                single<AppUpdater> { StoreAppUpdater(androidContext()) }
            }))
        }

        get<WorkScheduler>().scheduleAppExitInfoCollectionJob()
        get<WorkScheduler>().scheduleDatabaseRefreshJob()
    }
}
