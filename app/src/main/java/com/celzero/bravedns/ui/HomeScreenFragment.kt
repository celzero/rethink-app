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
import android.content.*
import android.content.res.TypedArray
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Html
import android.text.format.DateUtils
import android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.text.HtmlCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfoViewRepository
import com.celzero.bravedns.databinding.FragmentHomeScreenBinding
import com.celzero.bravedns.service.*
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appStartTime
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.braveMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.braveModeToggler
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.connectedDNS
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.lifeTimeQueries
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_DNS
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_DNS_FIREWALL
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_FIREWALL
import com.celzero.bravedns.util.Constants.Companion.DNS_SCREEN_CONFIG
import com.celzero.bravedns.util.Constants.Companion.DNS_SCREEN_LOGS
import com.celzero.bravedns.util.Constants.Companion.FIREWALL_SCREEN_ALL_APPS
import com.celzero.bravedns.util.Constants.Companion.FIREWALL_SCREEN_UNIVERSAL
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_INVALID
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_PROXY
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_UI
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.isAlwaysOnEnabled
import com.celzero.bravedns.util.Utilities.Companion.isOtherVpnHasAlwaysOn
import com.celzero.bravedns.util.Utilities.Companion.openVpnProfile
import com.facebook.shimmer.Shimmer
import org.koin.android.ext.android.inject
import settings.Settings
import java.net.NetworkInterface
import java.net.SocketException
import java.util.*

class HomeScreenFragment : Fragment(R.layout.fragment_home_screen) {
    private val b by viewBinding(FragmentHomeScreenBinding::bind)

    private val appInfoViewRepository by inject<AppInfoViewRepository>()
    private val persistentState by inject<PersistentState>()

    companion object {
        private var REQUEST_CODE_PREPARE_VPN: Int = 100
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        syncDnsStatus()
        initializeValues()
        initializeClickListeners()
        updateUptime()
        registerForBroadCastReceivers()
    }

    /*
        Registering the broadcast receiver for the DNS State and the DNS results returned
     */
    private fun registerForBroadCastReceivers() {
        // Register broadcast receiver
        val intentFilter = IntentFilter(InternalNames.DNS_STATUS.name)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(messageReceiver,
                                                                             intentFilter)
    }

    /*
        Assign initial values to the view and variables.
     */
    private fun initializeValues() {

        braveMode = persistentState.getBraveMode()

        modifyBraveMode(braveMode)

        lifeTimeQueries = persistentState.getLifetimeQueries()

        persistentState.blockedCount.postValue(persistentState.numberOfBlockedRequests)
        connectedDNS.postValue(persistentState.getConnectedDNS())
    }

    private fun initializeClickListeners() {

        b.fhsCardFirewallLl.setOnClickListener {
            startActivity(false, FIREWALL_SCREEN_UNIVERSAL)
        }

        b.fhsCardDnsLl.setOnClickListener {
            startActivity(true, DNS_SCREEN_LOGS)
        }

        b.fhsCardDnsConfigure.setOnClickListener {
            startActivity(true, DNS_SCREEN_CONFIG)
        }

        b.fhsCardDnsConfigureLl.setOnClickListener {
            startActivity(true, DNS_SCREEN_LOGS)
        }

        b.fhsCardFirewallConfigure.setOnClickListener {
            startActivity(false, FIREWALL_SCREEN_ALL_APPS)
        }

        b.fhsCardFirewallConfigureLl.setOnClickListener {
            startActivity(false, FIREWALL_SCREEN_ALL_APPS)
        }

        /**
         * TODO Replace the handlers with the timer.
         */
        b.homeFragmentBottomSheetIcon.setOnClickListener {
            b.homeFragmentBottomSheetIcon.isEnabled = false
            openBottomSheet()
            object : CountDownTimer(500, 500) {
                override fun onTick(millisUntilFinished: Long) {
                }

                override fun onFinish() {
                    b.homeFragmentBottomSheetIcon.isEnabled = true
                }
            }.start()
        }

        // Connect/Disconnect button ==> TODO : Change the label to Start and Stop
        b.fhsDnsOnOffBtn.setOnClickListener {
            handleStartBtnClickEvent()
            object : CountDownTimer(500, 500) {
                override fun onTick(millisUntilFinished: Long) {
                }

                override fun onFinish() {
                    b.homeFragmentBottomSheetIcon.isEnabled = true
                }
            }.start()
        }


        braveModeToggler.observe(viewLifecycleOwner, {
            if (persistentState.vpnEnabled) {
                updateFirewallCardView()
                updateDNSCardView()
            } else {
                b.fhsCardFirewallApps.text = getString(R.string.firewall_card_text_inactive)
                b.fhsCardFirewallStatus.text = getString(R.string.firewall_card_status_inactive)
                b.fhsCardDnsLatency.text = getString(R.string.dns_card_latency_inactive)
                b.fhsCardDnsConnectedDns.text = getString(
                    R.string.dns_card_connected_status_failure)
                b.fhsCardDnsConfigure.alpha = 0.5F
                b.fhsCardFirewallConfigure.alpha = 0.5F
                b.fhsCardDnsConfigure.setTextColor(fetchTextColor(R.color.textColorMain))
                b.fhsCardFirewallConfigure.setTextColor(fetchTextColor(R.color.textColorMain))
            }
        })
    }


