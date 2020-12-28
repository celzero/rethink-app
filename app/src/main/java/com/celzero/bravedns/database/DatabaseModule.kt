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

import com.celzero.bravedns.util.DatabaseHandler
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

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
object DatabaseModule {
    private val databaseModule = module {
        single { AppDatabase.buildDatabase(androidContext()) }
        single {
            RefreshDatabase(
                androidContext(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get(),
                get()
            )
        }
        single {
            DatabaseHandler(androidContext())
        }
    }
    private val daoModule = module {
        single<AppInfoDAO> { get<AppDatabase>().appInfoDAO() }
        single<BlockedConnectionsDAO> { get<AppDatabase>().blockedConnectionsDAO() }
        single<CategoryInfoDAO> { get<AppDatabase>().categoryInfoDAO() }
        single<ConnectionTrackerDAO> { get<AppDatabase>().connectionTrackerDAO() }
        single<DNSCryptEndpointDAO> { get<AppDatabase>().dnsCryptEndpointDAO() }
        single<DNSCryptRelayEndpointDAO> { get<AppDatabase>().dnsCryptRelayEndpointDAO() }
        single<DNSLogDAO> { get<AppDatabase>().dnsLogDAO() }
        single<DNSProxyEndpointDAO> { get<AppDatabase>().dnsProxyEndpointDAO() }
        single<DoHEndpointDAO> { get<AppDatabase>().dohEndpointsDAO() }
        single<ProxyEndpointDAO> { get<AppDatabase>().proxyEndpointDAO() }
    }
    private val repositoryModule = module {
        single { AppInfoRepository(get()) }
        single { BlockedConnectionsRepository(get()) }
        single { CategoryInfoRepository(get()) }
        single { ConnectionTrackerRepository(get()) }
        single { DNSCryptEndpointRepository(get()) }
        single { DNSCryptRelayEndpointRepository(get()) }
        single { DNSLogRepository(get()) }
        single { DNSProxyEndpointRepository(get()) }
        single { DoHEndpointRepository(get()) }
        single { ProxyEndpointRepository(get()) }
    }


    val modules = listOf(databaseModule, daoModule, repositoryModule)
}
