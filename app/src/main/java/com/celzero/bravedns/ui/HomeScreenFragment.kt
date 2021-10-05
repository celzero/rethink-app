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
package com.celzero.bravedns.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.TypedArray
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ExcludedAppListAdapter
import com.celzero.bravedns.adapter.WhitelistedAppsAdapter
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.databinding.FragmentHomeScreenBinding
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.NotificationActionType
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.Companion.delay
import com.celzero.bravedns.util.Utilities.Companion.isOtherVpnHasAlwaysOn
import com.celzero.bravedns.util.Utilities.Companion.openVpnProfile
import com.celzero.bravedns.util.Utilities.Companion.sendEmailIntent
import com.celzero.bravedns.util.Utilities.Companion.showToastUiCentered
import com.celzero.bravedns.util.Utilities.Companion.updateHtmlEncodedText
import com.celzero.bravedns.viewmodel.AppListViewModel
import com.celzero.bravedns.viewmodel.ExcludedAppViewModel
import com.facebook.shimmer.Shimmer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import xdns.Xdns.getBlocklistStampFromURL
import java.util.*
import java.util.concurrent.TimeUnit

class HomeScreenFragment : Fragment(R.layout.fragment_home_screen) {
    private val b by viewBinding(FragmentHomeScreenBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val appInfoViewModel: AppListViewModel by viewModel()
    private val excludeAppViewModel: ExcludedAppViewModel by viewModel()

    private var REQUEST_CODE_PREPARE_VPN: Int = 100

    private var isVpnActivated: Boolean = false

    private lateinit var themeNames: Array<String>

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeValues()
        initializeClickListeners()
        observeVpnState()
        observeChipStates()
        updateConfigureDnsChip(appConfig.getRemoteBlocklistCount())
    }

    private fun initializeValues() {
        persistentState.dnsBlockedCountLiveData.postValue(persistentState.numberOfBlockedRequests)
        isVpnActivated = VpnController.state().activationRequested

        themeNames = arrayOf(getString(R.string.settings_theme_dialog_themes_1),
                             getString(R.string.settings_theme_dialog_themes_2),
                             getString(R.string.settings_theme_dialog_themes_3),
                             getString(R.string.settings_theme_dialog_themes_4))

        appConfig.braveModeObserver.postValue(appConfig.getBraveMode().mode)

    }

    private fun handleQuickSettingsChips() {
        if (!canShowChips()) {
            b.chipsPrograms.visibility = View.GONE
            return
        }

        lightenUpChipIcons()
        b.chipsPrograms.visibility = View.VISIBLE

        b.fhsWhatsNewChip.visibility = if (persistentState.showWhatsNewChip) {
            View.VISIBLE
        } else {
            View.GONE
        }

        b.fhsProxyChip.visibility = if (appConfig.isProxyEnabled()) {
            View.VISIBLE
        } else {
            View.GONE
        }

        b.fhsThemeChip.text = getString(R.string.hsf_chip_appearance,
                                        themeNames[persistentState.theme])
    }

    // Icons used in chips are re-used in other screens as well.
    // instead of modifying the icon's color, the color filters
    // are applied for the icons which are part of chips
    private fun lightenUpChipIcons() {
        val colorFilter = PorterDuffColorFilter(
            ContextCompat.getColor(requireContext(), R.color.primaryText), PorterDuff.Mode.SRC_IN)
        b.fhsWhatsNewChip.chipIcon?.colorFilter = colorFilter
        b.fhsProxyChip.chipIcon?.colorFilter = colorFilter
        b.fhsDnsConfigureChip.chipIcon?.colorFilter = colorFilter
        b.fhsWhitelistChip.chipIcon?.colorFilter = colorFilter
        b.fhsExcludeChip.chipIcon?.colorFilter = colorFilter
        b.fhsThemeChip.chipIcon?.colorFilter = colorFilter
    }

    private fun observeChipStates() {
        persistentState.remoteBlocklistCount.observe(viewLifecycleOwner, {
            updateConfigureDnsChip(it)
        })
    }