    private fun updateDNSCardView() {
        if (braveMode == APP_MODE_FIREWALL) {
            b.fhsCardDnsLatency.text = getString(R.string.dns_card_latency_inactive)
            b.fhsCardDnsConnectedDns.text = getString(R.string.dns_card_connected_status_failure)
            b.fhsCardDnsConfigure.setTextColor(fetchTextColor(R.color.textColorMain))
            b.fhsCardDnsConfigure.alpha = 0.5F
            unregisterObserversForDNS()
        } else {
            b.fhsCardDnsConfigure.alpha = 1F
            b.fhsCardDnsConfigure.setTextColor(fetchTextColor(R.color.secondaryText))
            registerObserversForDNS()
        }
    }

    /**
     * The observers are for the DNS cards, when the mode is set to DNS/DNS+Firewall.
     * The observers are register to update the UI in the home screen
     */
    private fun registerObserversForDNS() {
        persistentState.median.observe(viewLifecycleOwner, {
            b.fhsCardDnsLatency.text = getString(R.string.dns_card_latency_active, it.toString())
        })

        connectedDNS.observe(viewLifecycleOwner, {
            b.fhsCardDnsConnectedDns.text = it
        })
    }

    /**
     * Unregister all the DNS related observers which updates the dns card.
     */
    private fun unregisterObserversForDNS() {
        persistentState.median.removeObservers(viewLifecycleOwner)
        connectedDNS.removeObservers(viewLifecycleOwner)
    }

