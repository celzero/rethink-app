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
 * distributed under the License is distributed on an AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui.compose.logs

import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.asFlow
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ConnectionRow
import com.celzero.bravedns.adapter.DnsLogRow
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.database.DnsLogRepository
import com.celzero.bravedns.database.LogAppCount
import com.celzero.bravedns.database.RethinkLogRepository
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallRuleset
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.compose.theme.RethinkBottomSheetCard
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.CardPosition
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.RethinkListItem
import com.celzero.bravedns.ui.compose.theme.cardPositionFor
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import com.celzero.bravedns.ui.compose.theme.RethinkModalBottomSheet
import com.celzero.bravedns.ui.compose.theme.RethinkSearchField
import com.celzero.bravedns.ui.compose.rememberDrawablePainter
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ConnectionTrackerViewModel
import com.celzero.bravedns.viewmodel.DnsLogViewModel
import com.celzero.bravedns.viewmodel.RethinkLogViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LogTab { CONNECTION, DNS }

private data class LogsTabSpec(
    val tab: LogTab,
    val title: Int
)

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun NetworkLogsScreen(
    connectionTrackerViewModel: ConnectionTrackerViewModel,
    dnsLogViewModel: DnsLogViewModel,
    rethinkLogViewModel: RethinkLogViewModel,
    connectionTrackerRepository: ConnectionTrackerRepository,
    dnsLogRepository: DnsLogRepository,
    rethinkLogRepository: RethinkLogRepository,
    persistentState: PersistentState,
    eventLogger: EventLogger,
    onBackClick: (() -> Unit)? = null
) {
    val tabs =
        listOf(
            LogsTabSpec(
                tab = LogTab.CONNECTION,
                title = R.string.firewall_act_network_monitor_tab
            ),
            LogsTabSpec(
                tab = LogTab.DNS,
                title = R.string.dns_mode_info_title
            )
        )
    val selectedTab = remember { mutableIntStateOf(0) }

    var selectedDns by remember { mutableStateOf<DnsLog?>(null) }
    var onRefreshLogs by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onClearLogs by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(selectedTab.intValue) {
        onRefreshLogs = null
        onClearLogs = null
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            RethinkLargeTopBar(
                title = stringResource(R.string.lbl_logs),
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior,
                actions = {
                    LogsInlineTabSwitch(
                        selectedTabIndex = selectedTab.intValue,
                        onTabSelected = { selectedTab.intValue = it }
                    )
                    LogsTopBarOverflowActions(
                        onRefresh = onRefreshLogs,
                        onDelete = onClearLogs
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            when (tabs[selectedTab.intValue].tab) {
                LogTab.CONNECTION -> {
                    ConnectionLogsContent(
                        viewModel = connectionTrackerViewModel,
                        repository = connectionTrackerRepository,
                        persistentState = persistentState,
                        onTopBarActionsChange = { refreshAction, clearAction ->
                            if (onRefreshLogs !== refreshAction) onRefreshLogs = refreshAction
                            if (onClearLogs !== clearAction) onClearLogs = clearAction
                        }
                    )
                }

                LogTab.DNS -> {
                    DnsLogsContent(
                        viewModel = dnsLogViewModel,
                        repository = dnsLogRepository,
                        persistentState = persistentState,
                        onTopBarActionsChange = { refreshAction, clearAction ->
                            if (onRefreshLogs !== refreshAction) onRefreshLogs = refreshAction
                            if (onClearLogs !== clearAction) onClearLogs = clearAction
                        },
                        onShowDnsLog = { selectedDns = it }
                    )
                }
            }
        }
    }

    if (selectedDns != null) {
        DnsLogDetailsSheet(
            log = selectedDns!!,
            onDismiss = { selectedDns = null }
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun ConnectionLogsContent(
    viewModel: ConnectionTrackerViewModel,
    repository: ConnectionTrackerRepository,
    persistentState: PersistentState,
    onTopBarActionsChange: (refreshAction: (() -> Unit)?, clearAction: (() -> Unit)?) -> Unit
) {
    val logsFlow = remember(viewModel) { viewModel.connectionTrackerList.asFlow() }
    val items = logsFlow.collectAsLazyPagingItems()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRulesDialog by remember { mutableStateOf(false) }
    var showAppFilterDialog by remember { mutableStateOf(false) }
    var selectedAppFilter by remember { mutableStateOf<String?>(null) }
    var appPickerQuery by remember { mutableStateOf("") }
    var appFilterOptions by remember { mutableStateOf<List<LogAppCount>>(emptyList()) }
    var appFilterOptionsLoading by remember { mutableStateOf(false) }
    var parentFilter by remember { mutableStateOf(ConnectionTrackerViewModel.TopLevelFilter.ALL) }
    var childFilters by remember { mutableStateOf(setOf<String>()) }

    val filterOptions = listOf(
        LogsFilterOption(
            value = ConnectionTrackerViewModel.TopLevelFilter.ALL,
            label = stringResource(R.string.lbl_all),
            selectedIcon = Icons.Rounded.Public,
            unselectedIcon = Icons.Outlined.Public
        ),
        LogsFilterOption(
            value = ConnectionTrackerViewModel.TopLevelFilter.ALLOWED,
            label = stringResource(R.string.lbl_allowed),
            selectedIcon = Icons.Rounded.CheckCircle,
            unselectedIcon = Icons.Outlined.CheckCircle
        ),
        LogsFilterOption(
            value = ConnectionTrackerViewModel.TopLevelFilter.BLOCKED,
            label = stringResource(R.string.lbl_blocked),
            selectedIcon = Icons.Rounded.Block,
            unselectedIcon = Icons.Outlined.Block
        )
    )
    val refreshAction = remember(items) { { items.refresh() } }
    val clearAction = remember { { showDeleteDialog = true } }
    val openAppFilterAction = remember { { showAppFilterDialog = true } }

    BackHandler(enabled = selectedAppFilter != null && !showDeleteDialog && !showRulesDialog && !showAppFilterDialog) {
        selectedAppFilter = null
    }

    LaunchedEffect(refreshAction, clearAction, persistentState.logsEnabled, items.itemCount) {
        onTopBarActionsChange(
            refreshAction.takeIf { persistentState.logsEnabled },
            clearAction.takeIf { persistentState.logsEnabled && items.itemCount > 0 }
        )
    }

    LaunchedEffect(Unit) {
        // Reset stale filters when re-entering the screen to avoid empty/hidden results.
        viewModel.setFilter("", emptySet(), ConnectionTrackerViewModel.TopLevelFilter.ALL)
    }

    LaunchedEffect(Unit) {
        snapshotFlow { Triple(selectedAppFilter.orEmpty(), parentFilter, childFilters) }
            .debounce(300)
            .distinctUntilChanged()
            .collect { (q, type, filters) ->
                viewModel.setFilter(q, filters, type)
            }
    }

    if (!persistentState.logsEnabled) {
        LogsDisabledState()
        return
    }

    val ruleFilters = when (parentFilter) {
        ConnectionTrackerViewModel.TopLevelFilter.BLOCKED -> FirewallRuleset.getBlockedRules()
        ConnectionTrackerViewModel.TopLevelFilter.ALLOWED -> FirewallRuleset.getAllowedRules()
        ConnectionTrackerViewModel.TopLevelFilter.ALL -> FirewallRuleset.entries.toList()
    }
    val hasRulesFilter = ruleFilters.isNotEmpty()

    LaunchedEffect(showAppFilterDialog, parentFilter, childFilters, persistentState.logsEnabled) {
        if (!showAppFilterDialog || !persistentState.logsEnabled) return@LaunchedEffect
        appFilterOptionsLoading = true
        appFilterOptions =
            withContext(Dispatchers.IO) {
                when (parentFilter) {
                    ConnectionTrackerViewModel.TopLevelFilter.ALL ->
                        repository.getAllLoggedAppsWithCount(childFilters)
                    ConnectionTrackerViewModel.TopLevelFilter.ALLOWED ->
                        repository.getAllowedLoggedAppsWithCount(childFilters)
                    ConnectionTrackerViewModel.TopLevelFilter.BLOCKED ->
                        repository.getBlockedLoggedAppsWithCount(childFilters)
                }
            }
        appFilterOptionsLoading = false
    }

    val listState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            LogsControlsDeck {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingXs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LogsPrimaryFilterRow(
                        options = filterOptions,
                        selectedValue = parentFilter,
                        onValueSelected = { filterType ->
                            parentFilter = filterType
                            childFilters = emptySet()
                        },
                        modifier = Modifier.weight(1f)
                    )

                    LogsCompactIconAction(
                        icon = Icons.Rounded.FilterList,
                        contentDescription = stringResource(R.string.lbl_rules),
                        selected = childFilters.isNotEmpty(),
                        enabled = hasRulesFilter,
                        count = childFilters.size,
                        onClick = { showRulesDialog = true }
                    )

                    LogsCompactIconAction(
                        icon = Icons.Rounded.Apps,
                        contentDescription = stringResource(R.string.lbl_apps),
                        selected = selectedAppFilter != null,
                        enabled = true,
                        onClick = { openAppFilterAction() }
                    )
                }
            }

            LogsPagedListContent(
                items = items,
                listState = listState,
                modifier = Modifier.weight(1f)
            ) { item, index, itemCount ->
                ConnectionRow(
                    ct = item,
                    index = index,
                    itemCount = itemCount
                )
            }
        }

        if (showRulesDialog && ruleFilters.isNotEmpty()) {
            LogsRulesDialog(
                rules = ruleFilters,
                selectedRules = childFilters,
                onToggleRule = { ruleId ->
                    childFilters =
                        if (childFilters.contains(ruleId)) childFilters - ruleId
                        else childFilters + ruleId
                },
                onClear = { childFilters = emptySet() },
                onDismiss = { showRulesDialog = false }
            )
        }

        if (showAppFilterDialog) {
            LogsAppFilterDialog(
                options = appFilterOptions,
                selectedApp = selectedAppFilter,
                searchQuery = appPickerQuery,
                isLoading = appFilterOptionsLoading,
                onSearchQueryChange = { appPickerQuery = it },
                onSelectApp = { selectedApp ->
                    selectedAppFilter = selectedApp
                    showAppFilterDialog = false
                },
                onClearSelection = { selectedAppFilter = null },
                onDismiss = { showAppFilterDialog = false }
            )
        }
    }

    LogsDeleteDialog(
        show = showDeleteDialog,
        onDismiss = { showDeleteDialog = false },
        onDelete = { repository.clearAllData() },
        onRefresh = { items.refresh() }
    )
}

@OptIn(FlowPreview::class)
@Composable
private fun DnsLogsContent(
    viewModel: DnsLogViewModel,
    repository: DnsLogRepository,
    persistentState: PersistentState,
    onTopBarActionsChange: (refreshAction: (() -> Unit)?, clearAction: (() -> Unit)?) -> Unit,
    onShowDnsLog: (DnsLog) -> Unit
) {
    val logsFlow = remember(viewModel) { viewModel.dnsLogsList.asFlow() }
    val items = logsFlow.collectAsLazyPagingItems()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAppFilterDialog by remember { mutableStateOf(false) }
    var selectedAppFilter by remember { mutableStateOf<String?>(null) }
    var appPickerQuery by remember { mutableStateOf("") }
    var appFilterOptions by remember { mutableStateOf<List<LogAppCount>>(emptyList()) }
    var appFilterOptionsLoading by remember { mutableStateOf(false) }
    var filterType by remember { mutableStateOf(DnsLogViewModel.DnsLogFilter.ALL) }

    val filterOptions = listOf(
        LogsFilterOption(
            value = DnsLogViewModel.DnsLogFilter.ALL,
            label = stringResource(R.string.lbl_all),
            selectedIcon = Icons.Rounded.Public,
            unselectedIcon = Icons.Outlined.Public
        ),
        LogsFilterOption(
            value = DnsLogViewModel.DnsLogFilter.ALLOWED,
            label = stringResource(R.string.lbl_allowed),
            selectedIcon = Icons.Rounded.CheckCircle,
            unselectedIcon = Icons.Outlined.CheckCircle
        ),
        LogsFilterOption(
            value = DnsLogViewModel.DnsLogFilter.BLOCKED,
            label = stringResource(R.string.lbl_blocked),
            selectedIcon = Icons.Rounded.Block,
            unselectedIcon = Icons.Outlined.Block
        )
    )
    val refreshAction = remember(items) { { items.refresh() } }
    val clearAction = remember { { showDeleteDialog = true } }

    BackHandler(enabled = selectedAppFilter != null && !showDeleteDialog && !showAppFilterDialog) {
        selectedAppFilter = null
    }

    LaunchedEffect(refreshAction, clearAction, persistentState.logsEnabled, items.itemCount) {
        onTopBarActionsChange(
            refreshAction.takeIf { persistentState.logsEnabled },
            clearAction.takeIf { persistentState.logsEnabled && items.itemCount > 0 }
        )
    }

    LaunchedEffect(Unit) {
        // Reset stale filters when re-entering the screen to avoid empty/hidden results.
        viewModel.setFilter("", DnsLogViewModel.DnsLogFilter.ALL)
    }

    LaunchedEffect(Unit) {
        snapshotFlow { Pair(selectedAppFilter.orEmpty(), filterType) }
            .debounce(300)
            .distinctUntilChanged()
            .collect { (q, type) ->
                viewModel.setFilter(q, type)
            }
    }

    if (!persistentState.logsEnabled) {
        LogsDisabledState()
        return
    }

    LaunchedEffect(showAppFilterDialog, filterType, persistentState.logsEnabled) {
        if (!showAppFilterDialog || !persistentState.logsEnabled) return@LaunchedEffect
        appFilterOptionsLoading = true
        appFilterOptions =
            withContext(Dispatchers.IO) {
                when (filterType) {
                    DnsLogViewModel.DnsLogFilter.ALL -> repository.getAllLoggedAppsWithCount()
                    DnsLogViewModel.DnsLogFilter.ALLOWED -> repository.getAllowedLoggedAppsWithCount()
                    DnsLogViewModel.DnsLogFilter.BLOCKED -> repository.getBlockedLoggedAppsWithCount()
                    else -> repository.getAllLoggedAppsWithCount()
                }
            }
        appFilterOptionsLoading = false
    }

    val listState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        LogsControlsDeck {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingXs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LogsPrimaryFilterRow(
                    options = filterOptions,
                    selectedValue = filterType,
                    onValueSelected = { selectedFilter ->
                        filterType = selectedFilter
                    },
                    modifier = Modifier.weight(1f)
                )

                LogsCompactIconAction(
                    icon = Icons.Rounded.Apps,
                    contentDescription = stringResource(R.string.lbl_apps),
                    selected = selectedAppFilter != null,
                    enabled = true,
                    onClick = { showAppFilterDialog = true }
                )
            }
        }

        LogsPagedListContent(
            items = items,
            listState = listState,
            modifier = Modifier.weight(1f)
        ) { item, index, itemCount ->
            DnsLogRow(
                log = item,
                loadFavIcon = persistentState.fetchFavIcon,
                isRethinkDns = false,
                onShowBlocklist = onShowDnsLog,
                index = index,
                itemCount = itemCount,
            )
        }
    }

    if (showAppFilterDialog) {
        LogsAppFilterDialog(
            options = appFilterOptions,
            selectedApp = selectedAppFilter,
            searchQuery = appPickerQuery,
            isLoading = appFilterOptionsLoading,
            onSearchQueryChange = { appPickerQuery = it },
            onSelectApp = { selectedApp ->
                selectedAppFilter = selectedApp
                showAppFilterDialog = false
            },
            onClearSelection = { selectedAppFilter = null },
            onDismiss = { showAppFilterDialog = false }
        )
    }

    LogsDeleteDialog(
        show = showDeleteDialog,
        onDismiss = { showDeleteDialog = false },
        onDelete = { repository.clearAllData() },
        onRefresh = { items.refresh() }
    )
}

