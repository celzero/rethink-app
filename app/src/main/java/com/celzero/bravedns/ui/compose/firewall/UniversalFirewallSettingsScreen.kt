/*
 * Copyright 2024 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.compose.firewall

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.compose.theme.CardPosition
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.RethinkListGroup
import com.celzero.bravedns.ui.compose.theme.RethinkTopBarLazyColumnScreen
import com.celzero.bravedns.ui.compose.theme.RethinkToggleListItem
import com.celzero.bravedns.ui.compose.theme.SectionHeader
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UniversalFirewallStatEntry(
    val ruleId: String,
    val count: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UniversalFirewallSettingsScreen(
    persistentState: PersistentState,
    eventLogger: EventLogger,
    connTrackerRepository: ConnectionTrackerRepository,
    onNavigateToLogs: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onBackClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stats by remember { mutableStateOf<List<UniversalFirewallStatEntry>>(emptyList()) }
    var isLoadingStats by remember { mutableStateOf(true) }

    var blockWhenDeviceLocked by remember { mutableStateOf(persistentState.getBlockWhenDeviceLocked()) }
    var blockAppWhenBackground by remember { mutableStateOf(persistentState.getBlockAppWhenBackground()) }
    var udpBlocked by remember { mutableStateOf(persistentState.getUdpBlocked()) }
    var blockUnknownConnections by remember { mutableStateOf(persistentState.getBlockUnknownConnections()) }
    var disallowDnsBypass by remember { mutableStateOf(persistentState.getDisallowDnsBypass()) }
    var blockNewApp by remember { mutableStateOf(persistentState.getBlockNewlyInstalledApp()) }
    var blockMeteredConnections by remember { mutableStateOf(persistentState.getBlockMeteredConnections()) }
    var blockHttpConnections by remember { mutableStateOf(persistentState.getBlockHttpConnections()) }
    var universalLockdown by remember { mutableStateOf(persistentState.getUniversalLockdown()) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    fun loadStats() {
        isLoadingStats = true
        scope.launch(Dispatchers.IO) {
            val blockedUniversalRules = connTrackerRepository.getBlockedUniversalRulesCount()
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
                blockedUniversalRules.filter { it.blockedByRule.contains(FirewallRuleset.RULE1F.id) }
            val http =
                blockedUniversalRules.filter { it.blockedByRule.contains(FirewallRuleset.RULE10.id) }
            val lockdown =
                blockedUniversalRules.filter { it.blockedByRule.contains(FirewallRuleset.RULE11.id) }

            val updatedStats = listOf(
                UniversalFirewallStatEntry(
                    FirewallRuleset.RULE3.id,
                    deviceLocked.size
                ),
                UniversalFirewallStatEntry(
                    FirewallRuleset.RULE4.id,
                    backgroundMode.size
                ),
                UniversalFirewallStatEntry(FirewallRuleset.RULE5.id, unknown.size),
                UniversalFirewallStatEntry(FirewallRuleset.RULE6.id, udp.size),
                UniversalFirewallStatEntry(
                    FirewallRuleset.RULE7.id,
                    dnsBypass.size
                ),
                UniversalFirewallStatEntry(FirewallRuleset.RULE1B.id, newApp.size),
                UniversalFirewallStatEntry(FirewallRuleset.RULE1F.id, metered.size),
                UniversalFirewallStatEntry(FirewallRuleset.RULE10.id, http.size),
                UniversalFirewallStatEntry(FirewallRuleset.RULE11.id, lockdown.size)
            )

            withContext(Dispatchers.Main) {
                stats = updatedStats
                isLoadingStats = false
            }
        }
    }

    fun logEvent(details: String) {
        eventLogger.log(
            EventType.FW_RULE_MODIFIED,
            Severity.LOW,
            "Univ firewall setting",
            EventSource.UI,
            false,
            details
        )
    }

    fun statsFor(ruleId: String): UniversalFirewallStatEntry? {
        return stats.firstOrNull { it.ruleId == ruleId }
    }

    fun handleStatsClick(ruleId: String) {
        val size = statsFor(ruleId)?.count ?: 0
        if (size > 0) {
            onNavigateToLogs(ruleId)
        }
    }

    fun handleBackgroundToggle(enabled: Boolean) {
        if (!enabled) {
            blockAppWhenBackground = false
            persistentState.setBlockAppWhenBackground(false)
            logEvent("Univ firewall background mode changed toggled to false")
            return
        }

        val isAccessibilityServiceRunning = Utilities.isAccessibilityServiceEnabled(
            context,
            BackgroundAccessibilityService::class.java
        )
        val isAccessibilityServiceEnabled = Utilities.isAccessibilityServiceEnabledViaSettingsSecure(
            context,
            BackgroundAccessibilityService::class.java
        )
        val isAccessibilityServiceFunctional = isAccessibilityServiceRunning && isAccessibilityServiceEnabled

        if (isAccessibilityServiceFunctional) {
            blockAppWhenBackground = true
            persistentState.setBlockAppWhenBackground(true)
            logEvent("Univ firewall background mode changed toggled to true")
            return
        }

        showPermissionDialog = true
        blockAppWhenBackground = false
        persistentState.setBlockAppWhenBackground(false)
        logEvent("Univ firewall background mode change to true failed due to accessibility service not enabled")
    }

    LaunchedEffect(Unit) {
        loadStats()
    }

    val blockedTotal = stats.sumOf { it.count }
    val topBarSubtitle =
        if (isLoadingStats) {
            stringResource(R.string.universal_firewall_explanation)
        } else {
            stringResource(
                R.string.two_argument_colon,
                stringResource(R.string.lbl_blocked),
                blockedTotal.toString()
            )
        }

    RethinkTopBarLazyColumnScreen(
        title = stringResource(R.string.univ_firewall_heading),
        subtitle = topBarSubtitle,
        onBackClick = onBackClick
    ) {
            item {
                SectionHeader(title = stringResource(R.string.univ_firewall_heading))
                RethinkListGroup {
                    ToggleWithStats(
                        iconRes = R.drawable.ic_device_lock,
                        label = stringResource(R.string.univ_firewall_rule_1),
                        checked = blockWhenDeviceLocked,
                        onCheckedChange = {
                            blockWhenDeviceLocked = it
                            persistentState.setBlockWhenDeviceLocked(it)
                            logEvent("Univ firewall device locked mode changed toggled to $it")
                        },
                        stats = statsFor(FirewallRuleset.RULE3.id),
                        loading = isLoadingStats,
                        onStatsClick = { handleStatsClick(FirewallRuleset.RULE3.id) },
                        position = CardPosition.First
                    )
                    ToggleWithStats(
                        iconRes = R.drawable.ic_foreground,
                        label = stringResource(R.string.univ_firewall_rule_2),
                        checked = blockAppWhenBackground,
                        onCheckedChange = { handleBackgroundToggle(it) },
                        stats = statsFor(FirewallRuleset.RULE4.id),
                        loading = isLoadingStats,
                        onStatsClick = { handleStatsClick(FirewallRuleset.RULE4.id) },
                        position = CardPosition.Middle
                    )
                    ToggleWithStats(
                        iconRes = R.drawable.ic_unknown_app,
                        label = stringResource(R.string.univ_firewall_rule_3),
                        checked = blockUnknownConnections,
                        onCheckedChange = {
                            blockUnknownConnections = it
                            persistentState.setBlockUnknownConnections(it)
                            logEvent("Univ firewall unknown connection mode changed toggled to $it")
                        },
                        stats = statsFor(FirewallRuleset.RULE5.id),
                        loading = isLoadingStats,
                        onStatsClick = { handleStatsClick(FirewallRuleset.RULE5.id) },
                        position = CardPosition.Middle
                    )
                    ToggleWithStats(
                        iconRes = R.drawable.ic_udp,
                        label = stringResource(R.string.univ_firewall_rule_4),
                        checked = udpBlocked,
                        onCheckedChange = {
                            udpBlocked = it
                            persistentState.setUdpBlocked(it)
                            logEvent("Univ firewall UDP connection mode changed toggled to $it")
                        },
                        stats = statsFor(FirewallRuleset.RULE6.id),
                        loading = isLoadingStats,
                        onStatsClick = { handleStatsClick(FirewallRuleset.RULE6.id) },
                        position = CardPosition.Middle
                    )
                    ToggleWithStats(
                        iconRes = R.drawable.ic_prevent_dns_leaks,
                        label = stringResource(R.string.univ_firewall_rule_5),
                        checked = disallowDnsBypass,
                        onCheckedChange = {
                            disallowDnsBypass = it
                            persistentState.setDisallowDnsBypass(it)
                            logEvent("Univ firewall DNS bypass mode changed toggled to $it")
                        },
                        stats = statsFor(FirewallRuleset.RULE7.id),
                        loading = isLoadingStats,
                        onStatsClick = { handleStatsClick(FirewallRuleset.RULE7.id) },
                        position = CardPosition.Middle
                    )
                    ToggleWithStats(
                        iconRes = R.drawable.ic_app_info,
                        label = stringResource(R.string.univ_firewall_rule_6),
                        checked = blockNewApp,
                        onCheckedChange = {
                            blockNewApp = it
                            persistentState.setBlockNewlyInstalledApp(it)
                            logEvent("Univ firewall new app block mode changed toggled to $it")
                        },
                        stats = statsFor(FirewallRuleset.RULE1B.id),
                        loading = isLoadingStats,
                        onStatsClick = { handleStatsClick(FirewallRuleset.RULE1B.id) },
                        position = CardPosition.Middle
                    )
                    ToggleWithStats(
                        iconRes = R.drawable.ic_univ_metered,
                        label = stringResource(R.string.univ_firewall_rule_9),
                        checked = blockMeteredConnections,
                        onCheckedChange = {
                            blockMeteredConnections = it
                            persistentState.setBlockMeteredConnections(it)
                            logEvent("Univ firewall metered connection block mode changed toggled to $it")
                        },
                        stats = statsFor(FirewallRuleset.RULE1F.id),
                        loading = isLoadingStats,
                        onStatsClick = { handleStatsClick(FirewallRuleset.RULE1F.id) },
                        position = CardPosition.Middle
                    )
                    ToggleWithStats(
                        iconRes = R.drawable.ic_http,
                        label = stringResource(R.string.univ_firewall_rule_8),
                        checked = blockHttpConnections,
                        onCheckedChange = {
                            blockHttpConnections = it
                            persistentState.setBlockHttpConnections(it)
                            logEvent("Univ firewall HTTP block mode changed toggled to $it")
                        },
                        stats = statsFor(FirewallRuleset.RULE10.id),
                        loading = isLoadingStats,
                        onStatsClick = { handleStatsClick(FirewallRuleset.RULE10.id) },
                        position = CardPosition.Middle
                    )
                    ToggleWithStats(
                        iconRes = R.drawable.ic_global_lockdown,
                        label = stringResource(R.string.univ_firewall_rule_10),
                        checked = universalLockdown,
                        onCheckedChange = {
                            universalLockdown = it
                            persistentState.setUniversalLockdown(it)
                            logEvent("Univ firewall universal lockdown mode changed toggled to $it")
                        },
                        stats = statsFor(FirewallRuleset.RULE11.id),
                        loading = isLoadingStats,
                        onStatsClick = { handleStatsClick(FirewallRuleset.RULE11.id) },
                        position = CardPosition.Last
                    )
                }
            }
    }

    if (showPermissionDialog) {
        RethinkConfirmDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = stringResource(R.string.alert_permission_accessibility),
            message = stringResource(R.string.alert_firewall_accessibility_explanation),
            confirmText = stringResource(R.string.univ_accessibility_dialog_positive),
            dismissText = stringResource(R.string.univ_accessibility_dialog_negative),
            onConfirm = {
                showPermissionDialog = false
                onOpenAccessibilitySettings()
            },
            onDismiss = { showPermissionDialog = false }
        )
    }
}

@Composable
private fun ToggleWithStats(
    iconRes: Int,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    stats: UniversalFirewallStatEntry?,
    loading: Boolean,
    onStatsClick: () -> Unit,
    position: CardPosition = CardPosition.Middle
) {
    val blockedCount = stats?.count ?: 0
    val supportingText =
        if (loading) {
            stringResource(R.string.lbl_loading)
        } else {
            stringResource(
                R.string.two_argument_colon,
                stringResource(R.string.lbl_blocked),
                blockedCount.toString()
            )
        }

    RethinkToggleListItem(
        title = label,
        description = supportingText,
        iconRes = iconRes,
        checked = checked,
        onCheckedChange = onCheckedChange,
        accentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        position = position,
        trailingPrefix = {
            if (!loading && blockedCount > 0) {
                IconButton(
                    onClick = onStatsClick,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.lbl_logs),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    )
}