    /**
     * The observers for the firewall card in the home screen, will be calling this method
     * when the VPN is active and the mode is set to either Firewall or DNS+Firewall.
     */
    private fun registerObserversForFirewall() {
        appInfoViewRepository.getAllAppDetailsForLiveData().observe(viewLifecycleOwner, {
            val blockedList = it.filter { a -> !a.isInternetAllowed }
            val whiteListApps = it.filter { a -> a.whiteListUniv1 }
            val excludedList = it.filter { a -> a.isExcluded }
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
    private fun unregisterObserversForFirewall() {
        appInfoViewRepository.getAllAppDetailsForLiveData().removeObservers(viewLifecycleOwner)
    }

    private fun updateFirewallCardView() {
        if (braveMode == APP_MODE_DNS) {
            b.fhsCardFirewallApps.text = getString(R.string.firewall_card_text_inactive)
            b.fhsCardFirewallStatus.text = getString(R.string.firewall_card_status_inactive)
            b.fhsCardFirewallConfigure.alpha = 0.5F
            b.fhsCardFirewallConfigure.setTextColor(fetchTextColor(R.color.textColorMain))
            unregisterObserversForFirewall()
        } else {
            registerObserversForFirewall()
            b.fhsCardFirewallConfigure.alpha = 1F
            b.fhsCardFirewallConfigure.setTextColor(fetchTextColor(R.color.secondaryText))
        }
    }

    private fun handleStartBtnClickEvent() {
        b.fhsDnsOnOffBtn.isEnabled = false
        //TODO : check for the service already running
        val status = persistentState.vpnEnabled
        if (!checkForPrivateDNSandAlwaysON()) {
            if (status) {
                appStartTime = System.currentTimeMillis()
                b.fhsDnsOnOffBtn.text = getString(R.string.hsf_start_btn_state)
                stopShimmer()
                b.fhsCardDnsConfigure.alpha = 0.5F
                b.fhsCardFirewallConfigure.alpha = 0.5F
                b.fhsCardDnsConfigure.setTextColor(fetchTextColor(R.color.textColorMain))
                b.fhsCardFirewallConfigure.setTextColor(fetchTextColor(R.color.textColorMain))
                braveModeToggler.postValue(braveMode)
                b.fhsAppConnectedDesc.text = getString(R.string.dns_explanation_disconnected)
                stopVpnService()
            } else {
                b.fhsCardDnsConfigure.alpha = 1F
                b.fhsCardFirewallConfigure.alpha = 1F
                b.fhsCardDnsConfigure.setTextColor(fetchTextColor(R.color.secondaryText))
                b.fhsCardFirewallConfigure.setTextColor(fetchTextColor(R.color.secondaryText))
                appStartTime = System.currentTimeMillis()
                val isVpnServiceAvailable = VpnController.getInstance().getBraveVpnService()
                if (DEBUG) Log.d(LOG_TAG_VPN,
                                 "VPN service start initiated with time $appStartTime, isVpnServiceAvailable - $isVpnServiceAvailable")
                if (isVpnServiceAvailable != null) {
                    VpnController.getInstance().stop(requireContext())
                }
                prepareAndStartDnsVpn()
            }
        } else if (status) {
            showStopDialogAlwaysOn()
        }
        object : CountDownTimer(500, 500) {
            override fun onTick(millisUntilFinished: Long) {
            }

            override fun onFinish() {
                b.fhsDnsOnOffBtn.isEnabled = true
            }
        }.start()
    }

    private fun showStopDialogAlwaysOn() {
        val builder = AlertDialog.Builder(requireContext())
        val isLockDownEnabled = isVpnLockdown()

        builder.setTitle(R.string.always_on_dialog_stop_heading)
        if (isLockDownEnabled == true) {
            // The Html.fromHtml requires a wrap around with the check or need to add requiresApi for the method so added the check.
            if (VERSION.SDK_INT >= VERSION_CODES.N) {
                builder.setMessage(
                    Html.fromHtml(getString(R.string.always_on_dialog_lockdown_stop_message),
                                  HtmlCompat.FROM_HTML_MODE_COMPACT))
            } else {
                builder.setMessage(
                    HtmlCompat.fromHtml(getString(R.string.always_on_dialog_lockdown_stop_message),
                                        HtmlCompat.FROM_HTML_MODE_COMPACT))
            }
        } else {
            builder.setMessage(R.string.always_on_dialog_stop_message)
        }

        builder.setCancelable(true)
        builder.setPositiveButton(R.string.always_on_dialog_positive) { _, _ ->
            stopVpnService()
        }

        builder.setNegativeButton(R.string.always_on_dialog_negative) { _, _ ->
        }

        builder.setNeutralButton(R.string.always_on_dialog_neutral) { _, _ ->
            openVpnProfile(requireContext())
        }

        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(false)
        alertDialog.show()
    }

    private fun isVpnLockdown(): Boolean? {
        if (VERSION.SDK_INT < VERSION_CODES.Q) {
            return false
        }
        return VpnController.getInstance().getBraveVpnService()?.isLockdownEnabled
    }

    private fun openBottomSheet() {
        val textConnectionDetails = b.fhsAppConnectedDesc.text.toString()
        val bottomSheetFragment = HomeScreenSettingBottomSheet(textConnectionDetails)
        val frag = context as FragmentActivity
        bottomSheetFragment.show(frag.supportFragmentManager, bottomSheetFragment.tag)
    }


    // FIXME: 19-11-2020 - Check the below code for all the edge cases.
    private fun checkForPrivateDNSandAlwaysON(): Boolean {
        val vpnService = VpnController.getInstance().getBraveVpnService()
        val alwaysOn = isAlwaysOnEnabled(vpnService, requireContext())
        if (DEBUG) Log.i(LOG_TAG_VPN, "AlwaysOn: $alwaysOn, isVpnService available? ${vpnService != null}")

        if (isOtherVpnHasAlwaysOn(requireContext())) {
            showDisableAlwaysOnDialog()
            return true
        }

        if (alwaysOn && vpnService != null) {
            return true
        }

        if (getPrivateDnsMode() == PrivateDnsMode.STRICT) {
            Utilities.showToastUiCentered(requireContext(),
                                          resources.getText(R.string.private_dns_toast).toString(),
                                          Toast.LENGTH_SHORT)
        }
        return false
    }

    private fun showDisableAlwaysOnDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.always_on_dialog_heading)
        builder.setMessage(R.string.always_on_dialog)
        builder.setCancelable(true)
        builder.setPositiveButton(R.string.always_on_dialog_positive_btn) { _, _ ->
            val intent = Intent(Constants.VPN_INTENT)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }

        builder.setNegativeButton(R.string.always_on_dialog_negative_btn) { _, _ ->
        }

        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(false)
        alertDialog.show()
    }

    override fun onResume() {
        super.onResume()
        autoStartCheck()
        updateUptime()
        if (persistentState.vpnEnabled) {
            startShimmer()
        } else {
            stopShimmer()
        }
        braveModeToggler.postValue(braveMode)
        syncDnsStatus()
    }

    private fun startActivity(isDns: Boolean, screenToLoad: Int) {
        val status: VpnState = VpnController.getInstance().getState()
        if (DEBUG) Log.d(LOG_TAG_VPN, "Status : ${status.on} , BraveMode: $braveMode")
        if (!status.on) {
            //when the DNS/Firewall is not enabled and VPN is not active. show the dialog to start VPN
            showStartDialog()
            return
        }
        when (braveMode) {
            APP_MODE_DNS_FIREWALL -> {
                startActivityIntent(isDns, screenToLoad)
                return
            }
            APP_MODE_FIREWALL -> {
                if (!isDns) {
                    startActivityIntent(isDns, screenToLoad)
                    return
                }
            }
            APP_MODE_DNS -> {
                if (isDns) {
                    startActivityIntent(isDns, screenToLoad)
                    return
                }
            }
        }
        openBottomSheet()
        Utilities.showToastUiCentered(requireContext(), resources.getText(
            R.string.brave_dns_connect_mode_change_firewall).toString().capitalize(Locale.ROOT),
                                      Toast.LENGTH_SHORT)
    }

    private fun startActivityIntent(isDns: Boolean, screenToLoad: Int) {
        val intent = when (isDns) {
            true -> Intent(requireContext(), DNSDetailActivity::class.java)
            false -> Intent(requireContext(), FirewallActivity::class.java)
        }
        intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        intent.putExtra(Constants.SCREEN_TO_LOAD, screenToLoad)
        startActivity(intent)
    }

    private fun getModeText(): String {
        return when (braveMode) {
            APP_MODE_DNS -> getString(R.string.app_mode_dns)
            APP_MODE_FIREWALL -> getString(R.string.app_mode_firewall)
            else -> getString(R.string.app_mode_dns_firewall)
        }
    }

    private fun showStartDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(getString(R.string.hsf_start_dialog_header, getModeText()))
        builder.setMessage(getString(R.string.hsf_start_dialog_message))
        builder.setCancelable(true)
        builder.setPositiveButton(R.string.hsf_start_dialog_positive) { _, _ ->
            handleStartBtnClickEvent()
            //Check for the VPN is first time, if so don't show the bottom sheet.
            val prepareVpnIntent: Intent? = try {
                VpnService.prepare(context)
            } catch (e: NullPointerException) {
                // In some cases, VpnService.prepare results in null pointer exception.
                // java.lang.NullPointerException: Attempt to invoke virtual method
                // VpnService.prepare(context) on a null object reference
                Log.w(LOG_TAG_VPN, "Prepare VPN Exception - ${e.message}", e)
                null
            }
            //If the VPN.prepare is not null, then the first time VPN dialog is shown.
            if (prepareVpnIntent == null) {
                openBottomSheet()
                Utilities.showToastUiCentered(requireContext(), resources.getText(
                    R.string.brave_dns_connect_mode_change_dns).toString().capitalize(Locale.ROOT),
                                              Toast.LENGTH_SHORT)
            }
        }

        builder.setNegativeButton(R.string.hsf_start_dialog_negative) { dialog, _ ->
            dialog.dismiss()
        }
        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(false)
        alertDialog.show()
    }


