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

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.adapter.DnsProxyEndpointAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DnsProxyEndpoint
import com.celzero.bravedns.databinding.DialogSetDnsProxyBinding
import com.celzero.bravedns.databinding.FragmentDnsProxyListBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.DnsProxyEndpointViewModel
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
        io {
            val appNames: MutableList<String> = ArrayList()
            appNames.add(getString(R.string.settings_app_list_default_app))
            appNames.addAll(FirewallManager.getAllAppNames())
            uiCtx { b.dohFabAddServerIcon.setOnClickListener { showAddDnsProxyDialog(appNames) } }
        }
    }

    private fun showAddDnsProxyDialog(appNames: List<String>) {
        val dialogBinding = DialogSetDnsProxyBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle(getString(R.string.cd_custom_dns_proxy_title))
        dialog.setContentView(dialogBinding.root)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(true)

        // TODO: figure out why window maybe null
        dialog.window?.attributes = lp

        val applyURLBtn = dialogBinding.dialogDnsProxyApplyBtn
        val cancelURLBtn = dialogBinding.dialogDnsProxyCancelBtn
        val proxyNameEditText = dialogBinding.dialogDnsProxyEditName
        val appNameSpinner = dialogBinding.dialogProxySpinnerAppname
        val ipAddressEditText = dialogBinding.dialogDnsProxyEditIp
        val portEditText = dialogBinding.dialogDnsProxyEditPort
        val errorTxt = dialogBinding.dialogDnsProxyErrorText

        // fetch the count from repository and increment by 1 to show the
        // next doh name in the dialog
        io {
            val nextIndex = appConfig.getDnsProxyCount().plus(1)
            uiCtx {
                proxyNameEditText.setText(
                    getString(R.string.cd_custom_dns_proxy_name, nextIndex.toString()),
                    TextView.BufferType.EDITABLE
                )
            }
        }

        proxyNameEditText.setText(
            getString(R.string.cd_custom_dns_proxy_name_default),
            TextView.BufferType.EDITABLE
        )
        ipAddressEditText.setText(
            getString(R.string.cd_custom_dns_proxy_default_ip),
            TextView.BufferType.EDITABLE
        )
        val proxySpinnerAdapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, appNames)
        appNameSpinner.adapter = proxySpinnerAdapter

        applyURLBtn.setOnClickListener {
            var port = 0
            var isPortValid: Boolean
            val isIpValid: Boolean
            val name = proxyNameEditText.text.toString()
            val mode = getString(R.string.cd_dns_proxy_mode_external)
            val ip = ipAddressEditText.text.toString()

            val appName = getString(R.string.cd_custom_dns_proxy_default_app)

            if (IPAddressString(ip).isIPAddress) {
                isIpValid = true
            } else {
                errorTxt.text = getString(R.string.cd_dns_proxy_error_text_1)
                isIpValid = false
            }

            try {
                port = portEditText.text.toString().toInt()
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
                Log.w(LoggerConstants.LOG_TAG_UI, "Error: ${e.message}", e)
                errorTxt.text = getString(R.string.cd_dns_proxy_error_text_3)
                isPortValid = false
            }

            if (isPortValid && isIpValid) {
                // Do the DNS Proxy setting there
                if (DEBUG) Log.d(LoggerConstants.LOG_TAG_UI, "new value inserted into DNSProxy")
                insertDNSProxyEndpointDB(mode, name, appName, ip, port)
                dialog.dismiss()
            } else {
                Log.i(
                    LoggerConstants.LOG_TAG_UI,
                    "cannot insert invalid dns-proxy IPs: $name, $appName"
                )
            }
        }

        cancelURLBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun insertDNSProxyEndpointDB(
        mode: String,
        name: String,
        appName: String?,
        ip: String,
        port: Int
    ) {
        if (appName == null) return

        io {
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
                    appName,
                    ip,
                    port,
                    isSelected = false,
                    isCustom = true,
                    modifiedDataTime = 0L,
                    latency = 0
                )
            appConfig.insertDnsproxyEndpoint(dnsProxyEndpoint)
            if (DEBUG)
                Log.d(LoggerConstants.LOG_TAG_UI, "Insert into DNSProxy database- $appName, $port")
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
