/*
 *Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns.ui

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.*
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.database.*
import com.celzero.bravedns.databinding.DialogSetCustomUrlBinding
import com.celzero.bravedns.databinding.DialogSetDnsCryptBinding
import com.celzero.bravedns.databinding.DialogSetDnsProxyBinding
import com.celzero.bravedns.databinding.FragmentConfigureDnsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_DNSCRYPT
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_DOH
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_PROXY
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_UI
import com.celzero.bravedns.util.UIUpdateInterface
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.isValidLocalPort
import com.celzero.bravedns.viewmodel.DNSCryptEndpointViewModel
import com.celzero.bravedns.viewmodel.DNSCryptRelayEndpointViewModel
import com.celzero.bravedns.viewmodel.DNSProxyEndpointViewModel
import com.celzero.bravedns.viewmodel.DoHEndpointViewModel
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.net.MalformedURLException
import java.net.URL


class ConfigureDNSFragment : Fragment(R.layout.fragment_configure_dns), UIUpdateInterface {
    private val b by viewBinding(FragmentConfigureDnsBinding::bind)

    //DOH UI elements
    private var layoutManager: RecyclerView.LayoutManager? = null
    private lateinit var dohRecyclerAdapter: DoHEndpointAdapter
    private val viewModel: DoHEndpointViewModel by viewModel()

    //DNSCrypt UI elements
    private lateinit var dnsCryptRecyclerAdapter: DNSCryptEndpointAdapter
    private var dnsCryptLayoutManager: RecyclerView.LayoutManager? = null
    private val dnsCryptViewModel: DNSCryptEndpointViewModel by viewModel()

    //DNSCryptRelay UI elements
    private lateinit var dnsCryptRelayRecyclerAdapter: DNSCryptRelayEndpointAdapter
    private var dnsCryptRelayLayoutManager: RecyclerView.LayoutManager? = null
    private val dnsCryptRelayViewModel: DNSCryptRelayEndpointViewModel by viewModel()

    //DNS Proxy UI Elements
    private lateinit var dnsProxyRecyclerAdapter: DNSProxyEndpointAdapter
    private var dnsProxyLayoutManager: RecyclerView.LayoutManager? = null
    private val dnsProxyViewModel: DNSProxyEndpointViewModel by viewModel()

    private lateinit var spinnerAdapter: CustomSpinnerAdapter

    private val appInfoRepository by inject<AppInfoRepository>()
    private val dohEndpointRepository by inject<DoHEndpointRepository>()
    private val dnsProxyEndpointRepository by inject<DNSProxyEndpointRepository>()
    private val dnsCryptEndpointRepository by inject<DNSCryptEndpointRepository>()
    private val dnsCryptRelayEndpointRepository by inject<DNSCryptRelayEndpointRepository>()
    private val doHEndpointRepository by inject<DoHEndpointRepository>()
    private val persistentState by inject<PersistentState>()
    private val appMode by inject<AppMode>()

    companion object {
        fun newInstance() = ConfigureDNSFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initClickListeners()
    }

    private fun initView() {
        val arraySpinner = requireContext().resources.getStringArray(
            R.array.dns_endpoint_modes).toList()

        spinnerAdapter = CustomSpinnerAdapter(requireContext(), arraySpinner)
        b.configureScreenSpinner.adapter = spinnerAdapter

        b.configureDnsProgressBar.visibility = View.VISIBLE

        //DOH init views
        layoutManager = LinearLayoutManager(requireContext())
        b.recyclerDohConnections.layoutManager = layoutManager

        //DNS Crypt init views
        dnsCryptLayoutManager = LinearLayoutManager(requireContext())
        b.recyclerDnsCryptConnections.layoutManager = dnsCryptLayoutManager

        //DNS Crypt Relay init views
        dnsCryptRelayLayoutManager = LinearLayoutManager(requireContext())
        b.recyclerDnsCryptRelays.layoutManager = dnsCryptRelayLayoutManager

        //Proxy init views
        dnsProxyLayoutManager = LinearLayoutManager(requireContext())
        b.recyclerDnsProxyConnections.layoutManager = dnsProxyLayoutManager

        dnsCryptRecyclerAdapter = DNSCryptEndpointAdapter(requireContext(),
                                                          dnsCryptEndpointRepository, appMode,
                                                          get(), this)
        dnsCryptViewModel.dnsCryptEndpointList.observe(viewLifecycleOwner,
                                                       androidx.lifecycle.Observer(
                                                           dnsCryptRecyclerAdapter::submitList))
        b.recyclerDnsCryptConnections.adapter = dnsCryptRecyclerAdapter

        dnsCryptRelayRecyclerAdapter = DNSCryptRelayEndpointAdapter(requireContext(),
                                                                    dnsCryptRelayEndpointRepository,
                                                                    persistentState,
                                                                    dnsCryptEndpointRepository)
        dnsCryptRelayViewModel.dnsCryptRelayEndpointList.observe(viewLifecycleOwner,
                                                                 androidx.lifecycle.Observer(
                                                                     dnsCryptRelayRecyclerAdapter::submitList))
        b.recyclerDnsCryptRelays.adapter = dnsCryptRelayRecyclerAdapter

        dohRecyclerAdapter = DoHEndpointAdapter(requireContext(), dohEndpointRepository,
                                                persistentState, appMode, get(), this)
        viewModel.dohEndpointList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            dohRecyclerAdapter::submitList))
        b.recyclerDohConnections.adapter = dohRecyclerAdapter

        dnsProxyRecyclerAdapter = DNSProxyEndpointAdapter(requireContext(),
                                                          dnsProxyEndpointRepository, appMode,
                                                          get(), this)
        dnsProxyViewModel.dnsProxyEndpointList.observe(viewLifecycleOwner,
                                                       androidx.lifecycle.Observer(
                                                           dnsProxyRecyclerAdapter::submitList))
        b.recyclerDnsProxyConnections.adapter = dnsProxyRecyclerAdapter

        b.configureDnsProgressBar.visibility = View.GONE
        val dnsValue = appMode.getDNSType()
        // To select the spinner position
        b.configureScreenSpinner.setSelection(dnsValue - 1)
        showRecycler(dnsValue)

    }

    private fun getAppName(): MutableList<String> {
        return appInfoRepository.getAppNameList()
    }

    private fun initClickListeners() {

        b.dohFabAddServerIcon.setOnClickListener {
            when (b.configureScreenSpinner.selectedItemPosition) {
                0 -> {
                    showDialogForDOHCustomURL()
                }
                1 -> {
                    showDialogForDNSCrypt()
                }
                2 -> {
                    showDialogForDNSProxy()
                }
            }
        }

        b.configureScreenSpinner.onItemSelectedListener = object :
                AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {

            }

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int,
                                        id: Long) {
                // Increment the position by 1 to match with appropriate DNS type.
                showRecycler(position + 1)
            }

        }

    }

    private fun showRecycler(position: Int) {
        when (position) {
            PREF_DNS_MODE_DOH -> {
                b.recyclerDohConnectionsHeader.visibility = View.VISIBLE
                b.recyclerDnsCryptConnectionsHeader.visibility = View.GONE
                b.recyclerDnsProxyConnectionsHeader.visibility = View.GONE
            }
            PREF_DNS_MODE_DNSCRYPT -> {
                b.recyclerDohConnectionsHeader.visibility = View.GONE
                b.recyclerDnsCryptConnectionsHeader.visibility = View.VISIBLE
                b.recyclerDnsProxyConnectionsHeader.visibility = View.GONE
            }
            PREF_DNS_MODE_PROXY -> {
                b.recyclerDohConnectionsHeader.visibility = View.GONE
                b.recyclerDnsCryptConnectionsHeader.visibility = View.GONE
                b.recyclerDnsProxyConnectionsHeader.visibility = View.VISIBLE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        notifyDataSet()
    }

    private fun notifyDataSet() {
        dohRecyclerAdapter.notifyDataSetChanged()
        dnsCryptRecyclerAdapter.notifyDataSetChanged()
        dnsCryptRelayRecyclerAdapter.notifyDataSetChanged()
        dnsProxyRecyclerAdapter.notifyDataSetChanged()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            val stamp = data?.getStringArrayExtra("stamp")
            if (DEBUG) Log.d(LOG_TAG_UI, "onActivityResult - Stamp : $stamp")
        }
    }


    /**
     * Shows dialog for custom DNS endpoint configuration
     * If entered DNS end point is valid, then the DNS queries are forwarded to that end point
     * else, it will revert back to default end point
     */
    private fun showDialogForDOHCustomURL() {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle(getString(R.string.cd_custom_doh_dialog_title))
        val dialogBinding = DialogSetCustomUrlBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window!!.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window!!.attributes = lp

        val applyURLBtn = dialogBinding.dialogCustomUrlOkBtn
        val cancelURLBtn = dialogBinding.dialogCustomUrlCancelBtn
        val customName = dialogBinding.dialogCustomNameEditText
        val customURL = dialogBinding.dialogCustomUrlEditText
        val progressBar = dialogBinding.dialogCustomUrlLoading
        val errorTxt = dialogBinding.dialogCustomUrlFailureText

        // Fetch the count from repository and increment by 1 to show the
        // next doh name in the dialog
        val count = dohEndpointRepository.getCount().plus(1)

        customName.setText(getString(R.string.cd_custom_doh_url_name, count.toString()),
                           TextView.BufferType.EDITABLE)
        applyURLBtn.setOnClickListener {
            val url = customURL.text.toString()
            val name = customName.text.toString()

            if (checkUrl(url)) {
                insertDoHEndpoint(name, url)
                dialog.dismiss()
            } else {
                errorTxt.text = resources.getString(R.string.custom_url_error_invalid_url)
                errorTxt.visibility = View.VISIBLE
                cancelURLBtn.visibility = View.VISIBLE
                applyURLBtn.visibility = View.VISIBLE
                progressBar.visibility = View.INVISIBLE
            }
        }

        cancelURLBtn.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }


    private fun showDialogForDNSProxy() {
        val dialogBinding = DialogSetDnsProxyBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle(getString(R.string.cd_custom_dns_proxy_title))
        dialog.setContentView(dialogBinding.root)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window!!.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        dialog.window!!.attributes = lp

        val applyURLBtn = dialogBinding.dialogDnsProxyApplyBtn
        val cancelURLBtn = dialogBinding.dialogDnsProxyCancelBtn
        val proxyNameEditText = dialogBinding.dialogDnsProxyEditName
        val ipAddressEditText = dialogBinding.dialogDnsProxyEditIp
        val portEditText = dialogBinding.dialogDnsProxyEditPort
        val appNameSpinner = dialogBinding.dialogDnsProxySpinnerAppname
        val errorTxt = dialogBinding.dialogDnsProxyErrorText
        val llSpinnerHeader = dialogBinding.dialogDnsProxySpinnerHeader
        val llIPHeader = dialogBinding.dialogDnsProxyIpHeader

        // Fetch the count from repository and increment by 1 to show the
        // next doh name in the dialog
        val count = dnsProxyEndpointRepository.getCount().plus(1)

        proxyNameEditText.setText(getString(R.string.cd_custom_dns_proxy_name, count.toString()),
                                  TextView.BufferType.EDITABLE)
        ipAddressEditText.setText(getString(R.string.cd_custom_dns_proxy_default_ip),
                                  TextView.BufferType.EDITABLE)
        val appNames: MutableList<String> = ArrayList()
        appNames.add(getString(R.string.cd_custom_dns_proxy_default_app))
        appNames.addAll(getAppName())
        val proxySpinnerAdapter = ArrayAdapter(requireContext(),
                                               android.R.layout.simple_spinner_dropdown_item,
                                               appNames)
        appNameSpinner.adapter = proxySpinnerAdapter
        llSpinnerHeader.visibility = View.VISIBLE
        llIPHeader.visibility = View.VISIBLE

        applyURLBtn.setOnClickListener {
            var port = 0
            var isPortValid: Boolean
            val isIPValid: Boolean
            val name = proxyNameEditText.text.toString()
            val mode = getString(R.string.cd_dns_proxy_mode_external)
            val ip = ipAddressEditText.text.toString()


            var appName = appNames[appNameSpinner.selectedItemPosition]
            if (appName.isEmpty() || appName == getString(
                    R.string.cd_custom_dns_proxy_default_app)) {
                appName = appNames[0]
            } else {
                appInfoRepository.getPackageNameForAppName(appName)
            }

            if (Patterns.IP_ADDRESS.matcher(ip).matches()) {
                isIPValid = true
            } else {
                errorTxt.text = getString(R.string.cd_dns_proxy_error_text_1)
                isIPValid = false
            }

            try {
                port = portEditText.text.toString().toInt()
                isPortValid = if (Utilities.isLanIpv4(ip)) {
                    isValidLocalPort(port)
                } else {
                    true
                }
                if (!isPortValid) {
                    errorTxt.text = getString(R.string.cd_dns_proxy_error_text_2)
                }
            } catch (e: NumberFormatException) {
                Log.w(LOG_TAG_UI, "Error: ${e.message}", e)
                errorTxt.text = getString(R.string.cd_dns_proxy_error_text_3)
                isPortValid = false
            }

            if (isPortValid && isIPValid) {
                //Do the DNS Proxy setting there
                if (DEBUG) Log.d(LOG_TAG_UI, "new value inserted into DNSProxy")
                insertDNSProxyEndpointDB(mode, name, appName, ip, port)
                b.recyclerDnsProxyConnections.visibility = View.VISIBLE
                dialog.dismiss()
            } else {
                Log.i(LOG_TAG_UI, "cannot insert invalid dns-proxy IPs: $name, $appName")
            }

        }

        cancelURLBtn.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }


    private fun showDialogForDNSCrypt() {
        val dialogBinding = DialogSetDnsCryptBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle(getString(R.string.cd_dns_crypt_dialog_title))
        dialog.setContentView(dialogBinding.root)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.attributes = lp

        val radioServer = dialogBinding.dialogDnsCryptRadioServer
        val radioRelay = dialogBinding.dialogDnsCryptRadioRelay
        val applyURLBtn = dialogBinding.dialogDnsCryptOkBtn
        val cancelURLBtn = dialogBinding.dialogDnsCryptCancelBtn
        val cryptNameEditText = dialogBinding.dialogDnsCryptName
        val cryptURLEditText = dialogBinding.dialogDnsCryptUrl
        val cryptDescEditText = dialogBinding.dialogDnsCryptDesc
        val errorText = dialogBinding.dialogDnsCryptErrorTxt

        radioServer.isChecked = true

        // Fetch the count from repository and increment by 1 to show the
        // next doh name in the dialog
        var count = dnsCryptEndpointRepository.getCount().plus(1)
        cryptNameEditText.setText(getString(R.string.cd_dns_crypt_name, count.toString()),
                                  TextView.BufferType.EDITABLE)

        radioServer.setOnClickListener {
            count = dnsCryptEndpointRepository.getCount().plus(1)
            cryptNameEditText.setText(getString(R.string.cd_dns_crypt_name, count.toString()),
                                      TextView.BufferType.EDITABLE)
        }

        radioRelay.setOnClickListener {
            count = dnsCryptRelayEndpointRepository.getCount().plus(1)
            cryptNameEditText.setText(getString(R.string.cd_dns_crypt_relay_name, count.toString()),
                                      TextView.BufferType.EDITABLE)
        }

        applyURLBtn.setOnClickListener {
            var isValid = true
            val name: String = cryptNameEditText.text.toString()
            val urlStamp = cryptURLEditText.text.toString()
            val desc = cryptDescEditText.text.toString()

            val mode = if (radioServer.isChecked) {
                0
            } else {
                1
            }
            if (urlStamp.isEmpty()) {
                isValid = false
                errorText.text = getString(R.string.cd_dns_crypt_error_text_1)
            }

            if (isValid) {
                //Do the DNS Crypt setting there
                if (mode == 0) {
                    insertDNSCryptServer(name, urlStamp, desc)
                } else if (mode == 1) {
                    insertDNSCryptRelay(name, urlStamp, desc)
                }
                dialog.dismiss()
            }
        }

        cancelURLBtn.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun insertDNSCryptRelay(name: String, urlStamp: String, desc: String) {
        var serverName = name
        if (serverName.isEmpty() || serverName.isBlank()) {
            serverName = urlStamp
        }
        val dnsCryptRelayEndpoint = DNSCryptRelayEndpoint(id = -1, serverName, urlStamp, desc,
                                                          isSelected = false, isCustom = true,
                                                          modifiedDataTime = 0L, latency = 0)
        dnsCryptRelayEndpointRepository.insertAsync(dnsCryptRelayEndpoint)
        notifyDataSet()
    }

    private fun insertDNSCryptServer(name: String, urlStamp: String, desc: String) {
        var serverName = name
        if (serverName.isEmpty() || serverName.isBlank()) {
            serverName = urlStamp
        }

        val dnsCryptEndpoint = DNSCryptEndpoint(id = 0, serverName, urlStamp, desc,
                                                isSelected = false, isCustom = true,
                                                modifiedDataTime = 0L, latency = 0)
        dnsCryptEndpointRepository.insertAsync(dnsCryptEndpoint)
        notifyDataSet()
    }

    private fun insertDoHEndpoint(name: String, url: String) {
        var dohName: String = name
        if (name.isEmpty() || name.isBlank()) {
            dohName = url
        }
        val doHEndpoint = DoHEndpoint(id = 0, dohName, url, dohExplanation = "", isSelected = false,
                                      isCustom = true, modifiedDataTime = 0, latency = 0)
        doHEndpointRepository.insertAsync(doHEndpoint)
        notifyDataSet()
    }

    private fun insertDNSProxyEndpointDB(mode: String, name: String, appName: String, ip: String,
                                         port: Int) {
        var proxyName = name
        if (proxyName.isEmpty() || proxyName.isBlank()) {
            proxyName = if (mode == getString(R.string.cd_dns_proxy_mode_internal)) {
                appName
            } else ip
        }
        val dnsProxyEndpoint = DNSProxyEndpoint(id = 0, proxyName, mode, appName, ip, port,
                                                isSelected = false, isCustom = true,
                                                modifiedDataTime = 0L, latency = 0)
        dnsProxyEndpointRepository.insertAsync(dnsProxyEndpoint)
        if (DEBUG) Log.d(LOG_TAG_UI, "Insert into DNSProxy database- $appName, $port")
        notifyDataSet()
    }

    // Check that the URL is a plausible DOH server: https with a domain, a path (at least "/"),
    // and no query parameters or fragment.
    private fun checkUrl(url: String): Boolean {
        return try {
            val parsed = URL(url)
            parsed.protocol == "https" && parsed.host.isNotEmpty() && parsed.path.isNotEmpty() && parsed.query == null && parsed.ref == null
        } catch (e: MalformedURLException) {
            false
        }
    }

    override fun updateUIFromAdapter(dnsType: Int) {
        if (DEBUG) Log.d(LOG_TAG_UI, "ui update from adapter with dns-type $dnsType")
        when (dnsType) {
            PREF_DNS_MODE_DOH -> {
                dnsCryptEndpointRepository.removeConnectionStatus()
                dnsCryptRelayEndpointRepository.removeConnectionStatus()
                dnsProxyEndpointRepository.removeConnectionStatus()
                notifyDataSet()
            }
            PREF_DNS_MODE_DNSCRYPT -> {
                doHEndpointRepository.removeConnectionStatus()
                dnsProxyEndpointRepository.removeConnectionStatus()
                notifyDataSet()
            }
            PREF_DNS_MODE_PROXY -> {
                doHEndpointRepository.removeConnectionStatus()
                dnsCryptEndpointRepository.removeConnectionStatus()
                dnsCryptRelayEndpointRepository.removeConnectionStatus()
                notifyDataSet()
            }
        }
        spinnerAdapter.notifyDataSetChanged()
    }

}