    //TODO -- Modify the logic for the below.
    private fun modifyBraveMode(position: Int) {
        var firewallMode = PREF_DNS_INVALID
        if (position == APP_MODE_DNS) {
            firewallMode = Settings.BlockModeNone
            braveMode = APP_MODE_DNS
        } else if (position == APP_MODE_FIREWALL) {
            firewallMode = if (VERSION.SDK_INT >= VERSION_CODES.M && VERSION.SDK_INT < VERSION_CODES.Q) Settings.BlockModeFilterProc
            else Settings.BlockModeFilter
            braveMode = APP_MODE_FIREWALL
        } else if (position == APP_MODE_DNS_FIREWALL) {
            firewallMode = if (VERSION.SDK_INT >= VERSION_CODES.M && VERSION.SDK_INT < VERSION_CODES.Q) Settings.BlockModeFilterProc
            else Settings.BlockModeFilter
            braveMode = APP_MODE_DNS_FIREWALL
        }

        if (DEBUG) Log.d(LOG_TAG_VPN,
                         "Firewall mode : $firewallMode, braveMode: $braveMode, SDK_Version: ${VERSION.SDK_INT}")

        appMode?.setFirewallMode(firewallMode)

        if (braveMode == APP_MODE_FIREWALL) {
            appMode?.setDNSMode(Settings.DNSModeNone)
        } else {
            appMode?.setDNSMode(PREF_DNS_INVALID)
        }

        persistentState.setBraveMode(braveMode)

        if (VpnController.getInstance().getState().activationRequested) {
            updateConnDesc()
        }
    }

