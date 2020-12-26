package com.celzero.bravedns

import android.content.ContentResolver
import com.celzero.bravedns.data.DataModule
import com.celzero.bravedns.database.DatabaseModule
import com.celzero.bravedns.service.ServiceModule
import com.celzero.bravedns.viewmodel.ViewModelModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

private val RootModule = module {
    single<ContentResolver> { androidContext().contentResolver }
}

val AppModules:List<Module> by lazy {
    mutableListOf<Module>().apply {
        add(RootModule)

        addAll(DatabaseModule.modules)
        addAll(ViewModelModule.modules)
        addAll(DataModule.modules)
        addAll(ServiceModule.modules)
    }
}