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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.ui.compose.theme.CardPosition
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.RethinkListGroup
import com.celzero.bravedns.ui.compose.theme.RethinkListItem
import com.celzero.bravedns.ui.compose.theme.RethinkSearchField
import com.celzero.bravedns.ui.compose.theme.RethinkTopBar
import com.celzero.bravedns.ui.compose.theme.RethinkConnectedChoiceButtonRow
import com.celzero.bravedns.ui.compose.theme.cardPositionFor
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_PORT
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import com.celzero.bravedns.viewmodel.CustomIpViewModel
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

enum class RulesTab(val value: Int) {
    IP(0),
    DOMAIN(1);

    companion object {
        fun fromValue(value: Int): RulesTab {
            return entries.firstOrNull { it.value == value } ?: IP
        }
    }
}

enum class RulesMode(val value: Int) {
    ALL_RULES(0),
    APP_SPECIFIC(1);

    companion object {
        fun fromValue(value: Int): RulesMode {
            return entries.firstOrNull { it.value == value } ?: APP_SPECIFIC
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomRulesScreen(
    uid: Int = UID_EVERYBODY,
    initialTab: RulesTab = RulesTab.IP,
    initialMode: RulesMode = RulesMode.APP_SPECIFIC,
    domainViewModel: CustomDomainViewModel,
    ipViewModel: CustomIpViewModel,
    eventLogger: EventLogger,
    onBackClick: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val navBarBottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    var selectedTab by rememberSaveable(uid, initialTab) { mutableStateOf(initialTab) }
    var selectedMode by rememberSaveable(uid, initialMode) { mutableStateOf(initialMode) }
    var showAddDialog by remember { mutableStateOf(false) }
    var ipQuery by rememberSaveable { mutableStateOf("") }
    var domainQuery by rememberSaveable { mutableStateOf("") }
    val canSwitchScope = uid == UID_EVERYBODY
    val effectiveMode = if (canSwitchScope) selectedMode else RulesMode.APP_SPECIFIC
    val isUniversalRules = uid == UID_EVERYBODY && effectiveMode == RulesMode.APP_SPECIFIC
    val showAddButton = effectiveMode == RulesMode.APP_SPECIFIC

    LaunchedEffect(effectiveMode) {
        if (effectiveMode != RulesMode.APP_SPECIFIC) {
            showAddDialog = false
        }
    }

    Scaffold(
        topBar = {
            RethinkTopBar(
                title =
                    if (isUniversalRules) {
                        stringResource(R.string.univ_view_blocked_ip)
                    } else if (effectiveMode == RulesMode.ALL_RULES) {
                        stringResource(R.string.lbl_app_wise)
                    } else {
                        stringResource(R.string.app_ip_domain_rules)
                    },
                onBackClick = onBackClick
            )
        },
        floatingActionButton = {
            if (showAddButton) {
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = true },
                    icon = {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    },
                    text = {
                        Text(text = stringResource(R.string.lbl_add))
                    },
                    modifier = Modifier.padding(bottom = navBarBottomInset + 6.dp)
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center,
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        when (selectedTab) {
            RulesTab.IP ->
                IpRulesContent(
                    modifier = Modifier.padding(padding),
                    uid = uid,
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    rulesMode = effectiveMode,
                    canSwitchScope = canSwitchScope,
                    onRulesModeChange = { selectedMode = it },
                    query = ipQuery,
                    onQueryChange = { ipQuery = it },
                    viewModel = ipViewModel,
                    eventLogger = eventLogger
                )

            RulesTab.DOMAIN ->
                DomainRulesContent(
                    modifier = Modifier.padding(padding),
                    uid = uid,
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    rulesMode = effectiveMode,
                    canSwitchScope = canSwitchScope,
                    onRulesModeChange = { selectedMode = it },
                    query = domainQuery,
                    onQueryChange = { domainQuery = it },
                    viewModel = domainViewModel,
                    eventLogger = eventLogger
                )
        }
    }

    if (showAddDialog && showAddButton) {
        AddRuleDialog(
            isIpRule = selectedTab == RulesTab.IP,
            onDismiss = { showAddDialog = false },
            onAddIpRule = { ip ->
                scope.launch(Dispatchers.IO) {
                    val ipAddress = IPAddressString(ip).address ?: return@launch
                    IpRulesManager.addIpRule(uid, ipAddress, null, IpRulesManager.IpRuleStatus.BLOCK, "", "")
                }
                eventLogger.log(
                    EventType.FW_RULE_MODIFIED,
                    Severity.LOW,
                    "Added IP rule",
                    EventSource.UI,
                    false,
                    "IP: $ip"
                )
                showAddDialog = false
            },
            onAddDomainRule = { domain ->
                scope.launch(Dispatchers.IO) {
                    DomainRulesManager.addDomainRule(
                        domain,
                        DomainRulesManager.Status.BLOCK,
                        DomainRulesManager.DomainType.DOMAIN,
                        uid = uid
                    )
                }
                eventLogger.log(
                    EventType.FW_RULE_MODIFIED,
                    Severity.LOW,
                    "Added domain rule",
                    EventSource.UI,
                    false,
                    "Domain: $domain"
                )
                showAddDialog = false
            }
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun IpRulesContent(
    modifier: Modifier = Modifier,
    uid: Int,
    selectedTab: RulesTab,
    onTabSelected: (RulesTab) -> Unit,
    rulesMode: RulesMode,
    canSwitchScope: Boolean,
    onRulesModeChange: (RulesMode) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    viewModel: CustomIpViewModel,
    eventLogger: EventLogger
) {
    val items =
        when (rulesMode) {
            RulesMode.APP_SPECIFIC -> viewModel.customIpDetails.asFlow().collectAsLazyPagingItems()
            RulesMode.ALL_RULES -> viewModel.allIpRules.asFlow().collectAsLazyPagingItems()
        }

    RulesContent(
        modifier = modifier,
        uid = uid,
        selectedTab = selectedTab,
        onTabSelected = onTabSelected,
        rulesMode = rulesMode,
        canSwitchScope = canSwitchScope,
        onRulesModeChange = onRulesModeChange,
        query = query,
        onQueryChange = onQueryChange,
        hint = stringResource(R.string.lbl_ip_rules),
        emptyText = stringResource(R.string.rules_load_failure_desc),
        items = items,
        setUid = { modeUid -> viewModel.setUid(modeUid) },
        setFilter = { filter -> viewModel.setFilter(filter) },
        groupBy = { it.uid },
        onDeleteRule = { item -> IpRulesManager.removeIpRule(item.uid, item.ipAddress, item.port) },
        deleteEventMessage = "Removed IP rule",
        deleteEventDetails = { item -> "IP: ${item.ipAddress}" },
        eventLogger = eventLogger
    ) { item, position, onDelete ->
        IpRuleListItem(
            rule = item,
            position = position,
            onDelete = onDelete
        )
    }
}

@Composable
private fun IpRuleListItem(
    rule: CustomIp,
    position: CardPosition,
    onDelete: () -> Unit
) {
    val status = IpRulesManager.IpRuleStatus.getStatus(rule.status)
    val statusLabelRes =
        when (status) {
            IpRulesManager.IpRuleStatus.BLOCK -> R.string.ci_block
            IpRulesManager.IpRuleStatus.TRUST -> R.string.ci_trust_rule
            IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> R.string.firewall_status_whitelisted
            IpRulesManager.IpRuleStatus.NONE -> R.string.ci_no_rule
        }
    val statusColor =
        when (status) {
            IpRulesManager.IpRuleStatus.BLOCK -> MaterialTheme.colorScheme.error
            IpRulesManager.IpRuleStatus.TRUST -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    val headline = if (rule.port == UNSPECIFIED_PORT) rule.ipAddress else "${rule.ipAddress}:${rule.port}"

    RuleListItem(
        headline = headline,
        supporting = stringResource(statusLabelRes),
        iconRes = R.drawable.ic_ip_address,
        accent = statusColor,
        position = position,
        onDelete = onDelete
    )
}

@OptIn(FlowPreview::class)
@Composable
private fun DomainRulesContent(
    modifier: Modifier = Modifier,
    uid: Int,
    selectedTab: RulesTab,
    onTabSelected: (RulesTab) -> Unit,
    rulesMode: RulesMode,
    canSwitchScope: Boolean,
    onRulesModeChange: (RulesMode) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    viewModel: CustomDomainViewModel,
    eventLogger: EventLogger
) {
    val items =
        when (rulesMode) {
            RulesMode.APP_SPECIFIC -> viewModel.customDomains.asFlow().collectAsLazyPagingItems()
            RulesMode.ALL_RULES -> viewModel.allDomainRules.asFlow().collectAsLazyPagingItems()
        }

    RulesContent(
        modifier = modifier,
        uid = uid,
        selectedTab = selectedTab,
        onTabSelected = onTabSelected,
        rulesMode = rulesMode,
        canSwitchScope = canSwitchScope,
        onRulesModeChange = onRulesModeChange,
        query = query,
        onQueryChange = onQueryChange,
        hint = stringResource(R.string.lbl_domain_rules),
        emptyText = stringResource(R.string.cd_no_rules_text),
        items = items,
        setUid = { modeUid -> viewModel.setUid(modeUid) },
        setFilter = { filter -> viewModel.setFilter(filter) },
        groupBy = { it.uid },
        onDeleteRule = { item -> DomainRulesManager.deleteDomain(item) },
        deleteEventMessage = "Removed domain rule",
        deleteEventDetails = { item -> "Domain: ${item.domain}" },
        eventLogger = eventLogger
    ) { item, position, onDelete ->
        DomainRuleListItem(
            rule = item,
            position = position,
            onDelete = onDelete
        )
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun <T : Any> RulesContent(
    modifier: Modifier,
    uid: Int,
    selectedTab: RulesTab,
    onTabSelected: (RulesTab) -> Unit,
    rulesMode: RulesMode,
    canSwitchScope: Boolean,
    onRulesModeChange: (RulesMode) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String,
    emptyText: String,
    items: LazyPagingItems<T>,
    setUid: (Int) -> Unit,
    setFilter: (String) -> Unit,
    groupBy: (T) -> Int,
    onDeleteRule: suspend (T) -> Unit,
    deleteEventMessage: String,
    deleteEventDetails: (T) -> String,
    eventLogger: EventLogger,
    row: @Composable (item: T, position: CardPosition, onDelete: () -> Unit) -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val navBarBottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }

    LaunchedEffect(uid, rulesMode) {
        setUid(if (rulesMode == RulesMode.APP_SPECIFIC) uid else UID_EVERYBODY)
    }

    LaunchedEffect(Unit) {
        snapshotFlow { query }
            .debounce(250)
            .distinctUntilChanged()
            .collect { q -> setFilter(q) }
    }

    val isRefreshing = items.loadState.refresh is LoadState.Loading
    val isEmpty = !isRefreshing && items.itemCount == 0

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        RulesControlDeck(
            selectedTab = selectedTab,
            onTabSelected = onTabSelected,
            rulesMode = rulesMode,
            canSwitchScope = canSwitchScope,
            onRulesModeChange = onRulesModeChange,
            query = query,
            onQueryChange = onQueryChange,
            hint = hint
        )

        if (isRefreshing) {
            RulesInfoRow(
                text = stringResource(R.string.lbl_loading),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimensions.spacingSm)
            )
        } else if (isEmpty) {
            RulesEmptyState(
                selectedTab = selectedTab,
                text = emptyText,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = Dimensions.screenPaddingHorizontal,
                        end = Dimensions.screenPaddingHorizontal,
                        bottom = navBarBottomInset
                    )
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = Dimensions.screenPaddingHorizontal,
                        end = Dimensions.screenPaddingHorizontal,
                        top = Dimensions.spacingSm,
                        bottom = if (rulesMode == RulesMode.APP_SPECIFIC) {
                            112.dp + navBarBottomInset
                        } else {
                            Dimensions.spacing3xl + navBarBottomInset
                        }
                    ),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(items.itemCount) { index ->
                    val item = items[index] ?: return@items
                    val showHeader =
                        rulesMode == RulesMode.ALL_RULES &&
                            shouldShowGroupHeader(items, index, groupBy)
                    if (showHeader) {
                        if (index > 0) {
                            Spacer(modifier = Modifier.height(Dimensions.spacingSm))
                        }
                        RulesAppHeader(uid = groupBy(item))
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    val position =
                        if (rulesMode == RulesMode.ALL_RULES) {
                            groupedCardPosition(items, index, item, groupBy)
                        } else {
                            cardPositionFor(index, items.itemCount - 1)
                        }

                    row(
                        item,
                        position,
                        {
                            scope.launch(Dispatchers.IO) {
                                onDeleteRule(item)
                            }
                            eventLogger.log(
                                EventType.FW_RULE_MODIFIED,
                                Severity.LOW,
                                deleteEventMessage,
                                EventSource.UI,
                                false,
                                deleteEventDetails(item)
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RulesEmptyState(
    selectedTab: RulesTab,
    text: String,
    modifier: Modifier = Modifier
) {
    val iconRes =
        when (selectedTab) {
            RulesTab.IP -> R.drawable.ic_ip_address
            RulesTab.DOMAIN -> R.drawable.ic_undelegated_domain
        }
    val accent =
        when (selectedTab) {
            RulesTab.IP -> MaterialTheme.colorScheme.primary
            RulesTab.DOMAIN -> MaterialTheme.colorScheme.tertiary
        }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Dimensions.cornerRadiusMdLg),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(Dimensions.cornerRadiusPill),
                    color = accent.copy(alpha = 0.14f)
                ) {
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier
                            .padding(10.dp)
                            .size(20.dp)
                    )
                }
                Text(
                    text = text,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun DomainRuleListItem(
    rule: CustomDomain,
    position: CardPosition,
    onDelete: () -> Unit
) {
    val status = DomainRulesManager.Status.getStatus(rule.status)
    val statusLabelRes =
        when (status) {
            DomainRulesManager.Status.BLOCK -> R.string.ci_block
            DomainRulesManager.Status.TRUST -> R.string.ci_trust_rule
            DomainRulesManager.Status.NONE -> R.string.ci_no_rule
        }
    val statusColor =
        when (status) {
            DomainRulesManager.Status.BLOCK -> MaterialTheme.colorScheme.error
            DomainRulesManager.Status.TRUST -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    RuleListItem(
        headline = rule.domain,
        supporting = stringResource(statusLabelRes),
        iconRes = R.drawable.ic_undelegated_domain,
        accent = statusColor,
        position = position,
        onDelete = onDelete
    )
}

@Composable
private fun RuleListItem(
    headline: String,
    supporting: String,
    iconRes: Int,
    accent: Color,
    position: CardPosition,
    onDelete: () -> Unit
) {
    RethinkListItem(
        headline = headline,
        supporting = null,
        leadingIconPainter = painterResource(id = iconRes),
        leadingIconTint = accent,
        leadingIconContainerColor = accent.copy(alpha = 0.14f),
        position = position,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Surface(
                    shape = RoundedCornerShape(Dimensions.cornerRadiusPill),
                    color = accent.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(imageVector = Icons.Filled.Delete, contentDescription = stringResource(R.string.lbl_delete))
                }
            }
        }
    )
}

@Composable
private fun RulesAppHeader(uid: Int) {
    val label by
        produceState(initialValue = "UID $uid", key1 = uid) {
            value =
                withContext(Dispatchers.IO) {
                    val appName = FirewallManager.getAppNameByUid(uid).orEmpty().trim()
                    if (appName.isEmpty()) {
                        "UID $uid"
                    } else {
                        appName
                    }
                }
        }

    val supporting = if (label == "UID $uid") null else "UID $uid"
    RethinkListItem(
        headline = label,
        supporting = supporting,
        leadingIcon = Icons.Rounded.Apps,
        position = CardPosition.Single,
        defaultContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    )
}

private fun <T : Any> shouldShowGroupHeader(
    items: LazyPagingItems<T>,
    index: Int,
    groupBy: (T) -> Int
): Boolean {
    if (index == 0) return true
    val current = items[index] ?: return true
    val prev = items[index - 1] ?: return true
    return groupBy(prev) != groupBy(current)
}

private fun <T : Any> groupedCardPosition(
    items: LazyPagingItems<T>,
    index: Int,
    item: T,
    groupBy: (T) -> Int
): CardPosition {
    val itemGroup = groupBy(item)
    val hasPrevSame = index > 0 && items[index - 1]?.let(groupBy) == itemGroup
    val hasNextSame = index < items.itemCount - 1 && items[index + 1]?.let(groupBy) == itemGroup
    return when {
        !hasPrevSame && !hasNextSame -> CardPosition.Single
        !hasPrevSame -> CardPosition.First
        !hasNextSame -> CardPosition.Last
        else -> CardPosition.Middle
    }
}

@Composable
private fun RulesControlDeck(
    selectedTab: RulesTab,
    onTabSelected: (RulesTab) -> Unit,
    rulesMode: RulesMode,
    canSwitchScope: Boolean,
    onRulesModeChange: (RulesMode) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimensions.screenPaddingHorizontal)
            .padding(top = Dimensions.spacingXs),
        verticalArrangement = Arrangement.spacedBy(Dimensions.spacingXs)
    ) {
        if (canSwitchScope) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingXs)
            ) {
                RuleTypeSelector(
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                    modifier = Modifier.weight(1f),
                    compact = true
                )
                RuleScopeSelector(
                    rulesMode = rulesMode,
                    onRulesModeChange = onRulesModeChange,
                    modifier = Modifier.weight(1f),
                    compact = true
                )
            }
        } else {
            RuleTypeSelector(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected
            )
        }

        RulesSearchField(
            query = query,
            onQueryChange = onQueryChange,
            hint = hint
        )
    }
}

@Composable
private fun RuleTypeSelector(
    selectedTab: RulesTab,
    onTabSelected: (RulesTab) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    RethinkConnectedChoiceButtonRow(
        options = listOf(RulesTab.IP, RulesTab.DOMAIN),
        selectedOption = selectedTab,
        onOptionSelected = { tab -> onTabSelected(tab) },
        modifier = modifier.fillMaxWidth(),
        buttonMinHeight = if (compact) 40.dp else 0.dp,
        label = { option, selected ->
            val labelRes =
                when (option) {
                    RulesTab.IP -> if (compact) R.string.lbl_ip else R.string.lbl_ip_rules
                    RulesTab.DOMAIN -> if (compact) R.string.lbl_domain else R.string.lbl_domain_rules
                }
            Text(
                text = stringResource(labelRes),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    )
}

@Composable
private fun RuleScopeSelector(
    rulesMode: RulesMode,
    onRulesModeChange: (RulesMode) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    RethinkConnectedChoiceButtonRow(
        options = listOf(RulesMode.APP_SPECIFIC, RulesMode.ALL_RULES),
        selectedOption = rulesMode,
        onOptionSelected = { mode -> onRulesModeChange(mode) },
        modifier = modifier.fillMaxWidth(),
        buttonMinHeight = if (compact) 40.dp else 0.dp,
        label = { option, _ ->
            Text(
                text =
                    stringResource(
                        when (option) {
                            RulesMode.APP_SPECIFIC -> R.string.firewall_act_universal_tab
                            RulesMode.ALL_RULES -> R.string.lbl_app_wise
                        }
                    ),
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                fontWeight = if (option == rulesMode) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    )
}

@Composable
private fun RulesSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    hint: String
) {
    RethinkSearchField(
        query = query,
        onQueryChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = stringResource(R.string.two_argument_colon, stringResource(R.string.lbl_search), hint),
        onClearQuery = { onQueryChange("") },
        clearQueryContentDescription = stringResource(R.string.cd_clear_search),
        shape = RoundedCornerShape(Dimensions.cornerRadiusMdLg),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    )
}

@Composable
private fun RulesInfoRow(
    text: String,
    modifier: Modifier = Modifier
) {
    RethinkListGroup {
        RethinkListItem(
            headline = text,
            position = CardPosition.Single,
            enabled = false,
            modifier = modifier.padding(horizontal = Dimensions.screenPaddingHorizontal)
        )
    }
}

@Composable
private fun AddRuleDialog(
    isIpRule: Boolean,
    onDismiss: () -> Unit,
    onAddIpRule: (String) -> Unit,
    onAddDomainRule: (String) -> Unit
) {
    var ruleValue by remember { mutableStateOf("") }
    val title =
        if (isIpRule) {
            stringResource(R.string.lbl_ip_rules)
        } else {
            stringResource(R.string.lbl_domain_rules)
        }

    RethinkConfirmDialog(
        onDismissRequest = onDismiss,
        title = title,
        text = {
            OutlinedTextField(
                value = ruleValue,
                onValueChange = { ruleValue = it },
                label = { Text(text = title) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmText = stringResource(R.string.lbl_add),
        dismissText = stringResource(R.string.lbl_cancel),
        confirmEnabled = ruleValue.isNotBlank(),
        onConfirm = {
            if (ruleValue.isNotBlank()) {
                if (isIpRule) onAddIpRule(ruleValue.trim())
                else onAddDomainRule(ruleValue.trim())
            }
        },
        onDismiss = onDismiss
    )
}