    private fun updateConnDesc() {
        when (braveMode) {
            APP_MODE_DNS -> {
                b.fhsAppConnectedDesc.text = getString(R.string.dns_explanation_dns_connected)
            }
            APP_MODE_FIREWALL -> {
                b.fhsAppConnectedDesc.text = getString(R.string.dns_explanation_firewall_connected)
            }
            else -> {
                b.fhsAppConnectedDesc.text = getString(R.string.dns_explanation_connected)
            }
        }
    }


    private val messageReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (InternalNames.DNS_STATUS.name == intent.action) {
                syncDnsStatus()
            }
        }
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(messageReceiver)
        super.onDestroy()
    }

    private fun updateUptime() {
        val upTime = DateUtils.getRelativeTimeSpanString(appStartTime, System.currentTimeMillis(),
                                                         MINUTE_IN_MILLIS, FORMAT_ABBREV_RELATIVE)
        b.fhsAppUptime.text = getString(R.string.hsf_uptime, upTime)
    }

    private fun prepareAndStartDnsVpn() {
        if (prepareVpnService()) {
            b.fhsDnsOnOffBtn.text = getString(R.string.hsf_stop_btn_state)
            b.fhsDnsOnOffBtn.setBackgroundResource(R.drawable.rounded_corners_button_accent)
            startShimmer()
            startVpnService()
            braveModeToggler.postValue(braveMode)
            if (DEBUG) Log.d(LOG_TAG_VPN, "VPN service start initiated - startDnsVpnService()")
        }
    }

    private fun startShimmer() {
        if (DEBUG) Log.d(LOG_TAG_UI, "Shimmer stop executed")
        b.shimmerViewContainer1.stopShimmer()
    }

    private fun stopShimmer() {
        val builder = Shimmer.AlphaHighlightBuilder()
        builder.setDuration(2000)
        builder.setBaseAlpha(0.85f)
        builder.setDropoff(1f)
        builder.setHighlightAlpha(0.35f)
        b.shimmerViewContainer1.setShimmer(builder.build())
        if (DEBUG) Log.d(LOG_TAG_UI, "Shimmer Start executed")
        b.shimmerViewContainer1.startShimmer()
    }

    private fun stopVpnService() {
        VpnController.getInstance().stop(requireContext())
    }

    private fun startVpnService() {
        VpnController.getInstance().start(requireContext())
        updateConnDesc()
    }

    @Throws(ActivityNotFoundException::class)
    private fun prepareVpnService(): Boolean {
        val prepareVpnIntent: Intent? = try {
            VpnService.prepare(context)
        } catch (e: NullPointerException) {
            // This exception is not mentioned in the documentation, but it has been encountered
            // users and also by other developers, e.g. https://stackoverflow.com/questions/45470113.
            Log.e("BraveVPN", "Device does not support system-wide VPN mode.", e)
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
        builder.setCancelable(true)
        builder.setPositiveButton(R.string.hsf_vpn_dialog_positive) { _, _ ->
            startActivityForResult(prepareVpnIntent, REQUEST_CODE_PREPARE_VPN)
        }

        builder.setNegativeButton(R.string.hsf_vpn_dialog_negative) { _, _ ->
            b.fhsCardDnsConfigure.alpha = 0.5F
            b.fhsCardFirewallConfigure.alpha = 0.5F
            b.fhsCardDnsConfigure.setTextColor(fetchTextColor(R.color.textColorMain))
            b.fhsCardFirewallConfigure.setTextColor(fetchTextColor(R.color.textColorMain))
        }
        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(false)
        alertDialog.show()
    }

    override fun onPause() {
        super.onPause()
        b.shimmerViewContainer1.stopShimmer()
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
    private fun autoStartCheck() {
        val controller = VpnController.getInstance()
        val state = controller.getState()
        if (state.activationRequested && !state.on) {
            Log.i(LOG_TAG_VPN, "start VPN (previous state)")
            prepareAndStartDnsVpn()
        }
    }


    // Sets the UI DNS status on/off.
    private fun syncDnsStatus() {

        val status = VpnController.getInstance().getState()

        if (status.activationRequested || status.connectionState == BraveVPNService.State.WORKING) {
            b.fhsDnsOnOffBtn.setBackgroundResource(R.drawable.rounded_corners_button_accent)
            b.fhsDnsOnOffBtn.text = getString(R.string.hsf_stop_btn_state)
            updateConnDesc()
        } else {
            b.fhsDnsOnOffBtn.setBackgroundResource(R.drawable.rounded_corners_button_primary)
            stopShimmer()
            b.fhsDnsOnOffBtn.text = getString(R.string.hsf_start_btn_state)
            b.fhsAppConnectedDesc.text = getString(R.string.dns_explanation_disconnected)
        }

        // Change status and explanation text
        var statusId: Int
        //val explanationId: Int
        var privateDnsMode: PrivateDnsMode = PrivateDnsMode.NONE
        if (status.activationRequested) {
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
                    R.string.status_failing
                }
            }
        } else if (isAnotherVpnActive()) {
            statusId = R.string.status_exposed
        } else {
            privateDnsMode = getPrivateDnsMode()
            statusId = when (privateDnsMode) {
                PrivateDnsMode.STRICT -> {
                    R.string.status_strict
                }
                PrivateDnsMode.UPGRADED -> {
                    R.string.status_exposed
                }
                else -> {
                    R.string.status_exposed
                }
            }
        }
        if (DEBUG) Log.d(LOG_TAG_VPN,
                         "Status - ${status.activationRequested}, ${status.connectionState}")

        if (status.connectionState == BraveVPNService.State.WORKING) {
            statusId = R.string.status_protected
        }

        if (braveMode == APP_MODE_FIREWALL && status.activationRequested) {
            statusId = R.string.status_protected
        }

        var colorId: Int
        colorId = if (status.on) {
            if (status.connectionState != BraveVPNService.State.FAILING) fetchTextColor(
                R.color.positive) else fetchTextColor(R.color.accent_bad)
        } else if (privateDnsMode == PrivateDnsMode.STRICT) {
            // If the VPN is off but we're in strict mode, show the status in white.  This isn't a bad
            // state, but Intra isn't helping.
            fetchTextColor(R.color.indicator)
        } else {
            fetchTextColor(R.color.accent_bad)
        }
        if (braveMode == APP_MODE_FIREWALL && status.activationRequested) colorId = fetchTextColor(
            R.color.positive)
        if (appMode?.getDNSType() == PREF_DNS_MODE_PROXY && status.activationRequested) {
            statusId = R.string.status_protected
            colorId = fetchTextColor(R.color.positive)
        }
        if (statusId == R.string.status_protected && persistentState.orbotRequestMode != Constants.ORBOT_MODE_NONE) {
            statusId = R.string.status_protected_with_tor
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
        } else PrivateDnsMode.NONE
    }

    private fun getLinkProperties(): LinkProperties? {
        val connectivityManager = requireContext().getSystemService(
            Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (VERSION.SDK_INT < VERSION_CODES.M) {
            // getActiveNetwork() requires M or later.
            return null
        }
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        return connectivityManager.getLinkProperties(activeNetwork)
    }

    private fun isAnotherVpnActive(): Boolean {
        if (VERSION.SDK_INT >= VERSION_CODES.M) {
            val connectivityManager = requireContext().getSystemService(
                Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(
                activeNetwork) ?: // It's not clear when this can happen, but it has occurred for at least one user.
            return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        // For pre-M versions, return true if there's any network whose name looks like a VPN.
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                val name = networkInterface.name

                if (networkInterface.isUp && name != null && (name.startsWith(
                        "tun") || name.startsWith("pptp") || name.startsWith("l2tp"))) {
                    return true
                }
            }
        } catch (e: SocketException) {
            Log.w(LOG_TAG_VPN, e.message, e)
        }
        return false
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
