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

import org.koin.androidx.viewmodel.dsl.viewModel
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