    private fun updateConfigureDnsChip(count: Int) {
        b.fhsDnsConfigureChip.text = if (appConfig.isRethinkDnsPlusUrl(
                appConfig.getConnectedDNS()) && count != 0) {
            getString(R.string.hsf_blocklist_chip_text, count.toString())
        } else {
            getString(R.string.hsf_blocklist_chip_text_no_data)
        }
    }

    private fun canShowChips(): Boolean {
        return !VpnController.isVpnLockdown() && appConfig.getBraveMode().isDnsFirewallMode()
    }

    private fun initializeClickListeners() {

        b.fhsCardFirewallLl.setOnClickListener {
            startFirewallActivity(FirewallActivity.Tabs.UNIVERSAL.screen)
        }

        b.fhsCardDnsLl.setOnClickListener {
            startDnsActivity(DNSDetailActivity.Tabs.LOGS.screen)
        }

        b.fhsCardDnsConfigure.setOnClickListener {
            startDnsActivity(DNSDetailActivity.Tabs.CONFIGURE.screen)
        }

        b.fhsCardFirewallConfigure.setOnClickListener {
            startFirewallActivity(FirewallActivity.Tabs.ALL_APPS.screen)
        }

        b.homeFragmentBottomSheetIcon.setOnClickListener {
            b.homeFragmentBottomSheetIcon.isEnabled = false
            openBottomSheet()
            delay(TimeUnit.MILLISECONDS.toMillis(500), lifecycleScope) {
                b.homeFragmentBottomSheetIcon.isEnabled = true
            }
        }

        b.homeFragmentPauseIcon.setOnClickListener {
            handlePause()
        }

        b.fhsDnsOnOffBtn.setOnClickListener {
            handleMainScreenBtnClickEvent()
            delay(TimeUnit.MILLISECONDS.toMillis(500), lifecycleScope) {
                if (isAdded) {
                    b.homeFragmentBottomSheetIcon.isEnabled = true
                }
            }
        }

        appConfig.braveModeObserver.observe(viewLifecycleOwner, {
            updateCardsUi()
            handleQuickSettingsChips()
            syncDnsStatus()
        })

        b.fhsDnsConfigureChip.setOnClickListener {
            io {
                val endpoint = appConfig.getDnsRethinkEndpoint()
                val stamp = getRemoteBlocklistStamp(endpoint.dohURL)
                if (appConfig.isRethinkDnsPlusUrl(appConfig.getConnectedDNS()) || stamp.isEmpty()) {
                    uiCtx {
                        openRethinkPlusConfigureActivity(stamp)
                    }
                } else {
                    enableRethinkPlusEndpoint(endpoint)
                }
            }
        }

        b.fhsWhitelistChip.setOnClickListener {
            openWhitelistDialog()
        }

        b.fhsExcludeChip.setOnClickListener {
            openExcludedDialog()
        }

        b.fhsProxyChip.setOnCloseIconClickListener {
            b.fhsProxyChip.isEnabled = false
            appConfig.removeAllProxies()
            b.fhsProxyChip.text = getString(R.string.hsf_proxy_chip_remove_text)
            syncDnsStatus()
            delay(TimeUnit.MINUTES.toMillis(2), lifecycleScope) {
                b.fhsProxyChip.visibility = View.GONE
                b.fhsProxyChip.isEnabled = true
                showToastUiCentered(requireContext(),
                                    getString(R.string.hsf_proxy_chip_removed_toast),
                                    Toast.LENGTH_SHORT)
            }
        }

        b.fhsThemeChip.setOnClickListener {
            applyAppTheme()
        }

        b.fhsWhatsNewChip.setOnClickListener {
            showNewFeaturesDialog()
        }

        b.fhsWhatsNewChip.setOnCloseIconClickListener {
            persistentState.showWhatsNewChip = false
            b.fhsWhatsNewChip.text = getString(R.string.hsf_whats_new_remove_text)
            delay(TimeUnit.MINUTES.toMillis(2), lifecycleScope) {
                b.fhsWhatsNewChip.visibility = View.GONE
            }
        }
    }

