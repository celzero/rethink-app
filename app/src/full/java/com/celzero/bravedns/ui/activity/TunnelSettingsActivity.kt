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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.databinding.ActivityTunnelSettingsBinding
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.bottomsheet.RethinkInRethinkWarningBottomSheet
import com.celzero.bravedns.ui.dialog.NetworkReachabilityDialog
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.SnackbarHelper
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.concurrent.TimeUnit

class TunnelSettingsActivity : BaseActivity(R.layout.activity_tunnel_settings) {
    private val b by viewBinding(ActivityTunnelSettingsBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val eventLogger by inject<EventLogger>()

    companion object {
        // Time conversion constants
        private const val SECONDS_PER_MINUTE = 60
        private const val SECONDS_PER_HOUR = 3600

        // Network policy indices
        private const val POLICY_AUTO = 0
        private const val POLICY_SENSITIVE = 1
        private const val POLICY_RELAXED = 2
        private const val POLICY_FIXED = 3

        // IP protocol dialog positions
        private const val IP_DIALOG_POS_IPV4 = 0
        private const val IP_DIALOG_POS_IPV6 = 1
        private const val IP_DIALOG_POS_ALWAYS_V46 = 2
        private const val IP_DIALOG_POS_V46 = 3

        // Alpha values for UI elements
        private const val ALPHA_ENABLED = 1f
        private const val ALPHA_DISABLED = 0.5f

        // Socket buffer size values in bytes: 128 KB, 256 KB, 512 KB, 1 MB, 2 MB, 4 MB, 8 MB, 16 MB
        private val SOCKET_BUFFER_SIZES_BYTES = longArrayOf(
            128 * 1024L,   // 128 KB
            256 * 1024L,   // 256 KB
            512 * 1024L,   // 512 KB
            1 * 1024 * 1024L,   // 1 MB
            2 * 1024 * 1024L,   // 2 MB
            4 * 1024 * 1024L,   // 4 MB
            8 * 1024 * 1024L,   // 8 MB
            16 * 1024 * 1024L   // 16 MB
        )
        private const val FOUR_MB_IN_BYTES = 4 * 1024 * 1024
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        //setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = Themes.isActivityLightTheme(isDarkThemeOn(), persistentState.theme)
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
    }

    private fun initView() {
        b.settingsActivityWireguardText.text = getString(R.string.settings_proxy_header)
        val text = getString(R.string.two_argument, getString(R.string.orbot_status_arg_2), getString(R.string.lbl_ip))
        b.settingsActivityTcpText.text = text.uppercase()
        b.dvWgAllowIncomingTxt.text = getString(R.string.two_argument_space, getString(R.string.settings_allow_incoming_wg_packets), getString(R.string.lbl_experimental))
        b.settingsUseMaxMtuHeading.text = getString(R.string.two_argument_space, getString(R.string.settings_jumbo_packets), getString(R.string.lbl_experimental))

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

        b.dvWgLockdownSwitch.isChecked = persistentState.wgGlobalLockdown

        b.dvFloodWgSwitch.isChecked = persistentState.floodWireGuard

        b.dvWgSmartPersistentKeepaliveSwitch.isChecked = persistentState.smartPersistentKeepalive

        // endpoint independent mapping (eim) / endpoint independent filtering (eif)
        b.dvEimfSwitch.isChecked = persistentState.endpointIndependence
        if (persistentState.endpointIndependence) {
            b.dvWgAllowIncomingRl.visibility = View.VISIBLE
            b.dividerWgAllowIncoming.visibility = View.VISIBLE
            b.dvWgAllowIncomingTxt.text = getString(R.string.two_argument_space, getString(R.string.settings_allow_incoming_wg_packets), getString(R.string.lbl_experimental))
            b.dvWgAllowIncomingSwitch.isChecked = persistentState.nwEngExperimentalFeatures
        } else {
            b.dvWgAllowIncomingRl.visibility = View.GONE
            b.dividerWgAllowIncoming.visibility = View.GONE
        }

        b.dvTcpKeepAliveSwitch.isChecked = persistentState.tcpKeepAlive
        b.dvTimeoutSeekbar.progress = persistentState.dialTimeoutSec / SECONDS_PER_MINUTE

        b.dvSocketBufferSizeSeekbar.progress = socketBufferSizeToProgress(persistentState.socketBufferSizeBytes)
        displaySocketBufferSizeUi(persistentState.socketBufferSizeBytes)

        b.settingsUseMaxMtuSwitch.isChecked = persistentState.useMaxMtu

        if (isAtleastQ()) {
            b.settingsActivityTunnelMeteredRl.visibility = View.VISIBLE
            b.settingsActivityTunnelMeteredSwitch.isChecked = persistentState.setVpnBuilderToMetered
        } else {
            b.settingsActivityTunnelMeteredRl.visibility = View.GONE
            b.dividerTunnelMetered.visibility = View.GONE
        }

        displayDialerTimeOutUi(persistentState.dialTimeoutSec)
        displayInternetProtocolUi()
        displayRethinkInRethinkUi()
        showNwPolicyDescription(persistentState.vpnBuilderPolicy)
        
        // If Fixed policy is selected, disable jumbo packets and IP version settings
        if (persistentState.vpnBuilderPolicy == POLICY_FIXED) {
            b.settingsUseMaxMtuRl.isEnabled = false
            b.settingsUseMaxMtuSwitch.isEnabled = false
            b.settingsActivityIpRl.isEnabled = false
        }
    }


    private fun displayDialerTimeOutUi(progressSec: Int) {
        val displayText = formatTimeShort(progressSec)
        b.dvTimeoutValue.text = displayText
    }

    private fun formatTimeShort(totalSeconds: Int): String {
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE

        val parts = mutableListOf<String>()

        if (hours > 0) parts.add("${hours}h")
        if (minutes > 0) parts.add("${minutes}m")
        if (seconds > 0) parts.add("${seconds}s")

        return if (parts.isEmpty()) getString(R.string.lbl_disabled) else parts.joinToString(" ")
    }

    private fun updateDialerTimeOut(valueMin: Int) {
        val inSec = valueMin * SECONDS_PER_MINUTE
        persistentState.dialTimeoutSec = inSec
        displayDialerTimeOutUi(inSec)
    }

    private fun displaySocketBufferSizeUi(bytes: Int) {
        val displayText = formatSocketBufferSize(bytes)
        b.dvSocketBufferSizeValue.text = displayText
    }

    private fun formatSocketBufferSize(bytes: Int): String {
        val kb = bytes / 1024
        return if (kb >= 1024) {
            "${kb / 1024} MB"
        } else {
            "$kb KB"
        }
    }

    private fun socketBufferSizeToProgress(bytes: Int): Int {
        return SOCKET_BUFFER_SIZES_BYTES.indexOf(bytes.toLong()).coerceIn(0, 7)
    }

    private fun progressToSocketBufferSize(progress: Int): Int {
        return SOCKET_BUFFER_SIZES_BYTES[progress.coerceIn(0, 7)].toInt()
    }

    private fun updateSocketBufferSize(progress: Int) {
        val bytes = progressToSocketBufferSize(progress)
        persistentState.socketBufferSizeBytes = bytes
        displaySocketBufferSizeUi(bytes)
    }

    private fun suggestSocketBufferSize() {
        if (persistentState.socketBufferSizeBytes < FOUR_MB_IN_BYTES) {
            val progress = socketBufferSizeToProgress(FOUR_MB_IN_BYTES)
            b.dvSocketBufferSizeSeekbar.progress = progress
            updateSocketBufferSize(progress)
        }
    }

    private fun setupClickListeners() {
        b.settingsActivityAllNetworkRl.setOnClickListener {
            b.settingsActivityAllNetworkSwitch.isChecked =
                !b.settingsActivityAllNetworkSwitch.isChecked
        }

        b.settingsActivityAllNetworkSwitch.setOnCheckedChangeListener {
            _: CompoundButton,
            bool: Boolean ->
            persistentState.useMultipleNetworks = bool
            if (bool) {
                if (persistentState.enableStabilityDependentSettings()) {
                    SnackbarHelper.showStabilityProgram(b.root, persistentState)
                }
            }
            if (!bool && persistentState.routeRethinkInRethink) {
                persistentState.routeRethinkInRethink = false
                displayRethinkInRethinkUi()
            }
            logEvent(
                "use all networks",
                "Use all networks for VPN: $bool"
            )
        }

        b.settingsActivityExcludeProxyAppsSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.excludeAppsInProxy = !isChecked
            logEvent(
                "exclude apps in proxy",
                "Exclude apps in proxy: ${!isChecked}"
            )
        }

        b.settingsActivityExcludeProxyAppsRl.setOnClickListener {
            if (persistentState.wgGlobalLockdown) {
                showToastUiCentered(
                    this,
                    getString(R.string.lockdown_check_setting_disabled),
                    Toast.LENGTH_SHORT
                )
                return@setOnClickListener
            }
            b.settingsActivityExcludeProxyAppsSwitch.isChecked = !b.settingsActivityExcludeProxyAppsSwitch.isChecked
        }

        b.settingsRInRRl.setOnClickListener {
            b.settingsRInRSwitch.isChecked = !b.settingsRInRSwitch.isChecked
        }

        b.settingsRInRSwitch.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            if (isChecked) {
                val sheet = RethinkInRethinkWarningBottomSheet()
                sheet.onProceed = {
                    val rethinkUid = android.os.Process.myUid()
                    io {
                        FirewallManager.exemptRethinkApp(rethinkUid)
                    }
                    if (!persistentState.useMultipleNetworks) {
                        b.settingsActivityAllNetworkSwitch.isChecked = true
                        persistentState.useMultipleNetworks = true
                    }
                    persistentState.routeRethinkInRethink = true
                    logEvent(
                        "rinr enabled",
                        "Rethink in Rethink enabled"
                    )
                    displayRethinkInRethinkUi()
                }
                sheet.onUnderstand = {
                    persistentState.routeRethinkInRethink = true
                    logEvent(
                        "rinr enabled",
                        "Rethink in Rethink enabled (no exemptions)"
                    )
                    displayRethinkInRethinkUi()
                }
                sheet.onCancel = {
                    b.settingsRInRSwitch.isChecked = false
                }
                sheet.show(supportFragmentManager, "rinrWarning")
            } else {
                persistentState.routeRethinkInRethink = false
                logEvent(
                    "rinr toggled",
                    "Rethink in Rethink set to: false"
                )
                displayRethinkInRethinkUi()
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
            if (checked) {
                if (persistentState.enableStabilityDependentSettings()) {
                    SnackbarHelper.showStabilityProgram(b.root, persistentState)
                }
            }
            b.settingsActivityLanTrafficSwitch.isEnabled = false

            Utilities.delay(TimeUnit.SECONDS.toMillis(1L), lifecycleScope) {
                b.settingsActivityLanTrafficSwitch.isEnabled = true
            }
            logEvent(
                "route lan traffic",
                "Route LAN traffic: $checked"
            )
        }

        b.settingsActivityVpnLockdownDesc.setOnClickListener { UIUtils.openVpnProfile(this) }

        b.settingsActivityIpRl.setOnClickListener {
            if (persistentState.vpnBuilderPolicy == POLICY_FIXED) return@setOnClickListener

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
            logEvent(
                "protocol translation",
                "Protocol translation set to: $isSelected"
            )
        }

        b.settingsActivityDefaultDnsRl.setOnClickListener { showDefaultDnsDialog() }

        b.settingsVpnProcessPolicyRl.setOnClickListener { showTunNetworkPolicyDialog() }

        b.settingsActivityConnectivityChecksRl.setOnClickListener {
            showConnectivityChecksOptionsDialog()
        }

        b.settingsActivityConnectivityChecksImg.setOnClickListener {
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
            persistentState.treatOnlyMobileNetworkAsMetered = isChecked
            logEvent(
                "treat mobile network as metered",
                "Treat only mobile network as metered: $isChecked"
            )
        }

        b.settingsActivityMobileMeteredRl.setOnClickListener {
            b.settingsActivityMobileMeteredSwitch.isChecked =
                !b.settingsActivityMobileMeteredSwitch.isChecked
        }

        b.settingsStallNoNwSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.stallOnNoNetwork = isChecked
            logEvent(
                "stall on no network",
                "Stall on no network: $isChecked"
            )
        }

