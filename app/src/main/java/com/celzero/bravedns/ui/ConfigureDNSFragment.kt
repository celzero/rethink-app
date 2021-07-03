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
import android.os.CountDownTimer
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
import com.celzero.bravedns.database.*
import com.celzero.bravedns.databinding.DialogSetCustomUrlBinding
import com.celzero.bravedns.databinding.DialogSetDnsCryptBinding
import com.celzero.bravedns.databinding.DialogSetDnsProxyBinding
import com.celzero.bravedns.databinding.FragmentConfigureDnsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
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
    private var dohRecyclerAdapter: DoHEndpointAdapter? = null
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initClickListeners()
    }

    companion object {
        fun newInstance() = ConfigureDNSFragment()
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
                                                          dnsCryptEndpointRepository,
                                                          persistentState, get(), this)
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
                                                persistentState, get(), this)
        viewModel.dohEndpointList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            dohRecyclerAdapter!!::submitList))
        b.recyclerDohConnections.adapter = dohRecyclerAdapter

        dnsProxyRecyclerAdapter = DNSProxyEndpointAdapter(requireContext(),
                                                          dnsProxyEndpointRepository,
                                                          persistentState, get(), this)
        dnsProxyViewModel.dnsProxyEndpointList.observe(viewLifecycleOwner,
                                                       androidx.lifecycle.Observer(
                                                           dnsProxyRecyclerAdapter::submitList))
        b.recyclerDnsProxyConnections.adapter = dnsProxyRecyclerAdapter

        b.configureDnsProgressBar.visibility = View.GONE
        val dnsValue = appMode?.getDNSType()
        if (dnsValue == 1) {
            b.configureScreenSpinner.setSelection(0)
            b.recyclerDohConnectionsHeader.visibility = View.VISIBLE
            b.recyclerDnsCryptConnectionsHeader.visibility = View.GONE
            b.recyclerDnsProxyConnectionsHeader.visibility = View.GONE
        } else if (dnsValue == 2) {
            b.configureScreenSpinner.setSelection(1)
            b.recyclerDohConnectionsHeader.visibility = View.GONE
            b.recyclerDnsCryptConnectionsHeader.visibility = View.VISIBLE
            b.recyclerDnsProxyConnectionsHeader.visibility = View.GONE
        } else {
            b.configureScreenSpinner.setSelection(2)
            b.recyclerDohConnectionsHeader.visibility = View.GONE
            b.recyclerDnsCryptConnectionsHeader.visibility = View.GONE
            b.recyclerDnsProxyConnectionsHeader.visibility = View.VISIBLE
        }

    }

    private fun getAppName(): MutableList<String> {
        return appInfoRepository.getAppNameList()
    }

    private fun initClickListeners() {

        b.dohFabAddServerIcon.setOnClickListener {
            when {
                b.configureScreenSpinner.selectedItemPosition == 0 -> {
                    showDialogForDOHCustomURL()
                }
                b.configureScreenSpinner.selectedItemPosition == 1 -> {
                    showDialogForDNSCrypt()
                }
                b.configureScreenSpinner.selectedItemPosition == 2 -> {
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
                when (position) {
                    0 -> {
                        b.recyclerDohConnectionsHeader.visibility = View.VISIBLE
                        b.recyclerDnsCryptConnectionsHeader.visibility = View.GONE
                        b.recyclerDnsProxyConnectionsHeader.visibility = View.GONE
                    }
                    1 -> {
                        b.recyclerDohConnectionsHeader.visibility = View.GONE
                        b.recyclerDnsCryptConnectionsHeader.visibility = View.VISIBLE
                        b.recyclerDnsProxyConnectionsHeader.visibility = View.GONE
                    }
                    2 -> {
                        b.recyclerDohConnectionsHeader.visibility = View.GONE
                        b.recyclerDnsCryptConnectionsHeader.visibility = View.GONE
                        b.recyclerDnsProxyConnectionsHeader.visibility = View.VISIBLE
                    }
                }
            }

        }

    }

    override fun onResume() {
        super.onResume()

        object : CountDownTimer(500, 500) {
            override fun onTick(millisUntilFinished: Long) {
            }

            override fun onFinish() {
                dohRecyclerAdapter?.notifyDataSetChanged()
                dnsCryptRecyclerAdapter.notifyDataSetChanged()
                dnsCryptRelayRecyclerAdapter.notifyDataSetChanged()
            }
        }.start()
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

        var count = dohEndpointRepository.getCount()
        count += 1

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

        var count = dnsProxyEndpointRepository.getCount()
        count += 1
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
            var isValid: Boolean
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
                isValid = if (Utilities.isLanIpv4(ip)) {
                    isValidLocalPort(port)
                } else {
                    true
                }
                if (!isValid) {
                    errorTxt.text = getString(R.string.cd_dns_proxy_error_text_2)
                }
            } catch (e: NumberFormatException) {
                Log.w(LOG_TAG_UI, "Error: ${e.message}", e)
                errorTxt.text = getString(R.string.cd_dns_proxy_error_text_3)
                isValid = false
            }

            if (isValid && isIPValid) {
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

    private fun insertDNSProxyEndpointDB(mode: String, name: String, appName: String, ip: String,
                                         port: Int) {
        var proxyName = name
        if (proxyName.isEmpty() || proxyName.isBlank()) {
            proxyName = if (mode == getString(R.string.cd_dns_proxy_mode_internal)) {
                appName
            } else ip
        }
        val dnsProxyEndpoint = DNSProxyEndpoint(id = -1, proxyName, mode, appName, ip, port,
                                                isSelected = false, isCustom = true,
                                                modifiedDataTime = 0L, latency = 0)
        dnsProxyEndpointRepository.insertAsync(dnsProxyEndpoint)
        if (DEBUG) Log.d(LOG_TAG_UI, "Insert into DNSProxy database- $appName, $port")
        object : CountDownTimer(500, 500) {
            override fun onTick(millisUntilFinished: Long) {
            }

            override fun onFinish() {
                dnsProxyRecyclerAdapter.notifyDataSetChanged()
            }
        }.start()
    }


    private fun showDialogForDNSCrypt() {
        val dialogBinding = DialogSetDnsCryptBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle(getString(R.string.cd_dns_crypt_dialog_title))
        dialog.setContentView(dialogBinding.root)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window!!.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window!!.attributes = lp

        val radioServer = dialogBinding.dialogDnsCryptRadioServer
        val radioRelay = dialogBinding.dialogDnsCryptRadioRelay
        val applyURLBtn = dialogBinding.dialogDnsCryptOkBtn
        val cancelURLBtn = dialogBinding.dialogDnsCryptCancelBtn
        val cryptNameEditText = dialogBinding.dialogDnsCryptName
        val cryptURLEditText = dialogBinding.dialogDnsCryptUrl
        val cryptDescEditText = dialogBinding.dialogDnsCryptDesc
        val errorText = dialogBinding.dialogDnsCryptErrorTxt

        radioServer.isChecked = true
        var count = dnsCryptEndpointRepository.getCount()
        count += 1
        cryptNameEditText.setText(getString(R.string.cd_dns_crypt_name, count.toString()),
                                  TextView.BufferType.EDITABLE)

        radioServer.setOnClickListener {
            count = dnsCryptEndpointRepository.getCount()
            count += 1
            cryptNameEditText.setText(getString(R.string.cd_dns_crypt_name, count.toString()),
                                      TextView.BufferType.EDITABLE)
        }

        radioRelay.setOnClickListener {
            count = dnsCryptRelayEndpointRepository.getCount()
            count += 1
            cryptNameEditText.setText(getString(R.string.cd_dns_crypt_relay_name, count.toString()),
                                      TextView.BufferType.EDITABLE)
        }

        applyURLBtn.setOnClickListener {
            var isValid = true
            var mode: Int = -1
            val name: String = cryptNameEditText.text.toString()
            val urlStamp = cryptURLEditText.text.toString()
            val desc = cryptDescEditText.text.toString()

            if (radioServer.isChecked) {
                mode = 0
            } else if (radioRelay.isChecked) {
                mode = 1
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
        object : CountDownTimer(500, 500) {
            override fun onTick(millisUntilFinished: Long) {
            }

            override fun onFinish() {
                dnsProxyRecyclerAdapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun insertDNSCryptServer(name: String, urlStamp: String, desc: String) {
        var serverName = name
        if (serverName.isEmpty() || serverName.isBlank()) {
            serverName = urlStamp
        }

        val dnsCryptEndpoint = DNSCryptEndpoint(id = -1, serverName, urlStamp, desc,
                                                isSelected = false, isCustom = true,
                                                modifiedDataTime = 0L, latency = 0)
        dnsCryptEndpointRepository.insertAsync(dnsCryptEndpoint)
        object : CountDownTimer(500, 500) {
            override fun onTick(millisUntilFinished: Long) {
            }

            override fun onFinish() {
                dnsProxyRecyclerAdapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun insertDoHEndpoint(name: String, url: String) {
        var dohName: String = name
        if (name.isEmpty() || name.isBlank()) {
            dohName = url
        }
        val doHEndpoint = DoHEndpoint(id = -1, dohName, url, dohExplanation = "",
                                      isSelected = false, isCustom = true, modifiedDataTime = 0,
                                      latency = 0)
        doHEndpointRepository.insertAsync(doHEndpoint)
        object : CountDownTimer(500, 500) {
            override fun onTick(millisUntilFinished: Long) {
            }

            override fun onFinish() {
                dohRecyclerAdapter?.notifyDataSetChanged()
            }
        }.start()
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
                dohRecyclerAdapter?.notifyDataSetChanged()
                dnsProxyRecyclerAdapter.notifyDataSetChanged()
                dnsCryptRecyclerAdapter.notifyDataSetChanged()
                dnsCryptRelayRecyclerAdapter.notifyDataSetChanged()
            }
            PREF_DNS_MODE_DNSCRYPT -> {
                doHEndpointRepository.removeConnectionStatus()
                dnsProxyEndpointRepository.removeConnectionStatus()
                dnsProxyRecyclerAdapter.notifyDataSetChanged()
                dohRecyclerAdapter?.notifyDataSetChanged()
            }
            PREF_DNS_MODE_PROXY -> {
                b.recyclerDnsProxyConnections.visibility = View.VISIBLE
                doHEndpointRepository.removeConnectionStatus()
                dnsCryptEndpointRepository.removeConnectionStatus()
                dnsCryptRelayEndpointRepository.removeConnectionStatus()
                dohRecyclerAdapter?.notifyDataSetChanged()
                dnsCryptRecyclerAdapter.notifyDataSetChanged()
                dnsCryptRelayRecyclerAdapter.notifyDataSetChanged()
            }
        }
        spinnerAdapter.notifyDataSetChanged()
    }

}
