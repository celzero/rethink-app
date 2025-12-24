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
package com.celzero.bravedns.service

import com.celzero.bravedns.database.RefreshDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

object ServiceModule {
    private val serviceModules = module {
        single { PersistentState(androidContext()) }
        single { EventLogger(get()) }
        single { NetLogTracker(androidContext(), get(), get(), get(), get(), get()) }
        single { RefreshDatabase(androidContext(), get(), get(), get(), get(), get()) }
    }

    val modules = listOf(serviceModules)
}
