package com.celzero.bravedns.ui

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomSheetHomeScreenBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koin.android.ext.android.inject
import settings.Settings

class HomeScreenSettingBottomSheet() : BottomSheetDialogFragment() {
    private var _binding: BottomSheetHomeScreenBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val b get() = _binding!!

    private var firewallMode = -1L
    private val LOG_FILE = "HOMESCREEN_BTM_SHEET"
    private var connectedDetails : String = ""

    private val persistentState by inject<PersistentState>()

    override fun getTheme(): Int = if (persistentState.theme == 0) {
        if (isDarkThemeOn()) {
            R.style.BottomSheetDialogTheme
        } else {
            R.style.BottomSheetDialogTheme_white
        }
    } else if (persistentState.theme == 1) {
        R.style.BottomSheetDialogTheme_white
    } else {
        R.style.BottomSheetDialogTheme
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetHomeScreenBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    constructor(connectedDetails : String) : this(){
        this.connectedDetails = connectedDetails
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        updateUptime()
        initializeClickListeners()
    }

    private fun initView() {
        b.bsHomeScreenConnectedStatus.text = connectedDetails
        var selectedIndex = HomeScreenActivity.GlobalVariable.braveMode
        if (DEBUG) Log.d(LOG_TAG, "$LOG_FILE - selectedIndex: $selectedIndex")
        updateBraveModeUI()
        if(selectedIndex == -1)
            selectedIndex = persistentState.getBraveMode()

        when (selectedIndex) {
            HomeScreenFragment.DNS_MODE -> {
                b.bsHomeScreenRadioDns.isChecked = true   //bs_home_screen_radio_dns
            }HomeScreenFragment.FIREWALL_MODE -> {
                b.bsHomeScreenRadioFirewall.isChecked = true //bs_home_screen_radio_firewall
            }HomeScreenFragment.DNS_FIREWALL_MODE-> {
                b.bsHomeScreenRadioDnsFirewall.isChecked = true //bs_home_screen_radio_dns_firewall
            }else -> {
                b.bsHomeScreenRadioDnsFirewall.isChecked = true
            }
        }
    }

    private fun initializeClickListeners() {
        b.bsHomeScreenRadioDns.setOnCheckedChangeListener{ _: CompoundButton, isSelected: Boolean ->
            if(isSelected){
               enableDNSMode()
            }
        }

        b.bsHomeScreenRadioFirewall.setOnCheckedChangeListener{ _: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
               enableFirewallMode()
            }
        }

        b.bsHomeScreenRadioDnsFirewall.setOnCheckedChangeListener{ _: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
               enableDNSFirewallMode()
            }
        }

        //bs_hs_dns_firewall_rl
        b.bsHsDnsRl.setOnClickListener{
            if(!b.bsHomeScreenRadioDns.isChecked){
                b.bsHomeScreenRadioDns.isChecked = true
               enableDNSMode()
            }
        }

        b.bsHsFirewallRl.setOnClickListener{
            if (!b.bsHomeScreenRadioFirewall.isChecked) {
                b.bsHomeScreenRadioFirewall.isChecked = true
                enableFirewallMode()
            }
        }

        b.bsHsDnsFirewallRl.setOnClickListener{
            if (!b.bsHomeScreenRadioDnsFirewall.isChecked) {
                b.bsHomeScreenRadioDnsFirewall.isChecked = true
                enableDNSFirewallMode()
            }
        }
    }

    private fun enableDNSMode(){
        b.bsHomeScreenRadioFirewall.isChecked = false
        b.bsHomeScreenRadioDnsFirewall.isChecked = false
        firewallMode = Settings.BlockModeNone
        HomeScreenActivity.GlobalVariable.braveMode = HomeScreenFragment.DNS_MODE
        modifyBraveMode()
    }

    private fun enableFirewallMode(){
        b.bsHomeScreenRadioDns.isChecked = false
        b.bsHomeScreenRadioDnsFirewall.isChecked = false
        firewallMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) Settings.BlockModeFilterProc
        else Settings.BlockModeFilter
        HomeScreenActivity.GlobalVariable.braveMode = HomeScreenFragment.FIREWALL_MODE
        modifyBraveMode()
    }

    private fun enableDNSFirewallMode(){
        b.bsHomeScreenRadioDns.isChecked = false
        b.bsHomeScreenRadioFirewall.isChecked = false
        firewallMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) Settings.BlockModeFilterProc
        else Settings.BlockModeFilter
        HomeScreenActivity.GlobalVariable.braveMode = HomeScreenFragment.DNS_FIREWALL_MODE
        modifyBraveMode()
    }

    private fun updateUptime() {
        val upTime = DateUtils.getRelativeTimeSpanString(HomeScreenActivity.GlobalVariable.appStartTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        b.bsHomeScreenAppUptime.text = getString(R.string.hsf_uptime, upTime)
    }

    private fun modifyBraveMode() {
        HomeScreenActivity.GlobalVariable.appMode?.setFirewallMode(firewallMode)
        if (HomeScreenActivity.GlobalVariable.braveMode == HomeScreenFragment.FIREWALL_MODE) {
            HomeScreenActivity.GlobalVariable.appMode?.setDNSMode(Settings.DNSModeNone)
        } else {
            HomeScreenActivity.GlobalVariable.appMode?.setDNSMode(-1)
        }
        persistentState.setBraveMode(HomeScreenActivity.GlobalVariable.braveMode)
        HomeScreenActivity.GlobalVariable.braveModeToggler.postValue(HomeScreenActivity.GlobalVariable.braveMode)
        if (VpnController.getInstance()!!.getState(requireContext())!!.activationRequested) {
            updateBraveModeUI()
        }
    }

    private fun updateBraveModeUI(){
        when (HomeScreenActivity.GlobalVariable.braveMode) {
            0 -> {
                b.bsHomeScreenConnectedStatus.text = getString(R.string.dns_explanation_dns_connected)
            }
            1 -> {
                b.bsHomeScreenConnectedStatus.text = getString(R.string.dns_explanation_firewall_connected)
            }
            else -> {
                b.bsHomeScreenConnectedStatus.text = getString(R.string.dns_explanation_connected)
            }
        }
    }

}