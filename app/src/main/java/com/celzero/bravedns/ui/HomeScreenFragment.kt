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
import android.app.Dialog
import android.content.*
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.Handler
import android.text.Html
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.util.Log
import android.view.View
import android.view.Window
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.CategoryInfoRepository
import com.celzero.bravedns.databinding.FragmentHomeScreenBinding
import com.celzero.bravedns.service.*
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appStartTime
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.blockedCount
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.braveMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.braveModeToggler
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.connectedDNS
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.lifeTimeQueries
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.median50
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.Utilities
import com.facebook.shimmer.Shimmer
import org.koin.android.ext.android.inject
import settings.Settings
import java.net.NetworkInterface
import java.net.SocketException
import java.util.*


class HomeScreenFragment : Fragment(R.layout.fragment_home_screen) {
    private val b by viewBinding(FragmentHomeScreenBinding::bind)

    //private lateinit var btmSheetIcon : Button
    private val MAIN_CHANNEL_ID = "vpn"

    private var REQUEST_CODE_PREPARE_VPN: Int = 100

    private val categoryInfoRepository by inject<CategoryInfoRepository>()
    private val appInfoRepository by inject<AppInfoRepository>()
    private val persistentState by inject<PersistentState>()

    companion object {
        //private
        const val DNS_MODE = 0
        const val FIREWALL_MODE = 1
        const val DNS_FIREWALL_MODE = 2
        private const val GREETING_CHANNEL_ID = 2021
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        syncDnsStatus()
        initializeValues()
        initializeClickListeners()

        //updateTime()
        updateUptime()
        registerForBroadCastReceivers()

        checkForHeaderUpdate()
    }

    /**
     *   Function to show the new year wishes to the user,
     *   As of now the function will check for new year time and
     *   will turn the text heading and description.
     */
    private fun checkForHeaderUpdate() {
        val currentTime = Calendar.getInstance(Locale.ROOT)
        val timeToMatch = Calendar.getInstance(Locale.ROOT)
        val timeToOverride = Calendar.getInstance(Locale.ROOT)

        timeToMatch[Calendar.HOUR_OF_DAY] = 23
        timeToMatch[Calendar.MINUTE] = 59
        timeToMatch[Calendar.SECOND] = 59
        timeToMatch[Calendar.DAY_OF_MONTH] = 31
        timeToMatch[Calendar.MONTH] = 11
        timeToMatch[Calendar.YEAR] = 2020

        timeToOverride[Calendar.HOUR_OF_DAY] = 23
        timeToOverride[Calendar.MINUTE] = 59
        timeToOverride[Calendar.SECOND] = 59
        timeToOverride[Calendar.DAY_OF_MONTH] = 1
        timeToOverride[Calendar.MONTH] = 0
        timeToOverride[Calendar.YEAR] = 2021
        if (DEBUG) Log.d(LOG_TAG, "NewYearAlarm : ${currentTime.time}, ${timeToMatch.time}, ${timeToOverride.time} ")
        if (currentTime > timeToMatch && currentTime < timeToOverride) {
            b.fhsTitleRethink.text = getString(R.string.new_year)
            b.fhsTitleRethinkDesc.text = getString(R.string.new_year_desc)
        } else {
            b.fhsTitleRethink.text = getString(R.string.app_name).toLowerCase(Locale.ROOT)
            b.fhsTitleRethinkDesc.text = getString(R.string.backed_by_mozilla)
        }
    }

    /*
        Registering the broadcast receiver for the DNS State and the DNS results returned
     */
    private fun registerForBroadCastReceivers() {
        // Register broadcast receiver
        val intentFilter = IntentFilter(InternalNames.DNS_STATUS.name)
        //intentFilter.addAction(InternalNames.DNS_STATUS.name)
        //intentFilter.addAction(InternalNames.TRACKER.name)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(messageReceiver, intentFilter)
    }


