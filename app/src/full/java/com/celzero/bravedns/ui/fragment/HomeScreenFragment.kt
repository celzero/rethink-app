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
package com.celzero.bravedns.ui.fragment

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.TypedArray
import android.icu.text.CompactDecimalFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.databinding.FragmentHomeScreenBinding
import com.celzero.bravedns.service.*
import com.celzero.bravedns.ui.activity.AlertsActivity
import com.celzero.bravedns.ui.activity.AppListActivity
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_NAME
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_URL
import com.celzero.bravedns.ui.activity.CustomRulesActivity
import com.celzero.bravedns.ui.activity.DnsDetailActivity
import com.celzero.bravedns.ui.activity.FirewallActivity
import com.celzero.bravedns.ui.activity.NetworkLogsActivity
import com.celzero.bravedns.ui.activity.PauseActivity
import com.celzero.bravedns.ui.activity.ProxySettingsActivity
import com.celzero.bravedns.ui.activity.WgMainActivity
import com.celzero.bravedns.ui.bottomsheet.HomeScreenSettingBottomSheet
import com.celzero.bravedns.util.*
import com.celzero.bravedns.util.Constants.Companion.RETHINKDNS_SPONSOR_LINK
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_UI
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.UIUtils.openNetworkSettings
import com.celzero.bravedns.util.UIUtils.openVpnProfile
import com.celzero.bravedns.util.UIUtils.updateHtmlEncodedText
import com.celzero.bravedns.util.Utilities.delay
import com.celzero.bravedns.util.Utilities.getPrivateDnsMode
import com.celzero.bravedns.util.Utilities.isOtherVpnHasAlwaysOn
import com.celzero.bravedns.util.Utilities.isPrivateDnsActive
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.viewmodel.AlertsViewModel
import com.facebook.shimmer.Shimmer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.*
import java.util.concurrent.TimeUnit

class HomeScreenFragment : Fragment(R.layout.fragment_home_screen) {
    private val b by viewBinding(FragmentHomeScreenBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val alertsViewModel: AlertsViewModel by viewModel()

    private var isVpnActivated: Boolean = false

    private lateinit var themeNames: Array<String>
    private lateinit var startForResult: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionResult: ActivityResultLauncher<String>

    enum class ScreenType {
        DNS,
        FIREWALL,
        LOGS,
        RULES,
        PROXY,
        ALERTS,
        RETHINK,
        PROXY_WIREGUARD
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        registerForActivityResult()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeValues()
        initializeClickListeners()
        observeVpnState()
    }

    private fun initializeValues() {
        isVpnActivated = VpnController.state().activationRequested

        themeNames =
            arrayOf(
                getString(R.string.settings_theme_dialog_themes_1),
                getString(R.string.settings_theme_dialog_themes_2),
                getString(R.string.settings_theme_dialog_themes_3),
                getString(R.string.settings_theme_dialog_themes_4)
            )

        appConfig.getBraveModeObservable().postValue(appConfig.getBraveMode().mode)
        b.fhsCardLogsTv.text = getString(R.string.lbl_logs).replaceFirstChar(Char::titlecase)
    }

    private fun initializeClickListeners() {
        b.fhsCardFirewallLl.setOnClickListener {
            startFirewallActivity(FirewallActivity.Tabs.UNIVERSAL.screen)
        }

        b.fhsCardAppsLl.setOnClickListener { startAppsActivity() }

        b.fhsCardDnsLl.setOnClickListener {
            startDnsActivity(DnsDetailActivity.Tabs.CONFIGURE.screen)
        }

        b.homeFragmentBottomSheetIcon.setOnClickListener {
            b.homeFragmentBottomSheetIcon.isEnabled = false
            openBottomSheet()
            delay(TimeUnit.MILLISECONDS.toMillis(500), lifecycleScope) {
                b.homeFragmentBottomSheetIcon.isEnabled = true
            }
        }

        b.homeFragmentPauseIcon.setOnClickListener { handlePause() }

        b.fhsDnsOnOffBtn.setOnClickListener {
            handleMainScreenBtnClickEvent()
            delay(TimeUnit.MILLISECONDS.toMillis(500), lifecycleScope) {
                if (isAdded) {
                    b.homeFragmentBottomSheetIcon.isEnabled = true
                }
            }
        }

        appConfig.getBraveModeObservable().observe(viewLifecycleOwner) {
            updateCardsUi()
            syncDnsStatus()
        }

        b.fhsCardLogsLl.setOnClickListener {
            startActivity(ScreenType.LOGS, NetworkLogsActivity.Tabs.NETWORK_LOGS.screen)
        }

        b.fhsCardProxyLl.setOnClickListener {
            if (appConfig.isWireGuardEnabled()) {
                startActivity(ScreenType.PROXY_WIREGUARD)
            } else {
                startActivity(ScreenType.PROXY)
            }
        }

        b.fhsSponsor.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, RETHINKDNS_SPONSOR_LINK.toUri())
            startActivity(intent)
        }

