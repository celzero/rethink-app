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
package com.celzero.bravedns.ui.activity

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.ActivityTunnelSettingsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koin.android.ext.android.inject
import java.util.concurrent.TimeUnit

class TunnelSettingsActivity : AppCompatActivity(R.layout.activity_tunnel_settings) {
    private val b by viewBinding(ActivityTunnelSettingsBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        initView()
        setupClickListeners()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onResume() {
        super.onResume()
        handleLockdownModeIfNeeded()
    }

    private fun initView() {
        b.settingsActivityAllowBypassProgress.visibility = View.GONE
        // allow apps part of the vpn to request networks outside of it, effectively letting it
        // bypass the vpn itself
        b.settingsActivityAllowBypassSwitch.isChecked = persistentState.allowBypass
        // use multiple networks
        b.settingsActivityAllNetworkSwitch.isChecked = persistentState.useMultipleNetworks
        // route lan traffic
        b.settingsActivityLanTrafficSwitch.isChecked = persistentState.privateIps
        // connectivity check
        b.settingsActivityConnectivityChecksSwitch.isChecked = persistentState.connectivityChecks
        // exclude apps in proxy
        b.settingsActivityExcludeProxyAppsSwitch.isChecked = !persistentState.excludeAppsInProxy
        // for protocol translation, enable only on DNS/DNS+Firewall mode
        if (appConfig.getBraveMode().isDnsActive()) {
            b.settingsActivityPtransSwitch.isChecked = persistentState.protocolTranslationType
        } else {
            persistentState.protocolTranslationType = false
            b.settingsActivityPtransSwitch.isChecked = false
        }

        displayInternetProtocolUi()
        displayRethinkInRethinkUi()
    }

    private fun setupClickListeners() {
        b.settingsActivityAllNetworkRl.setOnClickListener {
            b.settingsActivityAllNetworkSwitch.isChecked =
                !b.settingsActivityAllNetworkSwitch.isChecked
        }

        b.settingsActivityAllNetworkSwitch.setOnCheckedChangeListener {
            _: CompoundButton,
            b: Boolean ->
            persistentState.useMultipleNetworks = b
            if (!b && persistentState.routeRethinkInRethink) {
                persistentState.routeRethinkInRethink = false
                displayRethinkInRethinkUi()
            }
        }

        b.settingsActivityExcludeProxyAppsSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.excludeAppsInProxy = !isChecked
        }

        b.settingsActivityExcludeProxyAppsRl.setOnClickListener {
            b.settingsActivityExcludeProxyAppsSwitch.isChecked = !b.settingsActivityExcludeProxyAppsSwitch.isChecked
        }

        b.settingsRInRRl.setOnClickListener {
            b.settingsRInRSwitch.isChecked = !b.settingsRInRSwitch.isChecked
        }

        b.settingsRInRSwitch.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            // show a dialog to enable use multiple networks if the user selects yes
            // rinr will not work without multiple networks
            // reason: ConnectivityManager.activeNetwork returns VPN network when rinr is enabled
            if (isChecked && !persistentState.useMultipleNetworks) {
                val alertBuilder = MaterialAlertDialogBuilder(this)
                alertBuilder.setTitle(getString(R.string.settings_rinr_dialog_title))
                val msg =
                    getString(
                        R.string.settings_rinr_dialog_desc,
                        getString(R.string.settings_network_all_networks)
                    )
                alertBuilder.setMessage(msg)
                alertBuilder.setPositiveButton(getString(R.string.lbl_proceed)) { dialog, _ ->
                    dialog.dismiss()
                    b.settingsActivityAllNetworkSwitch.isChecked = true
                    persistentState.useMultipleNetworks = true
                    persistentState.routeRethinkInRethink = true
                    displayRethinkInRethinkUi()
                }
                alertBuilder.setNegativeButton(getString(R.string.lbl_cancel)) { dialog, _ ->
                    dialog.dismiss()
                    b.settingsRInRSwitch.isChecked = false
                }
                alertBuilder.create().show()
            } else {
                persistentState.routeRethinkInRethink = isChecked
                displayRethinkInRethinkUi()
            }
        }

