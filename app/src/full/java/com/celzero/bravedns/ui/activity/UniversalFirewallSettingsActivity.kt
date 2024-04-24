/*
 * Copyright 2023 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.activity

import Logger
import Logger.LOG_TAG_FIREWALL
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityUniversalFirewallSettingsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koin.android.ext.android.inject

class UniversalFirewallSettingsActivity :
    AppCompatActivity(R.layout.activity_universal_firewall_settings) {
    private val b by viewBinding(ActivityUniversalFirewallSettingsBinding::bind)
    private val persistentState by inject<PersistentState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        init()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {
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

        setupClickListeners()
    }

    private fun setupClickListeners() {
        b.firewallAllAppsCheck.setOnCheckedChangeListener { _, checked ->
            persistentState.setBlockWhenDeviceLocked(checked)
        }

        b.firewallAllAppsTxt.setOnClickListener {
            b.firewallAllAppsCheck.isChecked = !b.firewallAllAppsCheck.isChecked
        }

        b.firewallUnknownConnectionModeCheck.setOnCheckedChangeListener {
            _: CompoundButton,
            checked: Boolean ->
            persistentState.setBlockUnknownConnections(checked)
        }

        b.firewallUnknownConnectionModeTxt.setOnClickListener {
            b.firewallUnknownConnectionModeCheck.isChecked =
                !b.firewallUnknownConnectionModeCheck.isChecked
        }

        b.firewallUdpConnectionModeCheck.setOnCheckedChangeListener {
            _: CompoundButton,
            checked: Boolean ->
            persistentState.setUdpBlocked(checked)
        }

        b.firewallUdpConnectionModeTxt.setOnClickListener {
            b.firewallUdpConnectionModeCheck.isChecked = !b.firewallUdpConnectionModeCheck.isChecked
        }

        // Background mode toggle
        b.firewallBackgroundModeTxt.setOnClickListener {
            recheckFirewallBackgroundMode(!b.firewallBackgroundModeCheck.isChecked)
        }

        b.firewallBackgroundModeCheck.setOnCheckedChangeListener(null)
        b.firewallBackgroundModeCheck.setOnClickListener {
            // In this case, the isChecked property of the switch would have already flipped.
            recheckFirewallBackgroundMode(b.firewallBackgroundModeCheck.isChecked)
        }

        b.firewallDisallowDnsBypassModeCheck.setOnCheckedChangeListener { _, checked ->
            persistentState.setDisallowDnsBypass(checked)
        }

        b.firewallDisallowDnsBypassModeTxt.setOnClickListener {
            b.firewallDisallowDnsBypassModeCheck.isChecked =
                !b.firewallDisallowDnsBypassModeCheck.isChecked
        }

        b.firewallBlockNewAppCheck.setOnCheckedChangeListener { _, checked ->
            persistentState.setBlockNewlyInstalledApp(checked)
        }

        b.firewallBlockNewAppTxt.setOnClickListener {
            b.firewallBlockNewAppCheck.isChecked = !b.firewallBlockNewAppCheck.isChecked
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

        b.firewallBlockHttpCheck.setOnCheckedChangeListener { _, checked ->
            persistentState.setBlockHttpConnections(checked)
        }

        b.firewallBlockHttpTxt.setOnClickListener {
            b.firewallBlockHttpCheck.isChecked = !b.firewallBlockHttpCheck.isChecked
        }

        b.firewallBlockMeteredCheck.setOnCheckedChangeListener { _, b ->
            persistentState.setBlockMeteredConnections(b)
        }

        b.firewallBlockMeteredTxt.setOnClickListener {
            b.firewallBlockMeteredCheck.isChecked = !b.firewallBlockMeteredCheck.isChecked
        }

        b.firewallUnivLockdownCheck.setOnCheckedChangeListener { _, b ->
            persistentState.setUniversalLockdown(b)
        }

        b.firewallUnivLockdownTxt.setOnClickListener {
            b.firewallUnivLockdownCheck.isChecked = !b.firewallUnivLockdownCheck.isChecked
        }
    }

    private fun recheckFirewallBackgroundMode(isChecked: Boolean) {
        if (!isChecked) {
            b.firewallBackgroundModeCheck.isChecked = false
            persistentState.setBlockAppWhenBackground(false)
            return
        }

        val isAccessibilityServiceRunning =
            Utilities.isAccessibilityServiceEnabled(
                this,
                BackgroundAccessibilityService::class.java
            )
        val isAccessibilityServiceEnabled =
            Utilities.isAccessibilityServiceEnabledViaSettingsSecure(
                this,
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
                this,
                BackgroundAccessibilityService::class.java
            )
        val isAccessibilityServiceEnabled =
            Utilities.isAccessibilityServiceEnabledViaSettingsSecure(
                this,
                BackgroundAccessibilityService::class.java
            )

        Logger.d(
            LOG_TAG_FIREWALL,
            "backgroundEnabled? ${persistentState.getBlockAppWhenBackground()}, isServiceEnabled? $isAccessibilityServiceEnabled, isServiceRunning? $isAccessibilityServiceRunning"
        )
        val isAccessibilityServiceFunctional =
            isAccessibilityServiceRunning && isAccessibilityServiceEnabled

        if (!isAccessibilityServiceFunctional) {
            persistentState.setBlockAppWhenBackground(false)
            b.firewallBackgroundModeCheck.isChecked = false
            Utilities.showToastUiCentered(
                this,
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
        val builder = MaterialAlertDialogBuilder(this)
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
                this,
                getString(R.string.alert_firewall_accessibility_exception),
                Toast.LENGTH_SHORT
            )
            Logger.e(LOG_TAG_FIREWALL, "Failure accessing accessibility settings: ${e.message}", e)
        }
    }
}
