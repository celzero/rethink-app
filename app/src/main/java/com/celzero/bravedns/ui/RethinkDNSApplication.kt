package com.celzero.bravedns.ui

import android.app.Application
import android.content.Context

class RethinkDNSApplication : Application() {

    companion object{
        var context: Context? = null
    }

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
    }


}