        b.settingsActivityAllowBypassRl.setOnClickListener {
            b.settingsActivityAllowBypassSwitch.isChecked =
                !b.settingsActivityAllowBypassSwitch.isChecked
        }

        b.settingsActivityAllowBypassSwitch.setOnCheckedChangeListener {
            _: CompoundButton,
            checked: Boolean ->
            persistentState.allowBypass = checked
            b.settingsActivityAllowBypassSwitch.isEnabled = false
            b.settingsActivityAllowBypassSwitch.visibility = View.INVISIBLE
            b.settingsActivityAllowBypassProgress.visibility = View.VISIBLE

            Utilities.delay(TimeUnit.SECONDS.toMillis(1L), lifecycleScope) {
                b.settingsActivityAllowBypassSwitch.isEnabled = true
                b.settingsActivityAllowBypassProgress.visibility = View.GONE
                b.settingsActivityAllowBypassSwitch.visibility = View.VISIBLE
            }
        }

        b.settingsActivityLanTrafficRl.setOnClickListener {
            b.settingsActivityLanTrafficSwitch.isChecked =
                !b.settingsActivityLanTrafficSwitch.isChecked
        }

        b.settingsActivityLanTrafficSwitch.setOnCheckedChangeListener {
            _: CompoundButton,
            checked: Boolean ->
            persistentState.privateIps = checked
            b.settingsActivityLanTrafficSwitch.isEnabled = false

            Utilities.delay(TimeUnit.SECONDS.toMillis(1L), lifecycleScope) {
                b.settingsActivityLanTrafficSwitch.isEnabled = true
            }
        }

        b.settingsActivityVpnLockdownDesc.setOnClickListener { UIUtils.openVpnProfile(this) }

