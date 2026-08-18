/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.bottomsheet

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.lifecycle.lifecycleScope
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.Toast
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.databinding.BottomSheetHomeScreenBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.ProxySettingsActivity
import com.celzero.bravedns.ui.activity.WgMainActivity
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.SsidPermissionManager
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils.htmlToSpannedText
import com.celzero.bravedns.util.UIUtils.openVpnProfile
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.useTransparentNoDimBackground
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import kotlin.time.Duration.Companion.milliseconds

class HomeScreenSettingBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetHomeScreenBinding? = null

    private val b
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()

    private lateinit var animation: Animation

    override fun getTheme(): Int =
        Themes.getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    companion object {
        const val SCREEN_WG = "screen_wireguard"
        const val SCREEN_PROXY = "screen_proxy"
        // minimum duration the refresh icon animation stays visible, even if the
        // underlying refresh completes faster
        const val MIN_ANIMATION_DURATION_MS = 1500L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetHomeScreenBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.useTransparentNoDimBackground()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }
        initView()
        updateUptime()
        addAnimation()
        initializeClickListeners()
    }

    private fun addAnimation() {
        animation =
            RotateAnimation(
                0.0f,
                360.0f,
                Animation.RELATIVE_TO_SELF,
                0.5f,
                Animation.RELATIVE_TO_SELF,
                0.5f)
        animation.repeatCount = -1
        animation.duration = 750
    }

    private fun initView() {
        b.bsHomeScreenConnectedStatus.text = getConnectionStatus()
        val selectedIndex = appConfig.getBraveMode().mode
        Logger.d(LOG_TAG_VPN, "Home screen bottom sheet selectedIndex: $selectedIndex")
        updateStatus(selectedIndex)
    }

    private fun updateStatus(selectedState: Int) {
        when (selectedState) {
            AppConfig.BraveMode.DNS.mode -> {
                b.bsHomeScreenRadioDns.isChecked = true
            }
            AppConfig.BraveMode.FIREWALL.mode -> {
                b.bsHomeScreenRadioFirewall.isChecked = true
            }
            AppConfig.BraveMode.DNS_FIREWALL.mode -> {
                b.bsHomeScreenRadioDnsFirewall.isChecked = true
            }
            else -> {
                b.bsHomeScreenRadioDnsFirewall.isChecked = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handleLockdownModeIfNeeded()
    }

    private fun initializeClickListeners() {
        b.bsHomeScreenRadioDns.setOnCheckedChangeListener { _: CompoundButton, isSelected: Boolean
            ->
            handleDnsMode(isSelected)
        }

        b.bsHomeScreenRadioFirewall.setOnCheckedChangeListener {
            _: CompoundButton,
            isSelected: Boolean ->
            handleFirewallMode(isSelected)
        }

        b.bsHomeScreenRadioDnsFirewall.setOnCheckedChangeListener {
            _: CompoundButton,
            isSelected: Boolean ->
            handleDnsFirewallMode(isSelected)
        }

        b.bsHsDnsRl.setOnClickListener {
            val checked = b.bsHomeScreenRadioDns.isChecked
            if (!checked) {
                b.bsHomeScreenRadioDns.isChecked = true
            }
            handleDnsMode(checked)
        }

        b.bsHsFirewallRl.setOnClickListener {
            val checked = b.bsHomeScreenRadioFirewall.isChecked
            if (!checked) {
                b.bsHomeScreenRadioFirewall.isChecked = true
            }
            handleFirewallMode(checked)
        }

        b.bsHsDnsFirewallRl.setOnClickListener {
            val checked = b.bsHomeScreenRadioDnsFirewall.isChecked
            if (!checked) {
                b.bsHomeScreenRadioDnsFirewall.isChecked = true
            }
            handleDnsFirewallMode(checked)
        }

        b.bsHomeScreenVpnLockdownDesc.setOnClickListener {
            if (VpnController.isVpnLockdown()) {
                openVpnProfile(requireContext())
            } else if (RpnProxyManager.isRpnActive()) {
                openProxySettings(SCREEN_PROXY)
            } else if (appConfig.isProxyEnabled()) {
                // show proxy settings
                if (appConfig.isWireGuardEnabled()) {
                    openProxySettings(SCREEN_WG)
                } else {
                    openProxySettings(SCREEN_PROXY)
                }
            } else {
                // do nothing
            }
        }

        b.bsHomeScreenRefreshIcon.setOnClickListener {
            // prevent overlapping refreshes
            if (!b.bsHomeScreenRefreshIcon.isEnabled) return@setOnClickListener
            b.bsHomeScreenRefreshIcon.isEnabled = false
            b.bsHomeScreenRefreshIcon.startAnimation(animation)
            logEvent(
                "refresh triggered",
                "User triggered refresh from home screen btmsht"
            )
            ui {
                var success = false
                try {
                    // run the refresh off the main thread; keep the spin animation
                    // visible for at least the minimum duration even if the refresh
                    // completes almost instantly
                    val start = System.currentTimeMillis()
                    ioCtx {
                        VpnController.refreshResolvers()
                        VpnController.refreshProxies()
                    }
                    // ensure the animation stays visible for a minimum duration
                    val elapsed = System.currentTimeMillis() - start
                    if (elapsed < MIN_ANIMATION_DURATION_MS) {
                        delay((MIN_ANIMATION_DURATION_MS - elapsed).milliseconds)
                    }
                    success = true
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_VPN, "err refreshing resolvers/proxies: ${e.message}", e)
                } finally {
                    if (isAdded && isVisible) {
                        b.bsHomeScreenRefreshIcon.isEnabled = true
                        b.bsHomeScreenRefreshIcon.clearAnimation()
                        if (success) {
                            Utilities.showToastUiCentered(
                                requireContext(),
                                getString(R.string.dc_refresh_toast),
                                Toast.LENGTH_SHORT
                            )
                        }
                    }
                }
            }
        }
    }

    private fun openProxySettings(screen: String) {
        val intent = if (screen == SCREEN_WG) {
            Logger.d(LOG_TAG_UI, "hmbs; invoke wireguard settings screen")
            Intent(requireContext(), WgMainActivity::class.java)
        } else {
            Logger.d(LOG_TAG_UI, "hmbs; invoke proxy settings screen")
            Intent(requireContext(), ProxySettingsActivity::class.java)
        }
        startActivity(intent)
        this.dismiss()
    }

    // disable dns and firewall mode, show user that vpn in lockdown mode indicator if needed
    private fun handleLockdownModeIfNeeded() {
        val isLockdown = VpnController.isVpnLockdown()
        val isProxyEnabled = appConfig.isProxyEnabled() || RpnProxyManager.isRpnActive()
        if (isLockdown) {
            b.bsHomeScreenVpnLockdownDesc.text = htmlToSpannedText(getString(R.string.hs_btm_sheet_lock_down))
            b.bsHomeScreenVpnLockdownDesc.visibility = View.VISIBLE
            b.bsHsDnsRl.alpha = 0.5f
            b.bsHsFirewallRl.alpha = 0.5f
            setRadioButtonsEnabled(false)
        } else if (isProxyEnabled) {
            b.bsHomeScreenVpnLockdownDesc.text = htmlToSpannedText(getString(R.string.mode_change_error_proxy_enabled))
            b.bsHomeScreenVpnLockdownDesc.visibility = View.VISIBLE
            b.bsHsDnsRl.alpha = 0.5f
            b.bsHsFirewallRl.alpha = 0.5f
            setRadioButtonsEnabled(false)
        } else {
            b.bsHomeScreenVpnLockdownDesc.visibility = View.GONE
            b.bsHsDnsRl.alpha = 1f
            b.bsHsFirewallRl.alpha = 1f
            setRadioButtonsEnabled(true)
        }
    }

    private fun setRadioButtonsEnabled(isEnabled: Boolean) {
        b.bsHsDnsRl.isEnabled = isEnabled
        b.bsHsFirewallRl.isEnabled = isEnabled
        b.bsHsDnsFirewallRl.isEnabled = isEnabled
        b.bsHomeScreenRadioDns.isEnabled = isEnabled
        b.bsHomeScreenRadioFirewall.isEnabled = isEnabled
        b.bsHomeScreenRadioDnsFirewall.isEnabled = isEnabled
    }

    private fun handleDnsMode(isChecked: Boolean) {
        if (!isChecked) return

        b.bsHomeScreenRadioFirewall.isChecked = false
        b.bsHomeScreenRadioDnsFirewall.isChecked = false
        modifyBraveMode(AppConfig.BraveMode.DNS.mode)
    }

    private fun handleFirewallMode(isChecked: Boolean) {
        if (!isChecked) return

        b.bsHomeScreenRadioDns.isChecked = false
        b.bsHomeScreenRadioDnsFirewall.isChecked = false
        modifyBraveMode(AppConfig.BraveMode.FIREWALL.mode)
    }

    private fun handleDnsFirewallMode(isChecked: Boolean) {
        if (!isChecked) return

        b.bsHomeScreenRadioDns.isChecked = false
        b.bsHomeScreenRadioFirewall.isChecked = false
        modifyBraveMode(AppConfig.BraveMode.DNS_FIREWALL.mode)
    }

    private fun updateUptime() {
        val uptimeMs = VpnController.uptimeMs()
        val protocols = VpnController.protocols()
        val ssid = VpnController.underlyingSsid()
        val netType = VpnController.netType()
        val now = System.currentTimeMillis()
        val mtu = VpnController.mtu().toString()

        val isSsidPermissionGranted = SsidPermissionManager.hasRequiredPermissions(requireContext()) && SsidPermissionManager.isLocationEnabled(requireContext())
        // returns a string describing 'time' as a time relative to 'now'
        val t =
            DateUtils.getRelativeTimeSpanString(
                now - uptimeMs,
                now,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )

        b.bsHomeScreenAppUptime.text =
            if (uptimeMs < INIT_TIME_MS) {
                b.bsHomeScreenAppUptime.visibility = View.GONE
                getString(R.string.hsf_downtime, t)
            } else {
                b.bsHomeScreenAppUptime.visibility = View.VISIBLE
                if (isSsidPermissionGranted && !ssid.isNullOrEmpty()) {
                    getString(R.string.hsf_uptime, t, protocols, netType, mtu, ssid)
                } else {
                    getString(R.string.hsf_uptime, t, protocols, netType, mtu, "").dropLast(9) + ")"
                }
            }
    }

    private fun modifyBraveMode(braveMode: Int) {
        io { appConfig.changeBraveMode(braveMode) }
    }

    private fun getConnectionStatus(): String {
        return when (appConfig.getBraveMode()) {
            AppConfig.BraveMode.DNS -> {
                getString(R.string.dns_explanation_dns_connected)
            }
            AppConfig.BraveMode.FIREWALL -> {
                getString(R.string.dns_explanation_firewall_connected)
            }
            else -> {
                getString(R.string.dns_explanation_connected)
            }
        }
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.UI_SETTING_CHANGED, Severity.LOW, msg, EventSource.UI, false, details)
    }

    private fun ui(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun ioCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.IO) { f() }
    }
}
