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
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.databinding.BottomSheetHomeScreenBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Themes.Companion.getBottomsheetCurrentTheme
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.showToastUiCentered
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class HomeScreenSettingBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetHomeScreenBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val b get() = _binding!!

    private val appMode by inject<AppMode>()
    private val persistentState by inject<PersistentState>()

    override fun getTheme(): Int = getBottomsheetCurrentTheme(isDarkThemeOn(),
                                                              persistentState.theme)

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
        val selectedIndex = appMode.getBraveMode().mode
        if (DEBUG) Log.d(LOG_TAG_VPN, "Home screen bottom sheet selectedIndex: $selectedIndex")

        updateStatus(selectedIndex)
    }

    private fun updateStatus(selectedState: Int) {
        when (selectedState) {
            AppMode.BraveMode.DNS.mode -> {
                b.bsHomeScreenRadioDns.isChecked = true
            }
            AppMode.BraveMode.FIREWALL.mode -> {
                b.bsHomeScreenRadioFirewall.isChecked = true
            }
            AppMode.BraveMode.DNS_FIREWALL.mode -> {
                b.bsHomeScreenRadioDnsFirewall.isChecked = true
            }
            else -> {
                b.bsHomeScreenRadioDnsFirewall.isChecked = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handleLockdownModeIfNeeded()
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

        b.bsHomeScreenVpnLockdownDesc.setOnClickListener {
            Utilities.openVpnProfile(requireContext())
        }
    }

    // disable dns and firewall mode, show user that vpn in lockdown mode indicator if needed
    private fun handleLockdownModeIfNeeded() {
        val isLockdown = VpnController.isVpnLockdown()
        if (isLockdown) {
            b.bsHomeScreenVpnLockdownDesc.visibility = View.VISIBLE
            b.bsHsDnsRl.alpha = 0.5f
            b.bsHsFirewallRl.alpha = 0.5f
        } else {
            b.bsHomeScreenVpnLockdownDesc.visibility = View.GONE
            b.bsHsDnsRl.alpha = 1f
            b.bsHsFirewallRl.alpha = 1f
        }
        b.bsHsDnsRl.isEnabled = !isLockdown
        b.bsHsFirewallRl.isEnabled = !isLockdown
        b.bsHomeScreenRadioFirewall.isEnabled = !isLockdown
        b.bsHomeScreenRadioDns.isEnabled = !isLockdown
    }

    private fun handleDNSMode(isChecked: Boolean) {
        if (!isChecked) return

        b.bsHomeScreenRadioFirewall.isChecked = false
        b.bsHomeScreenRadioDnsFirewall.isChecked = false
        modifyBraveMode(AppMode.BraveMode.DNS.mode)
    }

    private fun handleFirewallMode(isChecked: Boolean) {
        if (!isChecked) return

        b.bsHomeScreenRadioDns.isChecked = false
        b.bsHomeScreenRadioDnsFirewall.isChecked = false
        modifyBraveMode(AppMode.BraveMode.FIREWALL.mode)
    }

    private fun handleDNSFirewallMode(isChecked: Boolean) {
        if (!isChecked) return

        b.bsHomeScreenRadioDns.isChecked = false
        b.bsHomeScreenRadioFirewall.isChecked = false
        modifyBraveMode(AppMode.BraveMode.DNS_FIREWALL.mode)
    }

    private fun updateUptime() {
        val upTime = DateUtils.getRelativeTimeSpanString(
            HomeScreenActivity.GlobalVariable.appStartTime, SystemClock.elapsedRealtime(),
            DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        b.bsHomeScreenAppUptime.text = getString(R.string.hsf_uptime, upTime)
    }

    private fun modifyBraveMode(braveMode: Int) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                appMode.changeBraveMode(braveMode)
            }
        }
    }

    private fun getConnectionStatus(): String {
        return when (appMode.getBraveMode()) {
            AppMode.BraveMode.DNS -> {
                getString(R.string.dns_explanation_dns_connected)
            }
            AppMode.BraveMode.FIREWALL -> {
                getString(R.string.dns_explanation_firewall_connected)
            }
            else -> {
                getString(R.string.dns_explanation_connected)
            }
        }
    }
}
