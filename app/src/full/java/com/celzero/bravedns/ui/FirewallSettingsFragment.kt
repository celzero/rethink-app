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
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.databinding.FragmentUniversalFirewallBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INTENT_UID
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.Utilities
import org.koin.android.ext.android.inject
import java.util.concurrent.TimeUnit

class FirewallSettingsFragment : Fragment(R.layout.fragment_universal_firewall) {
    private val b by viewBinding(FragmentUniversalFirewallBinding::bind)

    private val persistentState by inject<PersistentState>()

    companion object {
        fun newInstance() = FirewallSettingsFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun initView() {
        b.firewallAllAppsCheck.isChecked = persistentState.getBlockWhenDeviceLocked()
        b.firewallBackgroundModeCheck.isChecked = persistentState.getBlockAppWhenBackground()
        b.firewallUdpConnectionModeCheck.isChecked = persistentState.getUdpBlocked()
        b.firewallUnknownConnectionModeCheck.isChecked =
            persistentState.getBlockUnknownConnections()
        b.firewallDisallowDnsBypassModeCheck.isChecked = persistentState.getDisallowDnsBypass()
        b.firewallBlockNewAppCheck.isChecked = persistentState.getBlockNewlyInstalledApp()
        b.firewallBlockMeteredCheck.isChecked = persistentState.getBlockMeteredConnections()
        // now, the firewall rule (block ipv4 in ipv6) is hidden from user action.
        // decide whether we need to add this back in universal settings
        // uncomment the below code if enabled
        // includeView.firewallCheckIpv4Check.isChecked = persistentState.filterIpv4inIpv6
        b.firewallBlockHttpCheck.isChecked = persistentState.getBlockHttpConnections()
        b.firewallUnivLockdownCheck.isChecked = persistentState.getUniversalLockdown()

        setupClickListeners(b)
    }

    private fun setupClickListeners(includeView: FragmentUniversalFirewallBinding) {
        includeView.firewallAllAppsCheck.setOnCheckedChangeListener { _, b ->
            persistentState.setBlockWhenDeviceLocked(b)
        }

        includeView.firewallAllAppsTxt.setOnClickListener {
            includeView.firewallAllAppsCheck.isChecked = !includeView.firewallAllAppsCheck.isChecked
        }

        includeView.firewallUnknownConnectionModeCheck.setOnCheckedChangeListener {
            _: CompoundButton,
            b: Boolean ->
            persistentState.setBlockUnknownConnections(b)
        }

        includeView.firewallUnknownConnectionModeTxt.setOnClickListener {
            includeView.firewallUnknownConnectionModeCheck.isChecked =
                !includeView.firewallUnknownConnectionModeCheck.isChecked
        }

        includeView.firewallUdpConnectionModeCheck.setOnCheckedChangeListener {
            _: CompoundButton,
            b: Boolean ->
            persistentState.setUdpBlocked(b)
        }

        includeView.firewallUdpConnectionModeTxt.setOnClickListener {
            includeView.firewallUdpConnectionModeCheck.isChecked =
                !includeView.firewallUdpConnectionModeCheck.isChecked
        }

        // Background mode toggle
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
            persistentState.setDisallowDnsBypass(b)
        }

        includeView.firewallDisallowDnsBypassModeTxt.setOnClickListener {
            includeView.firewallDisallowDnsBypassModeCheck.isChecked =
                !includeView.firewallDisallowDnsBypassModeCheck.isChecked
        }

        includeView.firewallBlockNewAppCheck.setOnCheckedChangeListener { _, b ->
            persistentState.setBlockNewlyInstalledApp(b)
        }

        includeView.firewallBlockNewAppTxt.setOnClickListener {
            includeView.firewallBlockNewAppCheck.isChecked =
                !includeView.firewallBlockNewAppCheck.isChecked
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
            persistentState.setBlockHttpConnections(b)
        }

        includeView.firewallBlockHttpTxt.setOnClickListener {
            includeView.firewallBlockHttpCheck.isChecked =
                !includeView.firewallBlockHttpCheck.isChecked
        }

        includeView.firewallBlockMeteredCheck.setOnCheckedChangeListener { _, b ->
            persistentState.setBlockMeteredConnections(b)
        }

        includeView.firewallBlockMeteredTxt.setOnClickListener {
            includeView.firewallBlockMeteredCheck.isChecked =
                !includeView.firewallBlockMeteredCheck.isChecked
        }

        includeView.firewallUnivLockdownCheck.setOnCheckedChangeListener { _, b ->
            persistentState.setUniversalLockdown(b)
        }

        includeView.firewallUnivLockdownTxt.setOnClickListener {
            includeView.firewallUnivLockdownCheck.isChecked =
                !includeView.firewallUnivLockdownCheck.isChecked
        }
    }