    /*
        Assign initial values to the view and variables.
     */
    private fun initializeValues() {

        braveMode = persistentState.getBraveMode()

        modifyBraveMode(braveMode)

        lifeTimeQueries = persistentState.getNumOfReq()
        //var lifeTimeQ : MutableLiveData<Int> = MutableLiveData()
        //val aList = PersistentState.getExcludedPackagesWifi(requireContext())
        //appsBlocked.postValue(aList!!.size)
        blockedCount.postValue(persistentState.numberOfBlockedRequests)
        connectedDNS.postValue(persistentState.getConnectedDNS())
    }

    private fun initializeClickListeners() {

        b.fhsCardFirewallLl.setOnClickListener{
            startFirewallLogsActivity()
        }

        b.fhsCardDnsLl.setOnClickListener{
            startDNSLogsActivity()
        }

        b.fhsCardDnsConfigure.setOnClickListener{
            startDNSActivity()
        }

        b.fhsCardFirewallConfigure.setOnClickListener{
            startFirewallActivity()
        }

        b.homeFragmentBottomSheetIcon.setOnClickListener {
            b.homeFragmentBottomSheetIcon.isEnabled = false
            openBottomSheet()
            Handler().postDelayed({ b.homeFragmentBottomSheetIcon.isEnabled = true }, 500)
        }

        // Connect/Disconnect button ==> TODO : Change the label to Start and Stop
        b.fhsDnsOnOffBtn.setOnClickListener {
            handleStartBtnClickEvent()
            Handler().postDelayed({ b.homeFragmentBottomSheetIcon.isEnabled = true }, 500)
        }


        braveModeToggler.observe(viewLifecycleOwner, {
            if (DEBUG) Log.d(LOG_TAG, "HomeScreen -> braveModeToggler -> observer")
            if (persistentState.vpnEnabled) {
                updateDNSCardView()
                updateFirewallCardView()
            } else {
                b.fhsCardFirewallApps.text = getString(R.string.firewall_card_text_inactive)
                b.fhsCardFirewallStatus.text = getString(R.string.firewall_card_status_inactive)
                b.fhsCardDnsLatency.text = getString(R.string.dns_card_latency_inactive)
                b.fhsCardDnsConnectedDns.text = getString(R.string.dns_card_connected_status_failure)
                b.fhsCardDnsConfigure.alpha = 0.5F
                b.fhsCardFirewallConfigure.alpha = 0.5F
            }
        })
    }

    private fun updateDNSCardView(){
       if (braveMode == FIREWALL_MODE) {
           b.fhsCardDnsLatency.text = getString(R.string.dns_card_latency_inactive)
           b.fhsCardDnsConnectedDns.text = getString(R.string.dns_card_connected_status_failure)
           b.fhsCardDnsConfigure.alpha = 0.5F
           unregisterObserversForDNS()
        } else {
           b.fhsCardDnsConfigure.alpha = 1F
           registerObserversForDNS()
        }
    }

    /**
     * The observers are for the DNS cards, when the mode is set to DNS/DNS+Firewall.
     * The observers are register to update the UI in the home screen
     */
    private fun registerObserversForDNS(){
        median50.observe(viewLifecycleOwner, {
            b.fhsCardDnsLatency.text = getString(R.string.dns_card_latency_active, median50.value.toString())
        })

        connectedDNS.observe(viewLifecycleOwner, {
            b.fhsCardDnsConnectedDns.text = it
        })
    }

    /**
       * Unregister all the DNS related observers which updates the dns card.
       */
      private fun unregisterObserversForDNS(){
          median50.removeObservers(viewLifecycleOwner)
          connectedDNS.removeObservers(viewLifecycleOwner)
      }

    /**
     * The observers for the firewall card in the home screen, will be calling this method
     * when the VPN is active and the mode is set to either Firewall or DNS+Firewall.
     */
    private fun registerObserversForFirewall(){
        appInfoRepository.getAllAppDetailsForLiveData().observe(viewLifecycleOwner, {
            val blockedList = it.filter { a -> !a.isInternetAllowed }
            val whiteListApps = it.filter { a -> a.whiteListUniv1 }
            val excludedList = it.filter { a -> a.isExcluded }
            b.fhsCardFirewallStatus.text = getString(R.string.firewall_card_status_active, blockedList.size.toString())
            b.fhsCardFirewallApps.text = Html.fromHtml(getString(R.string.firewall_card_text_active, whiteListApps.size.toString(), excludedList.size.toString()))
        })
    }

