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
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.databinding.ActivityUniversalFirewallSettingsBinding
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.NewSettingsManager
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils.setBadgeDotVisible
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class UniversalFirewallSettingsActivity :
    AppCompatActivity(R.layout.activity_universal_firewall_settings) {
    private val b by viewBinding(ActivityUniversalFirewallSettingsBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val connTrackerRepository by inject<ConnectionTrackerRepository>()

    private var blockedUniversalRules : List<ConnectionTracker> = emptyList()

    companion object {
        const val RULES_SEARCH_ID = "R:"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }
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
        b.firewallUnknownDnsCheck.isChecked = persistentState.getBlockOtherDnsRecordTypes()

        setupClickListeners()
        updateStats()
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

        b.firewallUnknownDnsTxt.setOnClickListener {
            b.firewallUnknownDnsCheck.isChecked = !b.firewallUnknownDnsCheck.isChecked
        }

        b.firewallUnknownDnsCheck.setOnCheckedChangeListener { _, b ->
            NewSettingsManager.markSettingSeen(NewSettingsManager.UNIV_BLOCK_NON_A_AAAA_SETTING)
            persistentState.setBlockOtherDnsRecordTypes(b)
        }

        // click listener for the stats
        b.firewallDeviceLockedRl.setOnClickListener { startActivity(FirewallRuleset.RULE3.id) }

        b.firewallNotInUseRl.setOnClickListener { startActivity(FirewallRuleset.RULE4.id) }

        b.firewallUnknownRl.setOnClickListener { startActivity(FirewallRuleset.RULE5.id) }

        b.firewallUdpRl.setOnClickListener { startActivity(FirewallRuleset.RULE6.id) }

        b.firewallDnsBypassRl.setOnClickListener { startActivity(FirewallRuleset.RULE7.id) }

        b.firewallNewAppRl.setOnClickListener { startActivity(FirewallRuleset.RULE1B.id) }

        b.firewallMeteredRl.setOnClickListener { startActivity(FirewallRuleset.RULE1F.id) }

        b.firewallHttpRl.setOnClickListener { startActivity(FirewallRuleset.RULE10.id) }

        b.firewallLockdownRl.setOnClickListener { startActivity(FirewallRuleset.RULE11.id) }

        b.firewallUnknownRl.setOnClickListener { startActivity(FirewallRuleset.RULE12.id) }
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
        showNewBadgeIfNeeded()
    }

    private fun showNewBadgeIfNeeded() {
        val showBadge = NewSettingsManager.shouldShowBadge(NewSettingsManager.UNIV_BLOCK_NON_A_AAAA_SETTING)
        if (!showBadge) return

        b.firewallUnknownDnsTxt.setBadgeDotVisible(this, true)
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

    private var maxValue: Double = 0.0

    private fun calculatePercentage(c: Double): Int {
        if (maxValue == 0.0) return 0
        if (c > maxValue) {
            maxValue = c
            return 100
        }
        val percentage = (c / maxValue) * 100
        return percentage.toInt()
    }

    private fun showPermissionAlert() {
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
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

    private fun updateStats() {
        io {
            // get stats for all the firewall rules
            // update the UI with the stats
            // 1. device locked - 2. background mode - 3. unknown 4. udp 5. dns bypass 6. new app 7.
            // metered 8. http 9. universal lockdown
            // instead get all the stats in one go and update the UI
            blockedUniversalRules = connTrackerRepository.getBlockedUniversalRulesCount()
            val deviceLocked =
                blockedUniversalRules.filter { it.blockedByRule.contains(FirewallRuleset.RULE3.id) }
            val backgroundMode =
                blockedUniversalRules.filter { it.blockedByRule.contains(FirewallRuleset.RULE4.id) }
            val unknown =
                blockedUniversalRules.filter { it.blockedByRule.contains(FirewallRuleset.RULE5.id) }
            val udp =
                blockedUniversalRules.filter { it.blockedByRule.contains(FirewallRuleset.RULE6.id) }
            val dnsBypass =
                blockedUniversalRules.filter { it.blockedByRule.contains(FirewallRuleset.RULE7.id) }
            val newApp =
                blockedUniversalRules.filter { it.blockedByRule.contains(FirewallRuleset.RULE1B.id) }
            val metered =
                blockedUniversalRules.filter {
                    it.blockedByRule.contains(FirewallRuleset.RULE1F.id)
                }
            val http =
                blockedUniversalRules.filter {
                    it.blockedByRule.contains(FirewallRuleset.RULE10.id)
                }
            val universalLockdown =
                blockedUniversalRules.filter {
                    it.blockedByRule.contains(FirewallRuleset.RULE11.id)
                }
            val unknownDns =
                blockedUniversalRules.filter {
                    it.blockedByRule.contains(FirewallRuleset.RULE12.id)
                }

            val blockedCountList =
                listOf(
                    deviceLocked.size,
                    backgroundMode.size,
                    unknown.size,
                    udp.size,
                    dnsBypass.size,
                    newApp.size,
                    metered.size,
                    http.size,
                    universalLockdown.size,
                    unknownDns.size
                )

            maxValue = blockedCountList.maxOrNull()?.toDouble() ?: 0.0

            uiCtx {
                b.firewallDeviceLockedShimmerLayout.postDelayed(
                    {
                        if (!canPerformUiAction()) return@postDelayed

                        stopShimmer()
                        hideShimmer()

                        b.deviceLockedProgress.progress =
                            calculatePercentage(blockedCountList[0].toDouble())
                        b.notInUseProgress.progress =
                            calculatePercentage(blockedCountList[1].toDouble())
                        b.unknownProgress.progress =
                            calculatePercentage(blockedCountList[2].toDouble())
                        b.udpProgress.progress = calculatePercentage(blockedCountList[3].toDouble())
                        b.dnsBypassProgress.progress =
                            calculatePercentage(blockedCountList[4].toDouble())
                        b.newAppProgress.progress =
                            calculatePercentage(blockedCountList[5].toDouble())
                        b.meteredProgress.progress =
                            calculatePercentage(blockedCountList[6].toDouble())
                        b.httpProgress.progress =
                            calculatePercentage(blockedCountList[7].toDouble())
                        b.lockdownProgress.progress =
                            calculatePercentage(blockedCountList[8].toDouble())
                        b.unknownDnsProgress.progress =
                            calculatePercentage(blockedCountList[9].toDouble())

                        b.firewallDeviceLockedStats.text = deviceLocked.size.toString()
                        b.firewallNotInUseStats.text = backgroundMode.size.toString()
                        b.firewallUnknownStats.text = unknown.size.toString()
                        b.firewallUdpStats.text = udp.size.toString()
                        b.firewallDnsBypassStats.text = dnsBypass.size.toString()
                        b.firewallNewAppStats.text = newApp.size.toString()
                        b.firewallMeteredStats.text = metered.size.toString()
                        b.firewallHttpStats.text = http.size.toString()
                        b.firewallLockdownStats.text = universalLockdown.size.toString()
                        b.firewallUnknownDnsStats.text = unknownDns.size.toString()
                    },
                    500
                )
            }
        }
    }

    private fun canPerformUiAction(): Boolean {
        return !isFinishing &&
            !isDestroyed &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED) &&
            !isChangingConfigurations
    }

    override fun onPause() {
        super.onPause()
        stopShimmer()
    }

    private fun stopShimmer() {
        if (!canPerformUiAction()) return

        b.firewallUdpShimmerLayout.stopShimmer()
        b.firewallDeviceLockedShimmerLayout.stopShimmer()
        b.firewallNotInUseShimmerLayout.stopShimmer()
        b.firewallUnknownShimmerLayout.stopShimmer()
        b.firewallDnsBypassShimmerLayout.stopShimmer()
        b.firewallNewAppShimmerLayout.stopShimmer()
        b.firewallMeteredShimmerLayout.stopShimmer()
        b.firewallHttpShimmerLayout.stopShimmer()
        b.firewallLockdownShimmerLayout.stopShimmer()
        b.firewallUnknownDnsShimmerLayout.stopShimmer()
    }

    private fun hideShimmer() {
        if (!canPerformUiAction()) return

        b.firewallUdpShimmerLayout.visibility = View.GONE
        b.firewallDeviceLockedShimmerLayout.visibility = View.GONE
        b.firewallNotInUseShimmerLayout.visibility = View.GONE
        b.firewallUnknownShimmerLayout.visibility = View.GONE
        b.firewallDnsBypassShimmerLayout.visibility = View.GONE
        b.firewallNewAppShimmerLayout.visibility = View.GONE
        b.firewallMeteredShimmerLayout.visibility = View.GONE
        b.firewallHttpShimmerLayout.visibility = View.GONE
        b.firewallLockdownShimmerLayout.visibility = View.GONE
        b.firewallUnknownDnsShimmerLayout.visibility = View.GONE
    }

    private fun startActivity(rule: String?) {
        if (rule.isNullOrEmpty()) return

        // if the rules are not blocked, then no need to start the activity
        val size = blockedUniversalRules.filter { it.blockedByRule.contains(rule) }.size
        if (size == 0) return

        val intent = Intent(this, NetworkLogsActivity::class.java)
        val searchParam = RULES_SEARCH_ID + rule
        intent.putExtra(Constants.SEARCH_QUERY, searchParam)
        startActivity(intent)
    }

    private fun io(f: suspend () -> Unit): Job {
        return lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