    private fun openRethinkPlusConfigureActivity(stamp: String) {
        val intent = Intent(context, DNSConfigureWebViewActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        intent.putExtra(Constants.BLOCKLIST_LOCATION_INTENT_EXTRA,
                        DNSConfigureWebViewActivity.REMOTE)
        intent.putExtra(Constants.BLOCKLIST_STAMP_INTENT_EXTRA, stamp)
        startActivity(intent)
    }

    private suspend fun enableRethinkPlusEndpoint(endpoint: DoHEndpoint) {
        uiCtx {
            b.fhsDnsConfigureChip.text = getString(R.string.hsf_blocklist_updating_text)
        }

        io {
            kotlinx.coroutines.delay(1500)
            endpoint.isSelected = true
            appConfig.handleDoHChanges(endpoint)
            uiCtx {
                showToastUiCentered(requireContext(),
                                    getString(R.string.hsf_blocklist_selected_toast,
                                              appConfig.getRemoteBlocklistCount().toString()),
                                    Toast.LENGTH_SHORT)
            }
        }
    }

    private fun handlePause() {
        if (!VpnController.hasTunnel()) {
            showToastUiCentered(requireContext(),
                                requireContext().getString(R.string.hsf_pause_vpn_failure),
                                Toast.LENGTH_SHORT)
            return
        }

        if (VpnController.isVpnLockdown()) {
            showToastUiCentered(requireContext(), getString(R.string.hsf_pause_lockdown_failure),
                                Toast.LENGTH_SHORT)
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

    private fun applyAppTheme() {
        // Fetch the next theme.
        val theme = (persistentState.theme + 1) % Themes.getThemeCount()

        when (val styleId = Themes.getTheme(theme)) {
            Themes.SYSTEM_DEFAULT.id -> {
                if (requireActivity().isDarkThemeOn()) {
                    applyTheme(R.style.AppThemeTrueBlack)
                } else {
                    applyTheme(R.style.AppThemeWhite)
                }
            }
            else -> {
                applyTheme(styleId)
            }
        }
        persistentState.theme = theme
        b.fhsThemeChip.text = getString(R.string.hsf_chip_appearance, themeNames[theme])
    }

    private fun applyTheme(theme: Int) {
        requireActivity().setTheme(theme)
        requireActivity().recreate()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun openExcludedDialog() {
        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        val excludeAppAdapter = ExcludedAppListAdapter(requireContext())
        excludeAppViewModel.excludedAppList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            excludeAppAdapter::submitList))
        val excludeAppDialog = ExcludeAppsDialog(requireActivity(), excludeAppAdapter,
                                                 excludeAppViewModel, themeId)
        excludeAppDialog.setCanceledOnTouchOutside(false)
        excludeAppDialog.show()
    }

    private fun openWhitelistDialog() {
        val recyclerAdapter = WhitelistedAppsAdapter(requireContext())
        appInfoViewModel.appDetailsList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            recyclerAdapter::submitList))

        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)

        val customDialog = WhitelistAppDialog(requireActivity(), recyclerAdapter, appInfoViewModel,
                                              themeId)
        customDialog.setCanceledOnTouchOutside(false)
        customDialog.show()
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun getRemoteBlocklistStamp(url: String): String {
        // Interacts with GO lib to fetch the stamp (Xdnx#getBlocklistStampFromURL)
        return try {
            getBlocklistStampFromURL(url)
        } catch (e: Exception) {
            Log.w(LOG_TAG_DNS, "url $url has invalid blocklist-stamp", e)
            ""
        }
    }

    private fun updateCardsUi() {
        if (isVpnActivated) {
            showActiveCards()
        } else {
            showDisabledCards()
        }
    }

    private fun observeVpnState() {
        persistentState.vpnEnabledLiveData.observe(viewLifecycleOwner, {
            isVpnActivated = it
            updateMainButtonUi()
            updateCardsUi()
            handleQuickSettingsChips()
            syncDnsStatus()
        })

        VpnController.connectionStatus.observe(viewLifecycleOwner, {
            // No need to handle states in Home screen fragment for pause state
            if (VpnController.isAppPaused()) return@observe

            syncDnsStatus()
            handleShimmer()
        })
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
    }

