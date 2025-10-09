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

import Logger
import Logger.LOG_TAG_UI
import Logger.LOG_TAG_VPN
import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.TypedArray
import android.icu.text.CompactDecimalFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.databinding.FragmentHomeScreenBinding
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.DnsLogTracker
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.WG_HANDSHAKE_TIMEOUT
import com.celzero.bravedns.service.WireguardManager.WG_UPTIME_THRESHOLD
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
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.RETHINKDNS_SPONSOR_LINK
import com.celzero.bravedns.util.NotificationActionType
import com.celzero.bravedns.util.UIUtils.htmlToSpannedText
import com.celzero.bravedns.util.UIUtils.openNetworkSettings
import com.celzero.bravedns.util.UIUtils.openUrl
import com.celzero.bravedns.util.UIUtils.openVpnProfile
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.delay
import com.celzero.bravedns.util.Utilities.getPrivateDnsMode
import com.celzero.bravedns.util.Utilities.isAtleastN
import com.celzero.bravedns.util.Utilities.isAtleastP
import com.celzero.bravedns.util.Utilities.isAtleastR
import com.celzero.bravedns.util.Utilities.isOtherVpnHasAlwaysOn
import com.celzero.bravedns.util.Utilities.isPrivateDnsActive
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.firestack.backend.Backend
import com.facebook.shimmer.Shimmer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.waseemsabir.betterypermissionhelper.BatteryPermissionHelper
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.util.Locale
import java.util.concurrent.TimeUnit

class HomeScreenFragment : Fragment(R.layout.fragment_home_screen) {
    private val b by viewBinding(FragmentHomeScreenBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val workScheduler by inject<WorkScheduler>()

    private var isVpnActivated: Boolean = false

    private lateinit var themeNames: Array<String>
    private lateinit var startForResult: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionResult: ActivityResultLauncher<String>

    private val batteryPermissionHelper = BatteryPermissionHelper.getInstance()

    companion object {
        private const val TAG = "HSFragment"
        private const val GRACE_DIALOG_REMIND_AFTER_DAYS = 1 // days to remind again
    }

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
        Logger.v(LOG_TAG_UI, "$TAG: init view in home screen fragment")
        initializeValues()
        initializeClickListeners()
        observeVpnState()
    }

