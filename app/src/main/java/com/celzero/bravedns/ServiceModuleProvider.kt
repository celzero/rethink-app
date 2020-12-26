package com.celzero.bravedns

import android.content.ContentResolver
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

val RootModule = module {
    single<ContentResolver> { androidContext().contentResolver }
}

val AppModules:List<Module> by lazy {
    mutableListOf<Module>().apply {
        add(RootModule)
    }
}