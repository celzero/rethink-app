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
package com.celzero.bravedns.ui

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DnsCryptEndpoint
import com.celzero.bravedns.database.DnsCryptRelayEndpoint
import com.celzero.bravedns.database.DnsProxyEndpoint
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.databinding.ActivityOtherDnsListBinding
import com.celzero.bravedns.databinding.DialogSetCustomDohBinding
import com.celzero.bravedns.databinding.DialogSetDnsCryptBinding
import com.celzero.bravedns.databinding.DialogSetDnsProxyBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.net.MalformedURLException
import java.net.URL

class DnsListActivity : AppCompatActivity(R.layout.activity_other_dns_list) {
    private val b by viewBinding(ActivityOtherDnsListBinding::bind)

    private val dnsTabsCount = 3
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        init()
        setupClickListeners()
    }

    private fun init() {
        b.otherDnsActViewpager.adapter = object : FragmentStateAdapter(this) {
            override fun createFragment(position: Int): Fragment {
                return when (position) {
                    0 -> DohListFragment.newInstance()
                    1 -> DnsCryptListFragment.newInstance()
                    else -> DnsProxyListFragment.newInstance()
                }
            }

            override fun getItemCount(): Int {
                return dnsTabsCount
            }
        }

        TabLayoutMediator(b.otherDnsActTabLayout,
                          b.otherDnsActViewpager) { tab, position -> // Styling each tab here
            tab.text = when (position) {
                0 -> getString(R.string.other_dns_list_tab1)
                1 -> getString(R.string.other_dns_list_tab2)
                else -> getString(R.string.other_dns_list_tab3)
            }
        }.attach()
    }

    private fun setupClickListeners() {
        b.otherDnsAdd.setOnClickListener {
            when (b.otherDnsActViewpager.currentItem) {
                0 -> {
                    showAddCustomDohDialog()
                }
                1 -> {
                    showAddDnsCryptDialog()
                }
                2 -> {
                    showAddDnsProxyDialog()
                }
            }
        }
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Shows dialog for custom DNS endpoint configuration
     * If entered DNS end point is valid, then the DNS queries are forwarded to that end point
     * else, it will revert back to default end point
     */
    private fun showAddCustomDohDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle(getString(R.string.cd_custom_doh_dialog_title))
        val dialogBinding = DialogSetCustomDohBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.attributes = lp

        val applyURLBtn = dialogBinding.dialogCustomUrlOkBtn
        val cancelURLBtn = dialogBinding.dialogCustomUrlCancelBtn
        val customName = dialogBinding.dialogCustomNameEditText
        val customURL = dialogBinding.dialogCustomUrlEditText
        val progressBar = dialogBinding.dialogCustomUrlLoading
        val errorTxt = dialogBinding.dialogCustomUrlFailureText

        // Fetch the count from repository and increment by 1 to show the
        // next doh name in the dialog
        io {
            val nextIndex = appConfig.getDohCount().plus(1)
            uiCtx {
                customName.setText(getString(R.string.cd_custom_doh_url_name, nextIndex.toString()),
                                   TextView.BufferType.EDITABLE)
            }
        }

        customName.setText(getString(R.string.cd_custom_doh_url_name_default),
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

    private fun showAddDnsProxyDialog() {
        val dialogBinding = DialogSetDnsProxyBinding.inflate(layoutInflater)
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle(getString(R.string.cd_custom_dns_proxy_title))
        dialog.setContentView(dialogBinding.root)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)

        // TODO: figure out why window maybe null
        dialog.window?.attributes = lp

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
        io {
            val nextIndex = appConfig.getDnsProxyCount().plus(1)
            uiCtx {
                proxyNameEditText.setText(
                    getString(R.string.cd_custom_dns_proxy_name, nextIndex.toString()),
                    TextView.BufferType.EDITABLE)
            }
        }

        proxyNameEditText.setText(getString(R.string.cd_custom_dns_proxy_name_default),
                                  TextView.BufferType.EDITABLE)
        ipAddressEditText.setText(getString(R.string.cd_custom_dns_proxy_default_ip),
                                  TextView.BufferType.EDITABLE)
        val appNames: MutableList<String> = ArrayList()
        appNames.add(getString(R.string.cd_custom_dns_proxy_default_app))
        appNames.addAll(FirewallManager.getAllAppNames())
        val proxySpinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
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


            val appName = appNames[appNameSpinner.selectedItemPosition]
            val appPackageName = if (appName.isBlank() || appName == getString(
                    R.string.cd_custom_dns_proxy_default_app)) {
                appNames[0]
            } else {
                FirewallManager.getPackageNameByAppName(appName)
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

            if (isPortValid && isIPValid) {
                //Do the DNS Proxy setting there
                if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(LoggerConstants.LOG_TAG_UI,
                                                                   "new value inserted into DNSProxy")
                insertDNSProxyEndpointDB(mode, name, appPackageName, ip, port)
                dialog.dismiss()
            } else {
                Log.i(LoggerConstants.LOG_TAG_UI,
                      "cannot insert invalid dns-proxy IPs: $name, $appName")
            }

        }

        cancelURLBtn.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showAddDnsCryptDialog() {
        val dialogBinding = DialogSetDnsCryptBinding.inflate(layoutInflater)
        val dialog = Dialog(this)
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
        var dnscryptNextIndex = 0
        var relayNextIndex = 0

        // Fetch the count from repository and increment by 1 to show the
        // next doh name in the dialog
        io {
            dnscryptNextIndex = appConfig.getDnscryptCount().plus(1)
            relayNextIndex = appConfig.getDnscryptRelayCount().plus(1)
            uiCtx {
                cryptNameEditText.setText(
                    getString(R.string.cd_custom_dns_proxy_name, dnscryptNextIndex.toString()),
                    TextView.BufferType.EDITABLE)
            }
        }

        cryptNameEditText.setText(getString(R.string.cd_dns_crypt_name_default),
                                  TextView.BufferType.EDITABLE)

        radioServer.setOnClickListener {
            cryptNameEditText.setText(
                getString(R.string.cd_dns_crypt_name, dnscryptNextIndex.toString()),
                TextView.BufferType.EDITABLE)
        }

        radioRelay.setOnClickListener {
            cryptNameEditText.setText(
                getString(R.string.cd_dns_crypt_relay_name, relayNextIndex.toString()),
                TextView.BufferType.EDITABLE)
        }

        applyURLBtn.setOnClickListener {
            var isValid = true
            val name: String = cryptNameEditText.text.toString()
            val urlStamp = cryptURLEditText.text.toString()
            val desc = cryptDescEditText.text.toString()

            val mode = if (radioServer.isChecked) {
                0 // Selected radio button - DNS Crypt
            } else {
                1 // Selected radio button - DNS Crypt Relay
            }
            if (urlStamp.isBlank()) {
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
        io {
            var serverName = name
            if (serverName.isBlank()) {
                serverName = urlStamp
            }
            val dnsCryptRelayEndpoint = DnsCryptRelayEndpoint(id = 0, serverName, urlStamp, desc,
                                                              isSelected = false, isCustom = true,
                                                              modifiedDataTime = 0L, latency = 0)
            appConfig.insertDnscryptRelayEndpoint(dnsCryptRelayEndpoint)
        }
    }

    private fun insertDNSCryptServer(name: String, urlStamp: String, desc: String) {
        io {
            var serverName = name
            if (serverName.isBlank()) {
                serverName = urlStamp
            }

            val dnsCryptEndpoint = DnsCryptEndpoint(id = 0, serverName, urlStamp, desc,
                                                    isSelected = false, isCustom = true,
                                                    modifiedDataTime = 0L, latency = 0)
            appConfig.insertDnscryptEndpoint(dnsCryptEndpoint)
        }
    }

    private fun insertDoHEndpoint(name: String, url: String) {
        io {
            var dohName: String = name
            if (name.isBlank()) {
                dohName = url
            }
            val doHEndpoint = DoHEndpoint(id = 0, dohName, url, dohExplanation = "",
                                          isSelected = false, isCustom = true, modifiedDataTime = 0,
                                          latency = 0)
            appConfig.insertDohEndpoint(doHEndpoint)
        }
    }

    private fun insertDNSProxyEndpointDB(mode: String, name: String, appName: String?, ip: String,
                                         port: Int) {
        if (appName == null) return

        io {
            var proxyName = name
            if (proxyName.isBlank()) {
                proxyName = if (mode == getString(R.string.cd_dns_proxy_mode_internal)) {
                    appName
                } else ip
            }
            val dnsProxyEndpoint = DnsProxyEndpoint(id = 0, proxyName, mode, appName, ip, port,
                                                    isSelected = false, isCustom = true,
                                                    modifiedDataTime = 0L, latency = 0)
            appConfig.insertDnsproxyEndpoint(dnsProxyEndpoint)
            if (HomeScreenActivity.GlobalVariable.DEBUG) Log.d(LoggerConstants.LOG_TAG_UI,
                                                               "Insert into DNSProxy database- $appName, $port")
        }
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

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                f()
            }
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) {
            f()
        }
    }

}
