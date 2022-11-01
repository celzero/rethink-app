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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.BuildConfig.DEBUG
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.FragmentUniversalFirewallBinding
import com.celzero.bravedns.databinding.UniversalFragementContainerBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.Utilities
import com.google.android.material.switchmaterial.SwitchMaterial
import org.koin.android.ext.android.inject
import java.util.concurrent.TimeUnit
import kotlin.reflect.KMutableProperty0

class UniversalFirewallFragment : Fragment(R.layout.universal_fragement_container) {
    private val b by viewBinding(UniversalFragementContainerBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

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
        includeView.firewallUnknownConnectionModeCheck.isChecked =
            persistentState.blockUnknownConnections
        includeView.firewallDisallowDnsBypassModeCheck.isChecked = persistentState.disallowDnsBypass
        includeView.firewallBlockNewAppCheck.isChecked = persistentState.blockNewlyInstalledApp
        includeView.firewallBlockMeteredCheck.isChecked = persistentState.blockMeteredConnections
        // now, the firewall rule (block ipv4 in ipv6) is hidden from user action.
        // decide whether we need to add this back in universal settings
        // uncomment the below code if enabled
        //includeView.firewallCheckIpv4Check.isChecked = persistentState.filterIpv4inIpv6
        includeView.firewallBlockHttpCheck.isChecked = persistentState.blockHttpConnections
        includeView.firewallUnivLockdownCheck.isChecked = persistentState.universalLockdown

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
            toggle(
                includeView.firewallUnknownConnectionModeCheck,
                persistentState::blockUnknownConnections
            )
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
            enableAfterDelay(TimeUnit.SECONDS.toMillis(2L), includeView.firewallAppsShowTxt)
            openCustomIpScreen()
        }

        includeView.firewallDisallowDnsBypassModeCheck.setOnCheckedChangeListener { _, b ->
            persistentState.disallowDnsBypass = b
        }

        includeView.firewallDisallowDnsBypassModeTxt.setOnClickListener {
            toggle(
                includeView.firewallDisallowDnsBypassModeCheck,
                persistentState::disallowDnsBypass
            )
        }

        includeView.firewallBlockNewAppCheck.setOnCheckedChangeListener { _, b ->
            persistentState.blockNewlyInstalledApp = b
        }

        includeView.firewallBlockNewAppTxt.setOnClickListener {
            toggle(includeView.firewallBlockNewAppCheck, persistentState::blockNewlyInstalledApp)
        }

        // now, the firewall rule (block ipv4 in ipv6) is hidden from user action.
        // decide whether we need to add this back in universal settings
        // uncomment the below code if enabled
        /* includeView.firewallCheckIpv4Check.setOnCheckedChangeListener { _, b ->
            persistentState.filterIpv4inIpv6 = b
        }

        includeView.firewallCheckIpv4Txt.setOnClickListener {
            toggle(includeView.firewallCheckIpv4Check, persistentState::filterIpv4inIpv6)
        } */
        includeView.firewallBlockHttpCheck.setOnCheckedChangeListener { _, b ->
            persistentState.blockHttpConnections = b
        }

        includeView.firewallBlockHttpTxt.setOnClickListener {
            toggle(includeView.firewallBlockHttpCheck, persistentState::blockHttpConnections)
        }

        includeView.firewallBlockMeteredCheck.setOnCheckedChangeListener { _, b ->
            persistentState.blockMeteredConnections = b
        }

        includeView.firewallBlockMeteredTxt.setOnClickListener {
            toggle(includeView.firewallBlockMeteredCheck, persistentState::blockMeteredConnections)
        }

        includeView.firewallUnivLockdownCheck.setOnCheckedChangeListener { _, b ->
            persistentState.universalLockdown = b
        }

        includeView.firewallUnivLockdownTxt.setOnClickListener {
            toggle(includeView.firewallUnivLockdownCheck, persistentState::universalLockdown)
        }
    }

    private fun openCustomIpScreen() {
        val intent = Intent(requireContext(), CustomIpActivity::class.java)
        // this activity is either being started in a new task or bringing to the top an
        // existing task, then it will be launched as the front door of the task.
        // This will result in the application to have that task in the proper state.
        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        startActivity(intent)
    }

    private fun recheckFirewallBackgroundMode(isChecked: Boolean) {
        val includeView = b.appScrollingInclFirewall
        if (!isChecked) {
            includeView.firewallBackgroundModeCheck.isChecked = false
            persistentState.blockAppWhenBackground = false
            return
        }

        val isAccessibilityServiceRunning = Utilities.isAccessibilityServiceEnabled(
            requireContext(), BackgroundAccessibilityService::class.java
        )
        val isAccessibilityServiceEnabled =
            Utilities.isAccessibilityServiceEnabledViaSettingsSecure(
                requireContext(), BackgroundAccessibilityService::class.java
            )
        val isAccessibilityServiceFunctional =
            isAccessibilityServiceRunning && isAccessibilityServiceEnabled

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
        b.appScrollingInclFirewall.firewallAllAppsCheck.isChecked =
            persistentState.blockWhenDeviceLocked
        b.appScrollingInclFirewall.firewallBackgroundModeCheck.isChecked =
            persistentState.blockAppWhenBackground
        b.appScrollingInclFirewall.firewallUdpConnectionModeCheck.isChecked =
            persistentState.udpBlockedSettings
        b.appScrollingInclFirewall.firewallUnknownConnectionModeCheck.isChecked =
            persistentState.blockUnknownConnections
        checkAppNotInUseRule()
    }

    private fun checkAppNotInUseRule() {
        if (!persistentState.blockAppWhenBackground) return

        val isAccessibilityServiceRunning = Utilities.isAccessibilityServiceEnabled(
            requireContext(), BackgroundAccessibilityService::class.java
        )
        val isAccessibilityServiceEnabled =
            Utilities.isAccessibilityServiceEnabledViaSettingsSecure(
                requireContext(), BackgroundAccessibilityService::class.java
            )

        if (DEBUG) Log.d(
            LOG_TAG_FIREWALL,
            "backgroundEnabled? ${persistentState.blockAppWhenBackground}, isServiceEnabled? $isAccessibilityServiceEnabled, isServiceRunning? $isAccessibilityServiceRunning"
        )
        val isAccessibilityServiceFunctional =
            isAccessibilityServiceRunning && isAccessibilityServiceEnabled

        if (!isAccessibilityServiceFunctional) {
            persistentState.blockAppWhenBackground = false
            b.appScrollingInclFirewall.firewallBackgroundModeCheck.isChecked = false
            Utilities.showToastUiCentered(
                requireContext(),
                getString(R.string.accessibility_failure_toast),
                Toast.LENGTH_SHORT
            )
            return
        }

        if (isAccessibilityServiceRunning) {
            b.appScrollingInclFirewall.firewallBackgroundModeCheck.isChecked =
                persistentState.blockAppWhenBackground
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
            Utilities.showToastUiCentered(
                requireContext(), getString(
                    R.string.alert_firewall_accessibility_exception
                ), Toast.LENGTH_SHORT
            )
            Log.e(LOG_TAG_FIREWALL, "Failure accessing accessibility settings: ${e.message}", e)
        }
    }

    private fun toggle(v: SwitchMaterial, pref: KMutableProperty0<Boolean>) {
        pref.set(!pref.get())
        v.isChecked = pref.get()
    }

    private fun enableAfterDelay(ms: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        Utilities.delay(ms, lifecycleScope) {
            if (!isAdded) return@delay

            for (v in views) v.isEnabled = true
        }
    }

}
