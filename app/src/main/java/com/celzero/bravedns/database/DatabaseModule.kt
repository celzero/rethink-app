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
package com.celzero.bravedns.database

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

object DatabaseModule {
    private val databaseModule = module {
        single { AppDatabase.buildDatabase(androidContext()) }
        single { RefreshDatabase(androidContext(), get(), get(), get()) }
    }
    private val daoModule = module {
        single { get<AppDatabase>().appInfoDAO() }
        single { get<AppDatabase>().connectionTrackerDAO() }
        single { get<AppDatabase>().dnsCryptEndpointDAO() }
        single { get<AppDatabase>().dnsCryptRelayEndpointDAO() }
        single { get<AppDatabase>().dnsLogDAO() }
        single { get<AppDatabase>().dnsProxyEndpointDAO() }
        single { get<AppDatabase>().dohEndpointsDAO() }
        single { get<AppDatabase>().proxyEndpointDAO() }
        single { get<AppDatabase>().customDomainEndpointDAO() }
        single { get<AppDatabase>().customIpEndpointDao() }
        single { get<AppDatabase>().rethinkEndpointDao() }
        single { get<AppDatabase>().rethinkLocalFileTagDao() }
        single { get<AppDatabase>().rethinkRemoteFileTagDao() }
    }
    private val repositoryModule = module {
        single { get<AppDatabase>().appInfoRepository() }
        single { get<AppDatabase>().connectionTrackerRepository() }
        single { get<AppDatabase>().dnsCryptEndpointRepository() }
        single { get<AppDatabase>().dnsCryptRelayEndpointRepository() }
        single { get<AppDatabase>().dnsLogRepository() }
        single { get<AppDatabase>().dnsProxyEndpointRepository() }
        single { get<AppDatabase>().dohEndpointRepository() }
        single { get<AppDatabase>().proxyEndpointRepository() }
        single { get<AppDatabase>().customDomainRepository() }
        single { get<AppDatabase>().customIpRepository() }
        single { get<AppDatabase>().rethinkEndpointRepository() }
        single { get<AppDatabase>().rethinkRemoteFileTagRepository() }
        single { get<AppDatabase>().rethinkLocalFileTagRepository() }
    }

    val modules = listOf(databaseModule, daoModule, repositoryModule)
}
