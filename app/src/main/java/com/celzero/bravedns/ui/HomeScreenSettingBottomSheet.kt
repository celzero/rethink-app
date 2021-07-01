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
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_DNS
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_DNS_FIREWALL
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_FIREWALL
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_INVALID
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koin.android.ext.android.inject
import settings.Settings

class HomeScreenSettingBottomSheet() : BottomSheetDialogFragment() {
    private var _binding: BottomSheetHomeScreenBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val b get() = _binding!!

    private var firewallMode = -1L
    private var connectedDetails: String = ""

    private val persistentState by inject<PersistentState>()

    override fun getTheme(): Int = if (persistentState.theme == 0) {
        if (isDarkThemeOn()) {
            R.style.BottomSheetDialogThemeTrueBlack
        } else {
            R.style.BottomSheetDialogThemeWhite
        }
    } else if (persistentState.theme == 1) {
        R.style.BottomSheetDialogThemeWhite
    } else if (persistentState.theme == 2) {
        R.style.BottomSheetDialogTheme
    } else {
        R.style.BottomSheetDialogThemeTrueBlack
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = BottomSheetHomeScreenBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    constructor(connectedDetails: String) : this() {
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
        if (DEBUG) Log.d(LOG_TAG_VPN, "Homescreen bottom sheet selectedIndex: $selectedIndex")
        updateBraveModeUI()
        if (selectedIndex == -1) selectedIndex = persistentState.getBraveMode()

        when (selectedIndex) {
            APP_MODE_DNS -> {
                b.bsHomeScreenRadioDns.isChecked = true
            }
            APP_MODE_FIREWALL -> {
                b.bsHomeScreenRadioFirewall.isChecked = true
            }
            APP_MODE_DNS_FIREWALL -> {
                b.bsHomeScreenRadioDnsFirewall.isChecked = true
            }
            else -> {
                b.bsHomeScreenRadioDnsFirewall.isChecked = true
            }
        }
    }

    private fun initializeClickListeners() {
        b.bsHomeScreenRadioDns.setOnCheckedChangeListener { _: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
                enableDNSMode()
            }
        }

        b.bsHomeScreenRadioFirewall.setOnCheckedChangeListener { _: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
                enableFirewallMode()
            }
        }

        b.bsHomeScreenRadioDnsFirewall.setOnCheckedChangeListener { _: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
                enableDNSFirewallMode()
            }
        }

        b.bsHsDnsRl.setOnClickListener {
            if (!b.bsHomeScreenRadioDns.isChecked) {
                b.bsHomeScreenRadioDns.isChecked = true
                enableDNSMode()
            }
        }

        b.bsHsFirewallRl.setOnClickListener {
            if (!b.bsHomeScreenRadioFirewall.isChecked) {
                b.bsHomeScreenRadioFirewall.isChecked = true
                enableFirewallMode()
            }
        }

        b.bsHsDnsFirewallRl.setOnClickListener {
            if (!b.bsHomeScreenRadioDnsFirewall.isChecked) {
                b.bsHomeScreenRadioDnsFirewall.isChecked = true
                enableDNSFirewallMode()
            }
        }
    }

    private fun enableDNSMode() {
        b.bsHomeScreenRadioFirewall.isChecked = false
        b.bsHomeScreenRadioDnsFirewall.isChecked = false
        firewallMode = Settings.BlockModeNone
        HomeScreenActivity.GlobalVariable.braveMode = APP_MODE_DNS
        modifyBraveMode()
    }

    private fun enableFirewallMode() {
        b.bsHomeScreenRadioDns.isChecked = false
        b.bsHomeScreenRadioDnsFirewall.isChecked = false
        firewallMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) Settings.BlockModeFilterProc
        else Settings.BlockModeFilter
        HomeScreenActivity.GlobalVariable.braveMode = APP_MODE_FIREWALL
        modifyBraveMode()
    }

    private fun enableDNSFirewallMode() {
        b.bsHomeScreenRadioDns.isChecked = false
        b.bsHomeScreenRadioFirewall.isChecked = false
        firewallMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) Settings.BlockModeFilterProc
        else Settings.BlockModeFilter
        HomeScreenActivity.GlobalVariable.braveMode = APP_MODE_DNS_FIREWALL
        modifyBraveMode()
    }

    private fun updateUptime() {
        val upTime = DateUtils.getRelativeTimeSpanString(
            HomeScreenActivity.GlobalVariable.appStartTime, System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        b.bsHomeScreenAppUptime.text = getString(R.string.hsf_uptime, upTime)
    }

    private fun modifyBraveMode() {
        HomeScreenActivity.GlobalVariable.appMode?.setFirewallMode(firewallMode)
        if (HomeScreenActivity.GlobalVariable.braveMode == APP_MODE_FIREWALL) {
            HomeScreenActivity.GlobalVariable.appMode?.setDNSMode(Settings.DNSModeNone)
        } else {
            HomeScreenActivity.GlobalVariable.appMode?.setDNSMode(PREF_DNS_INVALID)
        }
        persistentState.setBraveMode(HomeScreenActivity.GlobalVariable.braveMode)
        HomeScreenActivity.GlobalVariable.braveModeToggler.postValue(
            HomeScreenActivity.GlobalVariable.braveMode)
        if (VpnController.getInstance().getState().activationRequested) {
            updateBraveModeUI()
        }
    }

    private fun updateBraveModeUI() {
        when (HomeScreenActivity.GlobalVariable.braveMode) {
            APP_MODE_DNS -> {
                b.bsHomeScreenConnectedStatus.text = getString(
                    R.string.dns_explanation_dns_connected)
            }
            APP_MODE_FIREWALL -> {
                b.bsHomeScreenConnectedStatus.text = getString(
                    R.string.dns_explanation_firewall_connected)
            }
            else -> {
                b.bsHomeScreenConnectedStatus.text = getString(R.string.dns_explanation_connected)
            }
        }
    }
}