        b.settingsStallNoNwRl.setOnClickListener {
            b.settingsStallNoNwSwitch.isChecked = !b.settingsStallNoNwSwitch.isChecked
        }

        b.dvWgListenPortSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.randomizeListenPort = !isChecked
            logEvent(
                "wireguard listen port",
                "WireGuard listen port randomize: ${!isChecked}"
            )
        }

        b.dvWgListenPortRl.setOnClickListener {
            b.dvWgListenPortSwitch.isChecked = !b.dvWgListenPortSwitch.isChecked
        }

        b.dvEimfSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.endpointIndependence = isChecked
            if (isChecked) {
                b.dvWgAllowIncomingRl.visibility = View.VISIBLE
                b.dividerWgAllowIncoming.visibility = View.VISIBLE
                b.dvWgAllowIncomingSwitch.isChecked = persistentState.nwEngExperimentalFeatures
            } else {
                b.dvWgAllowIncomingRl.visibility = View.GONE
                b.dividerWgAllowIncoming.visibility = View.GONE
                persistentState.nwEngExperimentalFeatures = false
            }
            logEvent(
                "endpoint independence",
                "Endpoint independence (EIM/EIF) set to: $isChecked"
            )
        }

        b.dvEimfRl.setOnClickListener { b.dvEimfSwitch.isChecked = !b.dvEimfSwitch.isChecked }

        b.dvWgAllowIncomingSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.nwEngExperimentalFeatures = isChecked
            logEvent(
                "wg allow incoming packets",
                "WireGuard allow incoming packets set to: $isChecked"
            )
        }

        b.dvWgAllowIncomingRl.setOnClickListener {
            b.dvWgAllowIncomingSwitch.isChecked = !b.dvWgAllowIncomingSwitch.isChecked
        }

        b.dvWgLockdownSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // do not flip the state yet; the compatibility dialog is responsible for
                // committing the change (on Apply/Proceed) or reverting it (on Cancel).
                showLockdownCheckDialog()
            } else {
                persistentState.wgGlobalLockdown = false
                logEvent(
                    "proxy global lockdown",
                    "proxy global lockdown mode set to: false"
                )
                handleLockdownModeIfNeeded()
            }
        }

        b.dvWgLockdownRl.setOnClickListener {
            b.dvWgLockdownSwitch.isChecked = !b.dvWgLockdownSwitch.isChecked
        }

        b.dvFloodWgSwitch.setOnCheckedChangeListener { _, bool ->
            persistentState.floodWireGuard = bool
            logEvent(
                "wg flood mode",
                "WireGuard flood mode set to: $bool"
            )
        }

        b.dvFloodWgRl.setOnClickListener {
            b.dvFloodWgSwitch.isChecked = !b.dvFloodWgSwitch.isChecked
        }

        b.dvWgSmartPersistentKeepaliveSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.smartPersistentKeepalive = isChecked
            logEvent(
                "wg smart persistent keepalive",
                "WireGuard smart persistent keep alive set to: $isChecked"
            )
        }

        b.dvWgSmartPersistentKeepaliveRl.setOnClickListener {
            b.dvWgSmartPersistentKeepaliveSwitch.isChecked = !b.dvWgSmartPersistentKeepaliveSwitch.isChecked
        }

        b.dvTcpKeepAliveSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.tcpKeepAlive = isChecked
            logEvent(
                "tcp keep alive",
                "TCP keep alive set to: $isChecked"
            )
        }

        b.dvTcpKeepAliveRl.setOnClickListener {
            b.dvTcpKeepAliveSwitch.isChecked = !b.dvTcpKeepAliveSwitch.isChecked
        }

        b.settingsUseMaxMtuRl.setOnClickListener {
            b.settingsUseMaxMtuSwitch.isChecked = !b.settingsUseMaxMtuSwitch.isChecked
        }

        b.settingsUseMaxMtuSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.useMaxMtu = isChecked
            if (isChecked) {
                suggestSocketBufferSize()
            }
            logEvent(
                "use jumbo packets",
                "Use jumbo packets set to: $isChecked"
            )
        }

        b.settingsActivityTunnelMeteredRl.setOnClickListener {
            if (!isAtleastQ()) return@setOnClickListener
            b.settingsActivityTunnelMeteredSwitch.isChecked = !b.settingsActivityTunnelMeteredSwitch.isChecked
        }

        b.settingsActivityTunnelMeteredSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!isAtleastQ()) return@setOnCheckedChangeListener
            persistentState.setVpnBuilderToMetered = isChecked
            logEvent(
                "set vpn metered",
                "Set VPN builder to metered: $isChecked"
            )
        }

        b.dvTimeoutSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
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

        b.dvSocketBufferSizeSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateSocketBufferSize(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                Logger.v(LOG_TAG_UI, "Socket buffer size seekbar tracking started")
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let { progress ->
                    updateSocketBufferSize(progress)
                }
            }
        })

        // Custom LAN IPs for VPN
        b.settingsCustomLanIpHeading.text = getString(R.string.custom_lan_ip_title)
        b.settingsCustomLanIpDesc.text = getString(R.string.custom_lan_ip_desc)
        b.settingsCustomLanIpRl.setOnClickListener {
            openCustomLanIpDialog()
        }
    }

    private fun openCustomLanIpDialog() {
        try {
            var themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
            if (Themes.isFrostTheme(themeId)) {
                themeId = R.style.App_Dialog_NoDim
            }
            val dialog = com.celzero.bravedns.ui.dialog.CustomLanIpDialog(
                this,
                persistentState,
                themeId
            )
            dialog.setCanceledOnTouchOutside(true)
            dialog.show()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err opening CustomLanIpDialog: ${e.message}", e)
            showToastUiCentered(
                this,
                getString(R.string.custom_lan_ip_open_error),
                Toast.LENGTH_LONG
            )
        }
    }

    private fun showDefaultDnsDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        alertBuilder.setTitle(getString(R.string.settings_default_dns_heading))

        // under proxy lockdown, the "System" (None) bootstrap DNS is not allowed because system
        // dns cannot be proxied through wireguard. exclude it from the list entirely so it can
        // neither be pre-selected (when the saved url is empty/unknown, the old "?: 0" fallback
        // picked index 0 = System) nor clicked
        val dnsList = if (persistentState.wgGlobalLockdown) {
            Constants.DEFAULT_DNS_LIST.filter { it.url.isNotEmpty() }
        } else {
            Constants.DEFAULT_DNS_LIST
        }
        val items = dnsList.map { it.name }.toTypedArray()
        // get the index of the default dns url
        // if the default dns url is not in the list, then select the first item
        val checkedItem =
            dnsList.firstOrNull { it.url == persistentState.defaultDnsUrl }
                ?.let { dnsList.indexOf(it) } ?: 0
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, pos ->
            dialog.dismiss()
            // update the default dns url
            persistentState.defaultDnsUrl = dnsList[pos].url
            logEvent(
                "default dns changed",
                "Default DNS changed to: ${dnsList[pos].name}"
            )
        }
        val dialog = alertBuilder.create()
        dialog.show()
    }

    data class NetworkPolicyOption(val title: String, val description: String)
    private fun showTunNetworkPolicyDialog() {
        val conservativeTxt = getString(R.string.two_argument_space, getString(R.string.vpn_policy_fixed), getString(R.string.lbl_experimental))
        val options = listOf(
            NetworkPolicyOption(getString(R.string.settings_ip_text_ipv46), getString(R.string.vpn_policy_auto_desc)),
            NetworkPolicyOption(getString(R.string.vpn_policy_sensitive), getString(R.string.vpn_policy_sensitive_desc)),
            NetworkPolicyOption(getString(R.string.vpn_policy_relaxed), getString(R.string.vpn_policy_relaxed_desc)),
            NetworkPolicyOption(conservativeTxt, getString(R.string.vpn_policy_fixed_desc))
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

        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(getString(R.string.vpn_policy_title))
            .setAdapter(adapter) { _, which ->
                currentSelection = which
                if (currentSelection == POLICY_FIXED) {
                    // enable experimental settings prompt
                    if (persistentState.enableStabilityDependentSettings()) {
                        SnackbarHelper.showStabilityProgram(b.root, persistentState)
                    }
                }
                saveNetworkPolicy(which)
                adapter.notifyDataSetChanged()
            }

        val dialog = builder.create()
        dialog.show()
    }

    private fun saveNetworkPolicy(which: Int) {
        persistentState.vpnBuilderPolicy = which
        showNwPolicyDescription(which)

        // If Fixed policy is selected (index 3), enable jumbo packets and set IPv4 & IPv6
        if (which == POLICY_FIXED) {
            // Enable jumbo packets
            persistentState.useMaxMtu = true
            b.settingsUseMaxMtuSwitch.isChecked = true
            suggestSocketBufferSize()

            // Set IP version to IPv4 & IPv6 (ALWAYSv46)
            persistentState.internetProtocolType = InternetProtocol.ALWAYSv46.id

            // Disable both settings (jumbo packets and IP version)
            b.settingsUseMaxMtuRl.isEnabled = false
            b.settingsUseMaxMtuSwitch.isEnabled = false
            b.settingsActivityIpRl.isEnabled = false

            // Update UI
            displayInternetProtocolUi()
        } else {
            // Enable both settings for other policies
            b.settingsUseMaxMtuRl.isEnabled = true
            b.settingsUseMaxMtuSwitch.isEnabled = true
            b.settingsActivityIpRl.isEnabled = true
        }
        logEvent(
            "vpn builder network policy changed",
            "VPN builder network policy changed to index: $which"
        )
    }

    private fun showNwPolicyDescription(which: Int) {
        when (which) {
            POLICY_AUTO -> { b.settingsVpnNwPolicyDesc.text = getString(R.string.settings_ip_text_ipv46) }
            POLICY_SENSITIVE -> { b.settingsVpnNwPolicyDesc.text = getString(R.string.vpn_policy_sensitive) }
            POLICY_RELAXED -> { b.settingsVpnNwPolicyDesc.text = getString(R.string.vpn_policy_relaxed) }
            POLICY_FIXED -> { b.settingsVpnNwPolicyDesc.text = getString(R.string.vpn_policy_fixed) }
        }
    }

    private fun showNwReachabilityCheckDialog() {
        var themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        if (Themes.isFrostTheme(themeId)) {
            themeId = R.style.App_Dialog_NoDim
        }
        val nwReachabilityDialog = NetworkReachabilityDialog(this, persistentState, themeId)
        nwReachabilityDialog.setCanceledOnTouchOutside(true)
        nwReachabilityDialog.show()
    }

    private fun displayInternetProtocolUi() {
        when (persistentState.internetProtocolType) {
            InternetProtocol.IPv4.id -> {
                b.genSettingsIpDesc.text =
                    getString(
                        R.string.settings_selected_ip_desc,
                        getString(R.string.settings_ip_text_ipv4)
                    )
                b.settingsActivityPtransRl.visibility = View.VISIBLE
                b.settingsActivityConnectivityChecksRl.visibility = View.GONE
                b.settingsActivityPingIpsBtn.visibility = View.GONE

                b.dividerIp.visibility = View.VISIBLE
                b.dividerPtrans.visibility = View.GONE
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

                b.dividerIp.visibility = View.VISIBLE
                b.dividerPtrans.visibility = View.GONE
            }
            InternetProtocol.IPv46.id -> {
                b.genSettingsIpDesc.text =
                    getString(
                        R.string.settings_selected_ip_desc,
                        getString(R.string.settings_ip_text_ipv46)
                    )
                b.settingsActivityPtransRl.visibility = View.VISIBLE
                b.settingsActivityConnectivityChecksRl.visibility = View.VISIBLE
                if (persistentState.connectivityChecks) {
                    b.settingsActivityPingIpsBtn.visibility = View.VISIBLE
                } else {
                    b.settingsActivityPingIpsBtn.visibility = View.GONE
                }

                b.dividerIp.visibility = View.VISIBLE
                b.dividerPtrans.visibility = View.VISIBLE
            }
            InternetProtocol.ALWAYSv46.id -> {
                b.genSettingsIpDesc.text =
                    getString(
                        R.string.settings_selected_ip_desc,
                        getString(R.string.settings_ip_text_ipv4) + " & " + getString(R.string.settings_ip_text_ipv6)
                    )
                b.settingsActivityPtransRl.visibility = View.VISIBLE
                b.settingsActivityConnectivityChecksRl.visibility = View.GONE
                b.settingsActivityPingIpsBtn.visibility = View.GONE

                b.dividerIp.visibility = View.VISIBLE
                b.dividerPtrans.visibility = View.GONE
            }
            else -> {
                b.genSettingsIpDesc.text =
                    getString(
                        R.string.settings_selected_ip_desc,
                        getString(R.string.settings_ip_text_ipv4)
                    )
                b.settingsActivityPtransRl.visibility = View.VISIBLE
                b.settingsActivityConnectivityChecksRl.visibility = View.GONE
                b.settingsActivityPingIpsBtn.visibility = View.GONE
                b.dividerIp.visibility = View.VISIBLE
                b.dividerPtrans.visibility = View.GONE
            }
        }
    }

    private fun displayRethinkInRethinkUi() {
        b.settingsRInRSwitch.isChecked = persistentState.routeRethinkInRethink
        if (persistentState.routeRethinkInRethink) {
            b.genRInRDesc.text = getString(R.string.settings_rinr_desc_enabled)
            disableBandwidthBoosterUi()
        } else {
            b.genRInRDesc.text = getString(R.string.settings_rinr_desc_disabled)
            enableBandwidthBoosterUi()
        }
    }

    private fun disableBandwidthBoosterUi() {
        b.settingsUseMaxMtuRl.alpha = ALPHA_DISABLED
        b.settingsUseMaxMtuSwitch.isEnabled = false
        b.settingsUseMaxMtuRl.isEnabled = false
    }

    private fun enableBandwidthBoosterUi() {
        b.settingsUseMaxMtuRl.alpha = ALPHA_ENABLED
        b.settingsUseMaxMtuSwitch.isEnabled = true
        b.settingsUseMaxMtuRl.isEnabled = true
    }

    private fun showIpDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        alertBuilder.setTitle(getString(R.string.settings_ip_dialog_title))
        val alwaysv46Txt = getString(R.string.settings_ip_text_ipv4) + " & " + getString(R.string.settings_ip_text_ipv6) + " " + getString(R.string.lbl_experimental)
        val items =
            arrayOf(
                getString(R.string.settings_ip_dialog_ipv4),
                getString(R.string.settings_ip_dialog_ipv6),
                alwaysv46Txt,
                getString(R.string.settings_ip_dialog_ipv46),
            )
        val chosenProtocol = persistentState.internetProtocolType
        val checkedItem = when (chosenProtocol) {
            InternetProtocol.ALWAYSv46.id -> {
                IP_DIALOG_POS_ALWAYS_V46 // alwaysV46 is at pos 2
            }
            InternetProtocol.IPv46.id -> {
                IP_DIALOG_POS_V46 // ipv46 is at pos 3
            }
            else -> {
                when (chosenProtocol) {
                    InternetProtocol.IPv4.id -> IP_DIALOG_POS_IPV4
                    InternetProtocol.IPv6.id -> IP_DIALOG_POS_IPV6
                    else -> IP_DIALOG_POS_IPV4
                }
            }
        }
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            val selectedItem = when (which) {
                IP_DIALOG_POS_V46 -> {
                    InternetProtocol.IPv46.id // ipv46 is at pos 3
                }
                IP_DIALOG_POS_ALWAYS_V46 -> {
                    InternetProtocol.ALWAYSv46.id // alwaysV46 is at pos 2
                }
                else -> {
                    which
                }
            }
            // return if already selected item is same as current item
            if (persistentState.internetProtocolType == selectedItem) {
                return@setSingleChoiceItems
            }

            val protocolType = InternetProtocol.getInternetProtocol(selectedItem)
            persistentState.internetProtocolType = protocolType.id

            // Enable experimental-dependent settings for IPv6, IPv46, and ALWAYSv46 (experimental protocols)
            if (protocolType.id == InternetProtocol.IPv6.id ||
                protocolType.id == InternetProtocol.IPv46.id ||
                protocolType.id == InternetProtocol.ALWAYSv46.id) {
                if (persistentState.enableStabilityDependentSettings()) {
                    SnackbarHelper.showStabilityProgram(b.root, persistentState)
                }
            }

            displayInternetProtocolUi()
            logEvent(
                "internet protocol changed",
                "Internet protocol changed to: ${protocolType.name}"
            )
        }
        alertBuilder.create().show()
    }

    private fun showConnectivityChecksOptionsDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
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
            logEvent(
                "connectivity checks changed",
                "Connectivity checks changed to option index: $which"
            )
        }
        alertBuilder.create().show()
    }

    private fun showLockdownCheckDialog() {
        io {
            val checks = collectLockdownChecks()
            val hasConflicts = checks.any { it.hasConflict }

            uiCtx {
                if (isFinishing || isDestroyed) return@uiCtx

                val dialogView = LayoutInflater.from(this@TunnelSettingsActivity)
                    .inflate(R.layout.dialog_lockdown_check, null)

                val subtitle = dialogView.findViewById<AppCompatTextView>(R.id.lockdown_check_subtitle)
                val itemsContainer = dialogView.findViewById<LinearLayout>(R.id.lockdown_check_items_container)
                val allOkText = dialogView.findViewById<AppCompatTextView>(R.id.lockdown_check_all_ok)
                val divider = dialogView.findViewById<View>(R.id.lockdown_check_divider)
                val disableRow = dialogView.findViewById<LinearLayout>(R.id.lockdown_check_disable_row)
                val disableLabel = dialogView.findViewById<AppCompatTextView>(R.id.lockdown_check_disable_label)
                val disableDesc = dialogView.findViewById<AppCompatTextView>(R.id.lockdown_check_disable_desc)
                val disableSwitch = dialogView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                    R.id.lockdown_check_disable_switch
                )

                subtitle.text = getString(R.string.lockdown_check_dialog_subtitle)
                disableLabel.text = getString(R.string.lockdown_check_disable_switch)
                disableDesc.text = getString(R.string.lockdown_check_disable_switch_desc)

                val greenColor = UIUtils.fetchColor(this@TunnelSettingsActivity, R.attr.accentGood)
                val redColor = UIUtils.fetchColor(this@TunnelSettingsActivity, R.attr.accentBad)

                for (check in checks) {
                    val itemView = LayoutInflater.from(this@TunnelSettingsActivity)
                        .inflate(R.layout.item_lockdown_check, itemsContainer, false)

                    val icon = itemView.findViewById<AppCompatImageView>(R.id.lockdown_check_item_icon)
                    val label = itemView.findViewById<AppCompatTextView>(R.id.lockdown_check_item_label)
                    val desc = itemView.findViewById<AppCompatTextView>(R.id.lockdown_check_item_desc)
                    val status = itemView.findViewById<AppCompatTextView>(R.id.lockdown_check_item_status)

                    label.text = check.label
                    desc.text = check.description

                    if (check.hasConflict) {
                        icon.setImageResource(R.drawable.ic_cross_accent)
                        icon.imageTintList = ColorStateList.valueOf(redColor)
                        status.text = getString(R.string.lockdown_check_conflict)
                        status.setTextColor(redColor)
                        status.background = createChipBackground(redColor)
                    } else {
                        icon.setImageResource(R.drawable.ic_check_circle)
                        icon.imageTintList = ColorStateList.valueOf(greenColor)
                        status.text = getString(R.string.lockdown_check_ok)
                        status.setTextColor(greenColor)
                        status.background = createChipBackground(greenColor)
                    }

                    itemsContainer.addView(itemView)
                }

                if (!hasConflicts) {
                    allOkText.visibility = View.VISIBLE
                    allOkText.text = getString(R.string.lockdown_check_all_ok)
                    divider.visibility = View.GONE
                    disableRow.visibility = View.GONE
                }

                val dialog = MaterialAlertDialogBuilder(this@TunnelSettingsActivity, R.style.App_Dialog_NoDim)
                    .setTitle(getString(R.string.lockdown_check_dialog_title))
                    .setView(dialogView)
                    .setPositiveButton(getString(R.string.lockdown_check_apply)) { d, _ ->
                        if (hasConflicts && disableSwitch.isChecked) {
                            disableConflictingOptions(checks)
                        }
                        persistentState.wgGlobalLockdown = true
                        logEvent(
                            "proxy global lockdown",
                            "proxy global lockdown mode set to: true"
                        )
                        handleLockdownModeIfNeeded()
                        d.dismiss()
                    }
                    .setNeutralButton(getString(R.string.lbl_proceed)) { d, _ ->
                        persistentState.wgGlobalLockdown = true
                        logEvent(
                            "proxy global lockdown",
                            "proxy global lockdown mode set to: true (forced)"
                        )
                        handleLockdownModeIfNeeded()
                        d.dismiss()
                    }
                    .setNegativeButton(getString(R.string.lockdown_check_cancel)) { d, _ ->
                        b.dvWgLockdownSwitch.isChecked = false
                        persistentState.wgGlobalLockdown = false
                        d.dismiss()
                    }
                    .setCancelable(false)
                    .create()
                dialog.setOnShowListener {
                    if (hasConflicts) {
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled = disableSwitch.isChecked
                    }
                }

                disableSwitch.setOnCheckedChangeListener { _, isChecked ->
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = isChecked
                }

                dialog.show()
            }
        }
    }

    private data class LockdownCheckItem(
        val label: String,
        val description: String,
        val hasConflict: Boolean,
        val type: CheckType
    )

    private enum class CheckType {
        SYSTEM_DNS,
        BOOTSTRAP_DNS,
        DNS_PROXY,
        ORBOT,
        HTTP_PROXY,
        SOCKS5,
        ANTI_CENSORSHIP,
        NO_PROXY,
        RETHINK_BYPASS
    }

    private suspend fun collectLockdownChecks(): List<LockdownCheckItem> {
        val checks = mutableListOf<LockdownCheckItem>()

        // 1. System DNS check
        val isSystemDns = appConfig.isSystemDns()
        checks.add(
            LockdownCheckItem(
                label = getString(R.string.lockdown_check_dns_system),
                description = getString(R.string.lockdown_check_dns_system_desc),
                hasConflict = isSystemDns,
                type = CheckType.SYSTEM_DNS
            )
        )

        // 2. Bootstrap DNS check
        val isBootstrapSystemDns = persistentState.defaultDnsUrl.isEmpty()
        checks.add(
            LockdownCheckItem(
                label = getString(R.string.lockdown_check_dns_bootstrap),
                description = getString(R.string.lockdown_check_dns_bootstrap_desc),
                hasConflict = isBootstrapSystemDns,
                type = CheckType.BOOTSTRAP_DNS
            )
        )

        // 3. DNS Proxy check
        if (appConfig.isDnsProxyActive()) {
            val dnsDetails = appConfig.getSelectedDnsProxyDetails()
            val appName = dnsDetails?.proxyAppName
            val hasConflict = !appName.isNullOrBlank()
            checks.add(
                LockdownCheckItem(
                    label = getString(R.string.lockdown_check_dns_proxy),
                    description = if (hasConflict) {
                        getString(R.string.lockdown_check_dns_proxy_desc, appName)
                    } else {
                        getString(R.string.lockdown_check_dns_proxy)
                    },
                    hasConflict = hasConflict,
                    type = CheckType.DNS_PROXY
                )
            )
        }

        // 4. Orbot check
        if (appConfig.isOrbotProxyEnabled()) {
            checks.add(
                LockdownCheckItem(
                    label = getString(R.string.lockdown_check_orbot),
                    description = getString(R.string.lockdown_check_orbot_desc),
                    hasConflict = true,
                    type = CheckType.ORBOT
                )
            )
        }

        // 5. HTTP Proxy check
        // HTTP proxy cannot be used in lockdown (any HTTP proxy conflicts, not just
        // app-bound ones), since it would override the lockdown proxy.
        if (appConfig.isCustomHttpProxyEnabled()) {
            val appName = appConfig.getConnectedHttpProxy()?.proxyAppName
            checks.add(
                LockdownCheckItem(
                    label = getString(R.string.lockdown_check_http_proxy),
                    description = getString(R.string.lockdown_check_http_proxy_desc, appName),
                    hasConflict = true,
                    type = CheckType.HTTP_PROXY
                )
            )
        }

        // 6. SOCKS5 Proxy check
        // SOCKS5 proxy cannot be used in lockdown (any SOCKS5 proxy conflicts, not
        // just app-bound ones), since it would override the lockdown proxy.
        if (appConfig.isCustomSocks5Enabled()) {
            val appName = appConfig.getConnectedSocks5Proxy()?.proxyAppName
            checks.add(
                LockdownCheckItem(
                    label = getString(R.string.lockdown_check_socks5),
                    description = getString(R.string.lockdown_check_socks5_desc, appName),
                    hasConflict = true,
                    type = CheckType.SOCKS5
                )
            )
        }

        // 7. Anti-Censorship check
        // disable anti-censorship, else only "hybrid" (TCP_PROXY) dial strategy with
        // "never retry" (RETRY_NEVER) is compatible. NEVER_SPLIT (no packet alteration) is also
        // treated as compatible since it does not alter/bypass routing. Any other dial strategy
        // or a non-never retry can bypass the lockdown proxy and so is a conflict.
        run {
            val dialStrategy = persistentState.dialStrategy
            val retryStrategy = persistentState.retryStrategy
            val isAcDisabled = dialStrategy == AntiCensorshipActivity.DialStrategies.NEVER_SPLIT.mode
            val isHybridDial = dialStrategy == AntiCensorshipActivity.DialStrategies.TCP_PROXY.mode
            val isNeverRetry = retryStrategy == AntiCensorshipActivity.RetryStrategies.RETRY_NEVER.mode
            val hasConflict = !isAcDisabled && !(isHybridDial && isNeverRetry)
            checks.add(
                LockdownCheckItem(
                    label = getString(R.string.lockdown_check_ac),
                    description = getString(R.string.lockdown_check_ac_desc),
                    hasConflict = hasConflict,
                    type = CheckType.ANTI_CENSORSHIP
                )
            )
        }

        // 8. No proxy enabled check
        val anyProxyEnabled = appConfig.isProxyEnabled() ||
            RpnProxyManager.isRpnEnabled()
        if (!anyProxyEnabled) {
            checks.add(
                LockdownCheckItem(
                    label = getString(R.string.lockdown_check_no_proxy),
                    description = getString(R.string.lockdown_check_no_proxy_desc),
                    hasConflict = true,
                    type = CheckType.NO_PROXY
                )
            )
        }

        // 9. Rethink bypass check
        if (persistentState.routeRethinkInRethink && FirewallManager.getAppInfoByPackage(this.packageName)?.isProxyExcluded == true) {
            checks.add(
                LockdownCheckItem(
                    label = getString(R.string.lockdown_check_rethink_bypass),
                    description = getString(R.string.lockdown_check_rethink_bypass_desc),
                    hasConflict = true,
                    type = CheckType.RETHINK_BYPASS
                )
            )
        }

        return checks
    }

    private fun disableConflictingOptions(checks: List<LockdownCheckItem>) {
        io {
            var proxiesRemoved = false
            for (check in checks) {
                if (!check.hasConflict) continue
                when (check.type) {
                    CheckType.SYSTEM_DNS -> {
                        appConfig.enableRethinkDnsPlus()
                    }
                    CheckType.BOOTSTRAP_DNS -> {
                        // set rethink as the bootstrap dns
                        persistentState.defaultDnsUrl = Constants.DEFAULT_DNS_LIST[1].url
                    }
                    CheckType.DNS_PROXY -> {
                        appConfig.enableRethinkDnsPlus()
                    }
                    CheckType.ORBOT -> {
                        if (!proxiesRemoved) {
                            appConfig.removeAllProxies()
                            proxiesRemoved = true
                        }
                    }
                    CheckType.HTTP_PROXY -> {
                        if (!proxiesRemoved) {
                            appConfig.removeAllProxies()
                            proxiesRemoved = true
                        }
                    }
                    CheckType.SOCKS5 -> {
                        if (!proxiesRemoved) {
                            appConfig.removeAllProxies()
                            proxiesRemoved = true
                        }
                    }
                    CheckType.ANTI_CENSORSHIP -> {
                        // Set anti-censorship to the lockdown-compatible "hybrid" state:
                        // TCP_PROXY (hybrid) dial strategy with RETRY_NEVER retry strategy.
                        // This keeps anti-censorship functional while remaining compatible
                        // with proxy lockdown (see collectLockdownChecks compatibility rule).
                        persistentState.dialStrategy = AntiCensorshipActivity.DialStrategies.TCP_PROXY.mode
                        persistentState.retryStrategy = AntiCensorshipActivity.RetryStrategies.RETRY_NEVER.mode
                        persistentState.autoProxyEnabled = true
                    }
                    CheckType.NO_PROXY -> {
                        // No automatic fix; user must enable a proxy
                    }
                    CheckType.RETHINK_BYPASS -> {
                        persistentState.routeRethinkInRethink = false
                    }
                }
            }
        }
    }

    private fun createChipBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f
            setColor(color and 0x00FFFFFF or 0x1A000000)
            setStroke(1, color)
        }
    }

    private fun handleLockdownModeIfNeeded() {
        val isSystemLockdown = VpnController.isVpnLockdown()
        val isProxyLockdown = persistentState.wgGlobalLockdown

        b.settingsActivityVpnLockdownDesc.visibility = if (isSystemLockdown) View.VISIBLE else View.GONE

        // "Exclude apps in proxy" is incompatible with both the system VPN lockdown and the
        // proxy lockdown: in proxy lockdown, apps excluded from the proxy are blocked instead of
        // bypassed (see TunFlowManager), so allowing this toggle would silently break traffic.
        when {
            isSystemLockdown -> {
                b.settingsActivityExcludeProxyAppsRl.alpha = ALPHA_DISABLED
                b.settingsActivityExcludeProxyAppsSwitch.isEnabled = false
                b.settingsActivityExcludeProxyAppsRl.isEnabled = false
            }
            isProxyLockdown -> {
                b.settingsActivityExcludeProxyAppsRl.alpha = ALPHA_DISABLED
                b.settingsActivityExcludeProxyAppsSwitch.isEnabled = false
                b.settingsActivityExcludeProxyAppsRl.isEnabled = true
            }
            else -> {
                b.settingsActivityExcludeProxyAppsRl.alpha = ALPHA_ENABLED
                b.settingsActivityExcludeProxyAppsSwitch.isEnabled = true
                b.settingsActivityExcludeProxyAppsRl.isEnabled = true
            }
        }

        b.settingsActivityLanTrafficRl.isEnabled = !isSystemLockdown
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.TUN_ESTABLISHED, Severity.LOW, msg, EventSource.UI, false, details)
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private fun uiCtx(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) { f() }
    }

    private fun enableAfterDelay(ms: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        Utilities.delay(ms, lifecycleScope) { for (v in views) v.isEnabled = true }
    }
}
