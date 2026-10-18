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
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import com.celzero.bravedns.database.DnsProxyEndpoint
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.DnsProxyEndpointViewModel
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.withContext

class DnsProxyListFragment : Fragment() {
    private val appConfig by inject<AppConfig>()
    private val rdb by inject<RefreshDatabase>()
    private val persistentState by inject<PersistentState>()
    private val dnsProxyViewModel: DnsProxyEndpointViewModel by viewModel()
    companion object { fun newInstance() = DnsProxyListFragment() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val owner = viewLifecycleOwner
        (view as ComposeView).setContent { RethinkTheme {
            val presenter = remember(owner) { DnsEndpointPresenter(requireContext(), owner, appConfig, persistentState) }
            var adding by remember { mutableStateOf(false) }
            var appNames by remember { mutableStateOf<List<String>>(emptyList()) }
            var initialName by remember { mutableStateOf("") }
            DnsEndpointScreen(dnsProxyViewModel.dnsProxyEndpointList, presenter::proxy,
                R.string.dns_proxy_empty_state_title, R.string.dns_proxy_empty_state_desc,
                { appNames = emptyList(); adding = true })
            if (adding) {
                LaunchedEffect(Unit) {
                    val result = withContext(Dispatchers.IO) {
                        rdb.refresh(RefreshDatabase.ACTION_REFRESH_FORCE)
                        val names = mutableListOf(getString(R.string.settings_app_list_default_app))
                        names.addAll(FirewallManager.getAllAppNamesSortedByVpnPermission(requireContext()))
                        names.toList() to appConfig.getDnsProxyCount().plus(1)
                    }
                    initialName = getString(R.string.cd_custom_dns_proxy_name, result.second.toString())
                    appNames = result.first
                }
                if (appNames.isNotEmpty()) DnsProxyEditor(appNames, initialName,
                    !persistentState.excludeAppsInProxy, VpnController.isVpnLockdown(),
                    onVpnSettings = { adding = false; UIUtils.openVpnProfile(requireContext()) },
                    onApply = { name, app, ips, port, forwardApps ->
                        val error = applyProxy(name, app, ips, port, forwardApps)
                        if (error == null) adding = false
                        error
                    }, onDismiss = { adding = false })
            }
        } }
    }

    private fun applyProxy(name: String, appName: String, ipInput: String, portInput: String, forwardApps: Boolean): String? {
        var port = 0
        var isPortValid: Boolean
        var error: String? = null
        val mode = getString(R.string.cd_dns_proxy_mode_external)
        val ipAddresses = ipInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (ipAddresses.isEmpty()) return getString(R.string.cd_dns_proxy_error_text_1)
        val invalidIps = mutableListOf<String>()
        val validIps = mutableListOf<String>()
        for (ip in ipAddresses) {
            if (IPAddressString(ip).isIPAddress) validIps.add(ip) else invalidIps.add(ip)
        }
        if (invalidIps.isNotEmpty()) return getString(R.string.cd_dns_proxy_error_text_1) + ": ${invalidIps.joinToString(", ")}"
        try {
            port = portInput.toInt()
            isPortValid = true
            for (ip in validIps) {
                if (Utilities.isLanIpv4(ip)) {
                    if (!Utilities.isValidLocalPort(port)) { isPortValid = false; break }
                }
            }
            if (!isPortValid) error = getString(R.string.cd_dns_proxy_error_text_2)
        } catch (e: NumberFormatException) {
            Logger.w(Logger.LOG_TAG_UI, "Error: ${e.message}", e)
            error = getString(R.string.cd_dns_proxy_error_text_3)
            isPortValid = false
        }
        if (isPortValid && validIps.isNotEmpty()) {
            val ipString = validIps.joinToString(",")
            Logger.d(Logger.LOG_TAG_UI, "new values inserted into DNSProxy: $name with ${validIps.size} IPs")
            io { insertDNSProxyEndpointDB(mode, name, appName, ipString, port) }
            persistentState.excludeAppsInProxy = !forwardApps
            return null
        }
        Logger.i(Logger.LOG_TAG_UI, "cannot insert invalid dns-proxy IPs: $name, $appName")
        return error
    }

    private suspend fun insertDNSProxyEndpointDB(
        mode: String,
        name: String,
        appName: String?,
        ip: String,
        port: Int
    ) {
        if (appName == null) return

        io {
            val packageName =
                if (appName == getString(R.string.settings_app_list_default_app)) {
                    ""
                } else {
                    FirewallManager.getPackageNameByAppName(appName).orEmpty()
                }
            var proxyName = name
            if (proxyName.isBlank()) {
                proxyName =
                    if (mode == getString(R.string.cd_dns_proxy_mode_internal)) {
                        appName
                    } else ip
            }
            val dnsProxyEndpoint =
                DnsProxyEndpoint(
                    id = 0,
                    proxyName,
                    mode,
                    packageName,
                    ip,
                    port,
                    isSelected = false,
                    isCustom = true,
                    modifiedDataTime = 0L,
                    latency = 0
                )
            appConfig.insertDnsproxyEndpoint(dnsProxyEndpoint)
            Logger.d(Logger.LOG_TAG_UI, "Insert into DNSProxy database: $packageName, $port")
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

}
