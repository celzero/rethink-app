package com.celzero.bravedns.viewmodel

import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

object ViewModelModule {
    private val viewModelModule = module {
        viewModel {
            AppListViewModel(get())
        }
        viewModel {
            BlockedConnectionsViewModel(get())
        }
        viewModel {
            ConnectionTrackerViewModel(get())
        }
        viewModel {
            DNSCryptEndpointViewModel(get())
        }
        viewModel {
            DNSCryptRelayEndpointViewModel(get())
        }
        viewModel {
            DNSLogViewModel(get())
        }
        viewModel {
            DNSProxyEndpointViewModel(get())
        }
        viewModel {
            DoHEndpointViewModel(get())
        }
        viewModel {
            ExcludedAppViewModel(get())
        }
        viewModel {
            FirewallAppViewModel(get())
        }
    }

    val modules = listOf(viewModelModule)
}