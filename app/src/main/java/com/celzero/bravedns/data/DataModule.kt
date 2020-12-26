package com.celzero.bravedns.data

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

object DataModule {
    private val dataModule = module {
        single { AppMode(androidContext(), get(), get(), get(), get(), get()) }
    }

    val modules = listOf(dataModule)
}