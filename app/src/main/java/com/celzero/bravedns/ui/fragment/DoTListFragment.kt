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
import com.celzero.bravedns.database.DoTEndpoint
import com.celzero.bravedns.viewmodel.DoTEndpointViewModel

class DoTListFragment : Fragment() {
    private val appConfig by inject<AppConfig>()
    private val viewModel: DoTEndpointViewModel by viewModel()
    companion object { fun newInstance() = DoTListFragment() }

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
            DnsEndpointScreen(viewModel.dohEndpointList, presenter::dot, R.string.dot_empty_state_title, R.string.dot_empty_state_desc, { adding = true })
            if (adding) DnsServerEditor(DnsServerKind.DOT, { getString(R.string.lbl_dot) + appConfig.getDoTCount().plus(1).toString() },
                onApply = { name, url, _, _, secure ->
                        insert(name, url, secure); adding = false; null
                }, onDismiss = { adding = false })
        } }
    }

    private fun insert(name: String, url: String, isSecure: Boolean) {
        io {
            var dotName: String = name
            if (name.isBlank()) {
                dotName = url
            }
            val endpoint =
                DoTEndpoint(
                    id = 0,
                    dotName,
                    url,
                    desc = "",
                    isSelected = false,
                    isCustom = true,
                    isSecure = isSecure,
                    modifiedDataTime = 0,
                    latency = 0
                )
            appConfig.insertDoTEndpoint(endpoint)
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

}