    private fun showActiveCards() {
        enableFirewallCardIfNeeded()
        enableDnsCardIfNeeded()
    }

    private fun showNewFeaturesDialog() {
        val inflater: LayoutInflater = LayoutInflater.from(requireContext())
        val view: View = inflater.inflate(R.layout.dialog_whatsnew, null)
        val builder = AlertDialog.Builder(requireContext())
        builder.setView(view).setTitle(getString(R.string.whats_dialog_title))

        builder.setPositiveButton(
            getString(R.string.about_dialog_positive_button)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }

        builder.setNeutralButton(getString(R.string.about_dialog_neutral_button)) { _, _ ->
            sendEmailIntent(requireContext())
        }

        builder.setCancelable(false)
        builder.create().show()
    }

    private fun enableFirewallCardIfNeeded() {
        // handle the positive case before negative.
        if (appConfig.getBraveMode().isDnsMode()) {
            disableFirewallCard()
            unobserveFirewallStates()
        } else {
            showActiveFirewallCard()
            observeFirewallStates()
        }
    }

    private fun enableDnsCardIfNeeded() {
        if (appConfig.getBraveMode().isFirewallMode()) {
            disabledDnsCard()
            unobserveDnsStates()
        } else {
            showActiveDnsCard()
            observeDnsStates()
        }
    }

    private fun disableFirewallCard() {
        b.fhsCardFirewallApps.text = getString(R.string.firewall_card_text_inactive)
        b.fhsCardFirewallStatus.text = getString(R.string.firewall_card_status_inactive)
        b.fhsCardFirewallConfigure.alpha = 0.5F
        b.fhsCardFirewallConfigure.setTextColor(fetchTextColor(R.color.primaryLightColorText))
    }

    private fun showActiveFirewallCard() {
        b.fhsCardFirewallConfigure.alpha = 1F
        b.fhsCardFirewallConfigure.setTextColor(fetchTextColor(R.color.secondaryText))
    }

    private fun showActiveDnsCard() {
        b.fhsCardDnsConfigure.alpha = 1F
        b.fhsCardDnsConfigure.setTextColor(fetchTextColor(R.color.secondaryText))
    }

    private fun disabledDnsCard() {
        b.fhsCardDnsLatency.text = getString(R.string.dns_card_latency_inactive)
        b.fhsCardDnsConnectedDns.text = getString(R.string.dns_card_connected_status_failure)
        b.fhsCardDnsConfigure.setTextColor(fetchTextColor(R.color.primaryLightColorText))
        b.fhsCardDnsConfigure.alpha = 0.5F
    }

    /**
     * The observers are for the DNS cards, when the mode is set to DNS/DNS+Firewall.
     * The observers are register to update the UI in the home screen
     */
    private fun observeDnsStates() {
        persistentState.median.observe(viewLifecycleOwner, {
            b.fhsCardDnsLatency.text = getString(R.string.dns_card_latency_active, it.toString())
        })

        appConfig.getConnectedDnsObservable().observe(viewLifecycleOwner, {
            b.fhsCardDnsConnectedDns.text = it
            updateConfigureDnsChip(appConfig.getRemoteBlocklistCount())
        })
    }

    /**
     * Unregister all the DNS related observers which updates the dns card.
     */
    private fun unobserveDnsStates() {
        persistentState.median.removeObservers(viewLifecycleOwner)
        appConfig.getConnectedDnsObservable().removeObservers(viewLifecycleOwner)
    }

    /**
     * The observers for the firewall card in the home screen, will be calling this method
     * when the VPN is active and the mode is set to either Firewall or DNS+Firewall.
     */
    private fun observeFirewallStates() {
        FirewallManager.getApplistObserver().observe(viewLifecycleOwner, {
            try {
                val copy = it.toList()
                val blockedList = copy.filter { a -> !a.isInternetAllowed }
                val whiteListApps = copy.filter { a -> a.whiteListUniv1 }
                val excludedList = copy.filter { a -> a.isExcluded }
                b.fhsCardFirewallStatus.text = getString(R.string.firewall_card_status_active,
                                                         blockedList.count().toString())
                b.fhsCardFirewallApps.text = getString(R.string.firewall_card_text_active,
                                                       whiteListApps.count().toString(),
                                                       excludedList.count().toString())
            } catch (e: Exception) { // NoSuchElementException, ConcurrentModification
                Log.e(LOG_TAG_VPN, "error retrieving value from appInfos observer ${e.message}", e)
            }
        })
    }