        b.settingsActivityIpRl.setOnClickListener {
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.settingsActivityIpRl)
            showIpDialog()
        }

        b.settingsActivityPtransRl.setOnClickListener {
            b.settingsActivityPtransSwitch.isChecked = !b.settingsActivityPtransSwitch.isChecked
        }

        b.settingsActivityPtransSwitch.setOnCheckedChangeListener { _, isSelected ->
            if (appConfig.getBraveMode().isDnsActive()) {
                persistentState.protocolTranslationType = isSelected
            } else {
                b.settingsActivityPtransSwitch.isChecked = false
                Utilities.showToastUiCentered(
                    this,
                    getString(R.string.settings_protocol_translation_dns_inactive),
                    Toast.LENGTH_SHORT
                )
            }
        }

        b.settingsActivityDefaultDnsRl.setOnClickListener { showDefaultDnsDialog() }

        b.settingsActivityConnectivityChecksRl.setOnClickListener {
            b.settingsActivityConnectivityChecksSwitch.isChecked =
                !b.settingsActivityConnectivityChecksSwitch.isChecked
        }

        b.settingsActivityConnectivityChecksSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.connectivityChecks = isChecked
        }
    }

    private fun showDefaultDnsDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this)
        alertBuilder.setTitle(getString(R.string.settings_default_dns_heading))
        val items = Constants.DEFAULT_DNS_LIST.map { it.name }.toTypedArray()
        // get the index of the default dns url
        // if the default dns url is not in the list, then select the first item
        val checkedItem =
            Constants.DEFAULT_DNS_LIST.firstOrNull { it.url == persistentState.defaultDnsUrl }
                ?.let { Constants.DEFAULT_DNS_LIST.indexOf(it) } ?: 0
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, pos ->
            dialog.dismiss()
            // update the default dns url
            persistentState.defaultDnsUrl = Constants.DEFAULT_DNS_LIST[pos].url
        }
        alertBuilder.create().show()
    }

    private fun displayInternetProtocolUi() {
        b.settingsActivityIpRl.isEnabled = true
        when (persistentState.internetProtocolType) {
            InternetProtocol.IPv4.id -> {
                b.genSettingsIpDesc.text =
                    getString(
                        R.string.settings_selected_ip_desc,
                        getString(R.string.settings_ip_text_ipv4)
                    )
                b.settingsActivityPtransRl.visibility = View.GONE
                b.settingsActivityConnectivityChecksRl.visibility = View.GONE
            }
            InternetProtocol.IPv6.id -> {
                b.genSettingsIpDesc.text =
                    getString(
                        R.string.settings_selected_ip_desc,
                        getString(R.string.settings_ip_text_ipv6)
                    )
                b.settingsActivityPtransRl.visibility = View.VISIBLE
                b.settingsActivityConnectivityChecksRl.visibility = View.GONE
            }
            InternetProtocol.IPv46.id -> {
                b.genSettingsIpDesc.text =
                    getString(
                        R.string.settings_selected_ip_desc,
                        getString(R.string.settings_ip_text_ipv46)
                    )
                b.settingsActivityPtransRl.visibility = View.GONE
                b.settingsActivityConnectivityChecksRl.visibility = View.VISIBLE
            }
            else -> {
                b.genSettingsIpDesc.text =
                    getString(
                        R.string.settings_selected_ip_desc,
                        getString(R.string.settings_ip_text_ipv4)
                    )
                b.settingsActivityPtransRl.visibility = View.GONE
                b.settingsActivityConnectivityChecksRl.visibility = View.GONE
            }
        }
    }

    private fun displayRethinkInRethinkUi() {
        b.settingsRInRSwitch.isChecked = persistentState.routeRethinkInRethink
        if (persistentState.routeRethinkInRethink) {
            b.genRInRDesc.text = getString(R.string.settings_rinr_desc_enabled)
        } else {
            b.genRInRDesc.text = getString(R.string.settings_rinr_desc_disabled)
        }
    }

    private fun showIpDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this)
        alertBuilder.setTitle(getString(R.string.settings_ip_dialog_title))
        val items =
            arrayOf(
                getString(R.string.settings_ip_dialog_ipv4),
                getString(R.string.settings_ip_dialog_ipv6),
                getString(R.string.settings_ip_dialog_ipv46)
            )
        val checkedItem = persistentState.internetProtocolType
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            // return if already selected item is same as current item
            if (persistentState.internetProtocolType == which) {
                return@setSingleChoiceItems
            }

            val protocolType = InternetProtocol.getInternetProtocol(which)
            persistentState.internetProtocolType = protocolType.id

            displayInternetProtocolUi()
        }
        alertBuilder.create().show()
    }

    private fun handleLockdownModeIfNeeded() {
        val isLockdown = VpnController.isVpnLockdown()
        if (isLockdown) {
            b.settingsActivityVpnLockdownDesc.visibility = View.VISIBLE
            b.settingsActivityAllowBypassRl.alpha = 0.5f
            b.settingsActivityLanTrafficRl.alpha = 0.5f
            b.settingsActivityExcludeProxyAppsRl.alpha = 0.5f
        } else {
            b.settingsActivityVpnLockdownDesc.visibility = View.GONE
            b.settingsActivityAllowBypassRl.alpha = 1f
            b.settingsActivityLanTrafficRl.alpha = 1f
            b.settingsActivityExcludeProxyAppsRl.alpha = 1f
        }
        b.settingsActivityAllowBypassSwitch.isEnabled = !isLockdown
        b.settingsActivityAllowBypassRl.isEnabled = !isLockdown
        b.settingsActivityLanTrafficSwitch.isEnabled = !isLockdown
        b.settingsActivityLanTrafficRl.isEnabled = !isLockdown
        b.settingsActivityExcludeProxyAppsSwitch.isEnabled = !isLockdown
        b.settingsActivityExcludeProxyAppsRl.isEnabled = !isLockdown
    }

    private fun enableAfterDelay(ms: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        Utilities.delay(ms, lifecycleScope) { for (v in views) v.isEnabled = true }
    }
}
