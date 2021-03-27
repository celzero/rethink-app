package com.celzero.bravedns

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.StrictMode
import com.celzero.bravedns.service.PersistentState
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
class RethinkDnsApplication:Application() {

    override fun onCreate() {
        val persistentState = PersistentState(this)
        if (persistentState.theme == 0) {
            if (isDarkThemeOn()) {
                setTheme(R.style.AppTheme)
            } else {
                setTheme(R.style.AppThemeWhite)
            }
        } else if (persistentState.theme == 1) {
            setTheme(R.style.AppThemeWhite)
        } else {
            setTheme(R.style.AppTheme)
        }
        super.onCreate()

        //if(BuildConfig.DEBUG) turnOnStrictMode()

        startKoin {
            if(BuildConfig.DEBUG) androidLogger()
            androidContext(this@RethinkDnsApplication)
            koin.loadModules(AppModules)
        }
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun turnOnStrictMode() {
        if (BuildConfig.DEBUG) {
           /* StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                            .detectAll()
                            .penaltyLog()
                            .permitDiskReads()
                            .permitDiskWrites()
                            .permitNetwork()
                            .penaltyLog()
                            .build())*/
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder()
                            .detectAll()
                            .detectLeakedSqlLiteObjects()
                            .penaltyLog()
                            .build())
        }
    }
}