    /**
     * Unregister all the firewall related observers for the Home screen card.
     */
    private fun unregisterObserversForFirewall(){
        appInfoRepository.getAllAppDetailsForLiveData().removeObservers(viewLifecycleOwner)
    }

    private fun updateFirewallCardView(){
        if (braveMode == DNS_MODE) {
            b.fhsCardFirewallApps.text = getString(R.string.firewall_card_text_inactive)
            b.fhsCardFirewallStatus.text = getString(R.string.firewall_card_status_inactive)
            b.fhsCardFirewallConfigure.alpha = 0.5F
            unregisterObserversForFirewall()
        } else {
            b.fhsCardFirewallConfigure.alpha = 1F
            registerObserversForFirewall()
        }
    }

    private fun handleStartBtnClickEvent() {
        b.fhsDnsOnOffBtn.isEnabled = false
        //TODO : check for the service already running
        //val status = VpnController.getInstance()!!.getState(requireContext())
        val status = persistentState.vpnEnabled
        if (!checkForPrivateDNSandAlwaysON()) {
            //if (status!!.activationRequested) {
            if (status) {
                appStartTime = System.currentTimeMillis()
                b.fhsDnsOnOffBtn.text = "start"
                shimmerForStop()
                b.fhsCardDnsConfigure.alpha = 0.5F
                b.fhsCardFirewallConfigure.alpha = 0.5F
                braveModeToggler.postValue(braveMode)
                //rippleRRLayout.startRippleAnimation()
                b.fhsAppConnectedDesc.text = getString(R.string.dns_explanation_disconnected)
                stopDnsVpnService()
            } else {
                b.fhsCardDnsConfigure.alpha = 1F
                b.fhsCardFirewallConfigure.alpha = 1F
                appStartTime = System.currentTimeMillis()
                if (DEBUG) Log.d(LOG_TAG, "VPN service start initiated with time $appStartTime")
                if (VpnController.getInstance()?.getBraveVpnService() != null) {
                    VpnController.getInstance()?.stop(requireContext())
                    if (DEBUG) Log.d(LOG_TAG, "VPN service start initiated but the brave service is already running - so stop called")
                } else {
                    if (DEBUG) Log.d(LOG_TAG, "VPN service start initiated")
                }
                prepareAndStartDnsVpn()
            }
        } else if (status) {
            Utilities.showToastInMidLayout(requireContext(), getString(R.string.always_on_rethink_enabled), Toast.LENGTH_SHORT)
        }
        Handler().postDelayed({ b.fhsDnsOnOffBtn.isEnabled = true }, 500)
    }

    private fun openBottomSheet() {
        val textConnectionDetails = b.fhsAppConnectedDesc.text.toString()
        val bottomSheetFragment = HomeScreenSettingBottomSheet(textConnectionDetails)
        val frag = context as FragmentActivity
        bottomSheetFragment.show(frag.supportFragmentManager, bottomSheetFragment.tag)
    }


    // FIXME: 19-11-2020 - Check the below code for all the edge cases.
    private fun checkForPrivateDNSandAlwaysON(): Boolean {
        val stats = persistentState.vpnEnabled
        val alwaysOn = android.provider.Settings.Secure.getString(context?.contentResolver, "always_on_vpn_app")
        if (!TextUtils.isEmpty(alwaysOn)) {
            if (context?.packageName == alwaysOn) {
                val status = VpnController.getInstance()!!.getState(requireContext())
                if (status?.connectionState == null) {
                    return false
                }
                if (DEBUG) Log.i(LOG_TAG, "Status: $stats , alwaysOn: $alwaysOn - lockdown")
            } else if (!stats) {
                if (DEBUG) Log.i(LOG_TAG, "Status: $stats , alwaysOn: $alwaysOn - stats value")
                showDialogForAlwaysOn()
            }
            return true
        }
        if (DEBUG) Log.i(LOG_TAG, "Status: $stats , alwaysOn: $alwaysOn")
        if (getPrivateDnsMode() == PrivateDnsMode.STRICT) {
            Utilities.showToastInMidLayout(requireContext(), resources.getText(R.string.private_dns_toast).toString(), Toast.LENGTH_SHORT)
        }
        return false
    }


