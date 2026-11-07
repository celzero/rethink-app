/*
 * Copyright 2026 RethinkDNS and its authors
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.dns.SmartDnsContent
import com.celzero.bravedns.ui.compose.wireguard.WgAlertDialogBuilder
import com.celzero.bravedns.viewmodel.SmartDnsEndpointViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class SmartDnsListFragment : Fragment() {
    private val appConfig by inject<AppConfig>()
    private val viewModel: SmartDnsEndpointViewModel by viewModel()
    companion object { fun newInstance() = SmartDnsListFragment() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply { setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (view as ComposeView).setContent { RethinkTheme {
            val items by viewModel.smartDnsEndpointList.observeAsState()
            SmartDnsContent(items, appConfig.isSmartDnsEnabled(),
                onSelect = { endpoint -> io { appConfig.enableSmartDns(endpoint.id) } },
                onInfo = { endpoint ->
                    WgAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
                        .setTitle(endpoint.dnsName).setMessage(endpoint.dnsExplanation).setCancelable(true)
                        .setPositiveButton(R.string.dns_info_positive) { dialog, _ -> dialog.dismiss() }.show()
                })
        } }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
