/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.FragmentUniversalFirewallBinding
import com.celzero.bravedns.databinding.UniversalFragementContainerBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.CustomIpViewModel
import com.google.android.material.switchmaterial.SwitchMaterial
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.reflect.KMutableProperty0

class UniversalFirewallFragment : Fragment(R.layout.universal_fragement_container) {
    private val b by viewBinding(UniversalFragementContainerBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    private val customIpViewModel: CustomIpViewModel by viewModel()

    companion object {
        fun newInstance() = UniversalFirewallFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        observeBraveMode()
        observeConnectedDns()
    }

    private fun observeBraveMode() {
        appConfig.getBraveModeObservable().observe(viewLifecycleOwner) {
            handleDisallowDnsBypassUi()
        }
    }

    private fun observeConnectedDns() {
        appConfig.getConnectedDnsObservable().observe(viewLifecycleOwner) {
            handleDisallowDnsBypassUi()
        }
    }

    private fun handleDisallowDnsBypassUi() {
        if (appConfig.canEnableDnsBypassFirewallSetting()) {
            showDisallowDnsBypassUi()
        } else {
            hideDisallowDnsBypassUi()
        }
    }

    private fun hideDisallowDnsBypassUi() {
        b.appScrollingInclFirewall.firewallDisallowDnsBypassLl.visibility = View.GONE
    }

    private fun showDisallowDnsBypassUi() {
        b.appScrollingInclFirewall.firewallDisallowDnsBypassLl.visibility = View.VISIBLE
    }

    private fun initView() {
        val includeView = b.appScrollingInclFirewall

        val isServiceRunning = VpnController.state().on

        if (!isServiceRunning) {
            includeView.firewallScrollConnectCheck.visibility = View.GONE
            return
        }

        includeView.firewallScrollConnectCheck.visibility = View.VISIBLE

        includeView.firewallAllAppsCheck.isChecked = persistentState.blockWhenDeviceLocked
        includeView.firewallBackgroundModeCheck.isChecked = persistentState.blockAppWhenBackground
        includeView.firewallUdpConnectionModeCheck.isChecked = persistentState.udpBlockedSettings
        includeView.firewallUnknownConnectionModeCheck.isChecked = persistentState.blockUnknownConnections
        includeView.firewallDisallowDnsBypassModeCheck.isChecked = persistentState.disallowDnsBypass
        includeView.firewallBlockNewAppCheck.isChecked = persistentState.blockNewlyInstalledApp
        includeView.firewallCheckIpv4Check.isChecked = persistentState.filterIpv4inIpv6

        setupClickListeners(includeView)
    }

    private fun setupClickListeners(includeView: FragmentUniversalFirewallBinding) {
        includeView.firewallAllAppsCheck.setOnCheckedChangeListener { _, b ->
            persistentState.blockWhenDeviceLocked = b
        }

        includeView.firewallAllAppsTxt.setOnClickListener {
            toggle(includeView.firewallAllAppsCheck, persistentState::blockWhenDeviceLocked)
        }

        includeView.firewallUnknownConnectionModeCheck.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.blockUnknownConnections = b
        }

        includeView.firewallUnknownConnectionModeTxt.setOnClickListener {
            toggle(includeView.firewallUnknownConnectionModeCheck,
                   persistentState::blockUnknownConnections)
        }

        includeView.firewallUdpConnectionModeCheck.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.udpBlockedSettings = b
        }

        includeView.firewallUdpConnectionModeTxt.setOnClickListener {
            toggle(includeView.firewallUdpConnectionModeCheck, persistentState::udpBlockedSettings)
        }

        //Background mode toggle
        includeView.firewallBackgroundModeTxt.setOnClickListener {
            recheckFirewallBackgroundMode(!includeView.firewallBackgroundModeCheck.isChecked)
        }

        includeView.firewallBackgroundModeCheck.setOnCheckedChangeListener(null)
        includeView.firewallBackgroundModeCheck.setOnClickListener {
            // In this case, the isChecked property of the switch would have already flipped.
            recheckFirewallBackgroundMode(includeView.firewallBackgroundModeCheck.isChecked)
        }

        includeView.firewallAppsShowTxt.setOnClickListener {
            openCustomIpDialog()
        }

        includeView.firewallDisallowDnsBypassModeCheck.setOnCheckedChangeListener { _, b ->
            persistentState.disallowDnsBypass = b
        }

        includeView.firewallDisallowDnsBypassModeTxt.setOnClickListener {
            toggle(includeView.firewallDisallowDnsBypassModeCheck,
                   persistentState::disallowDnsBypass)
        }

        includeView.firewallBlockNewAppCheck.setOnCheckedChangeListener { _, b ->
            persistentState.blockNewlyInstalledApp = b
        }

