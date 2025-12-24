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
package com.celzero.bravedns.viewmodel

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

object ViewModelModule {
    private val viewModelModule = module {
        viewModel { ConnectionTrackerViewModel(get()) }
        viewModel { DnsCryptEndpointViewModel(get()) }
        viewModel { DnsCryptRelayEndpointViewModel(get()) }
        viewModel { DnsLogViewModel(get()) }
        viewModel { DnsProxyEndpointViewModel(get()) }
        viewModel { DoHEndpointViewModel(get()) }
        viewModel { AppInfoViewModel(get()) }
        viewModel { CustomDomainViewModel(get()) }
        viewModel { CustomIpViewModel(get()) }
        viewModel { RethinkEndpointViewModel(get()) }
        viewModel { AppCustomIpViewModel(get()) }
        viewModel { RethinkRemoteFileTagViewModel(get()) }
        viewModel { RethinkLocalFileTagViewModel(get()) }
        viewModel { AppConnectionsViewModel(get(), get(), get()) }
        viewModel { SummaryStatisticsViewModel(get(), get()) }
        viewModel { DetailedStatisticsViewModel(get(), get()) }
        viewModel { LocalBlocklistPacksMapViewModel(get()) }
        viewModel { RemoteBlocklistPacksMapViewModel(get()) }
        viewModel { ProxyAppsMappingViewModel(get()) }
        viewModel { WgConfigViewModel(get()) }
        viewModel { DoTEndpointViewModel(get()) }
        viewModel { ODoHEndpointViewModel(get()) }
        viewModel { RethinkLogViewModel(get()) }
        viewModel { AlertsViewModel(get(), get()) }
        viewModel { ConsoleLogViewModel(get()) }
        viewModel { DomainConnectionsViewModel(get()) }
        viewModel { WgNwActivityViewModel(get()) }
        viewModel { EventsViewModel(get()) }
    }

    val modules = listOf(viewModelModule)
}