    private fun startDNSActivity() {
        val status: VpnState? = VpnController.getInstance()!!.getState(context)
        if ((status?.on!!)) {
            if (braveMode == DNS_FIREWALL_MODE || braveMode == DNS_MODE && (getPrivateDnsMode() != PrivateDnsMode.STRICT)) {
                val intent = Intent(requireContext(), DNSDetailActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                intent.putExtra(Constants.SCREEN_TO_LOAD,1)
                startActivity(intent)
            } else {
                if (getPrivateDnsMode() == PrivateDnsMode.STRICT) {
                    Utilities.showToastInMidLayout(requireContext(), resources.getText(R.string.private_dns_toast).toString().capitalize(Locale.ROOT), Toast.LENGTH_SHORT)
                }
                Utilities.showToastInMidLayout(requireContext(), resources.getText(R.string.brave_dns_connect_mode_change_dns).toString().capitalize(Locale.ROOT), Toast.LENGTH_SHORT)
            }
        } else {
            Utilities.showToastInMidLayout(requireContext(), resources.getText(R.string.brave_dns_connect_mode_change_dns).toString().capitalize(Locale.ROOT), Toast.LENGTH_SHORT)
        }
    }

    private fun showDialogForAlwaysOn() {
        val builder = AlertDialog.Builder(requireContext())
        //set title for alert dialog
        builder.setTitle(R.string.always_on_dialog_heading)
        //set message for alert dialog
        builder.setMessage(R.string.always_on_dialog)
        builder.setCancelable(true)
        //performing positive action
        builder.setPositiveButton(R.string.always_on_dialog_positive_btn) { _, _ ->
            val intent = Intent("android.net.vpn.SETTINGS")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }

        builder.setNegativeButton(R.string.always_on_dialog_negative_btn) { _, _ ->
        }

        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.setCancelable(false)
        alertDialog.show()

    }

    override fun onResume() {
        super.onResume()
        syncDnsStatus()
        updateUptime()
        if (persistentState.vpnEnabled) {
            shimmerForStart()
        } else {
            shimmerForStop()
        }
        braveModeToggler.postValue(braveMode)
    }

    private fun startFirewallLogsActivity(){
        val status: VpnState? = VpnController.getInstance()!!.getState(requireContext())
        if (DEBUG) Log.d(LOG_TAG, "Status : ${status?.on!!} , BraveMode: $braveMode")
        if (status?.on!!) {
            if (braveMode == DNS_FIREWALL_MODE || braveMode == FIREWALL_MODE) {
                val intent = Intent(requireContext(), FirewallActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                intent.putExtra(Constants.SCREEN_TO_LOAD,0)
                startActivity(intent)
            } else {
                Utilities.showToastInMidLayout(requireContext(), resources.getText(R.string.brave_dns_connect_mode_change_firewall).toString().capitalize(Locale.ROOT), Toast.LENGTH_SHORT)
            }
        } else {
            Utilities.showToastInMidLayout(requireContext(), resources.getText(R.string.brave_dns_connect_mode_change_firewall).toString().capitalize(Locale.ROOT), Toast.LENGTH_SHORT)
        }
    }

    private fun startDNSLogsActivity(){
        val status: VpnState? = VpnController.getInstance()!!.getState(context)
        if ((status?.on!!)) {
            if (braveMode == DNS_FIREWALL_MODE || braveMode == DNS_MODE && (getPrivateDnsMode() != PrivateDnsMode.STRICT)) {
                val intent = Intent(requireContext(), DNSDetailActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                intent.putExtra(Constants.SCREEN_TO_LOAD,0)
                startActivity(intent)
            } else {
                if (getPrivateDnsMode() == PrivateDnsMode.STRICT) {
                    Utilities.showToastInMidLayout(requireContext(), resources.getText(R.string.private_dns_toast).toString().capitalize(Locale.ROOT), Toast.LENGTH_SHORT)
                }
                Utilities.showToastInMidLayout(requireContext(), resources.getText(R.string.brave_dns_connect_mode_change_dns).toString().capitalize(Locale.ROOT), Toast.LENGTH_SHORT)
            }
        } else {
            Utilities.showToastInMidLayout(requireContext(), resources.getText(R.string.brave_dns_connect_mode_change_dns).toString().capitalize(Locale.ROOT), Toast.LENGTH_SHORT)
        }
    }

    /**
     * Start the Firewall activity
     */
    private fun startFirewallActivity() {
        val status: VpnState? = VpnController.getInstance()!!.getState(requireContext())
        if (DEBUG) Log.d(LOG_TAG, "Status : ${status?.on!!} , BraveMode: $braveMode")
        if (status?.on!!) {
            if (braveMode == DNS_FIREWALL_MODE || braveMode == FIREWALL_MODE) {
                val intent = Intent(requireContext(), FirewallActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                intent.putExtra(Constants.SCREEN_TO_LOAD,2)
                startActivity(intent)
            } else {
                Utilities.showToastInMidLayout(requireContext(), resources.getText(R.string.brave_dns_connect_mode_change_firewall).toString().capitalize(Locale.ROOT), Toast.LENGTH_SHORT)
            }
        } else {
            Utilities.showToastInMidLayout(requireContext(), resources.getText(R.string.brave_dns_connect_mode_change_firewall).toString().capitalize(Locale.ROOT), Toast.LENGTH_SHORT)
        }
    }

    //TODO -- Modify the logic for the below.
    private fun modifyBraveMode(position: Int) {
        var firewallMode = -1L
        if (position == 0) {
            firewallMode = Settings.BlockModeNone
            braveMode = DNS_MODE
        } else if (position == 1) {
            firewallMode = if (VERSION.SDK_INT >= VERSION_CODES.M && VERSION.SDK_INT < VERSION_CODES.Q) Settings.BlockModeFilterProc
            else Settings.BlockModeFilter
            braveMode = FIREWALL_MODE
        } else if (position == 2) {
            firewallMode = if (VERSION.SDK_INT >= VERSION_CODES.M && VERSION.SDK_INT < VERSION_CODES.Q) Settings.BlockModeFilterProc
            else Settings.BlockModeFilter
            braveMode = DNS_FIREWALL_MODE
        }

        if(DEBUG) Log.d(LOG_TAG, "Firewall mode : $firewallMode, braveMode: $braveMode, SDK_Version: ${VERSION.SDK_INT}")

        appMode?.setFirewallMode(firewallMode)

        if (braveMode == FIREWALL_MODE) {
            appMode?.setDNSMode(Settings.DNSModeNone)
        } else {
            appMode?.setDNSMode(-1)
        }

        //PersistentState.setFirewallMode(requireContext(), firewallMode)
        persistentState.setBraveMode(braveMode)

        if (VpnController.getInstance()!!.getState(requireContext())!!.activationRequested) {
            when (braveMode) {
                0 -> {
                    b.fhsAppConnectedDesc.text = getString(R.string.dns_explanation_dns_connected)
                }
                1 -> {
                    b.fhsAppConnectedDesc.text = getString(R.string.dns_explanation_firewall_connected)
                }
                else -> {
                    b.fhsAppConnectedDesc.text = getString(R.string.dns_explanation_connected)
                }
            }
        }
    }

    /**
     * Show the Info dialog for various modes in Brave
     */
    private fun showDialogForBraveModeInfo() {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle("BraveDNS Modes")
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(R.layout.dialog_info_custom_layout)
        val okBtn = dialog.findViewById(R.id.info_dialog_cancel_img) as ImageView
        okBtn.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
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
        val upTime = DateUtils.getRelativeTimeSpanString(appStartTime, System.currentTimeMillis(), MINUTE_IN_MILLIS, FORMAT_ABBREV_RELATIVE)
        b.fhsAppUptime.text = "($upTime)"
    }

    private fun prepareAndStartDnsVpn() {
        if (hasVpnService()) {
            if (prepareVpnService()) {
                b.fhsDnsOnOffBtn.text = "stop"
                b.fhsDnsOnOffBtn.setBackgroundResource(R.drawable.rounded_corners_button_accent)
                shimmerForStart()
                startDnsVpnService()
                braveModeToggler.postValue(braveMode)
                if(DEBUG) Log.d(LOG_TAG, "VPN service start initiated - startDnsVpnService()")
            }
        } else {
            Log.e("BraveVPN", "Device does not support system-wide VPN mode.")
        }
    }

    private fun shimmerForStart() {
        val builder = Shimmer.AlphaHighlightBuilder()
        builder.setDuration(10000)
        builder.setBaseAlpha(0.85f)
        builder.setDropoff(1f)
        builder.setHighlightAlpha(0.35f)
        b.shimmerViewContainer1.setShimmer(builder.build())
    }

    private fun shimmerForStop() {
        val builder = Shimmer.AlphaHighlightBuilder()
        builder.setDuration(2000)
        builder.setBaseAlpha(0.85f)
        builder.setDropoff(1f)
        builder.setHighlightAlpha(0.35f)
        b.shimmerViewContainer1.setShimmer(builder.build())
    }

    private fun stopDnsVpnService() {
        VpnController.getInstance()!!.stop(context)
    }

    private fun startDnsVpnService() {
        VpnController.getInstance()?.start(requireContext())
        if (braveMode == 0) {
            b.fhsAppConnectedDesc.text = getString(R.string.dns_explanation_dns_connected)
        } else if (braveMode == 1) {
            b.fhsAppConnectedDesc.text = getString(R.string.dns_explanation_firewall_connected)
        } else {
            b.fhsAppConnectedDesc.text = getString(R.string.dns_explanation_connected)
        }
    }

    // Returns whether the device supports the tunnel VPN service.
    private fun hasVpnService(): Boolean {
        return VERSION.SDK_INT >= VERSION_CODES.ICE_CREAM_SANDWICH
    }

    @Throws(ActivityNotFoundException::class) private fun prepareVpnService(): Boolean {
        var prepareVpnIntent: Intent? = null
        prepareVpnIntent = try {
            VpnService.prepare(context)
        } catch (e: NullPointerException) {
            // This exception is not mentioned in the documentation, but it has been encountered by Intra
            // users and also by other developers, e.g. https://stackoverflow.com/questions/45470113.
            Log.e("BraveVPN", "Device does not support system-wide VPN mode.")
            return false
        }
        if (prepareVpnIntent != null) {
            startActivityForResult(prepareVpnIntent, REQUEST_CODE_PREPARE_VPN)
            //TODO - Check the below code
            syncDnsStatus() // Set DNS status to off in case the user does not grant VPN permissions
            return false
        }
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PREPARE_VPN && resultCode == Activity.RESULT_OK) {
            startDnsVpnService()
        } else {
            stopDnsVpnService()
        }
    }

    // Sets the UI DNS status on/off.
    private fun syncDnsStatus() {

        val status = VpnController.getInstance()!!.getState(requireContext())

        if (status!!.activationRequested || status.connectionState == BraveVPNService.State.WORKING) {
            b.fhsDnsOnOffBtn.setBackgroundResource(R.drawable.rounded_corners_button_accent)
            b.fhsDnsOnOffBtn.text = "stop"
            if (braveMode == 0) b.fhsAppConnectedDesc.text = getString(R.string.dns_explanation_dns_connected)
            else if (braveMode == 1) b.fhsAppConnectedDesc.text = getString(R.string.dns_explanation_firewall_connected)
            else b.fhsAppConnectedDesc.text = getString(R.string.dns_explanation_connected)
        } else {
            b.fhsDnsOnOffBtn.setBackgroundResource(R.drawable.rounded_corners_button_primary)
            shimmerForStop()
            b.fhsDnsOnOffBtn.text = "start"
            b.fhsAppConnectedDesc.text = getString(R.string.dns_explanation_disconnected)
        }

        // Change status and explanation text
        var statusId: Int = R.string.status_exposed
        val explanationId: Int
        var privateDnsMode: PrivateDnsMode = PrivateDnsMode.NONE
        if (status.activationRequested) {
            when {
                status.connectionState == null -> {
                    statusId = R.string.status_waiting
                    explanationId = R.string.explanation_offline
                }
                status.connectionState === BraveVPNService.State.NEW -> {
                    statusId = R.string.status_starting
                    explanationId = R.string.explanation_starting
                }
                status.connectionState === BraveVPNService.State.WORKING -> {
                    statusId = R.string.status_protected
                    explanationId = R.string.explanation_protected
                }
                else -> {
                    statusId = R.string.status_failing
                    explanationId = R.string.explanation_failing
                }
            }
        } else if (isAnotherVpnActive()) {
            statusId = R.string.status_exposed
            explanationId = R.string.explanation_vpn
        } else {
            privateDnsMode = getPrivateDnsMode()
            when (privateDnsMode) {
                PrivateDnsMode.STRICT -> {
                    statusId = R.string.status_strict
                    explanationId = R.string.explanation_strict
                }
                PrivateDnsMode.UPGRADED -> {
                    statusId = R.string.status_exposed
                    explanationId = R.string.explanation_upgraded
                }
                else -> {
                    statusId = R.string.status_exposed
                    explanationId = R.string.explanation_exposed
                }
            }
        }
        if (DEBUG) Log.d(LOG_TAG, "Status - ${status.activationRequested}, ${status.connectionState}")

        if (status.connectionState == BraveVPNService.State.WORKING) {
            statusId = R.string.status_protected
        }

        if (braveMode == 1 && status.activationRequested) {
            statusId = R.string.status_protected
        }

        if (appMode?.getDNSType() == 3 && status.activationRequested) {
            statusId = R.string.status_protected
        }

        var colorId: Int
        colorId = if (status.on) {
            if (status.connectionState != BraveVPNService.State.FAILING) R.color.positive else R.color.accent_bad
        } else if (privateDnsMode == PrivateDnsMode.STRICT) {
            // If the VPN is off but we're in strict mode, show the status in white.  This isn't a bad
            // state, but Intra isn't helping.
            R.color.indicator
        } else {
            R.color.accent_bad
        }
        if (braveMode == FIREWALL_MODE && status.activationRequested) colorId = R.color.positive

        val color = ContextCompat.getColor(requireContext(), colorId)
        b.fhsProtectionLevelTxt.setTextColor(color)
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
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (VERSION.SDK_INT < VERSION_CODES.M) {
            // getActiveNetwork() requires M or later.
            return null
        }
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        return connectivityManager.getLinkProperties(activeNetwork)
    }

    private fun isAnotherVpnActive(): Boolean {
        if (VERSION.SDK_INT >= VERSION_CODES.M) {
            val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: // It's not clear when this can happen, but it has occurred for at least one user.
            return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        // For pre-M versions, return true if there's any network whose name looks like a VPN.
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                val name = networkInterface.name

                if (networkInterface.isUp && name != null && (name.startsWith("tun") || name.startsWith("pptp") || name.startsWith("l2tp"))) {
                    return true
                }
            }
        } catch (e: SocketException) {
            Log.e(LOG_TAG, e.message, e)
        }
        return false
    }

    enum class PrivateDnsMode {
        NONE,  // The setting is "Off" or "Opportunistic", and the DNS connection is not using TLS.
        UPGRADED,  // The setting is "Opportunistic", and the DNS connection has upgraded to TLS.
        STRICT // The setting is "Strict".
    }

}
