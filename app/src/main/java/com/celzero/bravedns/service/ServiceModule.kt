package com.celzero.bravedns.service

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module


object ServiceModule {
    private val serviceModules = module {
        single { IPTracker(get(), get(), get(), androidContext()) }
        single { DNSLogTracker(get(), get(), androidContext()) }
        single { PersistentState(androidContext()) }
        single { QueryTracker(get(), androidContext()) }
    }

    val modules = listOf(serviceModules)
}