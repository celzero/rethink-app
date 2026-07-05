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
package com.celzero.bravedns.ui.compose.apps

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.celzero.bravedns.R
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.ui.compose.firewall.AppListScreen as FirewallAppListScreen
import com.celzero.bravedns.ui.compose.firewall.BlockType
import com.celzero.bravedns.ui.compose.firewall.Filters
import com.celzero.bravedns.ui.compose.firewall.FirewallFilter
import com.celzero.bravedns.ui.compose.firewall.TopLevelFilter
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.AppInfoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect

private fun defaultAppFilters() = Filters(topLevelFilter = TopLevelFilter.INSTALLED)

/**
 * Full App List Screen for navigation integration.
 * Manages all state internally and delegates UI to firewall/AppListScreen.
 */
@Composable
fun AppListScreen(
    viewModel: AppInfoViewModel,
    eventLogger: EventLogger,
    refreshDatabase: RefreshDatabase,
    onAppClick: ((Int) -> Unit)? = null,
    onBackClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val refreshCompleteText = stringResource(R.string.refresh_complete)
    val bypassDnsText = stringResource(R.string.bypass_dns_firewall)
    val bypassDnsTooltipText = stringResource(R.string.bypass_dns_firewall_tooltip, bypassDnsText)
    
    // State
    var queryText by remember { mutableStateOf("") }
    var selectedFirewallFilter by remember { mutableStateOf(FirewallFilter.ALL) }
    var isRefreshing by remember { mutableStateOf(false) }
    var currentFilters by remember { mutableStateOf(defaultAppFilters()) }
    val latestFilters by remember { derivedStateOf { currentFilters } }
    
    // Bulk action states
    var bulkWifi by remember { mutableStateOf(false) }
    var bulkMobile by remember { mutableStateOf(false) }
    var bulkBypass by remember { mutableStateOf(false) }
    var bulkBypassDns by remember { mutableStateOf(false) }
    var bulkExclude by remember { mutableStateOf(false) }
    var bulkLockdown by remember { mutableStateOf(false) }
    var showBulkUpdateDialog by remember { mutableStateOf(false) }
    var bulkDialogTitle by remember { mutableStateOf("") }
    var bulkDialogMessage by remember { mutableStateOf("") }
    var bulkDialogType by remember { mutableStateOf<BlockType?>(null) }
    var showInfoDialog by remember { mutableStateOf(false) }
    var showBypassToolTip by remember { mutableStateOf(true) }

    val unmeteredBlockDialogTitle = stringResource(id = R.string.fapps_unmetered_block_dialog_title)
    val unmeteredUnblockDialogTitle = stringResource(id = R.string.fapps_unmetered_unblock_dialog_title)
    val meteredBlockDialogTitle = stringResource(id = R.string.fapps_metered_block_dialog_title)
    val meteredUnblockDialogTitle = stringResource(id = R.string.fapps_metered_unblock_dialog_title)
    val isolateBlockDialogTitle = stringResource(id = R.string.fapps_isolate_block_dialog_title)
    val isolateUnblockDialogTitle = stringResource(id = R.string.fapps_unblock_dialog_title)
    val bypassBlockDialogTitle = stringResource(id = R.string.fapps_bypass_block_dialog_title)
    val bypassUnblockDialogTitle = stringResource(id = R.string.fapps_unblock_dialog_title)
    val excludeBlockDialogTitle = stringResource(id = R.string.fapps_exclude_block_dialog_title)
    val excludeUnblockDialogTitle = stringResource(id = R.string.fapps_unblock_dialog_title)
    val bypassDnsFirewallBlockDialogTitle = stringResource(id = R.string.fapps_bypass_dns_firewall_dialog_title)
    val bypassDnsFirewallUnblockDialogTitle = stringResource(id = R.string.fapps_unblock_dialog_title)
    val unmeteredBlockDialogMessage = stringResource(id = R.string.fapps_unmetered_block_dialog_message)
    val unmeteredUnblockDialogMessage = stringResource(id = R.string.fapps_unmetered_unblock_dialog_message)
    val meteredBlockDialogMessage = stringResource(id = R.string.fapps_metered_block_dialog_message)
    val meteredUnblockDialogMessage = stringResource(id = R.string.fapps_metered_unblock_dialog_message)
    val isolateBlockDialogMessage = stringResource(id = R.string.fapps_isolate_block_dialog_message)
    val bypassBlockDialogMessage = stringResource(id = R.string.fapps_bypass_block_dialog_message)
    val bypassDnsFirewallBlockDialogMessage = stringResource(id = R.string.fapps_bypass_dns_firewall_dialog_message)
    val excludeBlockDialogMessage = stringResource(id = R.string.fapps_exclude_block_dialog_message)
    val unblockDialogMessage = stringResource(id = R.string.fapps_unblock_dialog_message)


    // Apply filters
    fun applyFilters(filters: Filters) {
        currentFilters = filters
        viewModel.setFilter(filters)
        selectedFirewallFilter = filters.firewallFilter
        queryText = filters.searchString
    }

    fun resetBulkStates(type: BlockType) {
        when (type) {
            BlockType.UNMETER -> {
                bulkMobile = false; bulkBypass = false; bulkBypassDns = false
                bulkExclude = false; bulkLockdown = false
            }
            BlockType.METER -> {
                bulkWifi = false; bulkBypass = false; bulkBypassDns = false
                bulkExclude = false; bulkLockdown = false
            }
            BlockType.LOCKDOWN -> {
                bulkWifi = false; bulkMobile = false; bulkBypass = false
                bulkBypassDns = false; bulkExclude = false
            }
            BlockType.BYPASS -> {
                bulkWifi = false; bulkMobile = false; bulkBypassDns = false
                bulkExclude = false; bulkLockdown = false
            }
            BlockType.BYPASS_DNS_FIREWALL -> {
                bulkWifi = false; bulkMobile = false; bulkBypass = false
                bulkExclude = false; bulkLockdown = false
            }
            BlockType.EXCLUDE -> {
                bulkWifi = false; bulkMobile = false; bulkBypass = false
                bulkBypassDns = false; bulkLockdown = false
            }
        }
    }
    
    fun logEvent(details: String) {
        eventLogger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, "App list, bulk change", EventSource.UI, false, details)
    }
    
    fun updateBulkRules(type: BlockType) {
        scope.launch(Dispatchers.IO) {
            when (type) {
                BlockType.UNMETER -> {
                    val unmeter = !bulkWifi
                    viewModel.updateUnmeteredStatus(unmeter)
                    withContext(Dispatchers.Main) {
                        bulkWifi = unmeter
                        resetBulkStates(BlockType.UNMETER)
                    }
                    logEvent("Bulk unmetered rule update, isUnmetered: $unmeter")
                }
                BlockType.METER -> {
                    val metered = !bulkMobile
                    viewModel.updateMeteredStatus(metered)
                    withContext(Dispatchers.Main) {
                        bulkMobile = metered
                        resetBulkStates(BlockType.METER)
                    }
                    logEvent("Bulk metered rule update, isMetered: $metered")
                }
                BlockType.LOCKDOWN -> {
                    val lockdown = !bulkLockdown
                    viewModel.updateLockdownStatus(lockdown)
                    withContext(Dispatchers.Main) {
                        bulkLockdown = lockdown
                        resetBulkStates(BlockType.LOCKDOWN)
                    }
                    logEvent("Bulk lockdown rule update, isLockdown: $lockdown")
                }
                BlockType.BYPASS -> {
                    val bypass = !bulkBypass
                    viewModel.updateBypassStatus(bypass)
                    withContext(Dispatchers.Main) {
                        bulkBypass = bypass
                        resetBulkStates(BlockType.BYPASS)
                    }
                    logEvent("Bulk bypass rule update, isBypass: $bypass")
                }
                BlockType.BYPASS_DNS_FIREWALL -> {
                    val bypassDns = !bulkBypassDns
                    viewModel.updateBypassDnsFirewall(bypassDns)
                    withContext(Dispatchers.Main) {
                        bulkBypassDns = bypassDns
                        resetBulkStates(BlockType.BYPASS_DNS_FIREWALL)
                    }
                    logEvent("Bulk bypass DNS firewall rule update, isBypassDnsFirewall: $bypassDns")
                }
                BlockType.EXCLUDE -> {
                    val exclude = !bulkExclude
                    viewModel.updateExcludeStatus(exclude)
                    withContext(Dispatchers.Main) {
                        bulkExclude = exclude
                        resetBulkStates(BlockType.EXCLUDE)
                    }
                    logEvent("Bulk exclude rule update, isExclude: $exclude")
                }
            }
        }
    }

    fun refreshAppList(action: Int = RefreshDatabase.ACTION_REFRESH_INTERACTIVE, showToast: Boolean = true) {
        if (isRefreshing) return

        isRefreshing = true
        scope.launch(Dispatchers.IO) {
            refreshDatabase.refresh(action) {
                withContext(Dispatchers.Main) {
                    isRefreshing = false
                    if (showToast) {
                        Utilities.showToastUiCentered(
                            context,
                            refreshCompleteText,
                            Toast.LENGTH_SHORT
                        )
                    }
                }
            }
        }
    }

    // Initialize
    LaunchedEffect(Unit) {
        applyFilters(defaultAppFilters())

        val appCount =
            withContext(Dispatchers.IO) {
                viewModel.getAppCount()
            }
        if (appCount == 0) {
            // Bootstrap app entries immediately when local app cache is empty.
            refreshAppList(action = RefreshDatabase.ACTION_REFRESH_FORCE, showToast = false)
        }
    }

    // Delegate to the firewall AppListScreen with all parameters
    FirewallAppListScreen(
        viewModel = viewModel,
        eventLogger = eventLogger,
        queryText = queryText,
        selectedFirewallFilter = selectedFirewallFilter,
        isRefreshing = isRefreshing,
        bulkWifi = bulkWifi,
        bulkMobile = bulkMobile,
        bulkBypass = bulkBypass,
        bulkBypassDns = bulkBypassDns,
        bulkExclude = bulkExclude,
        bulkLockdown = bulkLockdown,
        showBulkUpdateDialog = showBulkUpdateDialog,
        bulkDialogTitle = bulkDialogTitle,
        bulkDialogMessage = bulkDialogMessage,
        bulkDialogType = bulkDialogType,
        showInfoDialog = showInfoDialog,
        currentFilters = currentFilters,
        onQueryChange = { query ->
            queryText = query
            applyFilters(latestFilters.copy(searchString = query))
        },
        onRefreshClick = { refreshAppList() },
        onFilterApply = { applied -> applyFilters(applied) },
        onFilterClear = { cleared ->
            applyFilters(
                cleared.copy(
                    topLevelFilter = TopLevelFilter.INSTALLED,
                    searchString = queryText
                )
            )
        },
        onFirewallFilterClick = { filter ->
            val updated = currentFilters.copy(firewallFilter = filter)
            applyFilters(updated)
        },
        onBulkDialogConfirm = { type ->
            showBulkUpdateDialog = false
            bulkDialogType = null
            updateBulkRules(type)
        },
        onBulkDialogDismiss = {
            showBulkUpdateDialog = false
            bulkDialogType = null
        },
        onInfoDialogDismiss = { showInfoDialog = false },
        onShowInfoDialog = { showInfoDialog = true },
        onShowBulkDialog = { type ->
            when (type) {
                BlockType.UNMETER -> {
                    bulkDialogTitle = if (!bulkWifi) unmeteredBlockDialogTitle else unmeteredUnblockDialogTitle
                    bulkDialogMessage =
                        if (!bulkWifi) unmeteredBlockDialogMessage else unmeteredUnblockDialogMessage
                }
                BlockType.METER -> {
                    bulkDialogTitle = if (!bulkMobile) meteredBlockDialogTitle else meteredUnblockDialogTitle
                    bulkDialogMessage =
                        if (!bulkMobile) meteredBlockDialogMessage else meteredUnblockDialogMessage
                }
                BlockType.LOCKDOWN -> {
                    bulkDialogTitle = if (!bulkLockdown) isolateBlockDialogTitle else isolateUnblockDialogTitle
                    bulkDialogMessage = if (!bulkLockdown) isolateBlockDialogMessage else unblockDialogMessage
                }
                BlockType.BYPASS -> {
                    bulkDialogTitle = if (!bulkBypass) bypassBlockDialogTitle else bypassUnblockDialogTitle
                    bulkDialogMessage = if (!bulkBypass) bypassBlockDialogMessage else unblockDialogMessage
                }
                BlockType.BYPASS_DNS_FIREWALL -> {
                    bulkDialogTitle =
                        if (!bulkBypassDns) bypassDnsFirewallBlockDialogTitle else bypassDnsFirewallUnblockDialogTitle
                    bulkDialogMessage =
                        if (!bulkBypassDns) bypassDnsFirewallBlockDialogMessage else unblockDialogMessage
                }
                BlockType.EXCLUDE -> {
                    bulkDialogTitle = if (!bulkExclude) excludeBlockDialogTitle else excludeUnblockDialogTitle
                    bulkDialogMessage = if (!bulkExclude) excludeBlockDialogMessage else unblockDialogMessage
                }
            }
            bulkDialogType = type
            showBulkUpdateDialog = true
        },
        onBypassDnsTooltip = {
            showBypassToolTip = false
            Utilities.showToastUiCentered(
                context,
                bypassDnsTooltipText,
                Toast.LENGTH_SHORT
            )
        },
        showBypassToolTip = showBypassToolTip,
        onAppClick = onAppClick,
        onBackClick = onBackClick
    )
}
