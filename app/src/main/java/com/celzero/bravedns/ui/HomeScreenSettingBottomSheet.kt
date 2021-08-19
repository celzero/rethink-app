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
import android.widget.Toast
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
import com.celzero.bravedns.util.Utilities.Companion.showToastUiCentered
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class HomeScreenSettingBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetHomeScreenBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val b get() = _binding!!

    private val appMode by inject<AppMode>()
    private val persistentState by inject<PersistentState>()

    override fun getTheme(): Int = getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

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
        val selectedIndex = appMode.getBraveMode()
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
            val checked = b.bsHomeScreenRadioDns.isChecked
            if (!checked) {
                b.bsHomeScreenRadioDns.isChecked = true
            }
            handleDNSMode(checked)
        }

        b.bsHsFirewallRl.setOnClickListener {
            val checked = b.bsHomeScreenRadioFirewall.isChecked
            if (!checked) {
                b.bsHomeScreenRadioFirewall.isChecked = true
            }
            handleFirewallMode(checked)
        }

        b.bsHsDnsFirewallRl.setOnClickListener {
            val checked = b.bsHomeScreenRadioDnsFirewall.isChecked
            if (!checked) {
                b.bsHomeScreenRadioDnsFirewall.isChecked = true
            }
            handleDNSFirewallMode(checked)
        }

        b.bsHsWireguardRl.setOnClickListener {
            showToastUiCentered(requireContext(), getString(R.string.coming_soon_toast),
                                Toast.LENGTH_SHORT)
        }
    }

    private fun handleDNSMode(isChecked: Boolean) {
        if (!isChecked) return

        b.bsHomeScreenRadioFirewall.isChecked = false
        b.bsHomeScreenRadioDnsFirewall.isChecked = false
        modifyBraveMode(APP_MODE_DNS)
    }

    private fun handleFirewallMode(isChecked: Boolean) {
        if (!isChecked) return

        b.bsHomeScreenRadioDns.isChecked = false
        b.bsHomeScreenRadioDnsFirewall.isChecked = false
        modifyBraveMode(APP_MODE_FIREWALL)
    }

    private fun handleDNSFirewallMode(isChecked: Boolean) {
        if (!isChecked) return

        b.bsHomeScreenRadioDns.isChecked = false
        b.bsHomeScreenRadioFirewall.isChecked = false
        modifyBraveMode(APP_MODE_DNS_FIREWALL)
    }

    private fun updateUptime() {
        val upTime = DateUtils.getRelativeTimeSpanString(
            HomeScreenActivity.GlobalVariable.appStartTime, System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        b.bsHomeScreenAppUptime.text = getString(R.string.hsf_uptime, upTime)
    }

    private fun modifyBraveMode(braveMode: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            appMode.changeBraveMode(braveMode)
            braveModeToggler.postValue(appMode.getBraveMode())
        }
    }

    private fun getConnectionStatus(): String {
        return when (appMode.getBraveMode()) {
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
