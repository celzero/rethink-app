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
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomSheetHomeScreenBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koin.android.ext.android.inject
import settings.Settings

class HomeScreenSettingBottomSheet(var connectedDetails: String) : BottomSheetDialogFragment() {
    private var _binding: BottomSheetHomeScreenBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val b get() = _binding!!

    private var firewallMode = -1L
    private val LOG_FILE = "HOMESCREEN_BTM_SHEET"

    private val persistentState by inject<PersistentState>()

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetHomeScreenBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
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
        if (selectedIndex != -1) {
            (b.bsHomeScreenRadioGroup.getChildAt(selectedIndex) as RadioButton).isChecked = true
        } else {
            selectedIndex = persistentState.getBraveMode()
            (b.bsHomeScreenRadioGroup.getChildAt(selectedIndex) as RadioButton).isChecked = true
        }
    }

    private fun initializeClickListeners() {
        b.bsHomeScreenRadioGroup.setOnCheckedChangeListener { _: RadioGroup, i: Int ->
            when (i) {
                R.id.bs_home_screen_radio_dns -> {
                    firewallMode = Settings.BlockModeNone
                    HomeScreenActivity.GlobalVariable.braveMode = HomeScreenFragment.DNS_MODE
                }
                R.id.bs_home_screen_radio_firewall -> {
                    firewallMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) Settings.BlockModeFilterProc
                    else Settings.BlockModeFilter
                    HomeScreenActivity.GlobalVariable.braveMode = HomeScreenFragment.FIREWALL_MODE
                }
                R.id.bs_home_screen_radio_dns_firewall -> {
                    firewallMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) Settings.BlockModeFilterProc
                    else Settings.BlockModeFilter
                    HomeScreenActivity.GlobalVariable.braveMode = HomeScreenFragment.DNS_FIREWALL_MODE
                }
            }
            modifyBraveMode()
        }
    }

    private fun updateUptime() {
        val upTime = DateUtils.getRelativeTimeSpanString(HomeScreenActivity.GlobalVariable.appStartTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        b.bsHomeScreenAppUptime.text = "($upTime)"
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
            //enableBraveModeIcons()
        }
    }

}