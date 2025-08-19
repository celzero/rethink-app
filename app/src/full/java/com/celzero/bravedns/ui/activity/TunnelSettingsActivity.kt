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

import Logger
import Logger.LOG_TAG_UI
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.ActivityTunnelSettingsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.dialog.NetworkReachabilityDialog
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.NewSettingsManager
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.setBadgeDotVisible
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.isAtleastS
import com.celzero.bravedns.util.Utilities.showToastUiCentered
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

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

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
        showNewBadgeIfNeeded()
    }

    private fun showNewBadgeIfNeeded() {
        val tcpKeepAlive = NewSettingsManager.shouldShowBadge(NewSettingsManager.TCP_KEEP_ALIVE)
        val idleTimeout = NewSettingsManager.shouldShowBadge(NewSettingsManager.TCP_IDLE_TIMEOUT)
        val loopback = NewSettingsManager.shouldShowBadge(NewSettingsManager.LOOP_BACK_PROXY_FORWARDER)
        val eimf = NewSettingsManager.shouldShowBadge(NewSettingsManager.ENDPOINT_INDEPENDENT)
        val doNotStall = NewSettingsManager.shouldShowBadge(NewSettingsManager.DO_NOT_STALL)
        val performConnectionCheck = NewSettingsManager.shouldShowBadge(NewSettingsManager.PERFORM_CONNECTION_CHECK)
        val randomizeWgPort = NewSettingsManager.shouldShowBadge(NewSettingsManager.RANDOMIZE_WG_PORT)
        val mobileMetered = NewSettingsManager.shouldShowBadge(NewSettingsManager.MARK_MOBILE_METERED)
        val tunNetworkPolicy = NewSettingsManager.shouldShowBadge(NewSettingsManager.TUN_NETWORK_POLICY)

        b.dvTcpKeepAliveTxt.setBadgeDotVisible(this, tcpKeepAlive)
        b.dvTimeoutTxt.setBadgeDotVisible(this, idleTimeout)
        b.genSettingsExcludeProxyAppsTxt.setBadgeDotVisible(this, loopback)
        b.dvEimfTxt.setBadgeDotVisible(this, eimf)
        b.genStallNoNwTxt.setBadgeDotVisible(this, doNotStall)
        b.genSettingsConnectivityChecksTxt.setBadgeDotVisible(this, performConnectionCheck)
        b.dvWgListenPortTxt.setBadgeDotVisible(this, randomizeWgPort)
        b.genSettingsMobileMeteredTxt.setBadgeDotVisible(this, mobileMetered)
        b.settingsActivityVpnHeadingText.setBadgeDotVisible(this, tunNetworkPolicy)
    }

    private fun initView() {
        b.settingsActivityWireguardText.text = getString(R.string.settings_proxy_header).lowercase()
        val text = getString(R.string.two_argument, getString(R.string.orbot_status_arg_2).lowercase(), getString(R.string.lbl_ip))
        b.settingsActivityTcpText.text = text.lowercase()

        b.settingsActivityAllowBypassProgress.visibility = View.GONE
        displayAllowBypassUi()
        // use multiple networks
        b.settingsActivityAllNetworkSwitch.isChecked = persistentState.useMultipleNetworks
        // route lan traffic
        b.settingsActivityLanTrafficSwitch.isChecked = persistentState.privateIps
        // show ping ips
        b.settingsActivityPingIpsBtn.visibility = if (persistentState.connectivityChecks) View.VISIBLE else View.GONE
        // exclude apps in proxy
        b.settingsActivityExcludeProxyAppsSwitch.isChecked = !persistentState.excludeAppsInProxy
        // for protocol translation, enable only on DNS/DNS+Firewall mode
        if (appConfig.getBraveMode().isDnsActive()) {
            b.settingsActivityPtransSwitch.isChecked = persistentState.protocolTranslationType
        } else {
            persistentState.protocolTranslationType = false
            b.settingsActivityPtransSwitch.isChecked = false
        }

        b.settingsActivityMobileMeteredSwitch.isChecked = persistentState.treatOnlyMobileNetworkAsMetered

        b.settingsStallNoNwSwitch.isChecked = persistentState.stallOnNoNetwork

        b.dvWgListenPortSwitch.isChecked = !persistentState.randomizeListenPort

        // endpoint independent mapping (eim) / endpoint independent filtering (eif)
        b.dvEimfSwitch.isChecked = persistentState.endpointIndependence

        b.dvTcpKeepAliveSwitch.isChecked = persistentState.tcpKeepAlive
        b.dvTimeoutSeekbar.progress = persistentState.dialTimeoutSec / 60

        displayDialerTimeOutUi(persistentState.dialTimeoutSec)
        displayInternetProtocolUi()
        displayRethinkInRethinkUi()
        showNwPolicyDescription(persistentState.vpnBuilderPolicy)
    }


    private fun displayDialerTimeOutUi(progressSec: Int) {
        val displayText = formatTimeShort(progressSec)
        b.dvTimeoutValue.text = displayText
    }

    private fun formatTimeShort(totalSeconds: Int): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60

        val parts = mutableListOf<String>()

        if (hours > 0) parts.add("${hours}h")
        if (minutes > 0) parts.add("${minutes}m")
        if (seconds > 0) parts.add("${seconds}s")

        return if (parts.isEmpty()) getString(R.string.lbl_disabled) else parts.joinToString(" ")
    }

    private fun updateDialerTimeOut(valueMin: Int) {
        val inSec = valueMin * 60
        persistentState.dialTimeoutSec = inSec
        displayDialerTimeOutUi(inSec)
    }

    private fun displayAllowBypassUi() {
        // allow apps part of the vpn to request networks outside of it, effectively letting it
        // bypass the vpn itself
        if (!Utilities.isPlayStoreFlavour()) {
            b.settingsActivityAllowBypassRl.visibility = View.VISIBLE
            b.settingsActivityAllowBypassDesc.visibility = View.VISIBLE
            b.settingsActivityAllowBypassSwitch.visibility = View.VISIBLE
            b.settingsActivityAllowBypassProgress.visibility = View.GONE

            b.settingsActivityAllowBypassSwitch.isChecked = persistentState.allowBypass
        } else {
            b.settingsActivityAllowBypassRl.visibility = View.GONE
            b.settingsActivityAllowBypassDesc.visibility = View.GONE
            b.settingsActivityAllowBypassSwitch.visibility = View.GONE
            b.settingsActivityAllowBypassProgress.visibility = View.GONE
        }
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
            NewSettingsManager.markSettingSeen(NewSettingsManager.LOOP_BACK_PROXY_FORWARDER)
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
                alertBuilder.setCancelable(false)
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
            if (Utilities.isPlayStoreFlavour()) return@setOnCheckedChangeListener

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
                showToastUiCentered(
                    this,
                    getString(R.string.settings_protocol_translation_dns_inactive),
                    Toast.LENGTH_SHORT
                )
            }
        }

        b.settingsActivityDefaultDnsRl.setOnClickListener { showDefaultDnsDialog() }

        b.settingsVpnProcessPolicyRl.setOnClickListener { showTunNetworkPolicyDialog() }

        b.settingsActivityConnectivityChecksRl.setOnClickListener {
            NewSettingsManager.markSettingSeen(NewSettingsManager.PERFORM_CONNECTION_CHECK)
            showConnectivityChecksOptionsDialog()
        }

        b.settingsActivityConnectivityChecksImg.setOnClickListener {
            NewSettingsManager.markSettingSeen(NewSettingsManager.PERFORM_CONNECTION_CHECK)
            showConnectivityChecksOptionsDialog()
        }

        b.settingsActivityPingIpsBtn.setOnClickListener {
            if (!VpnController.hasTunnel()) {
                showToastUiCentered(
                    this,
                    getString(R.string.settings_socks5_vpn_disabled_error),
                    Toast.LENGTH_SHORT
                )
                return@setOnClickListener
            }
            showNwReachabilityCheckDialog()
        }

        b.settingsActivityMobileMeteredSwitch.setOnCheckedChangeListener { _, isChecked ->
            NewSettingsManager.markSettingSeen(NewSettingsManager.MARK_MOBILE_METERED)
            persistentState.treatOnlyMobileNetworkAsMetered = isChecked
        }

        b.settingsActivityMobileMeteredRl.setOnClickListener {
            b.settingsActivityMobileMeteredSwitch.isChecked =
                !b.settingsActivityMobileMeteredSwitch.isChecked
        }

        b.settingsStallNoNwSwitch.setOnCheckedChangeListener { _, isChecked ->
            NewSettingsManager.markSettingSeen(NewSettingsManager.DO_NOT_STALL)
            persistentState.stallOnNoNetwork = isChecked
        }

        b.settingsStallNoNwRl.setOnClickListener {
            b.settingsStallNoNwSwitch.isChecked = !b.settingsStallNoNwSwitch.isChecked
        }

        b.dvWgListenPortSwitch.setOnCheckedChangeListener { _, isChecked ->
            NewSettingsManager.markSettingSeen(NewSettingsManager.RANDOMIZE_WG_PORT)
            persistentState.randomizeListenPort = !isChecked
        }

        b.dvWgListenPortRl.setOnClickListener {
            b.dvWgListenPortSwitch.isChecked = !b.dvWgListenPortSwitch.isChecked
        }

        b.dvEimfSwitch.setOnCheckedChangeListener { _, isChecked ->
            NewSettingsManager.markSettingSeen(NewSettingsManager.ENDPOINT_INDEPENDENT)
            if (!isAtleastS()) {
                return@setOnCheckedChangeListener
            }

            persistentState.endpointIndependence = isChecked
        }

        b.dvEimfRl.setOnClickListener { b.dvEimfSwitch.isChecked = !b.dvEimfSwitch.isChecked }

        b.dvTcpKeepAliveSwitch.setOnCheckedChangeListener { _, isChecked ->
            NewSettingsManager.markSettingSeen(NewSettingsManager.TCP_KEEP_ALIVE)
            persistentState.tcpKeepAlive = isChecked
        }

        b.dvTcpKeepAliveRl.setOnClickListener {
            b.dvTcpKeepAliveSwitch.isChecked = !b.dvTcpKeepAliveSwitch.isChecked
        }

        b.dvTimeoutSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                NewSettingsManager.markSettingSeen(NewSettingsManager.TCP_IDLE_TIMEOUT)
                updateDialerTimeOut(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // No action needed on start tracking
                // This can be used to show a toast or a message if needed
                // For now, we will just log the start of tracking
                Logger.v(LOG_TAG_UI, "Dialer timeout seekbar tracking started")
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // When the user stops dragging the seekbar, update the dialer timeout
                seekBar?.progress?.let { progress ->
                    updateDialerTimeOut(progress)
                }
            }
        })

    }

    private fun showDefaultDnsDialog() {
        /*if (RpnProxyManager.isRpnEnabled()) {
            showToastUiCentered(
                this,
                getString(R.string.fallback_rplus_toast),
                Toast.LENGTH_SHORT
            )
            return
        }*/

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

    data class NetworkPolicyOption(val title: String, val description: String)
    private fun showTunNetworkPolicyDialog() {
        val options = listOf(
            NetworkPolicyOption(getString(R.string.settings_ip_text_ipv46), getString(R.string.vpn_policy_auto_desc)),
            NetworkPolicyOption(getString(R.string.vpn_policy_sensitive), getString(R.string.vpn_policy_sensitive_desc)),
            NetworkPolicyOption(getString(R.string.vpn_policy_relaxed), getString(R.string.vpn_policy_relaxed_desc))
        )
        var currentSelection = persistentState.vpnBuilderPolicy
        val adapter = object : ArrayAdapter<NetworkPolicyOption>(
            this, R.layout.item_network_policy, R.id.policyTitle, options
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val titleView = view.findViewById<AppCompatTextView>(R.id.policyTitle)
                val descView = view.findViewById<AppCompatTextView>(R.id.policyDesc)
                val radio = view.findViewById<AppCompatRadioButton>(R.id.radioButton)

                val item = getItem(position)
                titleView.text = item?.title
                descView.text = item?.description
                radio.isChecked = position == currentSelection

                return view
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.vpn_policy_title))
            .setAdapter(adapter) { _, which ->
                currentSelection = which
                saveNetworkPolicy(which)
                adapter.notifyDataSetChanged()
            }
            .setPositiveButton(getString(R.string.hs_download_positive_default)) { dialog, _ ->
                dialog.dismiss()
                saveNetworkPolicy(currentSelection)
            }
            .setNegativeButton(getString(R.string.lbl_cancel), null)
            .show()
    }

    private fun saveNetworkPolicy(which: Int) {
        persistentState.vpnBuilderPolicy = which
        showNwPolicyDescription(which)
    }

    private fun showNwPolicyDescription(which: Int) {
        when (which) {
            0 -> { b.settingsVpnNwPolicyDesc.text = getString(R.string.settings_ip_text_ipv46) }
            1 -> { b.settingsVpnNwPolicyDesc.text = getString(R.string.vpn_policy_sensitive) }
            2 -> { b.settingsVpnNwPolicyDesc.text = getString(R.string.vpn_policy_sensitive) }
        }
    }

    private fun showNwReachabilityCheckDialog() {
        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        val nwReachabilityDialog = NetworkReachabilityDialog(this, persistentState, themeId)
        nwReachabilityDialog.setCanceledOnTouchOutside(true)
        nwReachabilityDialog.show()
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
                b.settingsActivityPingIpsBtn.visibility = View.GONE
            }
            InternetProtocol.IPv6.id -> {
                b.genSettingsIpDesc.text =
                    getString(
                        R.string.settings_selected_ip_desc,
                        getString(R.string.settings_ip_text_ipv6)
                    )
                b.settingsActivityPtransRl.visibility = View.VISIBLE
                b.settingsActivityConnectivityChecksRl.visibility = View.GONE
                b.settingsActivityPingIpsBtn.visibility = View.GONE
            }
            InternetProtocol.IPv46.id -> {
                b.genSettingsIpDesc.text =
                    getString(
                        R.string.settings_selected_ip_desc,
                        getString(R.string.settings_ip_text_ipv46)
                    )
                b.settingsActivityPtransRl.visibility = View.GONE
                b.settingsActivityConnectivityChecksRl.visibility = View.VISIBLE
                if (persistentState.connectivityChecks) {
                    b.settingsActivityPingIpsBtn.visibility = View.VISIBLE
                } else {
                    b.settingsActivityPingIpsBtn.visibility = View.GONE
                }
            }
            else -> {
                b.genSettingsIpDesc.text =
                    getString(
                        R.string.settings_selected_ip_desc,
                        getString(R.string.settings_ip_text_ipv4)
                    )
                b.settingsActivityPtransRl.visibility = View.GONE
                b.settingsActivityConnectivityChecksRl.visibility = View.GONE
                b.settingsActivityPingIpsBtn.visibility = View.GONE
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

    private fun showConnectivityChecksOptionsDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this)
        alertBuilder.setTitle(getString(R.string.settings_connectivity_checks))
        val items = arrayOf(
            getString(R.string.settings_app_list_default_app),
            getString(R.string.settings_ip_text_ipv46),
            getString(R.string.lbl_manual)
        )
        val type = persistentState.performAutoNetworkConnectivityChecks
        val enabled = persistentState.connectivityChecks
        val checkedItem = if (!enabled) {
            0 // none
        } else {
            when (type) {
                true -> 1 // auto
                false -> 2 // manual
            }
        }

        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            when (which) {
                0 -> {
                    // none
                    persistentState.performAutoNetworkConnectivityChecks = true
                    persistentState.connectivityChecks = false
                    b.settingsActivityPingIpsBtn.visibility = View.GONE
                }
                1 -> {
                    // auto
                    persistentState.performAutoNetworkConnectivityChecks = true
                    persistentState.connectivityChecks = true
                    b.settingsActivityPingIpsBtn.visibility = View.VISIBLE
                }
                2 -> {
                    // manual
                    persistentState.performAutoNetworkConnectivityChecks = false
                    persistentState.connectivityChecks = true
                    b.settingsActivityPingIpsBtn.visibility = View.VISIBLE
                }
            }
        }
        alertBuilder.create().show()
    }

    private fun handleLockdownModeIfNeeded() {
        val isLockdown = VpnController.isVpnLockdown()
        if (isLockdown) {
            b.settingsActivityVpnLockdownDesc.visibility = View.VISIBLE
            b.settingsActivityAllowBypassRl.alpha = 0.5f
            b.settingsActivityExcludeProxyAppsRl.alpha = 0.5f
        } else {
            b.settingsActivityVpnLockdownDesc.visibility = View.GONE
            b.settingsActivityAllowBypassRl.alpha = 1f
            b.settingsActivityExcludeProxyAppsRl.alpha = 1f
        }
        b.settingsActivityAllowBypassSwitch.isEnabled = !isLockdown
        b.settingsActivityAllowBypassRl.isEnabled = !isLockdown
        b.settingsActivityLanTrafficRl.isEnabled = !isLockdown
        b.settingsActivityExcludeProxyAppsSwitch.isEnabled = !isLockdown
        b.settingsActivityExcludeProxyAppsRl.isEnabled = !isLockdown
    }

    private fun enableAfterDelay(ms: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        Utilities.delay(ms, lifecycleScope) { for (v in views) v.isEnabled = true }
    }
}