    private fun initializeValues() {
        themeNames =
            arrayOf(
                getString(R.string.settings_theme_dialog_themes_1),
                getString(R.string.settings_theme_dialog_themes_2),
                getString(R.string.settings_theme_dialog_themes_3),
                getString(R.string.settings_theme_dialog_themes_4)
            )

        appConfig.getBraveModeObservable().postValue(appConfig.getBraveMode().mode)
        b.fhsCardLogsTv.text = getString(R.string.lbl_logs).replaceFirstChar(Char::titlecase)

        // do not show the sponsor card if the rethink plus is enabled
        /*if (RpnProxyManager.isRpnEnabled()) {
            b.fhsSponsor.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_rethink_plus_sparkle))
            b.fhsSponsor.visibility = View.VISIBLE
        } else {
            b.fhsSponsor.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_heart_accent))
            b.fhsSponsor.visibility = View.VISIBLE
        }*/
        b.fhsSponsor.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_heart_accent))
        b.fhsSponsor.visibility = View.VISIBLE
    }

    private fun initializeClickListeners() {
        b.fhsCardFirewallLl.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: click event on firewall card")
            startFirewallActivity(FirewallActivity.Tabs.UNIVERSAL.screen)
        }

        b.fhsCardAppsCv.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: click event on apps card")
            startAppsActivity()
        }

        b.fhsCardDnsLl.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: click event on dns card")
            startDnsActivity(DnsDetailActivity.Tabs.CONFIGURE.screen)
        }

        b.homeFragmentBottomSheetIcon.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: click event on bottom sheet icon")
            b.homeFragmentBottomSheetIcon.isEnabled = false
            openBottomSheet()
            delay(TimeUnit.MILLISECONDS.toMillis(500), lifecycleScope) {
                b.homeFragmentBottomSheetIcon.isEnabled = true
            }
        }

        b.homeFragmentPauseIcon.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: click event on pause icon")
            handlePause()
        }

        b.fhsDnsOnOffBtn.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: click event on main button")
            handleMainScreenBtnClickEvent()
            delay(TimeUnit.MILLISECONDS.toMillis(500), lifecycleScope) {
                if (isAdded) {
                    b.homeFragmentBottomSheetIcon.isEnabled = true
                }
            }
        }

        appConfig.getBraveModeObservable().observe(viewLifecycleOwner) {
            Logger.v(LOG_TAG_UI, "$TAG: brave mode changed to $it")
            updateCardsUi()
            syncDnsStatus()
        }

        b.fhsCardLogsLl.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: click event on logs card")
            startActivity(ScreenType.LOGS, NetworkLogsActivity.Tabs.NETWORK_LOGS.screen)
        }

        b.fhsCardProxyLl.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: click event on proxy card")
            if (appConfig.isWireGuardEnabled()) {
                startActivity(ScreenType.PROXY_WIREGUARD)
            } else {
                startActivity(ScreenType.PROXY)
            }
        }

        b.fhsSponsor.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: click event on sponsor card")
            /*if (RpnProxyManager.isRpnEnabled()) {
                Logger.d(LOG_TAG_UI, "RPlus is enabled, not showing sponsor dialog")
                return@setOnClickListener
            }*/
            promptForAppSponsorship()
        }

        b.fhsSponsorBottom.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: click event on sponsor card")
            /*if (RpnProxyManager.isRpnEnabled()) {
                Logger.d(LOG_TAG_UI, "RPlus is enabled, not showing sponsor dialog")
                return@setOnClickListener
            }*/
            promptForAppSponsorship()
        }

        b.fhsTitleRethink.setOnClickListener {
            Logger.v(LOG_TAG_UI, "$TAG: click event on rethink card")
            /*if (RpnProxyManager.isRpnEnabled()) {
                Logger.d(LOG_TAG_UI, "RPlus is enabled, not showing sponsor dialog")
                return@setOnClickListener
            }*/
            promptForAppSponsorship()
        }

        // comment out the below code to disable the alerts card (v0.5.5b)
        // b.fhsCardAlertsLl.setOnClickListener { startActivity(ScreenType.ALERTS) }
    }

    private fun promptForAppSponsorship() {
        val installTime = requireContext().packageManager.getPackageInfo(
            requireContext().packageName,
            0
        ).firstInstallTime
        val timeDiff = System.currentTimeMillis() - installTime
        // convert it to month
        val days = (timeDiff / (1000 * 60 * 60 * 24)).toDouble()
        val month = days / 30
        // multiply the month with 0.60$ + 0.20$ for every month
        val amount = month * (0.60 + 0.20)
        Logger.d(LOG_TAG_UI, "Sponsor: $installTime, days/month: $days/$month, amount: $amount")
        val alertBuilder = MaterialAlertDialogBuilder(requireContext())
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_sponsor_info, null)
        alertBuilder.setView(dialogView)
        alertBuilder.setCancelable(true)

        val amountTxt = dialogView.findViewById<AppCompatTextView>(R.id.dialog_sponsor_info_amount)
        val usageTxt = dialogView.findViewById<AppCompatTextView>(R.id.dialog_sponsor_info_usage)
        val sponsorBtn = dialogView.findViewById<AppCompatTextView>(R.id.dialog_sponsor_info_sponsor)

        val dialog = alertBuilder.create()

        val msg = getString(R.string.sponser_dialog_usage_msg, days.toInt().toString(), "%.2f".format(amount))
        amountTxt.text = getString(R.string.two_argument_no_space, getString(R.string.symbol_dollar), "%.2f".format(amount))
        usageTxt.text = msg

        sponsorBtn.setOnClickListener {
            openUrl(requireContext(), RETHINKDNS_SPONSOR_LINK)
        }

        dialog.show()
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
            val isAnyProxyEnabled = appConfig.isProxyEnabled()
            if (isAnyProxyEnabled) {
                observeProxyStates()
            } else {
                disableProxyCard()
            }
        } else {
            disableProxyCard()
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
    private var proxyStateListenerJob: Job? = null

    private fun observeProxyStates() {
        persistentState.getProxyStatus().distinctUntilChanged().observe(viewLifecycleOwner) {
            Logger.vv(LOG_TAG_UI, "$TAG proxy state changed to $it")
            if (it != -1) {
                if (proxyStateListenerJob?.isActive == true) {
                    Logger.vv(LOG_TAG_UI, "$TAG cancel prev proxy state listener job")
                    proxyStateListenerJob?.cancel()
                    proxyStateListenerJob = null
                }
                proxyStateListenerJob = ui("proxyStates") {
                    while (isVisible && isAdded) {
                        updateUiWithProxyStates(it)
                        kotlinx.coroutines.delay(1500L)
                    }
                    proxyStateListenerJob?.cancel()
                }
            } else {
                b.fhsCardProxyCount.text = getString(R.string.lbl_inactive)
                b.fhsCardOtherProxyCount.visibility = View.VISIBLE
                b.fhsCardOtherProxyCount.text = getString(R.string.lbl_disabled)
            }
        }
    }

    private fun updateUiWithProxyStates(resId: Int) {
        if (
            !viewLifecycleOwner
                .lifecycle
                .currentState
                .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        ) {
            proxyStateListenerJob?.cancel()
            return
        }

        if (!isVpnActivated) {
            disableProxyCard()
            return
        }

        // get proxy type from app config
        val proxyType = AppConfig.ProxyType.of(appConfig.getProxyType())

        if (proxyType.isProxyTypeWireguard()) {
            io {
                val proxies = WireguardManager.getActiveConfigs()
                var active = 0
                var failing = 0
                var idle = 0
                val now = System.currentTimeMillis()
                Logger.v(LOG_TAG_UI, "$TAG wg active proxies: ${proxies.size}")
                
                // If no proxies are configured but WireGuard is enabled, show appropriate message
                if (proxies.isEmpty()) {
                    uiCtx {
                        if (!isVisible || !isAdded) return@uiCtx
                        b.fhsCardOtherProxyCount.visibility = View.VISIBLE
                        b.fhsCardProxyCount.text = getString(R.string.lbl_inactive)
                        b.fhsCardOtherProxyCount.text = getString(resId)
                    }
                    return@io
                }
                
                proxies.forEach {
                    val proxyId = "${ProxyManager.ID_WG_BASE}${it.getId()}"
                    Logger.vv(LOG_TAG_UI, "$TAG init stats check for $proxyId")
                    val stats = VpnController.getProxyStats(proxyId)
                    val status = VpnController.getProxyStatusById(proxyId).first
                    // check for dns status of the wg if splitDns is enabled
                    val dnsStats = if (isSplitDns()) {
                        VpnController.getDnsStatus(proxyId)
                    } else {
                        null
                    }

                    // Handle paused state as idle (TPU is the pause status)
                    if (status == Backend.TPU) {
                        idle++ // paused proxies are counted as idle
                        return@forEach
                    }

                    if (stats == null) {
                        failing++
                        return@forEach
                    }
                    if (dnsStats != null && isDnsError(dnsStats)) {
                        failing++
                        return@forEach
                    } // else proceed

                    val lastOk = stats.lastOK
                    val since = stats.since
                    // Only mark as failing if it has been running for a while without success
                    if (now - since > WG_UPTIME_THRESHOLD && lastOk == 0L) {
                        failing++
                        return@forEach
                    }
                    
                    if (status != null) {
                        // consider starting and up as active
                        when (status) {
                            Backend.TZZ -> {
                                // For TZZ (idle), be more lenient - consider it as idle if it has had any connection
                                if (stats.lastOK > 0L) {
                                    // Has had successful handshake before, consider it idle
                                    idle++
                                } else if (now - since < WG_UPTIME_THRESHOLD) {
                                    // Still in startup period, give it more time
                                    idle++
                                } else {
                                    // No recent handshake and been running long enough, consider it failing
                                    failing++
                                }
                            }
                            Backend.TOK -> {
                                // For TOK (connected), check if it's recently active
                                if (stats.lastOK > 0L && (now - stats.lastOK < WG_HANDSHAKE_TIMEOUT)) {
                                    active++
                                } else if (stats.lastOK > 0L) {
                                    // Has connected before but not recently, consider idle
                                    idle++
                                } else if (now - since < WG_UPTIME_THRESHOLD) {
                                    // Still in startup period, consider as starting (active)
                                    active++
                                } else {
                                    failing++
                                }
                            }
                            Backend.TUP -> {
                                // Starting state - always consider as active (transitioning)
                                active++
                            }
                            Backend.TNT -> {
                                // Waiting state - consider as idle if recently started, otherwise failing
                                if (now - since < WG_UPTIME_THRESHOLD) {
                                    idle++
                                } else {
                                    failing++
                                }
                            }
                            else -> {
                                // Unknown or error states
                                failing++
                            }
                        }
                    } else {
                        // No status available, but if we have recent stats, don't immediately mark as failing
                        if (stats.lastOK > 0L && (now - stats.lastOK < WG_HANDSHAKE_TIMEOUT)) {
                            idle++
                        } else {
                            failing++
                        }
                    }
                }
                uiCtx {
                    if (!isVisible || !isAdded) return@uiCtx
                    b.fhsCardOtherProxyCount.visibility = View.VISIBLE
                    var text = ""
                    // show as 3 active 1 failing 1 idle, prioritize showing something if any proxy exists
                    if (active > 0) {
                        text = getString(
                            R.string.two_argument_space,
                            active.toString(),
                            getString(R.string.lbl_active)
                        )
                    }
                    if (failing > 0) {
                        text += if (text.isNotEmpty()) {
                            "\n"
                        } else {
                            ""
                        }
                        text += getString(
                            R.string.two_argument_space,
                            failing.toString(),
                            getString(R.string.status_failing).replaceFirstChar(Char::titlecase)
                        )
                    }
                    if (idle > 0) {
                        text += if (text.isNotEmpty()) {
                            "\n"
                        } else {
                            ""
                        }
                        text += getString(
                            R.string.two_argument_space,
                            idle.toString(),
                            getString(R.string.lbl_idle).replaceFirstChar(Char::titlecase)
                        )
                    }
                    Logger.v(LOG_TAG_UI, "$TAG overall wg proxy status: $text, proxies: ${proxies.size}, active: $active, failing: $failing, idle: $idle")
                    
                    // If we have proxies but no status text, something went wrong - show a fallback
                    if (text.isEmpty() && proxies.isNotEmpty()) {
                        b.fhsCardProxyCount.text = getString(R.string.lbl_active)
                        Logger.w(LOG_TAG_UI, "$TAG proxy status empty but proxies exist, showing fallback active status")
                    } else if (text.isEmpty()) {
                        b.fhsCardProxyCount.text = getString(R.string.lbl_inactive)
                    } else {
                        b.fhsCardProxyCount.text = text
                    }
                }
            }
        } else {
            // For non-WireGuard proxies, show active if any proxy is enabled
            if (appConfig.isProxyEnabled()) {
                b.fhsCardProxyCount.text = getString(R.string.lbl_active)
            } else {
                b.fhsCardProxyCount.text = getString(R.string.lbl_inactive)
            }
        }
        b.fhsCardOtherProxyCount.visibility = View.VISIBLE
        b.fhsCardOtherProxyCount.text = getString(resId)
    }

    private fun isSplitDns(): Boolean {
        // by default, the split dns is enabled for android R and above, as we know the app
        // which sends dns queries
        if (isAtleastR()) return true

        return persistentState.splitDns
    }

    private fun isDnsError(statusId: Long?): Boolean {
        if (statusId == null) return true

        val s = Transaction.Status.fromId(statusId)
        return s == Transaction.Status.BAD_QUERY || s == Transaction.Status.BAD_RESPONSE || s == Transaction.Status.NO_RESPONSE || s == Transaction.Status.SEND_FAIL || s == Transaction.Status.CLIENT_ERROR || s == Transaction.Status.INTERNAL_ERROR || s == Transaction.Status.TRANSPORT_ERROR
    }


    private fun unobserveProxyStates() {
        persistentState.getProxyStatus().removeObservers(viewLifecycleOwner)
    }

    private fun disableLogsCard() {
        b.fhsCardNetworkLogsCount.text = getString(R.string.firewall_card_text_inactive)
        b.fhsCardDnsLogsCount.text = getString(R.string.lbl_disabled)
        b.fhsCardLogsDuration.visibility = View.GONE
    }

    private fun disableProxyCard() {
        proxyStateListenerJob?.cancel()
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
        b.fhsCardAppsStatusRl.visibility = View.GONE
        b.fhsCardApps.visibility = View.VISIBLE
        b.fhsCardApps.text = getString(R.string.firewall_card_text_inactive)
    }

    /**
     * The observers are for the DNS cards, when the mode is set to DNS/DNS+Firewall. The observers
     * are register to update the UI in the home screen
     */
    private fun observeDnsStates() {
        io {
            val dnsId = if (WireguardManager.oneWireGuardEnabled()) {
                val id = WireguardManager.getOneWireGuardProxyId()
                if (id == null) {
                    if (appConfig.isSmartDnsEnabled()) {
                        Backend.Plus
                    } else {
                        Backend.Preferred
                    }
                } else {
                    "${ProxyManager.ID_WG_BASE}${id}"
                }
            } else {
                if (appConfig.isSmartDnsEnabled()) {
                    Backend.Plus
                } else {
                    Backend.Preferred
                }
            }
            val p50 = VpnController.p50(dnsId)
            uiCtx {
                when (p50) {
                    in 0L..19L -> {
                        val string =
                            getString(
                                R.string.ci_desc,
                                getString(R.string.lbl_very),
                                getString(R.string.lbl_fast)
                            )
                                .replaceFirstChar(Char::titlecase)
                        b.fhsCardDnsLatency.text = string
                    }

                    in 20L..50L -> {
                        b.fhsCardDnsLatency.text =
                            getString(R.string.lbl_fast).replaceFirstChar(Char::titlecase)
                    }

                    in 50L..100L -> {
                        b.fhsCardDnsLatency.text =
                            getString(R.string.lbl_slow).replaceFirstChar(Char::titlecase)
                    }

                    else -> {
                        val string =
                            getString(
                                R.string.ci_desc,
                                getString(R.string.lbl_very),
                                getString(R.string.lbl_slow)
                            )
                                .replaceFirstChar(Char::titlecase)
                        b.fhsCardDnsLatency.text = string
                    }
                }

                b.fhsCardDnsLatency.isSelected = true
            }
        }

        appConfig.getConnectedDnsObservable().observe(viewLifecycleOwner) {
            updateUiWithDnsStates(it)
        }

        VpnController.getRegionLiveData().distinctUntilChanged().observe(viewLifecycleOwner) {
            if (it != null) {
                b.fhsCardRegion.text = it.uppercase()
            }
        }
    }

    private fun updateUiWithDnsStates(dnsName: String) {
        var dns = dnsName
        val preferredId = if (appConfig.isSystemDns()) {
            Backend.System
        } else if (appConfig.isSmartDnsEnabled()) {
            Backend.Plus
        } else {
            Backend.Preferred
        }
        // get the status from go to check if the dns transport is added or not
        val id =
            if (WireguardManager.oneWireGuardEnabled()) {
                val id = WireguardManager.getOneWireGuardProxyId()
                if (id == null) {
                    preferredId
                } else {
                    dns = getString(R.string.lbl_wireguard)
                    "${ProxyManager.ID_WG_BASE}${id}"
                }
            } else {
                if (persistentState.splitDns && WireguardManager.isAdvancedWgActive()) {
                    dns += ", " + resources.getString(R.string.lbl_wireguard)
                }

                preferredId
            }

        if (VpnController.isOn()) {
            io {
                var failing = false
                repeat(5) {
                    val status = VpnController.getDnsStatus(id)
                    if (status != null) {
                        failing = false
                        uiCtx {
                            if (isAdded) {
                                b.fhsCardDnsLatency.visibility = View.VISIBLE
                                b.fhsCardDnsFailure.visibility = View.INVISIBLE
                            }
                        }
                        return@io
                    }
                    // status null means the dns transport is not active / different id is used
                    kotlinx.coroutines.delay(1000L)
                    failing = true
                }
                uiCtx {
                    if (failing && isAdded) {
                        b.fhsCardDnsLatency.visibility = View.INVISIBLE
                        b.fhsCardDnsFailure.visibility = View.VISIBLE
                        b.fhsCardDnsFailure.text = getString(R.string.failed_using_default)
                    }
                }
            }
        }
        b.fhsCardDnsConnectedDns.text = dns
        b.fhsCardDnsConnectedDns.isSelected = true
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
        appConfig.getConnectedDnsObservable().removeObservers(viewLifecycleOwner)
        VpnController.getRegionLiveData().removeObservers(viewLifecycleOwner)
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
                val blockedCount =
                    copy.count { a ->
                        a.connectionStatus != FirewallManager.ConnectionStatus.ALLOW.id
                    }
                val bypassCount =
                    copy.count { a ->
                        a.firewallStatus == FirewallManager.FirewallStatus.BYPASS_UNIVERSAL.id ||
                                a.firewallStatus ==
                                FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL.id
                    }
                val excludedCount =
                    copy.count { a ->
                        a.firewallStatus == FirewallManager.FirewallStatus.EXCLUDE.id
                    }
                val isolatedCount =
                    copy.count { a ->
                        a.firewallStatus == FirewallManager.FirewallStatus.ISOLATE.id
                    }
                val allApps = copy.count()
                val allowedApps =
                    allApps - (blockedCount + bypassCount + excludedCount + isolatedCount)
                b.fhsCardAllowedApps.visibility = View.VISIBLE
                b.fhsCardAppsStatusRl.visibility = View.VISIBLE
                b.fhsCardAllowedApps.text = allowedApps.toString()
                b.fhsCardAppsAllApps.text = allApps.toString()
                b.fhsCardAppsBlockedCount.text = blockedCount.toString()
                b.fhsCardAppsBypassCount.text = bypassCount.toString()
                b.fhsCardAppsExcludeCount.text = excludedCount.toString()
                b.fhsCardAppsIsolatedCount.text = isolatedCount.toString()
                b.fhsCardApps.text =
                    getString(
                        R.string.firewall_card_text_active,
                        blockedCount.toString(),
                        bypassCount.toString(),
                        excludedCount.toString(),
                        isolatedCount.toString()
                    )
                b.fhsCardApps.visibility = View.GONE
                b.fhsCardAllowedApps.isSelected = true
            } catch (e: Exception) { // NoSuchElementException, ConcurrentModification
                Logger.e(
                    LOG_TAG_VPN,
                    "error retrieving value from appInfos observer ${e.message}",
                    e
                )
            }
        }
    }

    // unregister all firewall related observers
    private fun unobserveAppStates() {
        FirewallManager.getApplistObserver().removeObservers(viewLifecycleOwner)
    }

    private fun handleMainScreenBtnClickEvent() {
        b.fhsDnsOnOffBtn.isEnabled = false
        delay(TimeUnit.MILLISECONDS.toMillis(500), lifecycleScope) {
            if (isAdded) {
                b.fhsDnsOnOffBtn.isEnabled = true
            }
        }

        // prompt user to disable battery optimization and restrict background data
        if (isRestrictBackgroundActive(requireContext()) && batteryOptimizationActive(requireContext()) && !isVpnActivated) {
            showBatteryOptimizationDialog()
        }

        handleVpnActivation()
    }

    private fun handleVpnActivation() {
        if (handleAlwaysOnVpn()) return

        if (isVpnActivated) {
            stopVpnService()
        } else {
            prepareAndStartVpn()
        }
    }

    private fun batteryOptimizationActive(context: Context): Boolean {
        // check whether or not Battery Permission is Available for Device
        val bph = batteryPermissionHelper.isBatterySaverPermissionAvailable(context = context, onlyIfSupported = true)
        Logger.d(LOG_TAG_UI, "battery optimization available: $bph")
        return bph
    }

    private fun showBatteryOptimizationDialog() {
        if (!isAtleastN()) return

        val builder = MaterialAlertDialogBuilder(requireContext())
        val title =
            getString(
                R.string.battery_optimization_dialog_heading,
                getString(R.string.lbl_battery_optimization)
            )
        val msg =
            getString(
                R.string.restrict_dialog_message,
                getString(R.string.lbl_battery_optimization)
            )
        builder.setTitle(title)
        builder.setMessage(msg)
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.lbl_proceed) { _, _ ->
            Logger.v(LOG_TAG_UI, "launch battery optimization settings")
            batteryPermissionHelper.getPermission(requireContext(), open = true, newTask = true)
        }

        builder.setNegativeButton(R.string.lbl_dismiss) { _, _ ->
            // no-op
        }
        builder.create().show()
    }

    private fun isRestrictBackgroundActive(context: Context): Boolean {
        if (!isAtleastN()) return false

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val isBackgroundRestricted = cm.restrictBackgroundStatus
        Logger.d(LOG_TAG_UI, "restrict background status: $isBackgroundRestricted")

        return if (isAtleastP()) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            Logger.d(LOG_TAG_UI, "above P, background restricted: ${am.isBackgroundRestricted}")
            am.isBackgroundRestricted ||
                    isBackgroundRestricted == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        } else {
            isBackgroundRestricted == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        }
    }

    private fun showAlwaysOnStopDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext())

        builder.setTitle(R.string.always_on_dialog_stop_heading)
        if (VpnController.isVpnLockdown()) {
            builder.setMessage(
                htmlToSpannedText(getString(R.string.always_on_dialog_lockdown_stop_message))
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
        isVpnActivated = VpnController.state().activationRequested
        handleShimmer()
        maybeAutoStartVpn()
        updateCardsUi()
        syncDnsStatus()
        handleLockdownModeIfNeeded()
        startTrafficStats()
        //maybeShowGracePeriodDialog()
        b.fhsSponsorBottom.bringToFront()
    }

    private lateinit var trafficStatsTicker: Job

    private fun startTrafficStats() {
        trafficStatsTicker =
            ui("trafficStatsTicker") {
                var counter = 0
                while (true) {
                    // make it as 3 options and add the protos
                    if (!isAdded) return@ui

                    if (counter % 3 == 0) {
                        displayTrafficStatsRate()
                    } else if (counter % 3 == 1) {
                        displayTrafficStatsBW()
                    } else {
                        displayProtos()
                    }
                    // show protos
                    kotlinx.coroutines.delay(2500L)
                    counter++
                }
            }
    }

    private fun displayProtos() {
        b.fhsInternetSpeed.visibility = View.VISIBLE
        b.fhsInternetSpeedUnit.visibility = View.VISIBLE
        b.fhsInternetSpeed.text = VpnController.protocols()
        b.fhsInternetSpeedUnit.text = getString(R.string.lbl_protos)
    }

    private fun displayTrafficStatsBW() {
        val txRx = convertToCommonUnit(txRx.tx, txRx.rx)

        b.fhsInternetSpeed.visibility = View.VISIBLE
        b.fhsInternetSpeedUnit.visibility = View.VISIBLE
        b.fhsInternetSpeed.text =
            getString(
                R.string.two_argument_space,
                getString(
                    R.string.two_argument_space,
                    txRx.first,
                    getString(R.string.symbol_black_up)
                ),
                getString(
                    R.string.two_argument_space,
                    txRx.second,
                    getString(R.string.symbol_black_down)
                )
            )
        b.fhsInternetSpeedUnit.text = getCommonUnit(this.txRx.tx, this.txRx.rx)
    }

    private fun stopTrafficStats() {
        try {
            trafficStatsTicker.cancel()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "error stopping traffic stats ticker", e)
        }
    }

    data class TxRx(
        val tx: Long = TrafficStats.getTotalTxBytes(),
        val rx: Long = TrafficStats.getTotalRxBytes(),
        val time: Long = SystemClock.elapsedRealtime()
    )

    private var txRx = TxRx()

    private fun displayTrafficStatsRate() {
        val curr = TxRx()
        if (txRx.time <= 0L) {
            txRx = curr
            b.fhsInternetSpeed.visibility = View.GONE
            b.fhsInternetSpeedUnit.visibility = View.GONE
            return
        }
        val dur = (curr.time - txRx.time) / 1000L

        if (dur <= 0) {
            b.fhsInternetSpeed.visibility = View.GONE
            b.fhsInternetSpeedUnit.visibility = View.GONE
            return
        }
        val tx = curr.tx - txRx.tx
        val rx = curr.rx - txRx.rx
        txRx = curr
        val txRx = convertToCommonUnit(tx/dur, rx/dur)
        b.fhsInternetSpeed.visibility = View.VISIBLE
        b.fhsInternetSpeedUnit.visibility = View.VISIBLE
        b.fhsInternetSpeed.text =
            getString(
                R.string.two_argument_space,
                getString(
                    R.string.two_argument_space,
                    txRx.first,
                    getString(R.string.symbol_black_up)
                ),
                getString(
                    R.string.two_argument_space,
                    txRx.second,
                    getString(R.string.symbol_black_down)
                )
            )
        b.fhsInternetSpeedUnit.text = getString(R.string.symbol_ps, getCommonUnit(tx/dur, rx/dur))
    }

    // TODO: Move this to a common utility class
    private fun getCommonUnit(bytes1: Long, bytes2: Long): String {
        val maxBytes = maxOf(bytes1, bytes2)
        return when {
            maxBytes >= 1024L * 1024L * 1024L * 1024L -> "TB"
            maxBytes >= 1024L * 1024L * 1024L -> "GB"
            maxBytes >= 1024L * 1024L -> "MB"
            maxBytes >= 1024L -> "KB"
            else -> "B"
        }
    }

    private fun convertToCommonUnit(bytes1: Long, bytes2: Long): Pair<String, String> {
        val unit = getCommonUnit(bytes1, bytes2)
        val v = when (unit) {
            "TB" -> Pair(bytesToTB(bytes1), bytesToTB(bytes2))
            "GB" -> Pair(bytesToGB(bytes1), bytesToGB(bytes2))
            "MB" -> Pair(bytesToMB(bytes1), bytesToMB(bytes2))
            "KB" -> Pair(bytesToKB(bytes1), bytesToKB(bytes2))
            else -> Pair(bytes1.toDouble(), bytes2.toDouble())
        }
        return Pair(String.format(Locale.ROOT, "%.2f", v.first), String.format(Locale.ROOT, "%.2f", v.second))
    }

    private fun bytesToKB(bytes: Long): Double = bytes / 1024.0
    private fun bytesToMB(bytes: Long): Double = bytes / (1024.0 * 1024.0)
    private fun bytesToGB(bytes: Long): Double = bytes / (1024.0 * 1024.0 * 1024.0)
    private fun bytesToTB(bytes: Long): Double = bytes / (1024.0 * 1024.0 * 1024.0 * 1024.0)

    /**
     * Issue fix - https://github.com/celzero/rethink-app/issues/57 When the application
     * crashes/updates it goes into red waiting state. This causes confusion to the users also
     * requires click of START button twice to start the app. FIX : The check for the controller
     * state. If persistence state has vpn enabled and the VPN is not connected then the start will
     * be initiated.
     */
    private fun maybeAutoStartVpn() {
        if (isVpnActivated && !VpnController.isOn()) {
            // this case will happen when the app is updated or crashed
            // generate the bug report and start the vpn
            triggerBugReport()
            Logger.i(LOG_TAG_VPN, "start VPN (previous state)")
            prepareAndStartVpn()
        }
    }

    private fun triggerBugReport() {
        if (WorkScheduler.isWorkRunning(requireContext(), WorkScheduler.APP_EXIT_INFO_JOB_TAG)) {
            Logger.v(LOG_TAG_VPN, "bug report already triggered")
            return
        }

        Logger.v(LOG_TAG_VPN, "trigger bug report")
        workScheduler.scheduleOneTimeWorkForAppExitInfo()
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
        stopTrafficStats()
        proxyStateListenerJob?.cancel()
    }

    private fun startDnsActivity(screenToLoad: Int) {
        if (isPrivateDnsActive(requireContext())) {
            showPrivateDnsDialog()
            return
        }

        if (canStartRethinkActivity()) {
            // no need to pass value in intent, as default load to Rethink remote
            startActivity(ScreenType.RETHINK, screenToLoad)
            return
        }

        startActivity(ScreenType.DNS, screenToLoad)
        return
    }

    private fun canStartRethinkActivity(): Boolean {
        val dns = appConfig.getDnsType()
        return dns.isRethinkRemote() && !WireguardManager.oneWireGuardEnabled()
    }

    private fun showPrivateDnsDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(R.string.private_dns_dialog_heading)
        builder.setMessage(R.string.private_dns_dialog_desc)
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.private_dns_dialog_positive) { _, _ ->
            openNetworkSettings(requireContext(), Settings.ACTION_WIRELESS_SETTINGS)
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
        Logger.d(LOG_TAG_VPN, "Status : $isVpnActivated , BraveMode: ${appConfig.getBraveMode()}")

        // no need to check for app modes to open this activity
        // one use case: https://github.com/celzero/rethink-app/issues/611
        val intent = Intent(requireContext(), AppListActivity::class.java)
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
        VpnController.stop("home", requireContext())
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
            Logger.w(LOG_TAG_VPN, "User rejected notification permission for the app")
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
                Logger.i(LOG_TAG_VPN, "Preparing VPN service")
                VpnService.prepare(requireContext())
            } catch (e: NullPointerException) {
                // This exception is not mentioned in the documentation, but it has been encountered
                // users and also by other developers, e.g.
                // https://stackoverflow.com/questions/45470113.
                Logger.e(LOG_TAG_VPN, "Device does not support system-wide VPN mode.", e)
                return false
            }
        // If the VPN.prepare() is not null, then the first time VPN dialog is shown, Show info
        // dialog before that.
        if (prepareVpnIntent != null) {
            Logger.i(LOG_TAG_VPN, "VPN service is prepared")
            showFirstTimeVpnDialog(prepareVpnIntent)
            return false
        }
        Logger.i(LOG_TAG_VPN, "VPN service is prepared, starting VPN service")
        return true
    }

    private fun showFirstTimeVpnDialog(prepareVpnIntent: Intent) {
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(R.string.hsf_vpn_dialog_header)
        builder.setMessage(R.string.hsf_vpn_dialog_message)
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.lbl_proceed) { _, _ ->
            try {
                startForResult.launch(prepareVpnIntent)
            } catch (e: ActivityNotFoundException) {
                Logger.e(LOG_TAG_VPN, "Activity not found to start VPN service", e)
                showToastUiCentered(
                    requireContext(),
                    getString(R.string.hsf_vpn_prepare_failure),
                    Toast.LENGTH_LONG
                )
            }
        }

        builder.setNegativeButton(R.string.lbl_cancel) { _, _ ->
            // no-op
        }
        builder.create().show()
    }

    /*private fun maybeShowGracePeriodDialog() {
        val now = System.currentTimeMillis()

        val lastShown = persistentState.lastGracePeriodReminderTime
        val daysSinceLastShown = TimeUnit.MILLISECONDS.toDays(now - lastShown)
        if (daysSinceLastShown < GRACE_DIALOG_REMIND_AFTER_DAYS) return
        Logger.d(LOG_TAG_UI, "$TAG Grace period dialog last shown $daysSinceLastShown days ago")
        io {
            val currentSubs = RpnProxyManager.getSubscriptionState()
            if (currentSubs.isActive) {
                Logger.v(LOG_TAG_UI, "$TAG Current subscription is active, skipping grace period dialog")
                return@io
            }
            if (!currentSubs.isCancelled) {
                Logger.v(LOG_TAG_UI, "$TAG Current subscription is not cancelled, skipping grace period dialog, state: ${currentSubs.state().name}")
                return@io
            }
            val subsData = RpnProxyManager.getSubscriptionData()
            if (subsData == null) {
                Logger.v(LOG_TAG_UI, "$TAG No subscription data found, skipping grace period dialog")
                return@io
            }

            val billingExpiry = subsData.subscriptionStatus.billingExpiry
            val accountExpiry = subsData.subscriptionStatus.accountExpiry
            // grace period is calculated based on billingExpiry and accountExpiry
            val timeLeft = accountExpiry.minus(now)
            val timeLeftDays = TimeUnit.MILLISECONDS.toDays(timeLeft)
            val gracePeriod = accountExpiry - billingExpiry
            val gracePeriodDays = TimeUnit.MILLISECONDS.toDays(gracePeriod)
            if (gracePeriodDays <= 0L) {
                Logger.v(LOG_TAG_UI, "$TAG No grace period available($gracePeriodDays), skipping grace period dialog")
                return@io
            }

            if (timeLeftDays <= 0L) {
                Logger.i(LOG_TAG_UI, "$TAG Grace period has ended(@$timeLeftDays), skipping grace period dialog")
                return@io
            }

            val daysRemaining = TimeUnit.MILLISECONDS.toDays(timeLeft).toInt().coerceAtLeast(1)
            if (daysRemaining <= 0) {
                Logger.v(LOG_TAG_UI, "$TAG No days remaining in grace period, skipping dialog")
                return@io
            }
            Logger.v(LOG_TAG_UI, "$TAG Showing grace period dialog, $daysRemaining days remaining")
            uiCtx {
                val dialogView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.dialog_grace_period_layout, null)

                dialogView.findViewById<AppCompatTextView>(R.id.dialog_days_left).text =
                    "\u23F3 $daysRemaining days remaining"

                dialogView.findViewById<LinearProgressIndicator>(R.id.dialog_progress).apply {
                    max = 100
                    // should be decreased from 100 to 0
                    progress =  100 - (timeLeftDays * 100 / gracePeriodDays).toInt()
                    if (progress < 0) 0 else progress
                    Logger.v(LOG_TAG_UI, "$TAG Grace period progress: $progress%")
                }

                val dialog = MaterialAlertDialogBuilder(requireContext())
                    .setView(dialogView)
                    .setCancelable(false)
                    .create()

                dialogView.findViewById<AppCompatButton>(R.id.button_renew).setOnClickListener {
                    dialog.dismiss()
                    findNavController().navigate(R.id.rethinkPlus)
                }

                dialogView.findViewById<AppCompatButton>(R.id.button_later).setOnClickListener {
                    dialog.dismiss()
                    persistentState.lastGracePeriodReminderTime = System.currentTimeMillis()
                }
                persistentState.lastGracePeriodReminderTime = System.currentTimeMillis()
                dialog.show()
            }
        }
    }*/

    private fun registerForActivityResult() {
        startForResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
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
                persistentState.shouldRequestNotificationPermission = it
                if (it) {
                    Logger.i(LOG_TAG_UI, "User accepted notification permission")
                } else {
                    Logger.w(LOG_TAG_UI, "User rejected notification permission")
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
        val vpnState = VpnController.state()
        val isEch = vpnState.serverName?.contains(DnsLogTracker.ECH, true) == true

        // Change status and explanation text
        var statusId: Int
        var colorId: Int
        val privateDnsMode: Utilities.PrivateDnsMode = getPrivateDnsMode(requireContext())

        if (appConfig.getBraveMode().isFirewallMode()) {
            vpnState.connectionState = BraveVPNService.State.WORKING
        }
        if (vpnState.on) {
            colorId = fetchTextColor(R.color.accentGood)
            statusId =
                when {
                    vpnState.connectionState == null -> {
                        // app's waiting here, but such a status is a cause for confusion
                        // R.string.status_waiting
                        R.string.status_no_internet
                    }
                    vpnState.connectionState === BraveVPNService.State.NEW -> {
                        // app's starting here, but such a status confuses users
                        // R.string.status_starting
                        R.string.status_protected
                    }
                    vpnState.connectionState === BraveVPNService.State.WORKING -> {
                        R.string.status_protected
                    }
                    vpnState.connectionState === BraveVPNService.State.APP_ERROR -> {
                        colorId = fetchTextColor(R.color.accentBad)
                        R.string.status_app_error
                    }
                    vpnState.connectionState === BraveVPNService.State.DNS_ERROR -> {
                        colorId = fetchTextColor(R.color.accentBad)
                        R.string.status_dns_error
                    }
                    vpnState.connectionState === BraveVPNService.State.DNS_SERVER_DOWN -> {
                        colorId = fetchTextColor(R.color.accentBad)
                        R.string.status_dns_server_down
                    }
                    vpnState.connectionState === BraveVPNService.State.NO_INTERNET -> {
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

        if (statusId == R.string.status_no_internet || statusId == R.string.status_failing) {
            val message = getString(statusId)
            colorId = fetchTextColor(R.color.accentBad)
            if (appConfig.isCustomSocks5Enabled() && appConfig.isCustomHttpProxyEnabled()) {
                statusId = R.string.status_protected_with_proxy
            } else if (appConfig.isCustomSocks5Enabled()) {
                statusId = R.string.status_protected_with_socks5
            } else if (appConfig.isCustomHttpProxyEnabled()) {
                statusId = R.string.status_protected_with_http
            } else if (appConfig.isWireGuardEnabled()) {
                statusId = R.string.status_protected_with_wg
            } else if (isPrivateDnsActive(requireContext())) {
                statusId = R.string.status_protected_with_private_dns
            }
            // replace the string "protected" with appropriate string
            // FIXME: spilt the string literals to separate strings
            val string =
                getString(statusId)
                    .replaceFirst(getString(R.string.status_protected), message, true)
            b.fhsProtectionLevelTxt.setTextColor(colorId)
            b.fhsProtectionLevelTxt.text = string
        } else {
            if (isEch) {
                val stat = getString(statusId)
                val s  = stat.replaceFirst(getString(R.string.status_protected), getString(R.string.lbl_ultra_secure), true)
                Logger.d(LOG_TAG_UI, "Ech status : $stat")
                b.fhsProtectionLevelTxt.setTextColor(fetchTextColor(R.color.accentGood))
                b.fhsProtectionLevelTxt.text = s
            } else {
                b.fhsProtectionLevelTxt.setTextColor(colorId)
                b.fhsProtectionLevelTxt.setText(statusId)
            }
        }
        val isUnderlyingVpnNwEmpty = VpnController.isUnderlyingVpnNetworkEmpty()
        if (isUnderlyingVpnNwEmpty) {
            b.fhsProtectionLevelTxt.setTextColor(fetchTextColor(R.color.accentBad))
            b.fhsProtectionLevelTxt.text = getString(R.string.status_no_network)
        }
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
            when (attr) {
                R.color.accentGood -> {
                    R.attr.accentGood
                }
                R.color.accentBad -> {
                    R.attr.accentBad
                }
                R.color.primaryLightColorText -> {
                    R.attr.primaryLightColorText
                }
                R.color.secondaryText -> {
                    R.attr.invertedPrimaryTextColor
                }
                R.color.primaryText -> {
                    R.attr.primaryTextColor
                }
                R.color.primaryTextLight -> {
                    R.attr.primaryTextColor
                }
                else -> {
                    R.attr.accentGood
                }
            }
        val typedValue = TypedValue()
        val a: TypedArray =
            requireContext().obtainStyledAttributes(typedValue.data, intArrayOf(attributeFetch))
        val color = a.getColor(0, 0)
        a.recycle()
        return color
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun ui(n: String, f: suspend () -> Unit): Job {
        val cctx = CoroutineName(n) + Dispatchers.Main
        return lifecycleScope.launch(cctx) { f() }
    }
}