    private fun openCustomIpScreen() {
        val intent = Intent(requireContext(), CustomRulesActivity::class.java)
        // this activity is either being started in a new task or bringing to the top an
        // existing task, then it will be launched as the front door of the task.
        // This will result in the application to have that task in the proper state.
        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        intent.putExtra(
            Constants.VIEW_PAGER_SCREEN_TO_LOAD,
            CustomRulesActivity.Tabs.IP_RULES.screen
        )
        intent.putExtra(INTENT_UID, UID_EVERYBODY)
        startActivity(intent)
    }

    private fun recheckFirewallBackgroundMode(isChecked: Boolean) {
        if (!isChecked) {
            b.firewallBackgroundModeCheck.isChecked = false
            persistentState.setBlockAppWhenBackground(false)
            return
        }

        val isAccessibilityServiceRunning =
            Utilities.isAccessibilityServiceEnabled(
                requireContext(),
                BackgroundAccessibilityService::class.java
            )
        val isAccessibilityServiceEnabled =
            Utilities.isAccessibilityServiceEnabledViaSettingsSecure(
                requireContext(),
                BackgroundAccessibilityService::class.java
            )
        val isAccessibilityServiceFunctional =
            isAccessibilityServiceRunning && isAccessibilityServiceEnabled

        if (isAccessibilityServiceFunctional) {
            persistentState.setBlockAppWhenBackground(true)
            b.firewallBackgroundModeCheck.isChecked = true
            return
        }

        showPermissionAlert()
        b.firewallBackgroundModeCheck.isChecked = false
        persistentState.setBlockAppWhenBackground(false)
    }

    override fun onResume() {
        super.onResume()
        updateUniversalFirewallPreferences()
    }

    private fun updateUniversalFirewallPreferences() {
        b.firewallAllAppsCheck.isChecked = persistentState.getBlockWhenDeviceLocked()
        b.firewallBackgroundModeCheck.isChecked = persistentState.getBlockAppWhenBackground()
        b.firewallUdpConnectionModeCheck.isChecked = persistentState.getUdpBlocked()
        b.firewallUnknownConnectionModeCheck.isChecked =
            persistentState.getBlockUnknownConnections()
        checkAppNotInUseRule()
    }

    private fun checkAppNotInUseRule() {
        if (!persistentState.getBlockAppWhenBackground()) return

        val isAccessibilityServiceRunning =
            Utilities.isAccessibilityServiceEnabled(
                requireContext(),
                BackgroundAccessibilityService::class.java
            )
        val isAccessibilityServiceEnabled =
            Utilities.isAccessibilityServiceEnabledViaSettingsSecure(
                requireContext(),
                BackgroundAccessibilityService::class.java
            )

        if (DEBUG)
            Log.d(
                LOG_TAG_FIREWALL,
                "backgroundEnabled? ${persistentState.getBlockAppWhenBackground()}, isServiceEnabled? $isAccessibilityServiceEnabled, isServiceRunning? $isAccessibilityServiceRunning"
            )
        val isAccessibilityServiceFunctional =
            isAccessibilityServiceRunning && isAccessibilityServiceEnabled

        if (!isAccessibilityServiceFunctional) {
            persistentState.setBlockAppWhenBackground(false)
            b.firewallBackgroundModeCheck.isChecked = false
            Utilities.showToastUiCentered(
                requireContext(),
                getString(R.string.accessibility_failure_toast),
                Toast.LENGTH_SHORT
            )
            return
        }

        if (isAccessibilityServiceRunning) {
            b.firewallBackgroundModeCheck.isChecked = persistentState.getBlockAppWhenBackground()
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
                requireContext(),
                getString(R.string.alert_firewall_accessibility_exception),
                Toast.LENGTH_SHORT
            )
            Log.e(LOG_TAG_FIREWALL, "Failure accessing accessibility settings: ${e.message}", e)
        }
    }

    private fun enableAfterDelay(ms: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        Utilities.delay(ms, lifecycleScope) {
            if (!isAdded) return@delay

            for (v in views) v.isEnabled = true
        }
    }
}
