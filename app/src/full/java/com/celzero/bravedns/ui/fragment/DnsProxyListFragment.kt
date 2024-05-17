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

import Logger
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.DnsProxyEndpointAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DnsProxyEndpoint
import com.celzero.bravedns.databinding.DialogSetDnsProxyBinding
import com.celzero.bravedns.databinding.FragmentDnsProxyListBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.DnsProxyEndpointViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class DnsProxyListFragment : Fragment(R.layout.fragment_dns_proxy_list) {
    private val b by viewBinding(FragmentDnsProxyListBinding::bind)

    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()

    // DNS Proxy UI Elements
    private lateinit var dnsProxyRecyclerAdapter: DnsProxyEndpointAdapter
    private var dnsProxyLayoutManager: RecyclerView.LayoutManager? = null
    private val dnsProxyViewModel: DnsProxyEndpointViewModel by viewModel()

    companion object {
        fun newInstance() = DnsProxyListFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
        initClickListeners()
    }

    private fun init() {
        dnsProxyLayoutManager = LinearLayoutManager(requireContext())
        b.recyclerDnsProxyConnections.layoutManager = dnsProxyLayoutManager

        dnsProxyRecyclerAdapter =
            DnsProxyEndpointAdapter(requireContext(), viewLifecycleOwner, get())
        dnsProxyViewModel.dnsProxyEndpointList.observe(viewLifecycleOwner) {
            dnsProxyRecyclerAdapter.submitData(viewLifecycleOwner.lifecycle, it)
        }
        b.recyclerDnsProxyConnections.adapter = dnsProxyRecyclerAdapter
    }

    private fun initClickListeners() {
        b.dohFabAddServerIcon.bringToFront()
        b.dohFabAddServerIcon.setOnClickListener {
            io {
                val appNames: MutableList<String> = ArrayList()
                appNames.add(getString(R.string.settings_app_list_default_app))
                appNames.addAll(FirewallManager.getAllAppNames())
                // fetch the count from repository and increment by 1 to show the
                // next doh name in the dialog
                val nextIndex = appConfig.getDnsProxyCount().plus(1)
                uiCtx { showAddDnsProxyDialog(appNames, nextIndex) }
            }
        }
    }

    private fun showAddDnsProxyDialog(appNames: List<String>, nextIndex: Int) {
        val dialogBinding = DialogSetDnsProxyBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext()).setView(dialogBinding.root)
        val lp = WindowManager.LayoutParams()
        val dialog = builder.create()
        dialog.show()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT

        dialog.setCancelable(true)
        dialog.window?.attributes = lp

        val applyURLBtn = dialogBinding.dialogDnsProxyApplyBtn
        val cancelURLBtn = dialogBinding.dialogDnsProxyCancelBtn
        val lockdownDesc = dialogBinding.dialogDnsProxyLockdownDesc
        val proxyNameEditText = dialogBinding.dialogDnsProxyEditName
        val appNameSpinner = dialogBinding.dialogProxySpinnerAppname
        val ipAddressEditText = dialogBinding.dialogDnsProxyEditIp
        val portEditText = dialogBinding.dialogDnsProxyEditPort
        val errorTxt = dialogBinding.dialogDnsProxyErrorText
        val excludeAppCheckBox = dialogBinding.dialogDnsProxyExcludeAppsCheck

        excludeAppCheckBox.isChecked = !persistentState.excludeAppsInProxy
        excludeAppCheckBox.isEnabled = !VpnController.isVpnLockdown()
        lockdownDesc.visibility = if (VpnController.isVpnLockdown()) View.VISIBLE else View.GONE
        if (VpnController.isVpnLockdown()) {
            excludeAppCheckBox.alpha = 0.5f
        }
        proxyNameEditText.setText(
            getString(R.string.cd_custom_dns_proxy_name, nextIndex.toString()),
            TextView.BufferType.EDITABLE
        )
        ipAddressEditText.setText(
            getString(R.string.cd_custom_dns_proxy_default_ip),
            TextView.BufferType.EDITABLE
        )
        val proxySpinnerAdapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, appNames)
        appNameSpinner.adapter = proxySpinnerAdapter

        lockdownDesc.setOnClickListener {
            dialog.dismiss()
            UIUtils.openVpnProfile(requireContext())
        }

        applyURLBtn.setOnClickListener {
            var port = 0
            var isPortValid: Boolean
            val isIpValid: Boolean
            val name = proxyNameEditText.text.toString()
            val mode = getString(R.string.cd_dns_proxy_mode_external)
            val ip = ipAddressEditText.text.toString()

            val appName = appNameSpinner.selectedItem.toString()
            if (IPAddressString(ip).isIPAddress) {
                isIpValid = true
            } else {
                errorTxt.text = getString(R.string.cd_dns_proxy_error_text_1)
                isIpValid = false
            }

            try {
                port = portEditText.text.toString().toInt() // can cause NumberFormatException
                isPortValid =
                    if (Utilities.isLanIpv4(ip)) {
                        Utilities.isValidLocalPort(port)
                    } else {
                        true
                    }
                if (!isPortValid) {
                    errorTxt.text = getString(R.string.cd_dns_proxy_error_text_2)
                }
            } catch (e: NumberFormatException) {
                Logger.w(Logger.LOG_TAG_UI, "Error: ${e.message}", e)
                errorTxt.text = getString(R.string.cd_dns_proxy_error_text_3)
                isPortValid = false
            }

            if (isPortValid && isIpValid) {
                Logger.d(Logger.LOG_TAG_UI, "new value inserted into DNSProxy")
                io { insertDNSProxyEndpointDB(mode, name, appName, ip, port) }
                persistentState.excludeAppsInProxy = !excludeAppCheckBox.isChecked
                dialog.dismiss()
            } else {
                Logger.i(Logger.LOG_TAG_UI, "cannot insert invalid dns-proxy IPs: $name, $appName")
            }
        }

        cancelURLBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
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
                    FirewallManager.getPackageNameByAppName(appName) ?: ""
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

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
