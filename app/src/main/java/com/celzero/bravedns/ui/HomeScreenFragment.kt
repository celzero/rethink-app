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
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ExcludedAppListAdapter
import com.celzero.bravedns.adapter.WhitelistedAppListAdapter
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.database.DoHEndpointRepository
import com.celzero.bravedns.databinding.FragmentHomeScreenBinding
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appStartTime
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.braveModeToggler
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_DNS
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_FIREWALL
import com.celzero.bravedns.util.Constants.Companion.DNS_SCREEN_CONFIG
import com.celzero.bravedns.util.Constants.Companion.DNS_SCREEN_LOGS
import com.celzero.bravedns.util.Constants.Companion.FIREWALL_SCREEN_ALL_APPS
import com.celzero.bravedns.util.Constants.Companion.FIREWALL_SCREEN_UNIVERSAL
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.delay
import com.celzero.bravedns.util.Utilities.Companion.isAlwaysOnEnabled
import com.celzero.bravedns.util.Utilities.Companion.isOtherVpnHasAlwaysOn
import com.celzero.bravedns.util.Utilities.Companion.isVpnLockdownEnabled
import com.celzero.bravedns.util.Utilities.Companion.openVpnProfile
import com.celzero.bravedns.util.Utilities.Companion.showToastUiCentered
import com.celzero.bravedns.util.Utilities.Companion.updateHtmlEncodedText
import com.celzero.bravedns.viewmodel.AppListViewModel
import com.celzero.bravedns.viewmodel.ExcludedAppViewModel
import com.facebook.shimmer.Shimmer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import xdns.Xdns.getBlocklistStampFromURL
import java.util.*

class HomeScreenFragment : Fragment(R.layout.fragment_home_screen) {
    private val b by viewBinding(FragmentHomeScreenBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val doHEndpointRepository by inject<DoHEndpointRepository>()
    private val appMode by inject<AppMode>()
    private val appInfoViewModel: AppListViewModel by viewModel()
    private val excludeAppViewModel: ExcludedAppViewModel by viewModel()

    private var REQUEST_CODE_PREPARE_VPN: Int = 100

    private var isVpnStarted: Boolean = false

    private lateinit var themeValues: Array<Int>
    private lateinit var themeNames: Array<String>


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initializeValues()
        initializeClickListeners()
    }

    // Assign initial values to the view and variables.
    private fun initializeValues() {
        persistentState.dnsBlockedCountLiveData.postValue(persistentState.numberOfBlockedRequests)
        isVpnStarted = VpnController.state().activationRequested

        themeValues = arrayOf(Constants.THEME_SYSTEM_DEFAULT, R.style.AppThemeWhite,
                              R.style.AppTheme, R.style.AppThemeTrueBlack)

        themeNames = arrayOf(getString(R.string.settings_theme_dialog_themes_1),
                             getString(R.string.settings_theme_dialog_themes_2),
                             getString(R.string.settings_theme_dialog_themes_3),
                             getString(R.string.settings_theme_dialog_themes_4))
    }

    private fun handleQuickSettingChips() {
        if (!canShowChips()) {
            b.chipsPrograms.visibility = View.GONE
            return
        }
        observeChipStates()
        updateConfigureDnsChip(persistentState.getRemoteBlocklistCount())
        b.chipsPrograms.visibility = View.VISIBLE
        if (persistentState.showWhatsNewChip) {
            b.fhsWhatsNewChip.visibility = View.VISIBLE
        } else {
            b.fhsWhatsNewChip.visibility = View.GONE
        }
        if (appMode.isProxyEnabled()) {
            b.fhsProxyChip.visibility = View.VISIBLE
        } else {
            b.fhsProxyChip.visibility = View.GONE
        }
        b.fhsThemeChip.text = getString(R.string.hsf_chip_appearance,
                                        themeNames[persistentState.theme])
    }

    private fun observeChipStates() {
        persistentState.remoteBlocklistCount.observe(viewLifecycleOwner, {
            updateConfigureDnsChip(it)
        })
    }

    private fun updateConfigureDnsChip(count: Int) {
        if (isRethinkDnsPlus(appMode.getConnectedDNS()) && count != 0) {
            b.fhsDnsConfigureChip.text = getString(R.string.hsf_blocklist_chip_text,
                                                   count.toString())
        } else {
            b.fhsDnsConfigureChip.text = getString(R.string.hsf_blocklist_chip_text_no_data)
        }
    }

