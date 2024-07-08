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
import android.graphics.drawable.Drawable
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.CompoundButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.ActivityTunnelSettingsBinding
import com.celzero.bravedns.service.ConnectionMonitor
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import inet.ipaddr.IPAddress.IPVersion
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            if (isChecked) {
                b.settingsActivityPingIpsBtn.visibility = View.VISIBLE
            } else {
                b.settingsActivityPingIpsBtn.visibility = View.GONE
            }
        }

        b.settingsActivityPingIpsBtn.setOnClickListener {
            showPingIpsDialog()
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

    private fun showPingIpsDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this)
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_input_ips, null)
        alertBuilder.setView(dialogView)
        alertBuilder.setCancelable(false)

        val protocols = VpnController.protocols()

        val proto4 = dialogView.findViewById<AppCompatImageView>(R.id.protocol_v4)
        val proto6 = dialogView.findViewById<AppCompatImageView>(R.id.protocol_v6)

        val ip41 = dialogView.findViewById<AppCompatEditText>(R.id.ipv4_address_1)
        val progress41 = dialogView.findViewById<ProgressBar>(R.id.progress_ipv4_1)
        val status41 = dialogView.findViewById<AppCompatImageView>(R.id.status_ipv4_1)

        // Repeat for other IP address fields
        val ip42 = dialogView.findViewById<AppCompatEditText>(R.id.ipv4_address_2)
        val progress42 = dialogView.findViewById<ProgressBar>(R.id.progress_ipv4_2)
        val status42 = dialogView.findViewById<AppCompatImageView>(R.id.status_ipv4_2)

        val ip43 = dialogView.findViewById<AppCompatEditText>(R.id.ipv4_address_3)
        val progress43 = dialogView.findViewById<ProgressBar>(R.id.progress_ipv4_3)
        val status43 = dialogView.findViewById<AppCompatImageView>(R.id.status_ipv4_3)

        val ip61 = dialogView.findViewById<AppCompatEditText>(R.id.ipv6_address_1)
        val progress61 = dialogView.findViewById<ProgressBar>(R.id.progress_ipv6_1)
        val status61 = dialogView.findViewById<AppCompatImageView>(R.id.status_ipv6_1)

        val ip62 = dialogView.findViewById<AppCompatEditText>(R.id.ipv6_address_2)
        val progress62 = dialogView.findViewById<ProgressBar>(R.id.progress_ipv6_2)
        val status62 = dialogView.findViewById<AppCompatImageView>(R.id.status_ipv6_2)

        val ip63 = dialogView.findViewById<AppCompatEditText>(R.id.ipv6_address_3)
        val progress63 = dialogView.findViewById<ProgressBar>(R.id.progress_ipv6_3)
        val status63 = dialogView.findViewById<AppCompatImageView>(R.id.status_ipv6_3)

        val defaultDrawable = ContextCompat.getDrawable(this, R.drawable.edittext_default)
        val errorDrawable = ContextCompat.getDrawable(this, R.drawable.edittext_error)

        val saveBtn: AppCompatButton = dialogView.findViewById(R.id.save_button)
        val testBtn: AppCompatImageView = dialogView.findViewById(R.id.test_button)
        val cancelBtn: AppCompatButton = dialogView.findViewById(R.id.cancel_button)
        val resetChip: Chip = dialogView.findViewById(R.id.reset_chip)

        val errorMsg: AppCompatTextView = dialogView.findViewById(R.id.error_message)

        val items4 = persistentState.pingv4Ips.split(",").toTypedArray()
        val items6 = persistentState.pingv6Ips.split(",").toTypedArray()

        if (protocols.contains("IPv4")) {
            proto4.setImageResource(R.drawable.ic_tick)
        } else {
            proto4.setImageResource(R.drawable.ic_cross)
        }

        if (protocols.contains("IPv6")) {
            proto6.setImageResource(R.drawable.ic_tick)
        } else {
            proto6.setImageResource(R.drawable.ic_cross)
        }

        ip41.setText(items4.getOrNull(0) ?: "")
        ip42.setText(items4.getOrNull(1) ?: "")
        ip43.setText(items4.getOrNull(2) ?: "")

        ip61.setText(items6.getOrNull(0) ?: "")
        ip62.setText(items6.getOrNull(1) ?: "")
        ip63.setText(items6.getOrNull(2) ?: "")

        val dialog = alertBuilder.create()

        resetChip.setOnClickListener {
            // reset to default values
            ip41.setText(Constants.ip4probes[0])
            ip42.setText(Constants.ip4probes[1])
            ip43.setText(Constants.ip4probes[2])
            ip61.setText(Constants.ip6probes[0])
            ip62.setText(Constants.ip6probes[1])
            ip63.setText(Constants.ip6probes[2])
        }

        testBtn.setOnClickListener {
            try {
                progress41.visibility = View.VISIBLE
                progress42.visibility = View.VISIBLE
                progress43.visibility = View.VISIBLE
                progress61.visibility = View.VISIBLE
                progress62.visibility = View.VISIBLE
                progress63.visibility = View.VISIBLE

                io {
                    val valid41 = isReachable(ip41.text.toString())
                    val valid42 = isReachable(ip42.text.toString())
                    val valid43 = isReachable(ip43.text.toString())

                    val valid61 = isReachable(ip61.text.toString())
                    val valid62 = isReachable(ip62.text.toString())
                    val valid63 = isReachable(ip63.text.toString())

                    uiCtx {
                        if (!dialogView.isShown) return@uiCtx

                        progress41.visibility = View.GONE
                        progress42.visibility = View.GONE
                        progress43.visibility = View.GONE
                        progress61.visibility = View.GONE
                        progress62.visibility = View.GONE
                        progress63.visibility = View.GONE

                        status41.visibility = View.VISIBLE
                        status42.visibility = View.VISIBLE
                        status43.visibility = View.VISIBLE
                        status61.visibility = View.VISIBLE
                        status62.visibility = View.VISIBLE
                        status63.visibility = View.VISIBLE

                        status41.setImageDrawable(getImgRes(valid41))
                        status42.setImageDrawable(getImgRes(valid42))
                        status43.setImageDrawable(getImgRes(valid43))
                        status61.setImageDrawable(getImgRes(valid61))
                        status62.setImageDrawable(getImgRes(valid62))
                        status63.setImageDrawable(getImgRes(valid63))
                    }
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "err on ip ping: ${e.message}", e)
            }
        }

        cancelBtn.setOnClickListener {
            dialog.dismiss()
        }

        saveBtn.setOnClickListener {
            try {
                val valid41 = isValidIp(ip41.text.toString(), IPVersion.IPV4)
                val valid42 = isValidIp(ip42.text.toString(), IPVersion.IPV4)
                val valid43 = isValidIp(ip43.text.toString(), IPVersion.IPV4)

                val valid61 = isValidIp(ip61.text.toString(), IPVersion.IPV6)
                val valid62 = isValidIp(ip62.text.toString(), IPVersion.IPV6)
                val valid63 = isValidIp(ip63.text.toString(), IPVersion.IPV6)

                // mark the edit text background as red if the ip is invalid
                ip41.background = if (valid41) defaultDrawable else errorDrawable
                ip42.background = if (valid42) defaultDrawable else errorDrawable
                ip43.background = if (valid43) defaultDrawable else errorDrawable
                ip61.background = if (valid61) defaultDrawable else errorDrawable
                ip62.background = if (valid62) defaultDrawable else errorDrawable
                ip63.background = if (valid63) defaultDrawable else errorDrawable

                if (!valid41 || !valid42 || !valid43 || !valid61 || !valid62 || !valid63) {
                    errorMsg.visibility = View.VISIBLE
                    errorMsg.text = getString(R.string.cd_dns_proxy_error_text_1)
                    return@setOnClickListener
                } else {
                    errorMsg.visibility = View.VISIBLE
                    errorMsg.text = ""
                }

                val ip4 = listOf(ip41.text.toString(), ip42.text.toString(), ip43.text.toString())
                val ip6 = listOf(ip61.text.toString(), ip62.text.toString(), ip63.text.toString())

                val isSame = persistentState.pingv4Ips == ip4.joinToString(",") &&
                    persistentState.pingv6Ips == ip6.joinToString(",")

                if (isSame) {
                    dialog.dismiss()
                    return@setOnClickListener
                }

                persistentState.pingv4Ips = ip4.joinToString(",")
                persistentState.pingv6Ips = ip6.joinToString(",")
                Utilities.showToastUiCentered(
                    this,
                    getString(R.string.config_add_success_toast),
                    Toast.LENGTH_LONG
                )
                notifyConnectionMonitor()

                Logger.i(LOG_TAG_UI, "ping ips: ${persistentState.pingv4Ips}, ${persistentState.pingv6Ips}")
                dialog.dismiss()
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "err on ip save: ${e.message}", e)
                // reset persistent state to the previous value
                persistentState.pingv4Ips = Constants.ip4probes.joinToString(",")
                persistentState.pingv6Ips = Constants.ip6probes.joinToString(",")
            }
        }

        dialog.show()
    }

    private fun notifyConnectionMonitor() {
        // change in ips, inform connection monitor to recheck the connectivity
        io { VpnController.notifyConnectionMonitor() }
    }

    private fun getImgRes(probeResult: ConnectionMonitor.ProbeResult?): Drawable? {
        val failureDrawable = ContextCompat.getDrawable(this, R.drawable.ic_cross)

        if (probeResult == null) return failureDrawable

        if (!probeResult.ok) return failureDrawable

        val cap = probeResult.capabilities ?: return failureDrawable

        val a = if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            R.drawable.ic_firewall_wifi_on  // wifi
        } else if (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            R.drawable.ic_firewall_data_on
        } else {
            R.drawable.ic_tick
        }

        val successDrawable = ContextCompat.getDrawable(this, R.drawable.ic_tick)

        return ContextCompat.getDrawable(this, a) ?: successDrawable
    }

    private suspend fun isReachable(ip: String): ConnectionMonitor.ProbeResult? {
        delay(500)
        return try {
            val res = VpnController.probeIp(ip)
            Logger.d(LOG_TAG_UI, "probe res: ${res?.ok}, ${res?.ip}, ${res?.capabilities}")
            res
        } catch (e: Exception) {
            Logger.d(LOG_TAG_UI, "err on ip ping(isReachable): ${e.message}")
            null
        }
    }

    private fun isValidIp(ipString: String, type: IPVersion): Boolean {
        try {
            if (type.isIPv4) {
                return IPAddressString(ipString).toAddress().isIPv4
            }
            if (type.isIPv6) {
                return IPAddressString(ipString).toAddress().isIPv6
            }
        } catch (e: Exception) {
            Logger.i(LOG_TAG_UI, "err on ip validation: ${e.message}")
        }
        return false
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

    private fun io(fn: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { fn() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }


}
