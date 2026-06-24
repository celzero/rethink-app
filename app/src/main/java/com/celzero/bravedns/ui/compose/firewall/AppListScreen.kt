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
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.FirewallAppRow
import com.celzero.bravedns.adapter.FirewallRowPosition
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.CardPosition
import com.celzero.bravedns.ui.compose.theme.RethinkActionListItem
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.RethinkListGroup
import com.celzero.bravedns.ui.compose.theme.RethinkTopBar
import com.celzero.bravedns.ui.compose.theme.RethinkModalBottomSheet
import com.celzero.bravedns.ui.compose.theme.RethinkSearchField
import com.celzero.bravedns.ui.compose.theme.cardPositionFor
import com.celzero.bravedns.viewmodel.AppInfoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import androidx.compose.foundation.ExperimentalFoundationApi
import com.celzero.bravedns.database.AppInfo

private const val ANIMATION_DURATION = 750
private val FAST_SCROLLER_LIST_END_PADDING = 32.dp

private fun performSelectionHaptic(hapticFeedback: HapticFeedback) {
    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    viewModel: AppInfoViewModel,
    eventLogger: EventLogger,
    queryText: String,
    selectedFirewallFilter: FirewallFilter,
    isRefreshing: Boolean,
    bulkWifi: Boolean,
    bulkMobile: Boolean,
    bulkBypass: Boolean,
    bulkBypassDns: Boolean,
    bulkExclude: Boolean,
    bulkLockdown: Boolean,
    showBulkUpdateDialog: Boolean,
    bulkDialogTitle: String,
    bulkDialogMessage: String,
    bulkDialogType: BlockType?,
    showInfoDialog: Boolean,
    currentFilters: Filters?,
    onQueryChange: (String) -> Unit,
    onRefreshClick: () -> Unit,
    onFilterApply: (Filters) -> Unit,
    onFilterClear: (Filters) -> Unit,
    onFirewallFilterClick: (FirewallFilter) -> Unit,
    onBulkDialogConfirm: (BlockType) -> Unit,
    onBulkDialogDismiss: () -> Unit,
    onInfoDialogDismiss: () -> Unit,
    onShowInfoDialog: () -> Unit,
    onShowBulkDialog: (BlockType) -> Unit,
    onBypassDnsTooltip: () -> Unit,
    showBypassToolTip: Boolean,
    onAppClick: ((Int) -> Unit)? = null,
    onBackClick: (() -> Unit)? = null
) {
    val items by viewModel.appInfo.collectAsState()
    val refreshRotation = rememberInfiniteTransition(label = "refresh").animateFloat(
        initialValue = 0f,
        targetValue = if (isRefreshing) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = ANIMATION_DURATION, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "refreshRotation"
    )

    var showFilterSheet by remember { mutableStateOf(false) }
    var showRulesSheet by remember { mutableStateOf(false) }
    val effectiveFilters = currentFilters ?: Filters(topLevelFilter = TopLevelFilter.INSTALLED)
    val hasActiveFilters =
        effectiveFilters.topLevelFilter != TopLevelFilter.INSTALLED ||
            effectiveFilters.categoryFilters.isNotEmpty() ||
            selectedFirewallFilter != FirewallFilter.ALL
    val shouldConsumeBackForSearch =
        queryText.isNotBlank() &&
            !showFilterSheet &&
            !showRulesSheet &&
            !showBulkUpdateDialog &&
            !showInfoDialog

    BackHandler(enabled = shouldConsumeBackForSearch) {
        onQueryChange("")
    }

    if (showFilterSheet) {
        FirewallAppFilterSheet(
            initialFilters = currentFilters,
            firewallFilter = selectedFirewallFilter,
            onDismiss = { showFilterSheet = false },
            onApply = onFilterApply,
            onClear = onFilterClear
        )
    }

    if (showRulesSheet) {
        FirewallBulkActionsSheet(
            appliedAppsCount = items.size,
            bulkWifi = bulkWifi,
            bulkMobile = bulkMobile,
            bulkBypass = bulkBypass,
            bulkBypassDns = bulkBypassDns,
            bulkExclude = bulkExclude,
            bulkLockdown = bulkLockdown,
            showBypassToolTip = showBypassToolTip,
            onDismiss = { showRulesSheet = false },
            onBypassDnsTooltip = onBypassDnsTooltip,
            onAction = { action ->
                showRulesSheet = false
                onShowBulkDialog(action)
            }
        )
    }

    if (showBulkUpdateDialog && bulkDialogType != null) {
        RethinkConfirmDialog(
            onDismissRequest = onBulkDialogDismiss,
            title = bulkDialogTitle,
            message = bulkDialogMessage,
            confirmText = stringResource(R.string.lbl_apply),
            dismissText = stringResource(R.string.lbl_cancel),
            onConfirm = { onBulkDialogConfirm(bulkDialogType) },
            onDismiss = onBulkDialogDismiss
        )
    }

    if (showInfoDialog) {
        RethinkConfirmDialog(
            onDismissRequest = onInfoDialogDismiss,
            text = { FirewallInfoDialogContent() },
            confirmText = stringResource(R.string.fapps_info_dialog_positive_btn),
            onConfirm = onInfoDialogDismiss
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            RethinkTopBar(
                title = stringResource(R.string.apps_info_title),
                onBackClick = onBackClick,
                actions = {
                    IconButton(
                        onClick = onRefreshClick,
                        enabled = !isRefreshing
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh),
                            modifier = Modifier.rotate(if (isRefreshing) refreshRotation.value else 0f)
                        )
                    }
                    IconButton(onClick = { showFilterSheet = true }) {
                        Box {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = stringResource(R.string.cd_filter)
                            )
                            if (hasActiveFilters) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .align(Alignment.TopEnd)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    }
                    IconButton(onClick = { showRulesSheet = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = stringResource(R.string.lbl_rules)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            AppListControlDeck(
                queryText = queryText,
                displayedAppsCount = items.size,
                selectedFirewallFilter = selectedFirewallFilter,
                onQueryChange = onQueryChange,
                onFirewallFilterClick = onFirewallFilterClick
            )

            AppListRecycler(
                modifier = Modifier.weight(1f),
                items = items,
                eventLogger = eventLogger,
                searchQuery = queryText,
                onAppClick = onAppClick
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppListControlDeck(
    queryText: String,
    displayedAppsCount: Int,
    selectedFirewallFilter: FirewallFilter,
    onQueryChange: (String) -> Unit,
    onFirewallFilterClick: (FirewallFilter) -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    val quickFilters = listOf(
        FirewallFilter.ALL,
        FirewallFilter.ALLOWED,
        FirewallFilter.BLOCKED,
        FirewallFilter.BYPASS,
        FirewallFilter.EXCLUDED,
        FirewallFilter.LOCKDOWN
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimensions.screenPaddingHorizontal)
            .padding(bottom = Dimensions.spacingXs),
        verticalArrangement = Arrangement.spacedBy(Dimensions.spacingXs)
    ) {
        RethinkSearchField(
            query = queryText,
            onQueryChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(R.string.search_apps_count_placeholder, displayedAppsCount),
            onClearQuery = { onQueryChange("") },
            clearQueryContentDescription = stringResource(R.string.cd_clear_search),
            shape = RoundedCornerShape(Dimensions.cornerRadiusMdLg),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            iconSize = 20.dp,
            trailingIconSize = 16.dp,
            trailingIconButtonSize = 32.dp
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
        ) {
            quickFilters.forEachIndexed { index, filter ->
                val selected = selectedFirewallFilter == filter
                ToggleButton(
                    checked = selected,
                    onCheckedChange = { checked ->
                        if (checked && !selected) {
                            performSelectionHaptic(hapticFeedback)
                            onFirewallFilterClick(filter)
                        }
                    },
                    shapes =
                        when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            quickFilters.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
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
                    FirewallFilterIcon(filter = filter, selected = selected, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.size(ToggleButtonDefaults.IconSpacing))
                    Text(
                        text = filter.getLabel(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> ConnectedToggleButtonRow(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable (option: T, selected: Boolean) -> Unit)? = null,
    label: @Composable (option: T, selected: Boolean) -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selectedOption
            ToggleButton(
                checked = isSelected,
                onCheckedChange = { checked ->
                    if (checked && !isSelected) {
                        performSelectionHaptic(hapticFeedback)
                        onOptionSelected(option)
                    }
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
                    .weight(1f)
                    .semantics { role = Role.RadioButton }
            ) {
                icon?.invoke(option, isSelected)
                if (icon != null) {
                    Spacer(modifier = Modifier.size(ToggleButtonDefaults.IconSpacing))
                }
                label(option, isSelected)
            }
        }
    }
}

@Composable
private fun SheetSectionCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(Dimensions.cornerRadiusMdLg),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f),
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = containerColor,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
        ) {
            content()
        }
    }
}

@Composable
private fun SheetSectionTitle(
    title: String,
    count: Int = 0
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (count > 0) {
            Surface(
                shape = RoundedCornerShape(Dimensions.cornerRadiusPill),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.84f)
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FirewallBulkActionsSheet(
    appliedAppsCount: Int,
    bulkWifi: Boolean,
    bulkMobile: Boolean,
    bulkBypass: Boolean,
    bulkBypassDns: Boolean,
    bulkExclude: Boolean,
    bulkLockdown: Boolean,
    showBypassToolTip: Boolean,
    onDismiss: () -> Unit,
    onBypassDnsTooltip: () -> Unit,
    onAction: (BlockType) -> Unit
) {
    val ruleItems = listOf(
        RuleActionItem(
            type = BlockType.UNMETER,
            titleRes = R.string.ada_app_unmetered,
            descriptionRes = R.string.fapps_info_unmetered_msg,
            iconRes = if (bulkWifi) R.drawable.ic_firewall_wifi_off else R.drawable.ic_firewall_wifi_on_grey,
            selected = bulkWifi
        ),
        RuleActionItem(
            type = BlockType.METER,
            titleRes = R.string.lbl_mobile_data,
            descriptionRes = R.string.fapps_info_metered_msg,
            iconRes = if (bulkMobile) R.drawable.ic_firewall_data_off else R.drawable.ic_firewall_data_on_grey,
            selected = bulkMobile
        ),
        RuleActionItem(
            type = BlockType.BYPASS,
            titleRes = R.string.fapps_firewall_filter_bypass_universal,
            descriptionRes = R.string.fapps_info_bypass_msg,
            iconRes = if (bulkBypass) R.drawable.ic_firewall_bypass_on else R.drawable.ic_firewall_bypass_off,
            selected = bulkBypass
        ),
        RuleActionItem(
            type = BlockType.BYPASS_DNS_FIREWALL,
            titleRes = R.string.bypass_dns_firewall,
            descriptionRes = R.string.fapps_info_bypass_dns_firewall_msg,
            iconRes = if (bulkBypassDns) R.drawable.ic_bypass_dns_firewall_on else R.drawable.ic_bypass_dns_firewall_off,
            selected = bulkBypassDns
        ),
        RuleActionItem(
            type = BlockType.EXCLUDE,
            titleRes = R.string.fapps_firewall_filter_excluded,
            descriptionRes = R.string.fapps_info_exclude_msg,
            iconRes = if (bulkExclude) R.drawable.ic_firewall_exclude_on else R.drawable.ic_firewall_exclude_off,
            selected = bulkExclude
        ),
        RuleActionItem(
            type = BlockType.LOCKDOWN,
            titleRes = R.string.fapps_firewall_filter_isolate,
            descriptionRes = R.string.fapps_info_isolate_msg,
            iconRes = if (bulkLockdown) R.drawable.ic_firewall_lockdown_on else R.drawable.ic_firewall_lockdown_off,
            selected = bulkLockdown
        )
    )

    RethinkModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentPadding = PaddingValues(0.dp),
        verticalSpacing = 0.dp,
        includeBottomSpacer = true
    ) {
        Column(
            modifier = Modifier
                .padding(
                    horizontal = Dimensions.screenPaddingHorizontal,
                    vertical = Dimensions.spacingSm
                )
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
        ) {
            RulesSheetHeader(appliedAppsCount = appliedAppsCount)
            Text(
                text = stringResource(R.string.fapps_info_dialog_message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            RethinkListGroup {
                ruleItems.forEachIndexed { index, item ->
                    RuleActionListItem(
                        item = item,
                        position = cardPositionFor(index, ruleItems.lastIndex),
                        onClick = {
                            if (item.type == BlockType.BYPASS_DNS_FIREWALL && showBypassToolTip) {
                                onBypassDnsTooltip()
                            } else {
                                onAction(item.type)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RulesSheetHeader(appliedAppsCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.lbl_rules),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(Dimensions.cornerRadiusSmMd),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.84f)
        ) {
            Text(
                text = stringResource(
                    R.string.two_argument_colon,
                    stringResource(R.string.lbl_apply),
                    appliedAppsCount.toString()
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}

private data class RuleActionItem(
    val type: BlockType,
    val titleRes: Int,
    val descriptionRes: Int,
    val iconRes: Int,
    val selected: Boolean
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RuleActionListItem(
    item: RuleActionItem,
    position: CardPosition,
    onClick: () -> Unit
) {
    val accentColor =
        when (item.type) {
            BlockType.LOCKDOWN -> MaterialTheme.colorScheme.error
            BlockType.EXCLUDE -> MaterialTheme.colorScheme.secondary
            BlockType.BYPASS,
            BlockType.BYPASS_DNS_FIREWALL -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        }

    RethinkActionListItem(
        title = stringResource(item.titleRes),
        description = stringResource(item.descriptionRes),
        iconRes = item.iconRes,
        accentColor = accentColor,
        position = position,
        highlighted = item.selected,
        trailing = { RuleSelectionBadge(selected = item.selected) },
        onClick = onClick
    )
}

@Composable
private fun RuleSelectionBadge(selected: Boolean) {
    Surface(
        shape = RoundedCornerShape(Dimensions.cornerRadiusPill),
        color =
            if (selected) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
    ) {
        Text(
            text =
                stringResource(
                    if (selected) R.string.lbbs_enabled else R.string.lbl_disabled
                ),
            style = MaterialTheme.typography.labelMedium,
            color =
                if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun AppListRecycler(
    modifier: Modifier = Modifier,
    items: List<AppInfo>,
    eventLogger: EventLogger,
    searchQuery: String,
    onAppClick: ((Int) -> Unit)? = null
) {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    if (items.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = Dimensions.screenPaddingHorizontal),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(Dimensions.cornerRadius2xl),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.fapps_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.fapps_empty_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    val showFastScroller = items.size >= 8
    val fastScrollerKeys = remember(items) { buildFastScrollerIndexKeys(items) }
    val density = LocalDensity.current
    val navBarBottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }

    Box(modifier = modifier.fillMaxSize()) {
        AppListContent(
            loadedItems = items,
            listState = listState,
            eventLogger = eventLogger,
            searchQuery = searchQuery,
            showFastScroller = showFastScroller,
            onAppClick = onAppClick
        )

        if (showFastScroller) {
            IndexedFastScroller(
                items = fastScrollerKeys,
                listState = listState,
                getIndexKey = { it },
                scrollItemOffset = 2,
                minItemCount = 8,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(top = Dimensions.spacingSm, bottom = navBarBottomInset)
                    .padding(end = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppListContent(
    loadedItems: List<AppInfo>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    eventLogger: EventLogger,
    searchQuery: String,
    showFastScroller: Boolean,
    onAppClick: ((Int) -> Unit)? = null
) {
    val density = LocalDensity.current
    val navBarBottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dimensions.screenPaddingHorizontal,
            end = Dimensions.screenPaddingHorizontal + if (showFastScroller) FAST_SCROLLER_LIST_END_PADDING else 8.dp,
            top = Dimensions.spacingXs,
            bottom = Dimensions.screenPaddingHorizontal + navBarBottomInset
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        loadedItems.forEachIndexed { index, item ->
            val currentInitial = appInitial(item.appName, item.packageName)
            val previousItem = loadedItems.getOrNull(index - 1)
            val previousInitial =
                previousItem?.let { appInitial(it.appName, it.packageName) }
            val nextItem = loadedItems.getOrNull(index + 1)
            val nextInitial =
                nextItem?.let { appInitial(it.appName, it.packageName) }
            val isFirstInGroup = previousInitial == null || currentInitial != previousInitial
            val isLastInGroup = nextInitial == null || currentInitial != nextInitial

            val rowPosition =
                when {
                    isFirstInGroup && isLastInGroup -> FirewallRowPosition.Single
                    isFirstInGroup -> FirewallRowPosition.First
                    isLastInGroup -> FirewallRowPosition.Last
                    else -> FirewallRowPosition.Middle
                }

            if (index == 0 || isFirstInGroup) {
                stickyHeader(key = "header_$currentInitial") {
                    AppListLetterHeader(letter = currentInitial)
                }
            }

            item(key = "app_${item.uid}_${item.packageName}") {
                FirewallAppRow(
                    appInfo = item,
                    eventLogger = eventLogger,
                    searchQuery = searchQuery,
                    rowPosition = rowPosition,
                    onAppClick = onAppClick
                )
            }
        }
    }
}

@Composable
private fun AppListLetterHeader(letter: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 20.dp, top = 20.dp, bottom = 4.dp)
    ) {
        Text(
            text = letter,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun appInitial(appName: String, packageName: String): String {
    val source = appName.ifBlank { packageName }.trim()
    if (source.isEmpty()) return "#"
    val first = source.first()
    return if (first.isLetter()) {
        first.uppercaseChar().toString()
    } else {
        source.first().toString().uppercase(Locale.getDefault())
    }
}

private fun buildFastScrollerIndexKeys(loadedItems: List<AppInfo>): List<String> {
    val indexKeys = mutableListOf<String>()
    var previousInitial: String? = null

    loadedItems.forEach { item ->
        val initial = appInitial(item.appName, item.packageName)
        if (initial != previousInitial) {
            indexKeys.add(initial) // sticky header index
            previousInitial = initial
        }
        indexKeys.add(item.appName.ifBlank { item.packageName }) // app row index
    }

    return indexKeys
}

@Composable
private fun FirewallInfoDialogContent() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Dimensions.spacingLg)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)
    ) {
        Text(
            text = stringResource(R.string.fapps_info_dialog_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        HorizontalDivider(
            thickness = Dimensions.dividerThickness,
            color = MaterialTheme.colorScheme.outlineVariant
        )
        InfoRow(R.drawable.ic_firewall_wifi_on_grey, stringResource(R.string.fapps_info_unmetered_msg))
        InfoRow(R.drawable.ic_firewall_data_on_grey, stringResource(R.string.fapps_info_metered_msg))
        InfoRow(R.drawable.ic_firewall_bypass_off, stringResource(R.string.fapps_info_bypass_msg))
        InfoRow(R.drawable.ic_bypass_dns_firewall_off, stringResource(R.string.fapps_info_bypass_dns_firewall_msg))
        InfoRow(R.drawable.ic_firewall_exclude_off, stringResource(R.string.fapps_info_exclude_msg))
        InfoRow(R.drawable.ic_firewall_lockdown_off, stringResource(R.string.fapps_info_isolate_msg))
    }
}

@Composable
private fun InfoRow(icon: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Dimensions.spacingXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Image(
                    painter = painterResource(id = icon),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FirewallAppFilterSheet(
    initialFilters: Filters?,
    firewallFilter: FirewallFilter,
    onDismiss: () -> Unit,
    onApply: (Filters) -> Unit,
    onClear: (Filters) -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    var topFilter by remember { mutableStateOf(initialFilters?.topLevelFilter ?: TopLevelFilter.INSTALLED) }
    var selectedFirewallFilter by remember { mutableStateOf(firewallFilter) }
    val searchString = initialFilters?.searchString.orEmpty()
    val selectedCategories = remember {
        mutableStateListOf<String>().apply {
            if (initialFilters != null) addAll(initialFilters.categoryFilters)
        }
    }
    val categories = remember { mutableStateListOf<String>() }
    val statusOptions = listOf(
        FirewallFilter.ALL,
        FirewallFilter.ALLOWED,
        FirewallFilter.BLOCKED,
        FirewallFilter.BYPASS,
        FirewallFilter.EXCLUDED,
        FirewallFilter.LOCKDOWN,
        FirewallFilter.BLOCKED_WIFI,
        FirewallFilter.BLOCKED_MOBILE_DATA
    )

    LaunchedEffect(topFilter, initialFilters?.categoryFilters) {
        val result = fetchCategories(topFilter)
        categories.clear()
        categories.addAll(result)
        selectedCategories.retainAll(result.toSet())
    }

    fun currentFilters(
        top: TopLevelFilter = topFilter,
        status: FirewallFilter = selectedFirewallFilter,
        categoryFilters: Set<String> = selectedCategories.toSet()
    ): Filters {
        return Filters(
            categoryFilters = categoryFilters,
            topLevelFilter = top,
            firewallFilter = status,
            searchString = searchString
        )
    }

    val isDefaultSelection =
        topFilter == TopLevelFilter.INSTALLED &&
            selectedFirewallFilter == FirewallFilter.ALL &&
            selectedCategories.isEmpty()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f),
            shape = RoundedCornerShape(Dimensions.cornerRadiusLg),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 0.dp,
                        end = 0.dp,
                        top = 0.dp,
                        bottom = 0.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimensions.screenPaddingHorizontal),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            performSelectionHaptic(hapticFeedback)
                            selectedCategories.clear()
                            topFilter = TopLevelFilter.INSTALLED
                            selectedFirewallFilter = FirewallFilter.ALL
                            onClear(
                                Filters(
                                    topLevelFilter = TopLevelFilter.INSTALLED,
                                    firewallFilter = FirewallFilter.ALL,
                                    searchString = searchString
                                )
                            )
                        },
                        enabled = !isDefaultSelection,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.52f)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.fapps_filter_clear_btn),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                SheetSectionCard(shape = RoundedCornerShape(0.dp)) {
                    SheetSectionTitle(title = stringResource(R.string.lbl_view))
                    ConnectedToggleButtonRow(
                        options = listOf(TopLevelFilter.INSTALLED, TopLevelFilter.SYSTEM, TopLevelFilter.ALL),
                        selectedOption = topFilter,
                        onOptionSelected = {
                            topFilter = it
                            selectedCategories.clear()
                            onApply(currentFilters(top = it, categoryFilters = emptySet()))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { option, selected ->
                            Text(
                                text =
                                    when (option) {
                                        TopLevelFilter.INSTALLED -> stringResource(R.string.fapps_filter_parent_installed)
                                        TopLevelFilter.SYSTEM -> stringResource(R.string.fapps_filter_parent_system)
                                        TopLevelFilter.ALL -> stringResource(R.string.lbl_all)
                                    },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                            )
                        }
                    )
                }

                SheetSectionCard(shape = RoundedCornerShape(0.dp)) {
                    SheetSectionTitle(title = stringResource(R.string.lbl_status))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm),
                        verticalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
                    ) {
                        statusOptions.forEach { option ->
                            val selected = selectedFirewallFilter == option
                            ToggleButton(
                                checked = selected,
                                onCheckedChange = { checked ->
                                    if (checked && !selected) {
                                        performSelectionHaptic(hapticFeedback)
                                        selectedFirewallFilter = option
                                        onApply(currentFilters(status = option))
                                    }
                                },
                                colors = ToggleButtonDefaults.toggleButtonColors(
                                    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f),
                                    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                border = null,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Dimensions.cornerRadiusMd))
                                    .sizeIn(minHeight = Dimensions.touchTargetSm)
                                    .semantics { role = Role.RadioButton }
                            ) {
                                FirewallFilterIcon(
                                    filter = option,
                                    selected = selected,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.size(ToggleButtonDefaults.IconSpacing))
                                Text(
                                    text = option.getLabel(),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                SheetSectionCard(shape = RoundedCornerShape(0.dp)) {
                    SheetSectionTitle(
                        title = stringResource(R.string.fapps_filter_categories_heading),
                        count = selectedCategories.size
                    )

                    if (categories.isEmpty()) {
                        Text(
                            text = stringResource(R.string.fapps_empty_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm),
                            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingSm),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            categories.forEach { category ->
                                val isSelected = selectedCategories.contains(category)
                                ToggleButton(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        performSelectionHaptic(hapticFeedback)
                                        if (checked) {
                                            if (!isSelected) selectedCategories.add(category)
                                        } else {
                                            selectedCategories.remove(category)
                                        }
                                        onApply(currentFilters())
                                    },
                                    colors = ToggleButtonDefaults.toggleButtonColors(
                                        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f),
                                        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    border = null,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Dimensions.cornerRadiusMd))
                                        .sizeIn(minHeight = Dimensions.touchTargetSm)
                                        .semantics { role = Role.Checkbox }
                                ) {
                                    val iconRes = categoryFilterIconRes(category)
                                    if (iconRes != null) {
                                        Icon(
                                            painter = painterResource(id = iconRes),
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.size(ToggleButtonDefaults.IconSpacing))
                                    }
                                    Text(
                                        text = category,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

}

@Composable
private fun FirewallFilterIcon(
    filter: FirewallFilter,
    selected: Boolean = false,
    modifier: Modifier = Modifier
) {
    when (filter) {
        FirewallFilter.ALL -> Icon(
            imageVector = Icons.Rounded.Tune,
            contentDescription = null,
            modifier = modifier
        )
        FirewallFilter.ALLOWED -> Icon(
            imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Outlined.CheckCircle,
            contentDescription = null,
            modifier = modifier
        )
        FirewallFilter.BLOCKED -> Icon(
            imageVector = Icons.Rounded.Block,
            contentDescription = null,
            modifier = modifier
        )
        FirewallFilter.BYPASS -> Icon(
            painter = painterResource(id = R.drawable.ic_firewall_bypass_off),
            contentDescription = null,
            modifier = modifier
        )
        FirewallFilter.EXCLUDED -> Icon(
            painter = painterResource(id = R.drawable.ic_firewall_exclude_off),
            contentDescription = null,
            modifier = modifier
        )
        FirewallFilter.LOCKDOWN -> Icon(
            painter = painterResource(id = R.drawable.ic_firewall_lockdown_off),
            contentDescription = null,
            modifier = modifier
        )
        FirewallFilter.BLOCKED_WIFI -> Icon(
            painter = painterResource(id = R.drawable.ic_firewall_wifi_off),
            contentDescription = null,
            modifier = modifier
        )
        FirewallFilter.BLOCKED_MOBILE_DATA -> Icon(
            painter = painterResource(id = R.drawable.ic_firewall_data_off),
            contentDescription = null,
            modifier = modifier
        )
    }
}

private fun categoryFilterIconRes(category: String): Int? {
    val normalized = category.trim().lowercase(Locale.getDefault())
    return when {
        normalized.contains("system component") -> R.drawable.ic_settings
        normalized.contains("system service") || normalized.contains("non app") ->
            R.drawable.ic_network
        normalized.contains("system app") -> R.drawable.ic_android_icon
        normalized.contains("installed") -> R.drawable.ic_app_info
        normalized.contains("other") -> R.drawable.ic_other_settings
        normalized.contains("game") -> R.drawable.ic_firewall_lockdown_off
        normalized.contains("social") || normalized.contains("communication") ->
            R.drawable.ic_notification
        normalized.contains("news") -> R.drawable.ic_notification
        normalized.contains("photo") ||
            normalized.contains("image") ||
            normalized.contains("camera") -> R.drawable.ic_visibility
        normalized.contains("video") || normalized.contains("movie") ->
            R.drawable.ic_visibility
        normalized.contains("audio") || normalized.contains("music") -> R.drawable.ic_logs
        normalized.contains("map") || normalized.contains("travel") -> R.drawable.ic_location_on_24
        normalized.contains("productivity") ||
            normalized.contains("business") ||
            normalized.contains("tools") -> R.drawable.ic_settings
        normalized.contains("education") || normalized.contains("book") -> R.drawable.ic_about
        normalized.contains("health") || normalized.contains("fitness") -> R.drawable.ic_heart
        normalized.contains("finance") -> R.drawable.ic_backup
        else -> null
    }
}

private suspend fun fetchCategories(filter: TopLevelFilter): List<String> {
    return withContext(Dispatchers.IO) {
        when (filter) {
            TopLevelFilter.ALL -> FirewallManager.getAllCategories()
            TopLevelFilter.INSTALLED -> FirewallManager.getCategoriesForInstalledApps()
            TopLevelFilter.SYSTEM -> FirewallManager.getCategoriesForSystemApps()
        }
    }
}
