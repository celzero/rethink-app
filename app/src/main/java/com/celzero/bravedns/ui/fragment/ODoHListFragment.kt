/*
 * Copyright 2023 RethinkDNS and its authors
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
import com.celzero.bravedns.database.ODoHEndpoint
import com.celzero.bravedns.viewmodel.ODoHEndpointViewModel
import java.net.MalformedURLException
import java.net.URL

class ODoHListFragment : Fragment() {
    private val appConfig by inject<AppConfig>()
    private val viewModel: ODoHEndpointViewModel by viewModel()
    companion object { fun newInstance() = ODoHListFragment() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val owner = viewLifecycleOwner
        (view as ComposeView).setContent { RethinkTheme {
            val presenter = remember(owner) { DnsEndpointPresenter(requireContext(), owner, appConfig) }
            // Ordinary dialogs are transient; recreation dismisses them as in the baseline.
            var adding by remember { mutableStateOf(false) }
            DnsEndpointScreen(viewModel.dohEndpointList, presenter::odoh, R.string.odoh_empty_state_title, R.string.odoh_empty_state_desc, { adding = true })
            if (adding) DnsServerEditor(DnsServerKind.ODOH, { getString(R.string.lbl_odoh) + appConfig.getODoHCount().plus(1).toString() },
                onApply = { name, url, _, proxy, _ ->
                        if (checkUrl(url)) { insert(name, proxy, url); adding = false; null }
                        else getString(R.string.custom_url_error_invalid_url)
                }, onDismiss = { adding = false })
        } }
    }

    private fun checkUrl(url: String): Boolean {
        return try {
            val parsed = URL(url)
            parsed.protocol == "https" &&
                parsed.host.isNotEmpty() &&
                parsed.path.isNotEmpty() &&
                parsed.query == null &&
                parsed.ref == null
        } catch (e: MalformedURLException) {
            false
        }
    }

    private fun insert(name: String, proxy: String, resolver: String) {
        io {
            var odohName: String = name
            if (name.isBlank()) {
                odohName = resolver
            }
            val endpoint =
                ODoHEndpoint(
                    id = 0,
                    odohName,
                    proxy,
                    resolver,
                    proxyIps = "",
                    desc = "",
                    isSelected = false,
                    isCustom = true,
                    modifiedDataTime = 0,
                    latency = 0
                )
            appConfig.insertODoHEndpoint(endpoint)
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

}