        includeView.firewallBlockNewAppTxt.setOnClickListener {
            toggle(includeView.firewallBlockNewAppCheck, persistentState::blockNewlyInstalledApp)
        }

        includeView.firewallCheckIpv4Check.setOnCheckedChangeListener { _, b ->
            persistentState.filterIpv4inIpv6 = b
        }

        includeView.firewallCheckIpv4Txt.setOnClickListener {
            toggle(includeView.firewallCheckIpv4Check, persistentState::filterIpv4inIpv6)
        }
    }

    private fun openCustomIpDialog() {
        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        val customDialog = CustomIpDialog(requireActivity(), customIpViewModel, themeId)
        customDialog.show()
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun recheckFirewallBackgroundMode(isChecked: Boolean) {
        val includeView = b.appScrollingInclFirewall
        if (!isChecked) {
            includeView.firewallBackgroundModeCheck.isChecked = false
            persistentState.blockAppWhenBackground = false
            return
        }

        val isAccessibilityServiceRunning = Utilities.isAccessibilityServiceEnabled(
            requireContext(), BackgroundAccessibilityService::class.java)
        val isAccessibilityServiceEnabled = Utilities.isAccessibilityServiceEnabledViaSettingsSecure(
            requireContext(), BackgroundAccessibilityService::class.java)
        val isAccessibilityServiceFunctional = isAccessibilityServiceRunning && isAccessibilityServiceEnabled

        if (isAccessibilityServiceFunctional) {
            persistentState.blockAppWhenBackground = true
            includeView.firewallBackgroundModeCheck.isChecked = true
            return
        }

        showPermissionAlert()
        includeView.firewallBackgroundModeCheck.isChecked = false
        persistentState.blockAppWhenBackground = false
    }

    override fun onResume() {
        super.onResume()
        updateUniversalFirewallPreferences()
    }

    private fun updateUniversalFirewallPreferences() {
        b.appScrollingInclFirewall.firewallAllAppsCheck.isChecked = persistentState.blockWhenDeviceLocked
        b.appScrollingInclFirewall.firewallBackgroundModeCheck.isChecked = persistentState.blockAppWhenBackground
        b.appScrollingInclFirewall.firewallUdpConnectionModeCheck.isChecked = persistentState.udpBlockedSettings
        b.appScrollingInclFirewall.firewallUnknownConnectionModeCheck.isChecked = persistentState.blockUnknownConnections
        checkAppNotInUseRule()
    }

    private fun checkAppNotInUseRule() {
        if (!persistentState.blockAppWhenBackground) return

        val isAccessibilityServiceRunning = Utilities.isAccessibilityServiceEnabled(
            requireContext(), BackgroundAccessibilityService::class.java)
        val isAccessibilityServiceEnabled = Utilities.isAccessibilityServiceEnabledViaSettingsSecure(
            requireContext(), BackgroundAccessibilityService::class.java)

        if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                         "backgroundEnabled? ${persistentState.blockAppWhenBackground}, isServiceEnabled? $isAccessibilityServiceEnabled, isServiceRunning? $isAccessibilityServiceRunning")
        val isAccessibilityServiceFunctional = isAccessibilityServiceRunning && isAccessibilityServiceEnabled

        if (!isAccessibilityServiceFunctional) {
            persistentState.blockAppWhenBackground = false
            b.appScrollingInclFirewall.firewallBackgroundModeCheck.isChecked = false
            Utilities.showToastUiCentered(requireContext(),
                                          getString(R.string.accessibility_failure_toast),
                                          Toast.LENGTH_SHORT)
            return
        }

        if (isAccessibilityServiceRunning) {
            b.appScrollingInclFirewall.firewallBackgroundModeCheck.isChecked = persistentState.blockAppWhenBackground
            return
        }
    }

    private fun showPermissionAlert() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.alert_permission_accessibility)
        builder.setMessage(R.string.alert_firewall_accessibility_explanation)
        builder.setPositiveButton(getString(R.string.univ_accessibility_dialog_positive)) { _, _ ->
            openAccessibilitySettings()
        }
        builder.setNegativeButton(getString(R.string.univ_accessibility_dialog_negative)) { _, _ ->
        }
        builder.setCancelable(false)
        builder.create().show()
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Utilities.showToastUiCentered(requireContext(), getString(
                R.string.alert_firewall_accessibility_exception), Toast.LENGTH_SHORT)
            Log.e(LOG_TAG_FIREWALL, "Failure accessing accessibility settings: ${e.message}", e)
        }
    }

    private fun toggle(v: SwitchMaterial, pref: KMutableProperty0<Boolean>) {
        pref.set(!pref.get())
        v.isChecked = pref.get()
    }

}