    /**
     * Unregister all the firewall related observers for the Home screen card.
     */
    private fun unobserveFirewallStates() {
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
        val builder = AlertDialog.Builder(requireContext())

        builder.setTitle(R.string.always_on_dialog_stop_heading)
        if (VpnController.isVpnLockdown()) {
            builder.setMessage(
                updateHtmlEncodedText(getString(R.string.always_on_dialog_lockdown_stop_message)))
        } else {
            builder.setMessage(R.string.always_on_dialog_stop_message)
        }

        builder.setCancelable(false)
        builder.setPositiveButton(R.string.always_on_dialog_positive) { _, _ ->
            stopVpnService()
        }

        builder.setNegativeButton(R.string.always_on_dialog_negative) { _, _ ->
            /* No Op */
        }

        builder.setNeutralButton(R.string.always_on_dialog_neutral) { _, _ ->
            openVpnProfile(requireContext())
        }

        builder.create().show()
    }

    private fun openBottomSheet() {
        val bottomSheetFragment = HomeScreenSettingBottomSheet()
        bottomSheetFragment.show(requireActivity().supportFragmentManager, bottomSheetFragment.tag)
    }

    private fun handleAlwaysOnVpn(): Boolean {
        if (isOtherVpnHasAlwaysOn(requireContext())) {
            showAlwaysOnDisableDialog()
            return true
        }

        if (VpnController.isAlwaysOn(requireContext())) {
            showAlwaysOnStopDialog()
            return true
        }

        return false
    }