    private fun canShowChips(): Boolean {
        return isVpnStarted && !isVpnLockdownEnabled(
            VpnController.getBraveVpnService()) && appMode.isDnsFirewallMode()
    }

    private fun initializeClickListeners() {

        b.fhsCardFirewallLl.setOnClickListener {
            startFirewallActivity(FIREWALL_SCREEN_UNIVERSAL)
        }

        b.fhsCardDnsLl.setOnClickListener {
            startDnsActivity(DNS_SCREEN_LOGS)
        }

        b.fhsCardDnsConfigure.setOnClickListener {
            startDnsActivity(DNS_SCREEN_CONFIG)
        }

        b.fhsCardDnsConfigureLl.setOnClickListener {
            startDnsActivity(DNS_SCREEN_LOGS)
        }

        b.fhsCardFirewallConfigure.setOnClickListener {
            startFirewallActivity(FIREWALL_SCREEN_ALL_APPS)
        }

        b.fhsCardFirewallConfigureLl.setOnClickListener {
            startFirewallActivity(FIREWALL_SCREEN_ALL_APPS)
        }

        b.homeFragmentBottomSheetIcon.setOnClickListener {
            b.homeFragmentBottomSheetIcon.isEnabled = false
            openBottomSheet()
            delay(500) {
                b.homeFragmentBottomSheetIcon.isEnabled = true
            }
        }

        b.homeFragmentPauseIcon.setOnClickListener {
            handlePause()
        }

        b.fhsDnsOnOffBtn.setOnClickListener {
            handleMainScreenBtnClickEvent()
            delay(500) {
                if (isAdded) {
                    b.homeFragmentBottomSheetIcon.isEnabled = true
                }
            }
        }

        braveModeToggler.observe(viewLifecycleOwner, {
            updateCardsUi()
            handleQuickSettingChips()
            syncDnsStatus()
        })

        b.fhsDnsConfigureChip.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val endpoint = doHEndpointRepository.getRethinkDnsEndpoint()
                val stamp = getRemoteBlocklistStamp(endpoint.dohURL)
                withContext(Dispatchers.Main) {
                    val intent = Intent(context, DNSConfigureWebViewActivity::class.java)
                    intent.putExtra(Constants.LOCATION_INTENT_EXTRA,
                                    DNSConfigureWebViewActivity.REMOTE)
                    intent.putExtra(Constants.STAMP_INTENT_EXTRA, stamp)
                    startActivity(intent)
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
            appMode.removeProxy(AppMode.ProxyType.NONE, AppMode.ProxyProvider.NONE)
            b.fhsProxyChip.text = getString(R.string.hsf_proxy_chip_remove_text)
            syncDnsStatus()
            delay(2000) {
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
            delay(2000) {
                b.fhsWhatsNewChip.visibility = View.GONE
            }
        }

    }

    private fun handlePause() {
        if (VpnController.getBraveVpnService() == null) {
            showToastUiCentered(requireContext(),
                                requireContext().getString(R.string.hsf_pause_vpn_failure),
                                Toast.LENGTH_SHORT)
            return
        }

        if (isVpnLockdownEnabled(VpnController.getBraveVpnService())) {
            showToastUiCentered(requireContext(), getString(R.string.hsf_pause_lockdown_failure),
                                Toast.LENGTH_SHORT)
            return
        }

        appMode.setAppState(AppMode.AppState.PAUSE)
        persistentState.notificationAction = Constants.NOTIFICATION_ACTION_STOP
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
        val theme = (persistentState.theme + 1) % 4
        when (themeValues[theme]) {
            Constants.THEME_SYSTEM_DEFAULT -> {
                if (requireActivity().isDarkThemeOn()) {
                    applyTheme(R.style.AppThemeTrueBlack)
                } else {
                    applyTheme(R.style.AppThemeWhite)
                }
            }
            R.style.AppThemeWhite -> {
                applyTheme(R.style.AppThemeWhite)
            }
            R.style.AppTheme -> {
                applyTheme(R.style.AppTheme)
            }
            R.style.AppThemeTrueBlack -> {
                applyTheme(R.style.AppThemeTrueBlack)
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
        val themeID = Utilities.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        val excludeAppAdapter = ExcludedAppListAdapter(requireContext())
        excludeAppViewModel.excludedAppList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            excludeAppAdapter::submitList))
        val excludeAppDialog = ExcludeAppsDialog(requireContext(), excludeAppAdapter,
                                                 excludeAppViewModel, themeID)
        excludeAppDialog.setCanceledOnTouchOutside(false)
        excludeAppDialog.show()
    }

    private fun openWhitelistDialog() {
        val recyclerAdapter = WhitelistedAppListAdapter(requireContext())
        appInfoViewModel.appDetailsList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            recyclerAdapter::submitList))