@Composable
private fun LogsControlsDeck(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier =
            modifier
            .fillMaxWidth()
            .padding(horizontal = Dimensions.screenPaddingHorizontal)
            .padding(top = Dimensions.spacingXs),
        verticalArrangement = Arrangement.spacedBy(Dimensions.spacingXs)
    ) {
        content()
    }
}

@Composable
private fun LogsDisabledState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(Dimensions.cornerRadius2xl),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .padding(horizontal = Dimensions.screenPaddingHorizontal)
                .padding(vertical = Dimensions.spacingXl)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimensions.spacingLg, vertical = Dimensions.spacingMd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_logs_accent),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = stringResource(R.string.logs_disabled_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LogsTopBarOverflowActions(
    onRefresh: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    val isRefreshEnabled = onRefresh != null
    val isDeleteEnabled = onDelete != null
    var expanded by remember { mutableStateOf(false) }

    IconButton(
        onClick = { expanded = true }
    ) {
        Icon(
            imageVector = Icons.Rounded.MoreVert,
            contentDescription = stringResource(R.string.wireguard_fab_more_actions)
        )
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.cd_refresh)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null
                )
            },
            enabled = isRefreshEnabled,
            onClick = {
                expanded = false
                onRefresh?.invoke()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.lbl_delete)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            enabled = isDeleteEnabled,
            onClick = {
                expanded = false
                onDelete?.invoke()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LogsInlineTabSwitch(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val options =
        listOf(
            0 to R.string.firewall_act_network_monitor_tab,
            1 to R.string.dns_mode_info_title
        )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        options.forEachIndexed { index, (value, labelRes) ->
            val selected = selectedTabIndex == value
            ToggleButton(
                checked = selected,
                onCheckedChange = { checked ->
                    if (checked && !selected) onTabSelected(value)
                },
                shapes =
                    when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                colors = ToggleButtonDefaults.toggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f),
                    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                border = null,
                modifier = Modifier
                    .heightIn(min = 34.dp)
                    .semantics { role = Role.RadioButton }
            ) {
                val tabIcon =
                    when (value) {
                        0 -> if (selected) Icons.Rounded.NetworkPing else Icons.Outlined.NetworkPing
                        else -> if (selected) Icons.Rounded.Shield else Icons.Outlined.Shield
                    }
                Icon(
                    imageVector = tabIcon,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.size(ToggleButtonDefaults.IconSpacing))
                Text(
                    text = stringResource(labelRes),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun LogsCompactIconAction(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean = false,
    enabled: Boolean,
    count: Int = 0,
    onClick: () -> Unit
) {
    Box(modifier = Modifier.size(36.dp)) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.matchParentSize()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint =
                    if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
                    else if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (selected) {
            Surface(
                shape = RoundedCornerShape(Dimensions.cornerRadiusPill),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 5.dp, end = 5.dp)
                    .size(6.dp)
            ) {}
        }
        if (count > 0) {
            Surface(
                shape = RoundedCornerShape(Dimensions.cornerRadiusPill),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 1.dp, end = 1.dp)
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LogsAppFilterDialog(
    options: List<LogAppCount>,
    selectedApp: String?,
    searchQuery: String,
    isLoading: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onSelectApp: (String?) -> Unit,
    onClearSelection: () -> Unit,
    onDismiss: () -> Unit
) {
    val filteredOptions =
        remember(options, searchQuery) {
            if (searchQuery.isBlank()) {
                options
            } else {
                options.filter { it.appName.contains(searchQuery.trim(), ignoreCase = true) }
            }
        }
    val totalCount = remember(options) { options.sumOf { it.count } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(Dimensions.cornerRadius2xl),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = Dimensions.spacingMd)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimensions.spacingSm, vertical = Dimensions.spacingSm),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                RethinkSearchField(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    placeholder = stringResource(R.string.search_apps_count_placeholder, options.size),
                    onClearQuery = { onSearchQueryChange("") },
                    clearQueryContentDescription = stringResource(R.string.cd_clear_search),
                    closeWhenEmptyContentDescription = stringResource(R.string.lbl_dismiss),
                    onCloseWhenEmpty = onDismiss,
                    shape = RoundedCornerShape(Dimensions.cornerRadiusMdLg),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    iconSize = 18.dp,
                    trailingIconSize = 16.dp,
                    trailingIconButtonSize = 30.dp
                )
                if (selectedApp != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onClearSelection) {
                            Text(
                                text = stringResource(R.string.fapps_filter_clear_btn),
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                if (isLoading) {
                    LogsLoadingState()
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        item("all_apps") {
                            LogsAppFilterListItem(
                                app = null,
                                selected = selectedApp == null,
                                count = totalCount,
                                onClick = { onSelectApp(null) }
                            )
                        }

                        items(filteredOptions, key = { "${it.packageName}|${it.appName}" }) { app ->
                            LogsAppFilterListItem(
                                app = app,
                                selected = selectedApp == app.appName,
                                count = app.count,
                                onClick = { onSelectApp(app.appName) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogsAppFilterListItem(
    app: LogAppCount?,
    selected: Boolean,
    count: Int,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    var appIcon by remember(app?.packageName, app?.appName) { mutableStateOf<Drawable?>(null) }

    LaunchedEffect(app?.packageName, app?.appName) {
        if (app == null) return@LaunchedEffect
        appIcon =
            withContext(Dispatchers.IO) {
                if (app.packageName.isBlank()) {
                    Utilities.getDefaultIcon(context)
                } else {
                    Utilities.getIcon(context, app.packageName, app.appName)
                }
            }
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(Dimensions.cornerRadiusMdLg),
        color =
            if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.34f)
            else MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimensions.spacingMd, vertical = Dimensions.spacingSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (app == null) {
                Icon(
                    imageVector = Icons.Rounded.Public,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint =
                        if (selected) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val iconPainter = rememberDrawablePainter(appIcon ?: Utilities.getDefaultIcon(context))
                iconPainter?.let { painter ->
                    Image(
                        painter = painter,
                        contentDescription = null,
                        modifier = Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(7.dp))
                    )
                }
            }

            Spacer(modifier = Modifier.size(Dimensions.spacingMd))

            Text(
                text = app?.appName ?: stringResource(R.string.lbl_all),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface
            )

            Surface(
                shape = RoundedCornerShape(Dimensions.cornerRadiusPill),
                color =
                    if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.84f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

private data class LogsFilterOption<T>(
    val value: T,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector = selectedIcon
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> LogsPrimaryFilterRow(
    options: List<LogsFilterOption<T>>,
    selectedValue: T,
    onValueSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        options.forEachIndexed { index, option ->
            val selected = option.value == selectedValue
            ToggleButton(
                checked = selected,
                onCheckedChange = { checked ->
                    if (checked && !selected) onValueSelected(option.value)
                },
                shapes =
                    when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                colors = ToggleButtonDefaults.toggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f),
                    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                border = null,
                modifier = Modifier.semantics { role = Role.RadioButton }
            ) {
                val showLabel = selected
                Icon(
                    imageVector = if (selected) option.selectedIcon else option.unselectedIcon,
                    contentDescription = if (showLabel) null else option.label,
                    modifier = Modifier.size(16.dp)
                )
                if (showLabel) {
                    Spacer(modifier = Modifier.size(ToggleButtonDefaults.IconSpacing))
                    Text(
                        text = option.label,
                        maxLines = 1,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun LogsRulesDialog(
    rules: List<FirewallRuleset>,
    selectedRules: Set<String>,
    onToggleRule: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    val selectedCount = selectedRules.size

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f),
            shape = RoundedCornerShape(Dimensions.cornerRadiusLg),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = Dimensions.screenPaddingHorizontal,
                            end = Dimensions.spacingXs,
                            top = Dimensions.spacingMd,
                            bottom = Dimensions.spacingSm
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FilterList,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.size(Dimensions.spacingSm))
                    Text(
                        text = stringResource(R.string.lbl_rules),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (selectedCount > 0) {
                        Spacer(modifier = Modifier.size(Dimensions.spacingSm))
                        Surface(
                            shape = RoundedCornerShape(Dimensions.cornerRadiusPill),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = selectedCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    if (selectedCount > 0) {
                        TextButton(onClick = onClear) {
                            Text(
                                text = stringResource(R.string.fapps_filter_clear_btn),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.lbl_dismiss),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    contentPadding = PaddingValues(
                        start = Dimensions.spacingSm,
                        end = Dimensions.spacingSm,
                        bottom = Dimensions.spacingSm
                    )
                ) {
                    itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
                        val selected = selectedRules.contains(rule.id)
                        RethinkListItem(
                            headline = stringResource(rule.title),
                            supportingAnnotated = htmlToAnnotatedString(stringResource(rule.desc)),
                            leadingIconPainter = painterResource(id = FirewallRuleset.getRulesIcon(rule.id)),
                            leadingIconTint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            leadingIconContainerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                            position = cardPositionFor(index = index, lastIndex = rules.lastIndex),
                            highlighted = selected,
                            highlightContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                            trailing = if (selected) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else null,
                            onClick = { onToggleRule(rule.id) }
                        )
                    }
                }
            }
        }
    }
}

private fun htmlToAnnotatedString(input: String): AnnotatedString {
    val sanitized = input.replace(Regex("(?i)</?u>"), "")
    val tokenRegex = Regex("(?i)<br\\s*/?>|</?i>")
    val builder = AnnotatedString.Builder()
    var cursor = 0
    var italicDepth = 0

    tokenRegex.findAll(sanitized).forEach { match ->
        if (match.range.first > cursor) {
            builder.append(sanitized.substring(cursor, match.range.first))
        }

        when {
            match.value.matches(Regex("(?i)<br\\s*/?>")) -> builder.append("\n")
            match.value.equals("<i>", ignoreCase = true) -> {
                builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                italicDepth++
            }
            match.value.equals("</i>", ignoreCase = true) && italicDepth > 0 -> {
                builder.pop()
                italicDepth--
            }
        }
        cursor = match.range.last + 1
    }

    if (cursor < sanitized.length) {
        builder.append(sanitized.substring(cursor))
    }
    while (italicDepth > 0) {
        builder.pop()
        italicDepth--
    }
    return builder.toAnnotatedString()
}

@Composable
private fun <T : Any> LogsPagedListContent(
    items: LazyPagingItems<T>,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    rowContent: @Composable (item: T, index: Int, itemCount: Int) -> Unit
) {
    val refreshState = items.loadState.refresh
    val itemCount = items.itemCount
    val isLoading = refreshState is LoadState.Loading && itemCount == 0
    val isEmpty = refreshState is LoadState.NotLoading && itemCount == 0
    val hasLoadError = refreshState is LoadState.Error && itemCount == 0
    val density = LocalDensity.current
    val navBarBottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dimensions.screenPaddingHorizontal,
            end = Dimensions.screenPaddingHorizontal,
            top = Dimensions.spacingXs,
            bottom = Dimensions.screenPaddingHorizontal + navBarBottomInset
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (isLoading) {
            item(key = "logs_loading") {
                LogsLoadingState()
            }
        } else if (isEmpty) {
            item(key = "logs_empty") {
                LogsEmptyState()
            }
        } else if (hasLoadError) {
            item(key = "logs_load_error") {
                LogsLoadErrorState(onRetry = { items.retry() })
            }
        } else {
            items(
                count = itemCount
            ) { index ->
                val item = items[index] ?: return@items
                rowContent(item, index, itemCount)
            }
        }
    }
}

@Composable
private fun LogsLoadingState() {
    Surface(
        shape = RoundedCornerShape(Dimensions.cornerRadiusXl),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimensions.spacingSm)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimensions.spacingLg, vertical = Dimensions.spacingLg),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                text = stringResource(id = R.string.lbl_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LogsLoadErrorState(onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Dimensions.cornerRadiusXl),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimensions.spacingSm)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimensions.spacingLg, vertical = Dimensions.spacingMd),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(id = R.string.error_loading_log_file),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry) {
                Text(text = stringResource(id = R.string.cd_refresh))
            }
        }
    }
}

@Composable
private fun LogsDeleteDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onDelete: suspend () -> Unit,
    onRefresh: () -> Unit
) {
    if (!show) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val refreshCompleteText = stringResource(R.string.refresh_complete)
    ConfirmClearLogsDialog(
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            scope.launch(Dispatchers.IO) { onDelete() }
            Utilities.showToastUiCentered(
                context,
                refreshCompleteText,
                Toast.LENGTH_SHORT
            )
            onRefresh()
        }
    )
}

@Composable
private fun LogsEmptyState() {
    Surface(
        shape = RoundedCornerShape(Dimensions.cornerRadiusXl),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimensions.spacingSm)
    ) {
        Text(
            text = stringResource(id = R.string.lbl_no_logs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = Dimensions.spacingXl, vertical = Dimensions.spacingLg)
        )
    }
}

@Composable
private fun ConfirmClearLogsDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    RethinkConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.conn_track_clear_logs_title),
        message = stringResource(R.string.conn_track_clear_logs_message),
        confirmText = stringResource(R.string.lbl_delete),
        dismissText = stringResource(R.string.lbl_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        isConfirmDestructive = true
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnTrackerDetailsSheet(
    connection: ConnectionTracker,
    onDismiss: () -> Unit
) {
    val status = if (connection.isBlocked) stringResource(R.string.lbl_blocked) else stringResource(R.string.lbl_allowed)
    LogDetailsSheet(
        title = connection.appName,
        appPackageName = connection.packageName,
        appDisplayName = connection.appName,
        details = listOf(
            LogDetailEntry(stringResource(R.string.log_detail_ip_address), connection.ipAddress),
            LogDetailEntry(stringResource(R.string.log_detail_port), connection.port.toString()),
            LogDetailEntry(stringResource(R.string.log_detail_protocol), connection.protocol.toString()),
            LogDetailEntry(stringResource(R.string.lbl_status), status, isError = connection.isBlocked)
        ),
        onDismiss = onDismiss
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsLogDetailsSheet(
    log: DnsLog,
    onDismiss: () -> Unit
) {
    val status = if (log.isBlocked) stringResource(R.string.lbl_blocked) else stringResource(R.string.lbl_allowed)
    val response = log.responseIps.ifEmpty { stringResource(R.string.settings_app_list_default_app) }
    LogDetailsSheet(
        title = log.queryStr,
        appPackageName = log.packageName,
        appDisplayName = log.appName,
        details = listOf(
            LogDetailEntry(stringResource(R.string.log_detail_app_name), log.appName),
            LogDetailEntry(stringResource(R.string.log_detail_response), response),
            LogDetailEntry(stringResource(R.string.dns_detail_latency), "${log.latency}ms"),
            LogDetailEntry(stringResource(R.string.lbl_status), status, isError = log.isBlocked)
        ),
        onDismiss = onDismiss
    )
}

private data class LogDetailEntry(
    val label: String,
    val value: String,
    val isError: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogDetailsSheet(
    title: String,
    appPackageName: String,
    appDisplayName: String,
    details: List<LogDetailEntry>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var appIcon by remember(appPackageName, appDisplayName) { mutableStateOf<Drawable?>(null) }

    LaunchedEffect(appPackageName, appDisplayName) {
        appIcon = kotlinx.coroutines.withContext(Dispatchers.IO) {
            if (appPackageName.isBlank()) {
                Utilities.getDefaultIcon(context)
            } else {
                Utilities.getIcon(context, appPackageName, appDisplayName)
            }
        }
    }

    RethinkModalBottomSheet(onDismissRequest = onDismiss, includeBottomSpacer = true) {
        RethinkBottomSheetCard(
            shape = RoundedCornerShape(Dimensions.cornerRadius4xl),
            contentPadding = PaddingValues(Dimensions.spacingLg)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
                ) {
                    val iconPainter =
                        rememberDrawablePainter(appIcon ?: Utilities.getDefaultIcon(context))
                    iconPainter?.let { painter ->
                        Image(
                            painter = painter,
                            contentDescription = null,
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(9.dp))
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                details.forEach { entry ->
                    DetailRow(
                        label = entry.label,
                        value = entry.value,
                        isError = entry.isError
                    )
                }

                Spacer(modifier = Modifier.height(Dimensions.spacingXl))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text(text = stringResource(R.string.lbl_dismiss))
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, isError: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimensions.spacingXs),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    }
}
