package com.celzero.bravedns.ui

import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koin.android.ext.android.inject
import settings.Settings

class HomeScreenSettingBottomSheet(var connectedDetails : String) : BottomSheetDialogFragment() {

    private var fragmentView: View? = null
    private lateinit var appUpTimeTxt : TextView
    private lateinit var connectedStatusTxt : TextView
    private lateinit var braveModeRadioGroup: RadioGroup
    private var firewallMode = -1L
    private val LOG_FILE = "HOMESCREEN_BTM_SHEET"

    private val persistentState by inject<PersistentState>()

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        fragmentView = inflater.inflate(R.layout.bottom_sheet_home_screen, container, false)
        return fragmentView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView(view)
        updateUptime()
        initializeClickListeners()
    }

    private fun initView(view: View) {
        braveModeRadioGroup = view.findViewById(R.id.bs_home_screen_radio_group)
        appUpTimeTxt = view.findViewById(R.id.bs_home_screen_app_uptime)
        connectedStatusTxt = view.findViewById(R.id.bs_home_screen_connected_status)
        connectedStatusTxt.text = connectedDetails
        var selectedIndex = HomeScreenActivity.GlobalVariable.braveMode
        if(DEBUG) Log.d(LOG_TAG,"$LOG_FILE - selectedIndex: $selectedIndex")
        if(selectedIndex != -1) {
            (braveModeRadioGroup.getChildAt(selectedIndex) as RadioButton).isChecked = true
        }else{
            selectedIndex =  persistentState.getBraveMode()
            (braveModeRadioGroup.getChildAt(selectedIndex) as RadioButton).isChecked = true
        }
    }

    private fun initializeClickListeners() {
        braveModeRadioGroup.setOnCheckedChangeListener{ radioGroup: RadioGroup, i: Int ->
            if(i == R.id.bs_home_screen_radio_dns){
                firewallMode = Settings.BlockModeNone
                HomeScreenActivity.GlobalVariable.braveMode = HomeScreenFragment.DNS_MODE
            }else if(i == R.id.bs_home_screen_radio_firewall){
                firewallMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) Settings.BlockModeFilterProc
                else Settings.BlockModeFilter
                HomeScreenActivity.GlobalVariable.braveMode = HomeScreenFragment.FIREWALL_MODE
            }else if(i == R.id.bs_home_screen_radio_dns_firewall){
                firewallMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) Settings.BlockModeFilterProc
                else Settings.BlockModeFilter
                HomeScreenActivity.GlobalVariable.braveMode = HomeScreenFragment.DNS_FIREWALL_MODE
            }
            modifyBraveMode()
        }
    }

    private fun updateUptime() {
        val upTime = DateUtils.getRelativeTimeSpanString(HomeScreenActivity.GlobalVariable.appStartTime,
            System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        appUpTimeTxt.text = "($upTime)"
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
            if (HomeScreenActivity.GlobalVariable.braveMode == 0) {
                connectedStatusTxt.text = getString(R.string.dns_explanation_dns_connected)
            } else if (HomeScreenActivity.GlobalVariable.braveMode == 1) {
                connectedStatusTxt.text = getString(R.string.dns_explanation_firewall_connected)
            } else {
                connectedStatusTxt.text = getString(R.string.dns_explanation_connected)
            }
            //enableBraveModeIcons()
        }
    }

}