        val themeID = Utilities.getCurrentTheme(isDarkThemeOn(), persistentState.theme)

        val customDialog = WhitelistAppDialog(requireContext(), recyclerAdapter, appInfoViewModel,
                                              themeID, isChip = true)
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
            Log.w(LOG_TAG_DNS, "failure fetching stamp from Go ${e.message}", e)
            ""
        }
    }

    private fun updateCardsUi() {
        if (isVpnStarted) {
            showActiveCards()
        } else {
            showDisabledCards()
        }
    }

    private fun observeVpnState() {
        persistentState.vpnEnabledLiveData.observe(viewLifecycleOwner, {
            isVpnStarted = it
            updateMainButtonUi()
            updateCardsUi()
            handleQuickSettingChips()
        })

        VpnController.connectionStatus.observe(viewLifecycleOwner, {
            syncDnsStatus()
            handleShimmer()
        })
    }

    private fun updateMainButtonUi() {
        if (isVpnStarted) {
            b.fhsDnsOnOffBtn.setBackgroundResource(R.drawable.rounded_corners_button_accent)
            b.fhsDnsOnOffBtn.text = getString(R.string.hsf_stop_btn_state)
        } else {
            b.fhsDnsOnOffBtn.setBackgroundResource(R.drawable.rounded_corners_button_primary)
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
            val intent = Intent(Intent.ACTION_VIEW, (getString(R.string.about_mail_to)).toUri())
            intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_mail_subject))
            startActivity(intent)
        }

        builder.setCancelable(false)
        builder.create().show()
    }

    private fun enableFirewallCardIfNeeded() {
        // TODO create method in appMode to get the brave modes.
        // handle the positive case before negative.
        if (appMode.isDnsMode()) {
            disableFirewallCard()
            unobserveFirewallStates()
        } else {
            showActiveFirewallCard()
            observeFirewallStates()
        }
    }

    private fun enableDnsCardIfNeeded() {
        if (appMode.getBraveMode() == APP_MODE_FIREWALL) {
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
        b.fhsCardFirewallConfigure.setTextColor(fetchTextColor(R.color.textColorMain))
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
        b.fhsCardDnsConfigure.setTextColor(fetchTextColor(R.color.textColorMain))
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

        appMode.getConnectedDnsObservable().observe(viewLifecycleOwner, {
            b.fhsCardDnsConnectedDns.text = it
            if (isRethinkDnsPlus(it)) {
                updateConfigureDnsChip(persistentState.getRemoteBlocklistCount())
            }
        })
    }

    private fun isRethinkDnsPlus(dohName: String): Boolean {
        return Constants.RETHINK_DNS_PLUS == dohName
    }

    /**
     * Unregister all the DNS related observers which updates the dns card.
     */
    private fun unobserveDnsStates() {
        persistentState.median.removeObservers(viewLifecycleOwner)
        appMode.getConnectedDnsObservable().removeObservers(viewLifecycleOwner)
    }

    /**
     * The observers for the firewall card in the home screen, will be calling this method
     * when the VPN is active and the mode is set to either Firewall or DNS+Firewall.
     */
    private fun observeFirewallStates() {
        FirewallManager.getApplistObserver().observe(viewLifecycleOwner, {
            val copy = it.toMutableList()
            val blockedList = copy.filter { a -> !a.isInternetAllowed }
            val whiteListApps = copy.filter { a -> a.whiteListUniv1 }
            val excludedList = copy.filter { a -> a.isExcluded }
            b.fhsCardFirewallStatus.text = getString(R.string.firewall_card_status_active,
                                                     blockedList.size.toString())
            b.fhsCardFirewallApps.text = getString(R.string.firewall_card_text_active,
                                                   whiteListApps.size.toString(),
                                                   excludedList.size.toString())

        })
    }

    /**
     * Unregister all the firewall related observers for the Home screen card.
     */
    private fun unobserveFirewallStates() {
        FirewallManager.getApplistObserver().removeObservers(viewLifecycleOwner)
    }

    private fun handleMainScreenBtnClickEvent() {
        showPrivateDnsToastIfNeeded()

        if (handleAlwaysOnVpn()) {
            return
        }

        b.fhsDnsOnOffBtn.isEnabled = false
        delay(500) {
            if (isAdded) {
                b.fhsDnsOnOffBtn.isEnabled = true
            }
        }

        appStartTime = System.currentTimeMillis()

        if (isVpnStarted) {
            stopVpnService()
        } else {
            prepareAndStartVpn()
        }
    }

    private fun showAlwaysOnStopDialog() {
        val builder = AlertDialog.Builder(requireContext())

        builder.setTitle(R.string.always_on_dialog_stop_heading)
        if (isVpnLockdownEnabled(VpnController.getBraveVpnService())) {
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


    // FIXME: 19-11-2020 - Check the below code for all the edge cases.
    private fun handleAlwaysOnVpn(): Boolean {
        val alwaysOn = isAlwaysOnEnabled(requireContext(), VpnController.getBraveVpnService())
        if (DEBUG) Log.i(LOG_TAG_VPN, "AlwaysOn: $alwaysOn")

        if (isOtherVpnHasAlwaysOn(requireContext())) {
            showAlwaysOnDisableDialog()
            return true
        }

        if (alwaysOn && VpnController.getBraveVpnService() != null) {
            showAlwaysOnStopDialog()
            return true
        }

        return false
    }

    private fun showPrivateDnsToastIfNeeded() {
        if (getPrivateDnsMode() == PrivateDnsMode.STRICT) {
            showToastUiCentered(requireContext(),
                                resources.getText(R.string.private_dns_toast).toString(),
                                Toast.LENGTH_SHORT)
        }
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
        observeVpnState()
        handleQuickSettingChips()
    }

    private fun handleShimmer() {
        if (!isVpnStarted) {
            startShimmer()
            return
        }

        val state = VpnController.state().connectionState
        if (state == BraveVPNService.State.WORKING || state == BraveVPNService.State.FAILING) {
            stopShimmer()
        }
    }

    override fun onPause() {
        super.onPause()
        stopShimmer()
    }

    private fun startDnsActivity(screenToLoad: Int) {
        if (!isVpnStarted) {
            //when the dns/firewall is not enabled and VPN is not active. show the dialog to start VPN
            showStartDialog()
            return
        }

        if (isPrivateDnsActive()) {
            showToastUiCentered(requireContext(),
                                resources.getText(R.string.private_dns_toast).toString().capitalize(
                                    Locale.ROOT), Toast.LENGTH_SHORT)
            return
        }

        if (appMode.isDnsActive()) {
            startActivity(isDns = true, screenToLoad)
            return
        }

        openBottomSheet()
        showToastUiCentered(requireContext(), resources.getText(
            R.string.brave_dns_connect_mode_change_firewall).toString().capitalize(Locale.ROOT),
                            Toast.LENGTH_SHORT)

    }

    private fun startFirewallActivity(screenToLoad: Int) {
        if (DEBUG) Log.d(LOG_TAG_VPN,
                         "Status : $isVpnStarted , BraveMode: ${appMode.getBraveMode()}")
        if (!isVpnStarted) {
            //when the dns/firewall is not enabled and VPN is not active. show the dialog to start VPN
            showStartDialog()
            return
        }

        if (appMode.isFirewallActive()) {
            startActivity(isDns = false, screenToLoad)
            return
        }

        openBottomSheet()
        showToastUiCentered(requireContext(), resources.getText(
            R.string.brave_dns_connect_mode_change_firewall).toString().capitalize(Locale.ROOT),
                            Toast.LENGTH_SHORT)
    }

    private fun startActivity(isDns: Boolean, screenToLoad: Int) {
        val intent = when (isDns) {
            true -> Intent(requireContext(), DNSDetailActivity::class.java)
            false -> Intent(requireContext(), FirewallActivity::class.java)
        }
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        intent.putExtra(Constants.SCREEN_TO_LOAD, screenToLoad)
        startActivity(intent)
    }

    private fun getModeText(): String {
        return when (appMode.getBraveMode()) {
            APP_MODE_DNS -> getString(R.string.app_mode_dns)
            APP_MODE_FIREWALL -> getString(R.string.app_mode_firewall)
            else -> getString(R.string.app_mode_dns_firewall)
        }
    }

    private fun showStartDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(getString(R.string.hsf_start_dialog_header, getModeText()))
        builder.setMessage(getString(R.string.hsf_start_dialog_message))
        builder.setCancelable(false)
        builder.setPositiveButton(R.string.hsf_start_dialog_positive) { _, _ ->
            handleMainScreenBtnClickEvent()
            delay(1000) {
                if (isVpnStarted) {
                    openBottomSheet()
                    showToastUiCentered(requireContext(), resources.getText(
                        R.string.brave_dns_connect_mode_change_dns).toString().capitalize(
                        Locale.ROOT), Toast.LENGTH_SHORT)
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
     * FIX : The check for the controller state. If the state of the controller
     * is activationRequested and the VPN is not connected then
     * the start will be initiated.
     */
    private fun maybeAutoStart() {
        if (isVpnStarted && !VpnController.state().on) {
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

        if (appMode.isDnsProxyActive() || appMode.isFirewallMode()) {
            status.connectionState = BraveVPNService.State.WORKING
        }

        if (status.on) {
            colorId = fetchTextColor(R.color.positive)
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
                    colorId = fetchTextColor(R.color.accent_bad)
                    R.string.status_failing
                }
            }
        } else if (isVpnStarted) {
            colorId = fetchTextColor(R.color.accent_bad)
            statusId = R.string.status_waiting
        } else if (isAnotherVpnActive()) {
            colorId = fetchTextColor(R.color.accent_bad)
            statusId = R.string.status_exposed
        } else {
            colorId = fetchTextColor(R.color.accent_bad)
            statusId = when (privateDnsMode) {
                PrivateDnsMode.STRICT -> R.string.status_strict
                else -> R.string.status_exposed
            }
        }

        if (statusId == R.string.status_protected) {
            if (appMode.isDnsMode() && isPrivateDnsActive()) {
                statusId = R.string.status_protected_with_private_dns
                colorId = fetchTextColor(R.color.indicator)
            } else if (appMode.isDnsMode()) {
                statusId = R.string.status_protected
            } else if (appMode.isOrbotProxyEnabled() && isPrivateDnsActive()) {
                statusId = R.string.status_protected_with_tor_private_dns
                colorId = fetchTextColor(R.color.indicator)
            } else if (appMode.isOrbotProxyEnabled()) {
                statusId = R.string.status_protected_with_tor
            } else if ((appMode.isCustomSocks5Enabled() && appMode.isCustomHttpProxyEnabled()) && isPrivateDnsActive()) { // SOCKS5 + Http + PrivateDns
                statusId = R.string.status_protected_with_proxy_private_dns
                colorId = fetchTextColor(R.color.indicator)
            } else if (appMode.isCustomSocks5Enabled() && appMode.isCustomHttpProxyEnabled()) {
                statusId = R.string.status_protected_with_proxy
            } else if (appMode.isCustomSocks5Enabled() && isPrivateDnsActive()) {
                statusId = R.string.status_protected_with_socks5_private_dns
                colorId = fetchTextColor(R.color.indicator)
            } else if (appMode.isCustomHttpProxyEnabled() && isPrivateDnsActive()) {
                statusId = R.string.status_protected_with_http_private_dns
                colorId = fetchTextColor(R.color.indicator)
            } else if (appMode.isCustomHttpProxyEnabled()) {
                statusId = R.string.status_protected_with_http
            } else if (appMode.isCustomSocks5Enabled()) {
                statusId = R.string.status_protected_with_socks5
            } else if (isPrivateDnsActive()) {
                statusId = R.string.status_protected_with_private_dns
                colorId = fetchTextColor(R.color.indicator)
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
        val attributeFetch = if (attr == R.color.positive) {
            R.attr.accentGood
        } else if (attr == R.color.accent_bad) {
            R.attr.accentBad
        } else if (attr == R.color.textColorMain) {
            R.attr.primaryLightColorText
        } else if (attr == R.color.secondaryText) {
            R.attr.invertedPrimaryTextColor
        } else if (attr == R.color.primaryText) {
            R.attr.primaryDarkColorText
        } else if (attr == R.color.black_white) {
            R.attr.primaryTextColor
        } else {
            R.attr.accentGood
        }
        val typedValue = TypedValue()
        val a: TypedArray = requireContext().obtainStyledAttributes(typedValue.data,
                                                                    intArrayOf(attributeFetch))
        val color = a.getColor(0, 0)
        a.recycle()
        return color
    }

}
