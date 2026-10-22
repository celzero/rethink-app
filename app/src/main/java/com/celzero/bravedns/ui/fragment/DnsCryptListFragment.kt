/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.fragment

import com.celzero.bravedns.ui.compose.controls.*
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.dns.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.database.DnsCryptEndpoint
import com.celzero.bravedns.database.DnsCryptRelayEndpoint
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.viewmodel.DnsCryptEndpointViewModel
import com.celzero.bravedns.viewmodel.DnsCryptRelayEndpointViewModel

class DnsCryptListFragment : Fragment() {
    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()
    private val dnsCryptViewModel: DnsCryptEndpointViewModel by viewModel()
    private val dnsCryptRelayViewModel: DnsCryptRelayEndpointViewModel by viewModel()
    companion object { fun newInstance() = DnsCryptListFragment() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val owner = viewLifecycleOwner
        (view as ComposeView).setContent { RethinkTheme {
            val presenter = remember(owner) { DnsEndpointPresenter(requireContext(), owner, appConfig) }
            var adding by remember { mutableStateOf(false) }
            var relays by remember { mutableStateOf(false) }
            DnsEndpointScreen(dnsCryptViewModel.dnsCryptEndpointList, presenter::crypt,
                R.string.dns_crypt_empty_state_title, R.string.dns_crypt_empty_state_desc, { adding = true }, header = {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.cd_dns_crypt_title), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        OriginalButton("fragment_dns_crypt_list:add_relay_btn", onClick = {
                            if (!isAdded && requireActivity().isFinishing) Logger.w(Logger.LOG_TAG_UI, "DnsCrypt relay, Fragment not added to activity")
                            else relays = true
                        }) { Text(stringResource(R.string.cd_dnscrypt_relay_heading)) }
                    }
                })
            if (adding) DnsCryptEditor({ appConfig.getDnscryptCount().plus(1) to appConfig.getDnscryptRelayCount().plus(1) },
                onApply = { name, stamp, description, relay ->
                    if (stamp.isBlank()) getString(R.string.cd_dns_crypt_error_text_1)
                    else {
                        if (relay) insertDNSCryptRelay(name, stamp, description) else insertDNSCryptServer(name, stamp, description)
                        adding = false
                        null
                    }
                }, onDismiss = { adding = false })
            if (relays) DnsRelaysDialog(dnsCryptRelayViewModel.dnsCryptRelayEndpointList, presenter::relay,
                Themes.isFrostTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)), { relays = false })
        } }
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun insertDNSCryptRelay(name: String, urlStamp: String, desc: String) {
        io {
            var serverName = name
            if (serverName.isBlank()) {
                serverName = urlStamp
            }
            val dnsCryptRelayEndpoint =
                DnsCryptRelayEndpoint(
                    id = 0,
                    serverName,
                    urlStamp,
                    desc,
                    isSelected = false,
                    isCustom = true,
                    modifiedDataTime = 0L,
                    latency = 0
                )
            appConfig.insertDnscryptRelayEndpoint(dnsCryptRelayEndpoint)
        }
    }

    private fun insertDNSCryptServer(name: String, urlStamp: String, desc: String) {
        io {
            var serverName = name
            if (serverName.isBlank()) {
                serverName = urlStamp
            }

            val dnsCryptEndpoint =
                DnsCryptEndpoint(
                    id = 0,
                    serverName,
                    urlStamp,
                    desc,
                    isSelected = false,
                    isCustom = true,
                    modifiedDataTime = 0L,
                    latency = 0
                )
            appConfig.insertDnscryptEndpoint(dnsCryptEndpoint)
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

}
