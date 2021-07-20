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
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.databinding.BottomSheetHomeScreenBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.braveModeToggler
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_DNS
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_DNS_FIREWALL
import com.celzero.bravedns.util.Constants.Companion.APP_MODE_FIREWALL
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities.Companion.getBottomsheetCurrentTheme
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koin.android.ext.android.inject

class HomeScreenSettingBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetHomeScreenBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val b get() = _binding!!

    private val persistentState by inject<PersistentState>()
    private val appMode by inject<AppMode>()

    override fun getTheme(): Int = getBottomsheetCurrentTheme(isDarkThemeOn())

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


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        updateUptime()
        initializeClickListeners()
    }

    private fun initView() {
        b.bsHomeScreenConnectedStatus.text = getConnectionStatus()
        val selectedIndex = persistentState.getBraveMode()
        if (DEBUG) Log.d(LOG_TAG_VPN, "Home screen bottom sheet selectedIndex: $selectedIndex")

        updateStatus(selectedIndex)
    }

    private fun updateStatus(selectedState: Int) {
        when (selectedState) {
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
            handleDNSMode(isSelected)
        }

        b.bsHomeScreenRadioFirewall.setOnCheckedChangeListener { _: CompoundButton, isSelected: Boolean ->
            handleFirewallMode(isSelected)
        }

        b.bsHomeScreenRadioDnsFirewall.setOnCheckedChangeListener { _: CompoundButton, isSelected: Boolean ->
            handleDNSFirewallMode(isSelected)
        }

        b.bsHsDnsRl.setOnClickListener {
            if (!b.bsHomeScreenRadioDns.isChecked) {
                b.bsHomeScreenRadioDns.isChecked = true
                handleDNSMode(isChecked = true)
            }
        }

        b.bsHsFirewallRl.setOnClickListener {
            if (!b.bsHomeScreenRadioFirewall.isChecked) {
                b.bsHomeScreenRadioFirewall.isChecked = true
                handleFirewallMode(isChecked = true)
            }
        }

        b.bsHsDnsFirewallRl.setOnClickListener {
            if (!b.bsHomeScreenRadioDnsFirewall.isChecked) {
                b.bsHomeScreenRadioDnsFirewall.isChecked = true
                handleDNSFirewallMode(isChecked = true)
            }
        }
    }

    private fun handleDNSMode(isChecked: Boolean) {
        if (isChecked) {
            b.bsHomeScreenRadioFirewall.isChecked = false
            b.bsHomeScreenRadioDnsFirewall.isChecked = false
            modifyBraveMode(APP_MODE_DNS)
        }
    }

    private fun handleFirewallMode(isChecked: Boolean) {
        if (isChecked) {
            b.bsHomeScreenRadioDns.isChecked = false
            b.bsHomeScreenRadioDnsFirewall.isChecked = false
            modifyBraveMode(APP_MODE_FIREWALL)
        }
    }

    private fun handleDNSFirewallMode(isChecked: Boolean) {
        if (isChecked) {
            b.bsHomeScreenRadioDns.isChecked = false
            b.bsHomeScreenRadioFirewall.isChecked = false
            modifyBraveMode(APP_MODE_DNS_FIREWALL)
        }
    }

    private fun updateUptime() {
        val upTime = DateUtils.getRelativeTimeSpanString(
            HomeScreenActivity.GlobalVariable.appStartTime, System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        b.bsHomeScreenAppUptime.text = getString(R.string.hsf_uptime, upTime)
    }

    private fun modifyBraveMode(braveMode: Int) {
        appMode.changeBraveMode(braveMode)
        braveModeToggler.postValue(persistentState.getBraveMode())
    }

    private fun getConnectionStatus(): String {
        return when (persistentState.getBraveMode()) {
            APP_MODE_DNS -> {
                getString(R.string.dns_explanation_dns_connected)
            }
            APP_MODE_FIREWALL -> {
                getString(R.string.dns_explanation_firewall_connected)
            }
            else -> {
                getString(R.string.dns_explanation_connected)
            }
        }
    }
}
