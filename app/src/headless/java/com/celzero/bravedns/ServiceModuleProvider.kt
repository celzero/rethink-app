/*
 * Copyright 2022 RethinkDNS and its authors
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

import android.content.ContentResolver
import com.celzero.bravedns.data.DataModule
import com.celzero.bravedns.database.DatabaseModule
import com.celzero.bravedns.scheduler.ScheduleManager
import com.celzero.bravedns.service.ServiceModule
import com.celzero.bravedns.util.OrbotHelper
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

private val RootModule = module {
    single<ContentResolver> { androidContext().contentResolver }
}

private val orbotHelperModule = module {
    single { OrbotHelper(androidContext(), get(), get()) }
}

private val schedulerModule = module {
    single { ScheduleManager(androidContext()) }
}

val AppModules: List<Module> by lazy {
    mutableListOf<Module>().apply {
        add(RootModule)
        addAll(DatabaseModule.modules)
        addAll(DataModule.modules)
        addAll(ServiceModule.modules)
        add(orbotHelperModule)
        add(schedulerModule)
    }
}