        // comment out the below code to disable the alerts card (v0.5.5b)
        // b.fhsCardAlertsLl.setOnClickListener { startActivity(ScreenType.ALERTS) }
    }

    private fun handlePause() {
        if (!VpnController.hasTunnel()) {
            showToastUiCentered(
                requireContext(),
                requireContext().getString(R.string.hsf_pause_vpn_failure),
                Toast.LENGTH_SHORT
            )
            return
        }

        if (VpnController.isVpnLockdown()) {
            showToastUiCentered(
                requireContext(),
                getString(R.string.hsf_pause_lockdown_failure),
                Toast.LENGTH_SHORT
            )
            return
        }

        VpnController.pauseApp()
        persistentState.notificationActionType = NotificationActionType.PAUSE_STOP.action
        openPauseActivity()
    }

    private fun openPauseActivity() {
        val intent = Intent()
        intent.setClass(requireContext(), PauseActivity::class.java)
        startActivity(intent)
        requireActivity().finish()
    }

    private fun updateCardsUi() {
        if (isVpnActivated) {
            showActiveCards()
        } else {
            showDisabledCards()
        }
    }

    private fun observeVpnState() {
        persistentState.vpnEnabledLiveData.observe(viewLifecycleOwner) {
            isVpnActivated = it
            updateMainButtonUi()
            updateCardsUi()
            syncDnsStatus()
        }

        VpnController.connectionStatus.observe(viewLifecycleOwner) {
            // No need to handle states in Home screen fragment for pause state
            if (VpnController.isAppPaused()) return@observe

            syncDnsStatus()
            handleShimmer()
        }
    }

    private fun updateMainButtonUi() {
        if (isVpnActivated) {
            b.fhsDnsOnOffBtn.setBackgroundResource(R.drawable.home_screen_button_stop_bg)
            b.fhsDnsOnOffBtn.text = getString(R.string.hsf_stop_btn_state)
        } else {
            b.fhsDnsOnOffBtn.setBackgroundResource(R.drawable.home_screen_button_start_bg)
            b.fhsDnsOnOffBtn.text = getString(R.string.hsf_start_btn_state)
        }
    }

    private fun showDisabledCards() {
        disableFirewallCard()
        disabledDnsCard()
        disableAppsCard()
        disableProxyCard()
        disableLogsCard()
    }

    private fun showActiveCards() {
        enableFirewallCardIfNeeded()
        enableDnsCardIfNeeded()
        enableAppsCardIfNeeded()
        enableProxyCardIfNeeded()
        enableLogsCardIfNeeded()
        // comment out the below code to disable the alerts card (v0.5.5b)
        // enableAlertsCardIfNeeded()
    }

    private fun enableFirewallCardIfNeeded() {
        if (appConfig.getBraveMode().isFirewallActive()) {
            b.fhsCardFirewallUnivRules.visibility = View.INVISIBLE
            b.fhsCardFirewallUnivRulesCount.text =
                getString(
                    R.string.firewall_card_universal_rules,
                    persistentState.getUniversalRulesCount().toString()
                )
            b.fhsCardFirewallUnivRulesCount.isSelected = true
            b.fhsCardFirewallDomainRulesCount.visibility = View.VISIBLE
            b.fhsCardFirewallIpRulesCount.visibility = View.VISIBLE
            observeUniversalStates()
            observeCustomRulesCount()
        } else {
            disableFirewallCard()
            unobserveUniversalStates()
            unObserveCustomRulesCount()
        }
    }

    private fun enableAppsCardIfNeeded() {
        // apps screen can be accessible on all app modes.
        if (isVpnActivated) {
            observeAppStates()
        } else {
            disableAppsCard()
            unobserveAppStates()
        }
    }

    private fun enableDnsCardIfNeeded() {
        if (appConfig.getBraveMode().isDnsActive()) {
            observeDnsStates()
        } else {
            disabledDnsCard()
            unobserveDnsStates()
        }
    }

    private fun enableProxyCardIfNeeded() {
        if (isVpnActivated && !appConfig.getBraveMode().isDnsMode()) {
            if (persistentState.getProxyStatus() != -1) {
                observeProxyStates()
            } else {
                disableProxyCard()
            }
        } else {
            disableProxyCard()
            unobserveProxyStates()
        }
    }

    private fun enableLogsCardIfNeeded() {
        if (isVpnActivated) {
            observeLogsCount()
        } else {
            disableLogsCard()
            unObserveLogsCount()
        }
    }

    // comment out the below code to disable the alerts card (v0.5.5b)
    /* private fun enableAlertsCardIfNeeded() {
        if (isVpnActivated) {
            observeAlertsCount()
        } else {
            disableAlertsCard()
            unObserveAlertsCount()
        }
    }

    private fun disableAlertsCard() {
        b.fhsCardAlertsApps.text = getString(R.string.firewall_card_text_inactive)
        b.fhsCardAlertsApps.isSelected = true
    }

    private fun unObserveAlertsCount() {
        alertsViewModel.getBlockedAppsLogList().removeObservers(viewLifecycleOwner)
    }

    private fun observeAlertsCount() {
        alertsViewModel.getBlockedAppsLogList().observe(viewLifecycleOwner) {
            var message = ""
            it.forEach { apps ->
                if (it.indexOf(apps) > 2) return@forEach
                message += apps.appOrDnsName + ", "
            }
            if (message.isEmpty()) {
                b.fhsCardAlertsApps.text = "No alerts"
                b.fhsCardAlertsApps.isSelected = true
                return@observe
            }
            message = message.dropLastWhile { i -> i == ' ' }
            message = message.dropLastWhile { i -> i == ',' }
            b.fhsCardAlertsApps.text = "$message recently blocked"
            b.fhsCardAlertsApps.isSelected = true
        }
    } */

    private fun observeProxyStates() {
        persistentState.proxyStatus.observe(viewLifecycleOwner) {
            if (it != -1) {
                b.fhsCardProxyCount.text = getString(R.string.lbl_active)
                b.fhsCardOtherProxyCount.visibility = View.VISIBLE
                b.fhsCardOtherProxyCount.text = getString(it)
            } else {
                b.fhsCardProxyCount.text = getString(R.string.lbl_inactive)
                b.fhsCardOtherProxyCount.visibility = View.VISIBLE
                b.fhsCardOtherProxyCount.text = getString(R.string.lbl_disabled)
            }
        }
    }

    private fun unobserveProxyStates() {
        persistentState.proxyStatus.removeObservers(viewLifecycleOwner)
    }

    private fun disableLogsCard() {
        b.fhsCardNetworkLogsCount.text = getString(R.string.firewall_card_text_inactive)
        b.fhsCardDnsLogsCount.text = getString(R.string.lbl_disabled)
        b.fhsCardLogsDuration.visibility = View.GONE
    }

    private fun disableProxyCard() {
        b.fhsCardProxyCount.text = getString(R.string.lbl_inactive)
        b.fhsCardOtherProxyCount.visibility = View.VISIBLE
        b.fhsCardOtherProxyCount.text = getString(R.string.lbl_disabled)
    }

    private fun disableFirewallCard() {
        b.fhsCardFirewallUnivRules.visibility = View.VISIBLE
        b.fhsCardFirewallUnivRules.text = getString(R.string.lbl_disabled)
        b.fhsCardFirewallUnivRulesCount.visibility = View.VISIBLE
        b.fhsCardFirewallUnivRulesCount.text = getString(R.string.firewall_card_text_inactive)
        b.fhsCardFirewallDomainRulesCount.visibility = View.GONE
        b.fhsCardFirewallIpRulesCount.visibility = View.GONE
    }

    private fun disabledDnsCard() {
        b.fhsCardDnsLatency.text = getString(R.string.dns_card_latency_inactive)
        b.fhsCardDnsConnectedDns.text = getString(R.string.lbl_disabled)
        b.fhsCardDnsConnectedDns.isSelected = true
    }

    private fun disableAppsCard() {
        b.fhsCardAppsStatus.visibility = View.GONE
        b.fhsCardApps.text = getString(R.string.firewall_card_text_inactive)
    }

    /**
     * The observers are for the DNS cards, when the mode is set to DNS/DNS+Firewall. The observers
     * are register to update the UI in the home screen
     */
    private fun observeDnsStates() {
        persistentState.median.observe(viewLifecycleOwner) {
            // show status as very fast, fast, slow, and very slow based on the latency
            if (it in 0L..19L) {
                val string =
                    getString(
                            R.string.ci_desc,
                            getString(R.string.lbl_very),
                            getString(R.string.lbl_fast)
                        )
                        .replaceFirstChar(Char::titlecase)
                b.fhsCardDnsLatency.text = string
            } else if (it in 20L..50L) {
                b.fhsCardDnsLatency.text =
                    getString(R.string.lbl_fast).replaceFirstChar(Char::titlecase)
            } else if (it in 50L..100L) {
                b.fhsCardDnsLatency.text =
                    getString(R.string.lbl_slow).replaceFirstChar(Char::titlecase)
            } else {
                val string =
                    getString(
                            R.string.ci_desc,
                            getString(R.string.lbl_very),
                            getString(R.string.lbl_slow)
                        )
                        .replaceFirstChar(Char::titlecase)
                b.fhsCardDnsLatency.text = string
            }

            b.fhsCardDnsLatency.isSelected = true
        }

        appConfig.getConnectedDnsObservable().observe(viewLifecycleOwner) {
            b.fhsCardDnsConnectedDns.text = it
            b.fhsCardDnsConnectedDns.isSelected = true
        }
    }

    private fun observeLogsCount() {
        io {
            val time = appConfig.getLeastLoggedNetworkLogs()
            if (time == 0L) return@io

            val now = System.currentTimeMillis()
            // returns a string describing 'time' as a time relative to 'now'
            val t =
                DateUtils.getRelativeTimeSpanString(
                    time,
                    now,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
            uiCtx {
                if (!isAdded) return@uiCtx

                b.fhsCardLogsDuration.visibility = View.VISIBLE
                b.fhsCardLogsDuration.text = getString(R.string.logs_card_duration, t)
            }
        }

        appConfig.dnsLogsCount.observe(viewLifecycleOwner) {
            val count = formatDecimal(it)
            b.fhsCardDnsLogsCount.text = getString(R.string.logs_card_dns_count, count)
            b.fhsCardDnsLogsCount.isSelected = true
        }

        appConfig.networkLogsCount.observe(viewLifecycleOwner) {
            val count = formatDecimal(it)
            b.fhsCardNetworkLogsCount.text = getString(R.string.logs_card_network_count, count)
            b.fhsCardNetworkLogsCount.isSelected = true
        }
    }

    private fun formatDecimal(i: Long?): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            CompactDecimalFormat.getInstance(Locale.US, CompactDecimalFormat.CompactStyle.SHORT)
                .format(i)
        } else {
            i.toString()
        }
    }

    // unregister all dns related observers
    private fun unobserveDnsStates() {
        persistentState.median.removeObservers(viewLifecycleOwner)
        appConfig.getConnectedDnsObservable().removeObservers(viewLifecycleOwner)
    }

    private fun observeUniversalStates() {
        persistentState.universalRulesCount.observe(viewLifecycleOwner) {
            b.fhsCardFirewallUnivRulesCount.text =
                getString(R.string.firewall_card_universal_rules, it.toString())
            b.fhsCardFirewallUnivRulesCount.isSelected = true
        }
    }

    private fun observeCustomRulesCount() {
        // observer for ips count
        IpRulesManager.getCustomIpsLiveData().observe(viewLifecycleOwner) {
            b.fhsCardFirewallIpRulesCount.text =
                getString(R.string.apps_card_ips_count, it.toString())
        }

        DomainRulesManager.getUniversalCustomDomainCount().observe(viewLifecycleOwner) {
            b.fhsCardFirewallDomainRulesCount.text =
                getString(R.string.rules_card_domain_count, it.toString())
        }
    }

    private fun unObserveLogsCount() {
        appConfig.dnsLogsCount.removeObservers(viewLifecycleOwner)
        appConfig.networkLogsCount.removeObservers(viewLifecycleOwner)
    }

    private fun unObserveCustomRulesCount() {
        DomainRulesManager.getUniversalCustomDomainCount().removeObservers(viewLifecycleOwner)
        IpRulesManager.getCustomIpsLiveData().removeObservers(viewLifecycleOwner)
    }

    // remove firewall card related observers
    private fun unobserveUniversalStates() {
        persistentState.universalRulesCount.removeObservers(viewLifecycleOwner)
    }

    /**
     * The observers for the firewall card in the home screen, will be calling this method when the
     * VPN is active and the mode is set to either Firewall or DNS+Firewall.
     */
    private fun observeAppStates() {
        FirewallManager.getApplistObserver().observe(viewLifecycleOwner) {
            try {
                val copy: Collection<AppInfo>
                // adding synchronized block, found a case of concurrent modification
                // exception that happened once when trying to filter the received object (t).
                // creating a copy of the received value in a synchronized block.
                synchronized(it) { copy = mutableListOf<AppInfo>().apply { addAll(it) }.toList() }
                val blockedApps =
                    copy.filter { a ->
                        a.connectionStatus != FirewallManager.ConnectionStatus.ALLOW.id
                    }
                val whiteListApps =
                    copy.filter { a ->
                        a.firewallStatus == FirewallManager.FirewallStatus.BYPASS_UNIVERSAL.id ||
                            a.firewallStatus ==
                                FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL.id
                    }
                val excludedApps =
                    copy.filter { a ->
                        a.firewallStatus == FirewallManager.FirewallStatus.EXCLUDE.id
                    }
                val isolateApps =
                    copy.filter { a ->
                        a.firewallStatus == FirewallManager.FirewallStatus.ISOLATE.id
                    }
                b.fhsCardAppsStatus.visibility = View.VISIBLE
                b.fhsCardAppsStatus.text = copy.count().toString()
                // getString(R.string.firewall_card_status_active, copy.count().toString())
                b.fhsCardApps.text =
                    getString(
                        R.string.firewall_card_text_active,
                        blockedApps.count().toString(),
                        whiteListApps.count().toString(),
                        excludedApps.count().toString(),
                        isolateApps.count().toString()
                    )
                b.fhsCardApps.isSelected = true
            } catch (e: Exception) { // NoSuchElementException, ConcurrentModification
                Log.e(LOG_TAG_VPN, "error retrieving value from appInfos observer ${e.message}", e)
            }
        }
    }

    // unregister all firewall related observers
    private fun unobserveAppStates() {
        FirewallManager.getApplistObserver().removeObservers(viewLifecycleOwner)
    }

    private fun handleMainScreenBtnClickEvent() {

        if (handleAlwaysOnVpn()) {
            return
        }

        b.fhsDnsOnOffBtn.isEnabled = false
        delay(TimeUnit.MILLISECONDS.toMillis(500), lifecycleScope) {
            if (isAdded) {
                b.fhsDnsOnOffBtn.isEnabled = true
            }
        }

        if (isVpnActivated) {
            stopVpnService()
        } else {
            prepareAndStartVpn()
        }
    }

    private fun showAlwaysOnStopDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext())

        builder.setTitle(R.string.always_on_dialog_stop_heading)
        if (VpnController.isVpnLockdown()) {
            builder.setMessage(
                updateHtmlEncodedText(getString(R.string.always_on_dialog_lockdown_stop_message))
            )
        } else {
            builder.setMessage(R.string.always_on_dialog_stop_message)
        }

        builder.setCancelable(false)
        builder.setPositiveButton(R.string.always_on_dialog_positive) { _, _ -> stopVpnService() }

        builder.setNegativeButton(R.string.lbl_cancel) { _, _ ->
            // no-op
        }

        builder.setNeutralButton(R.string.always_on_dialog_neutral) { _, _ ->
            openVpnProfile(requireContext())
        }

        builder.create().show()
    }

    private fun openBottomSheet() {
        val bottomSheetFragment = HomeScreenSettingBottomSheet()
        bottomSheetFragment.show(parentFragmentManager, bottomSheetFragment.tag)
    }

    private fun handleAlwaysOnVpn(): Boolean {
        if (isOtherVpnHasAlwaysOn(requireContext())) {
            showAlwaysOnDisableDialog()
            return true
        }

        // if always-on is enabled and vpn is activated, show the dialog to stop the vpn #799
        if (VpnController.isAlwaysOn(requireContext()) && isVpnActivated) {
            showAlwaysOnStopDialog()
            return true
        }

        return false
    }

    private fun showAlwaysOnDisableDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(R.string.always_on_dialog_heading)
        builder.setMessage(R.string.always_on_dialog)
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.always_on_dialog_positive_btn) { _, _ ->
            openVpnProfile(requireContext())
        }

        builder.setNegativeButton(R.string.lbl_cancel) { _, _ ->
            // no-op
        }

        builder.create().show()
    }

    override fun onResume() {
        super.onResume()
        handleShimmer()
        maybeAutoStartVpn()
        updateCardsUi()
        syncDnsStatus()
        handleLockdownModeIfNeeded()
    }

    /**
     * Issue fix - https://github.com/celzero/rethink-app/issues/57 When the application
     * crashes/updates it goes into red waiting state. This causes confusion to the users also
     * requires click of START button twice to start the app. FIX : The check for the controller
     * state. If persistence state has vpn enabled and the VPN is not connected then the start will
     * be initiated.
     */
    private fun maybeAutoStartVpn() {
        if (isVpnActivated && !VpnController.isOn()) {
            Log.i(LOG_TAG_VPN, "start VPN (previous state)")
            prepareAndStartVpn()
        }
    }

    // set the app mode to dns+firewall mode when vpn in lockdown state
    private fun handleLockdownModeIfNeeded() {
        if (VpnController.isVpnLockdown() && !appConfig.getBraveMode().isDnsFirewallMode()) {
            io { appConfig.changeBraveMode(AppConfig.BraveMode.DNS_FIREWALL.mode) }
        }
    }

    private fun handleShimmer() {
        if (!isVpnActivated) {
            startShimmer()
            return
        }

        if (VpnController.hasStarted()) {
            stopShimmer()
        }
    }

    override fun onPause() {
        super.onPause()
        stopShimmer()
    }

    private fun startDnsActivity(screenToLoad: Int) {
        if (isPrivateDnsActive(requireContext())) {
            showPrivateDnsDialog()
            return
        }

        if (isRethinkDnsActive()) {
            // no need to pass value in intent, as default load to Rethink remote
            startActivity(ScreenType.RETHINK, screenToLoad)
            return
        }

        startActivity(ScreenType.DNS, screenToLoad)
        return
    }

    private fun isRethinkDnsActive(): Boolean {
        val dns = appConfig.getDnsType()
        return dns.isRethinkRemote()
    }

    private fun showPrivateDnsDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(R.string.private_dns_dialog_heading)
        builder.setMessage(R.string.private_dns_dialog_desc)
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.private_dns_dialog_positive) { _, _ ->
            openNetworkSettings(requireContext())
        }

        builder.setNegativeButton(R.string.lbl_dismiss) { _, _ ->
            // no-op
        }
        builder.create().show()
    }

    private fun startFirewallActivity(screenToLoad: Int) {
        startActivity(ScreenType.FIREWALL, screenToLoad)
        return
    }

    private fun startAppsActivity() {
        if (DEBUG)
            Log.d(LOG_TAG_VPN, "Status : $isVpnActivated , BraveMode: ${appConfig.getBraveMode()}")

        // no need to check for app modes to open this activity
        // one use case: https://github.com/celzero/rethink-app/issues/611
        val intent = Intent(requireContext(), AppListActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        startActivity(intent)
    }

    private fun startActivity(type: ScreenType, screenToLoad: Int = 0) {
        val intent =
            when (type) {
                ScreenType.DNS -> Intent(requireContext(), DnsDetailActivity::class.java)
                ScreenType.FIREWALL -> Intent(requireContext(), FirewallActivity::class.java)
                ScreenType.LOGS -> Intent(requireContext(), NetworkLogsActivity::class.java)
                ScreenType.RULES -> Intent(requireContext(), CustomRulesActivity::class.java)
                ScreenType.PROXY -> Intent(requireContext(), ProxySettingsActivity::class.java)
                ScreenType.ALERTS -> Intent(requireContext(), AlertsActivity::class.java)
                ScreenType.RETHINK ->
                    Intent(requireContext(), ConfigureRethinkBasicActivity::class.java)
                ScreenType.PROXY_WIREGUARD -> Intent(requireContext(), WgMainActivity::class.java)
            }
        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        if (type == ScreenType.RETHINK) {
            io {
                val url = appConfig.getRemoteRethinkEndpoint()?.url
                val name = appConfig.getRemoteRethinkEndpoint()?.name
                intent.putExtra(RETHINK_BLOCKLIST_NAME, name)
                intent.putExtra(RETHINK_BLOCKLIST_URL, url)
                uiCtx { startActivity(intent) }
            }
        } else {
            intent.putExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, screenToLoad)
            startActivity(intent)
        }
    }

    private fun prepareAndStartVpn() {
        if (prepareVpnService()) {
            startVpnService()
        }
    }

    private fun stopShimmer() {
        if (!b.shimmerViewContainer1.isShimmerStarted) return

        b.shimmerViewContainer1.stopShimmer()
    }

    private fun startShimmer() {
        val builder = Shimmer.AlphaHighlightBuilder()
        builder.setDuration(2000)
        builder.setBaseAlpha(0.85f)
        builder.setDropoff(1f)
        builder.setHighlightAlpha(0.35f)
        b.shimmerViewContainer1.setShimmer(builder.build())
        b.shimmerViewContainer1.startShimmer()
    }

    private fun stopVpnService() {
        VpnController.stop(requireContext())
    }

    private fun startVpnService() {
        // runtime permission for notification (Android 13)
        getNotificationPermissionIfNeeded()
        VpnController.start(requireContext())
    }

    private fun getNotificationPermissionIfNeeded() {
        if (!Utilities.isAtleastT()) {
            // notification permission is needed for version 13 or above
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // notification permission is granted to the app, do nothing
            return
        }

        if (!persistentState.shouldRequestNotificationPermission) {
            // user rejected notification permission
            Log.w(LOG_TAG_VPN, "User rejected notification permission for the app")
            return
        }

        notificationPermissionResult.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Throws(ActivityNotFoundException::class)
    private fun prepareVpnService(): Boolean {
        val prepareVpnIntent: Intent? =
            try {
                // In some cases, the intent used to register the VPN service does not open the
                // application (from Android settings). This happens in some of the Android
                // versions.
                // VpnService.prepare() is now registered with requireContext() instead of context.
                // Issue #469
                VpnService.prepare(requireContext())
            } catch (e: NullPointerException) {
                // This exception is not mentioned in the documentation, but it has been encountered
                // users and also by other developers, e.g.
                // https://stackoverflow.com/questions/45470113.
                Log.e(LOG_TAG_VPN, "Device does not support system-wide VPN mode.", e)
                return false
            }
        // If the VPN.prepare() is not null, then the first time VPN dialog is shown, Show info
        // dialog before that.
        if (prepareVpnIntent != null) {
            showFirstTimeVpnDialog(prepareVpnIntent)
            return false
        }
        return true
    }

    private fun showFirstTimeVpnDialog(prepareVpnIntent: Intent) {
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(R.string.hsf_vpn_dialog_header)
        builder.setMessage(R.string.hsf_vpn_dialog_message)
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.lbl_proceed) { _, _ ->
            startForResult.launch(prepareVpnIntent)
        }

        builder.setNegativeButton(R.string.lbl_cancel) { _, _ ->
            // no-op
        }
        builder.create().show()
    }

    private fun registerForActivityResult() {
        startForResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                result: ActivityResult ->
                when (result.resultCode) {
                    Activity.RESULT_OK -> {
                        startVpnService()
                    }
                    Activity.RESULT_CANCELED -> {
                        showToastUiCentered(
                            requireContext(),
                            getString(R.string.hsf_vpn_prepare_failure),
                            Toast.LENGTH_LONG
                        )
                    }
                    else -> {
                        stopVpnService()
                    }
                }
            }

        // Sets up permissions request launcher.
        notificationPermissionResult =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                if (it) {
                    Log.i(LOG_TAG_UI, "User accepted notification permission")
                } else {
                    persistentState.shouldRequestNotificationPermission = false
                    Log.w(LOG_TAG_UI, "User rejected notification permission")
                    Snackbar.make(
                            requireActivity().findViewById<View>(android.R.id.content).rootView,
                            getString(R.string.hsf_notification_permission_failure),
                            Snackbar.LENGTH_LONG
                        )
                        .show()
                }
            }
    }

    // Sets the UI DNS status on/off.
    private fun syncDnsStatus() {
        val status = VpnController.state()

        // Change status and explanation text
        var statusId: Int
        var colorId: Int
        // val explanationId: Int
        val privateDnsMode: Utilities.PrivateDnsMode = getPrivateDnsMode(requireContext())

        if (appConfig.getBraveMode().isFirewallMode()) {
            status.connectionState = BraveVPNService.State.WORKING
        }

        if (status.on) {
            colorId = fetchTextColor(R.color.accentGood)
            statusId =
                when {
                    status.connectionState == null -> {
                        // app's waiting here, but such a status is a cause for confusion
                        // R.string.status_waiting
                        R.string.status_protected
                    }
                    status.connectionState === BraveVPNService.State.NEW -> {
                        // app's starting here, but such a status confuses users
                        // R.string.status_starting
                        R.string.status_protected
                    }
                    status.connectionState === BraveVPNService.State.WORKING -> {
                        R.string.status_protected
                    }
                    status.connectionState === BraveVPNService.State.APP_ERROR -> {
                        colorId = fetchTextColor(R.color.accentBad)
                        R.string.status_app_error
                    }
                    status.connectionState === BraveVPNService.State.DNS_ERROR -> {
                        colorId = fetchTextColor(R.color.accentBad)
                        R.string.status_dns_error
                    }
                    status.connectionState === BraveVPNService.State.DNS_SERVER_DOWN -> {
                        colorId = fetchTextColor(R.color.accentBad)
                        R.string.status_dns_server_down
                    }
                    status.connectionState === BraveVPNService.State.NO_INTERNET -> {
                        colorId = fetchTextColor(R.color.accentBad)
                        R.string.status_no_internet
                    }
                    else -> {
                        colorId = fetchTextColor(R.color.accentBad)
                        R.string.status_failing
                    }
                }
        } else if (isVpnActivated) {
            colorId = fetchTextColor(R.color.accentBad)
            statusId = R.string.status_waiting
        } else if (isAnotherVpnActive()) {
            colorId = fetchTextColor(R.color.accentBad)
            statusId = R.string.status_exposed
        } else {
            colorId = fetchTextColor(R.color.accentBad)
            statusId =
                when (privateDnsMode) {
                    Utilities.PrivateDnsMode.STRICT -> R.string.status_strict
                    else -> R.string.status_exposed
                }
        }

        if (statusId == R.string.status_protected) {
            if (appConfig.getBraveMode().isDnsMode() && isPrivateDnsActive(requireContext())) {
                statusId = R.string.status_protected_with_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (appConfig.getBraveMode().isDnsMode()) {
                statusId = R.string.status_protected
            } else if (appConfig.isOrbotProxyEnabled() && isPrivateDnsActive(requireContext())) {
                statusId = R.string.status_protected_with_tor_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (appConfig.isOrbotProxyEnabled()) {
                statusId = R.string.status_protected_with_tor
            } else if (
                (appConfig.isCustomSocks5Enabled() && appConfig.isCustomHttpProxyEnabled()) &&
                    isPrivateDnsActive(requireContext())
            ) { // SOCKS5 + Http + PrivateDns
                statusId = R.string.status_protected_with_proxy_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (appConfig.isCustomSocks5Enabled() && appConfig.isCustomHttpProxyEnabled()) {
                statusId = R.string.status_protected_with_proxy
            } else if (appConfig.isCustomSocks5Enabled() && isPrivateDnsActive(requireContext())) {
                statusId = R.string.status_protected_with_socks5_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (
                appConfig.isCustomHttpProxyEnabled() && isPrivateDnsActive(requireContext())
            ) {
                statusId = R.string.status_protected_with_http_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (appConfig.isCustomHttpProxyEnabled()) {
                statusId = R.string.status_protected_with_http
            } else if (appConfig.isCustomSocks5Enabled()) {
                statusId = R.string.status_protected_with_socks5
            } else if (isPrivateDnsActive(requireContext())) {
                statusId = R.string.status_protected_with_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (appConfig.isWireGuardEnabled() && isPrivateDnsActive(requireContext())) {
                statusId = R.string.status_protected_with_wg_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (appConfig.isWireGuardEnabled()) {
                statusId = R.string.status_protected_with_wg
            }
        }

        b.fhsProtectionLevelTxt.setTextColor(colorId)
        b.fhsProtectionLevelTxt.setText(statusId)
    }

    private fun isAnotherVpnActive(): Boolean {
        val connectivityManager =
            requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities =
            connectivityManager.getNetworkCapabilities(activeNetwork)
                ?: // It's not clear when this can happen, but it has occurred for at least one
                // user.
                return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun fetchTextColor(attr: Int): Int {
        val attributeFetch =
            if (attr == R.color.accentGood) {
                R.attr.accentGood
            } else if (attr == R.color.accentBad) {
                R.attr.accentBad
            } else if (attr == R.color.primaryLightColorText) {
                R.attr.primaryLightColorText
            } else if (attr == R.color.secondaryText) {
                R.attr.invertedPrimaryTextColor
            } else if (attr == R.color.primaryText) {
                R.attr.primaryTextColor
            } else if (attr == R.color.primaryTextLight) {
                R.attr.primaryTextColor
            } else {
                R.attr.accentGood
            }
        val typedValue = TypedValue()
        val a: TypedArray =
            requireContext().obtainStyledAttributes(typedValue.data, intArrayOf(attributeFetch))
        val color = a.getColor(0, 0)
        a.recycle()
        return color
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