    private fun showAlwaysOnDisableDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.always_on_dialog_heading)
        builder.setMessage(R.string.always_on_dialog)
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.always_on_dialog_positive_btn) { _, _ ->
            openVpnProfile(requireContext())
        }

        builder.setNegativeButton(R.string.always_on_dialog_negative_btn) { _, _ ->
            // no-op
        }

        builder.create().show()
    }

    override fun onResume() {
        super.onResume()
        handleShimmer()
        maybeAutoStart()
        updateCardsUi()
        syncDnsStatus()
        handleQuickSettingsChips()
        handleLockdownModeIfNeeded()
    }

    // set the app mode to dns+firewall mode when vpn in lockdown state
    private fun handleLockdownModeIfNeeded() {
        if (VpnController.isVpnLockdown() && !appConfig.getBraveMode().isDnsFirewallMode()) {
            io {
                appConfig.changeBraveMode(AppConfig.BraveMode.DNS_FIREWALL.mode)
            }
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
        if (!isVpnActivated) {
            //when the dns/firewall is not enabled and VPN is not active. show the dialog to start VPN
            showStartDialog()
            return
        }

        if (isPrivateDnsActive()) {
            showToastUiCentered(requireContext(), resources.getText(
                R.string.private_dns_toast).toString().replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }, Toast.LENGTH_SHORT)
            return
        }

        if (appConfig.getBraveMode().isDnsActive()) {
            startActivity(isDns = true, screenToLoad)
            return
        }

        openBottomSheet()
        showToastUiCentered(requireContext(), resources.getText(
            R.string.brave_dns_connect_mode_change_firewall).toString().replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }, Toast.LENGTH_SHORT)

    }

    private fun startFirewallActivity(screenToLoad: Int) {
        if (DEBUG) Log.d(LOG_TAG_VPN,
                         "Status : $isVpnActivated , BraveMode: ${appConfig.getBraveMode()}")
        if (!isVpnActivated) {
            //when the dns/firewall is not enabled and VPN is not active. show the dialog to start VPN
            showStartDialog()
            return
        }

        if (appConfig.getBraveMode().isFirewallActive()) {
            startActivity(isDns = false, screenToLoad)
            return
        }

        openBottomSheet()
        showToastUiCentered(requireContext(), resources.getText(
            R.string.brave_dns_connect_mode_change_firewall).toString().replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }, Toast.LENGTH_SHORT)
    }

    private fun startActivity(isDns: Boolean, screenToLoad: Int) {
        val intent = when (isDns) {
            true -> Intent(requireContext(), DNSDetailActivity::class.java)
            false -> Intent(requireContext(), FirewallActivity::class.java)
        }
        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        intent.putExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, screenToLoad)
        startActivity(intent)
    }

    private fun getModeText(): String {
        return when (appConfig.getBraveMode()) {
            AppConfig.BraveMode.DNS -> getString(R.string.app_mode_dns)
            AppConfig.BraveMode.FIREWALL -> getString(R.string.app_mode_firewall)
            AppConfig.BraveMode.DNS_FIREWALL -> getString(R.string.app_mode_dns_firewall)
        }
    }

    private fun showStartDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(getString(R.string.hsf_start_dialog_header, getModeText()))
        builder.setMessage(getString(R.string.hsf_start_dialog_message))
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.hsf_start_dialog_positive) { _, _ ->
            handleMainScreenBtnClickEvent()
            delay(TimeUnit.SECONDS.toMillis(1L), lifecycleScope) {
                if (isVpnActivated) {
                    openBottomSheet()
                    showToastUiCentered(requireContext(), resources.getText(
                        R.string.brave_dns_connect_mode_change_dns).toString().replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                    }, Toast.LENGTH_SHORT)
                }
            }
        }

        builder.setNegativeButton(R.string.hsf_start_dialog_negative) { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()
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
        VpnController.start(requireContext())
    }

    @Throws(ActivityNotFoundException::class)
    private fun prepareVpnService(): Boolean {
        val prepareVpnIntent: Intent? = try {
            VpnService.prepare(context)
        } catch (e: NullPointerException) {
            // This exception is not mentioned in the documentation, but it has been encountered
            // users and also by other developers, e.g. https://stackoverflow.com/questions/45470113.
            Log.e(LOG_TAG_VPN, "Device does not support system-wide VPN mode.", e)
            return false
        }
        //If the VPN.prepare is not null, then the first time VPN dialog is shown, Show info dialog
        //before that.
        if (prepareVpnIntent != null) {
            showFirstTimeVpnDialog(prepareVpnIntent)
            return false
        }
        return true
    }

    private fun showFirstTimeVpnDialog(prepareVpnIntent: Intent) {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.hsf_vpn_dialog_header)
        builder.setMessage(R.string.hsf_vpn_dialog_message)
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.hsf_vpn_dialog_positive) { _, _ ->
            startActivityForResult(prepareVpnIntent, REQUEST_CODE_PREPARE_VPN)
        }

        builder.setNegativeButton(R.string.hsf_vpn_dialog_negative) { _, _ ->
            /* No Op */
        }
        builder.create().show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PREPARE_VPN && resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            stopVpnService()
        }
    }

    /**
     * Issue fix - https://github.com/celzero/rethink-app/issues/57
     * When the application crashes/updates it goes into
     * red waiting state. This causes confusion to the users also requires
     * click of START button twice to start the app.
     * FIX : The check for the controller state. If persistence state has
     * vpn enabled and the VPN is not connected then
     * the start will be initiated.
     */
    private fun maybeAutoStart() {
        if (isVpnActivated && !VpnController.state().on) {
            Log.i(LOG_TAG_VPN, "start VPN (previous state)")
            prepareAndStartVpn()
        }
    }

    // Sets the UI DNS status on/off.
    private fun syncDnsStatus() {
        val status = VpnController.state()

        // Change status and explanation text
        var statusId: Int
        var colorId: Int
        //val explanationId: Int
        val privateDnsMode: PrivateDnsMode = getPrivateDnsMode()

        if (appConfig.isDnsProxyActive() || appConfig.getBraveMode().isFirewallMode()) {
            status.connectionState = BraveVPNService.State.WORKING
        }

        if (status.on) {
            colorId = fetchTextColor(R.color.accentGood)
            statusId = when {
                status.connectionState == null -> {
                    R.string.status_waiting
                }
                status.connectionState === BraveVPNService.State.NEW -> {
                    R.string.status_starting
                }
                status.connectionState === BraveVPNService.State.WORKING -> {
                    R.string.status_protected
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
            statusId = when (privateDnsMode) {
                PrivateDnsMode.STRICT -> R.string.status_strict
                else -> R.string.status_exposed
            }
        }

        if (statusId == R.string.status_protected) {
            if (appConfig.getBraveMode().isDnsMode() && isPrivateDnsActive()) {
                statusId = R.string.status_protected_with_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (appConfig.getBraveMode().isDnsMode()) {
                statusId = R.string.status_protected
            } else if (appConfig.isOrbotProxyEnabled() && isPrivateDnsActive()) {
                statusId = R.string.status_protected_with_tor_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (appConfig.isOrbotProxyEnabled()) {
                statusId = R.string.status_protected_with_tor
            } else if ((appConfig.isCustomSocks5Enabled() && appConfig.isCustomHttpProxyEnabled()) && isPrivateDnsActive()) { // SOCKS5 + Http + PrivateDns
                statusId = R.string.status_protected_with_proxy_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (appConfig.isCustomSocks5Enabled() && appConfig.isCustomHttpProxyEnabled()) {
                statusId = R.string.status_protected_with_proxy
            } else if (appConfig.isCustomSocks5Enabled() && isPrivateDnsActive()) {
                statusId = R.string.status_protected_with_socks5_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (appConfig.isCustomHttpProxyEnabled() && isPrivateDnsActive()) {
                statusId = R.string.status_protected_with_http_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            } else if (appConfig.isCustomHttpProxyEnabled()) {
                statusId = R.string.status_protected_with_http
            } else if (appConfig.isCustomSocks5Enabled()) {
                statusId = R.string.status_protected_with_socks5
            } else if (isPrivateDnsActive()) {
                statusId = R.string.status_protected_with_private_dns
                colorId = fetchTextColor(R.color.primaryLightColorText)
            }
        }

        b.fhsProtectionLevelTxt.setTextColor(colorId)
        b.fhsProtectionLevelTxt.setText(statusId)
    }

    private fun getPrivateDnsMode(): PrivateDnsMode {
        if (VERSION.SDK_INT < VERSION_CODES.P) {
            // Private DNS was introduced in P.
            return PrivateDnsMode.NONE
        }
        val linkProperties: LinkProperties = getLinkProperties() ?: return PrivateDnsMode.NONE
        if (linkProperties.privateDnsServerName != null) {
            return PrivateDnsMode.STRICT
        }
        return if (linkProperties.isPrivateDnsActive) {
            PrivateDnsMode.UPGRADED
        } else {
            PrivateDnsMode.NONE
        }
    }

    private fun isPrivateDnsActive(): Boolean {
        return getPrivateDnsMode() != PrivateDnsMode.NONE
    }

    private fun getLinkProperties(): LinkProperties? {
        val connectivityManager = requireContext().getSystemService(
            Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        return connectivityManager.getLinkProperties(activeNetwork)
    }

    private fun isAnotherVpnActive(): Boolean {
        val connectivityManager = requireContext().getSystemService(
            Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(
            activeNetwork) ?: // It's not clear when this can happen, but it has occurred for at least one user.
        return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    enum class PrivateDnsMode {
        NONE,  // The setting is "Off" or "Opportunistic", and the DNS connection is not using TLS.
        UPGRADED,  // The setting is "Opportunistic", and the DNS connection has upgraded to TLS.
        STRICT // The setting is "Strict".
    }

    private fun fetchTextColor(attr: Int): Int {
        val attributeFetch = if (attr == R.color.accentGood) {
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
        } else if (attr == R.color.primaryLightColorText) {
            R.attr.primaryLightColorText
        }else {
            R.attr.accentGood
        }
        val typedValue = TypedValue()
        val a: TypedArray = requireContext().obtainStyledAttributes(typedValue.data,
                                                                    intArrayOf(attributeFetch))
        val color = a.getColor(0, 0)
        a.recycle()
        return color
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
