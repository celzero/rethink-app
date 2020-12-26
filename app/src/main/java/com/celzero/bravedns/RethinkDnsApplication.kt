package com.celzero.bravedns

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class RethinkDnsApplication:Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            if(BuildConfig.DEBUG) androidLogger()
            androidContext(this@RethinkDnsApplication)
            koin.loadModules(AppModules)
        }
    }
}