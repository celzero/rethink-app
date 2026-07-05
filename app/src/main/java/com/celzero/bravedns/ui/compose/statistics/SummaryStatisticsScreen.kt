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
package com.celzero.bravedns.ui.compose.statistics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Unspecified
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.data.DataUsageSummary
import com.celzero.bravedns.data.SummaryStatisticsType
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.compose.theme.CompactEmptyState
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkAnimatedSection
import com.celzero.bravedns.ui.compose.theme.RethinkListGroup
import com.celzero.bravedns.ui.compose.theme.RethinkListItem
import com.celzero.bravedns.ui.compose.theme.RethinkTopBarLazyColumnScreen
import com.celzero.bravedns.ui.compose.theme.SectionHeader
import com.celzero.bravedns.ui.compose.theme.cardPositionFor
import com.celzero.bravedns.util.UIUtils.formatBytes
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel.TimeCategory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryStatisticsScreen(
    viewModel: SummaryStatisticsViewModel,
    persistentState: PersistentState,
    onSeeMoreClick: (SummaryStatisticsType) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    val topActiveConns = viewModel.getTopActiveConns.collectAsLazyPagingItems()
    val mostConnectedApps = viewModel.getAllowedAppNetworkActivity.collectAsLazyPagingItems()
    val mostBlockedApps = viewModel.getBlockedAppNetworkActivity.collectAsLazyPagingItems()
    val mostConnectedAsn = viewModel.getMostConnectedASN.collectAsLazyPagingItems()
    val mostBlockedAsn = viewModel.getMostBlockedASN.collectAsLazyPagingItems()
    val mostContactedDomains = viewModel.mcd.collectAsLazyPagingItems()
    val mostBlockedDomains = viewModel.mbd.collectAsLazyPagingItems()
    val mostContactedCountries = viewModel.getMostContactedCountries.collectAsLazyPagingItems()
    val mostContactedIps = viewModel.getMostContactedIps.collectAsLazyPagingItems()
    val mostBlockedIps = viewModel.getMostBlockedIps.collectAsLazyPagingItems()

    RethinkTopBarLazyColumnScreen(
        title = stringResource(id = R.string.title_statistics),
        containerColor = MaterialTheme.colorScheme.surface,
        topBarTitleTextStyle = MaterialTheme.typography.headlineMedium,
        listState = listState,
        contentPadding = PaddingValues(
            start = Dimensions.screenPaddingHorizontal,
            end = Dimensions.screenPaddingHorizontal,
            top = Dimensions.spacingMd,
            bottom = Dimensions.spacing3xl
        ),
        topBarActions = {
            TimeCategorySelector(
                selectedCategory = uiState.timeCategory,
                onCategorySelected = { viewModel.timeCategoryChanged(it) }
            )
        }
    ) {
            item {
                RethinkAnimatedSection(index = 0) {
                    UsageOverviewCard(dataUsage = uiState.dataUsage)
                }
            }

            item {
                RethinkAnimatedSection(index = 1) {
                    SummaryStatSection(
                        title = stringResource(id = R.string.ssv_app_network_activity_heading),
                        type = SummaryStatisticsType.MOST_CONNECTED_APPS,
                        pagingItems = mostConnectedApps,
                        accentColor = MaterialTheme.colorScheme.primary,
                        onSeeMoreClick = onSeeMoreClick,
                        viewModel = viewModel,
                        refreshToken = uiState.timeCategory
                    )
                }
            }

            item {
                RethinkAnimatedSection(index = 2) {
                    SummaryStatSection(
                        title = stringResource(id = R.string.ssv_app_blocked_heading),
                        type = SummaryStatisticsType.MOST_BLOCKED_APPS,
                        pagingItems = mostBlockedApps,
                        accentColor = MaterialTheme.colorScheme.error,
                        onSeeMoreClick = onSeeMoreClick,
                        viewModel = viewModel,
                        refreshToken = uiState.timeCategory
                    )
                }
            }

            item {
                RethinkAnimatedSection(index = 3) {
                    SummaryStatSection(
                        title = stringResource(id = R.string.ssv_most_contacted_countries_heading),
                        type = SummaryStatisticsType.MOST_CONTACTED_COUNTRIES,
                        pagingItems = mostContactedCountries,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        onSeeMoreClick = onSeeMoreClick,
                        viewModel = viewModel,
                        refreshToken = uiState.timeCategory
                    )
                }
            }

            if (persistentState.downloadIpInfo) {
                if (shouldShowOptionalSection(mostConnectedAsn)) {
                    item {
                        SummaryStatSection(
                            title = stringResource(id = R.string.most_contacted_asn),
                            type = SummaryStatisticsType.MOST_CONNECTED_ASN,
                            pagingItems = mostConnectedAsn,
                            accentColor = MaterialTheme.colorScheme.secondary,
                            onSeeMoreClick = onSeeMoreClick,
                            viewModel = viewModel,
                            refreshToken = uiState.timeCategory
                        )
                    }
                }

                if (shouldShowOptionalSection(mostBlockedAsn)) {
                    item {
                        SummaryStatSection(
                            title = stringResource(id = R.string.most_blocked_asn),
                            type = SummaryStatisticsType.MOST_BLOCKED_ASN,
                            pagingItems = mostBlockedAsn,
                            accentColor = MaterialTheme.colorScheme.error,
                            onSeeMoreClick = onSeeMoreClick,
                            viewModel = viewModel,
                            refreshToken = uiState.timeCategory
                        )
                    }
                }
            }

            item {
                SummaryStatSection(
                    title = stringResource(id = R.string.ssv_most_contacted_domain_heading),
                    type = SummaryStatisticsType.MOST_CONTACTED_DOMAINS,
                    pagingItems = mostContactedDomains,
                    accentColor = MaterialTheme.colorScheme.secondary,
                    onSeeMoreClick = onSeeMoreClick,
                    viewModel = viewModel,
                    refreshToken = uiState.timeCategory
                )
            }

            item {
                SummaryStatSection(
                    title = stringResource(id = R.string.ssv_most_blocked_domain_heading),
                    type = SummaryStatisticsType.MOST_BLOCKED_DOMAINS,
                    pagingItems = mostBlockedDomains,
                    accentColor = MaterialTheme.colorScheme.error,
                    onSeeMoreClick = onSeeMoreClick,
                    viewModel = viewModel,
                    refreshToken = uiState.timeCategory
                )
            }

            item {
                SummaryStatSection(
                    title = stringResource(id = R.string.ssv_most_contacted_ips_heading),
                    type = SummaryStatisticsType.MOST_CONTACTED_IPS,
                    pagingItems = mostContactedIps,
                    accentColor = MaterialTheme.colorScheme.secondary,
                    onSeeMoreClick = onSeeMoreClick,
                    viewModel = viewModel,
                    refreshToken = uiState.timeCategory
                )
            }

            item {
                SummaryStatSection(
                    title = stringResource(id = R.string.ssv_most_blocked_ips_heading),
                    type = SummaryStatisticsType.MOST_BLOCKED_IPS,
                    pagingItems = mostBlockedIps,
                    accentColor = MaterialTheme.colorScheme.error,
                    onSeeMoreClick = onSeeMoreClick,
                    viewModel = viewModel,
                    refreshToken = uiState.timeCategory
                )
            }

            if (shouldShowOptionalSection(topActiveConns)) {
                item {
                    SummaryStatSection(
                        title = stringResource(id = R.string.top_active_conns),
                        type = SummaryStatisticsType.TOP_ACTIVE_CONNS,
                        pagingItems = topActiveConns,
                        accentColor = MaterialTheme.colorScheme.primary,
                        onSeeMoreClick = onSeeMoreClick,
                        viewModel = viewModel,
                        refreshToken = uiState.timeCategory
                    )
                }
            }
        }
}

@Composable
private fun UsageOverviewCard(dataUsage: DataUsageSummary) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimensions.cornerRadius4xl),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(id = R.string.lbl_overall),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                UsageStatPill(
                    label = stringResource(id = R.string.lbl_download),
                    value = formatBytes(dataUsage.totalDownload),
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.44f),
                    valueColor = MaterialTheme.colorScheme.primary
                )
                UsageStatPill(
                    label = stringResource(id = R.string.lbl_upload),
                    value = formatBytes(dataUsage.totalUpload),
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.42f),
                    valueColor = MaterialTheme.colorScheme.tertiary
                )
                UsageStatPill(
                    label = stringResource(id = R.string.lbl_connections),
                    value = dataUsage.connectionsCount.toString(),
                    modifier = Modifier.weight(1f),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    valueColor = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun UsageStatPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    containerColor: Color,
    valueColor: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Dimensions.cornerRadiusLg),
        color = containerColor
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = valueColor
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun TimeCategorySelector(
    selectedCategory: TimeCategory,
    onCategorySelected: (TimeCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = listOf(
        TimeCategory.ONE_HOUR to timeCategoryShortLabel(TimeCategory.ONE_HOUR),
        TimeCategory.TWENTY_FOUR_HOUR to timeCategoryShortLabel(TimeCategory.TWENTY_FOUR_HOUR),
        TimeCategory.SEVEN_DAYS to timeCategoryShortLabel(TimeCategory.SEVEN_DAYS)
    )
    val selectedIndex = options.indexOfFirst { it.first == selectedCategory }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEachIndexed { index, (category, label) ->
            val selected = index == selectedIndex
            ToggleButton(
                checked = selected,
                onCheckedChange = { isChecked ->
                    if (isChecked && selectedCategory != category) {
                        onCategorySelected(category)
                    }
                },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.toggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.semantics { role = Role.RadioButton }
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun timeCategoryShortLabel(category: TimeCategory): String {
    return when (category) {
        TimeCategory.ONE_HOUR -> stringResource(id = R.string.time_window_one_hour_short)
        TimeCategory.TWENTY_FOUR_HOUR -> stringResource(id = R.string.time_window_twenty_four_hours_short)
        TimeCategory.SEVEN_DAYS -> stringResource(id = R.string.time_window_seven_days_short)
    }
}

@Composable
private fun SummaryStatSection(
    title: String,
    type: SummaryStatisticsType,
    pagingItems: LazyPagingItems<AppConnection>,
    accentColor: Color,
    onSeeMoreClick: (SummaryStatisticsType) -> Unit,
    viewModel: SummaryStatisticsViewModel,
    refreshToken: TimeCategory
) {
    val isCountrySection = type == SummaryStatisticsType.MOST_CONTACTED_COUNTRIES
    val refreshState = pagingItems.loadState.refresh
    val isLoading = refreshState is LoadState.Loading && pagingItems.itemCount == 0
    val hasData = pagingItems.itemCount > 0
    val isEmpty = !hasData && !isLoading
    var showAllInlineCountries by remember(type) { mutableStateOf(false) }
    val showAllVisibilityState = remember(type) { MutableTransitionState(false) }
    val snapshotItems: List<AppConnection> = pagingItems.itemSnapshotList.items.filterNotNull()
    val visibleItems = if (isCountrySection) snapshotItems.take(5) else snapshotItems
    var expandedCountryFlag by remember(type) { mutableStateOf<String?>(null) }

    LaunchedEffect(showAllInlineCountries) {
        showAllVisibilityState.targetState = showAllInlineCountries
    }

    Column {
        SectionHeader(
            title = title,
            color = accentColor,
            actionLabel = null,
            onAction = null
        )

        if (isLoading) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimensions.cornerRadius4xl),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Dimensions.spacingLg),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(Dimensions.iconSizeMd))
                }
            }
        } else if (isEmpty) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Dimensions.cornerRadius4xl),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f))
            ) {
                CompactEmptyState(
                    message = stringResource(id = R.string.lbl_no_logs),
                    modifier = Modifier.padding(vertical = Dimensions.spacingSm)
                )
            }
        } else {
            val sizeSpec = tween<IntSize>(durationMillis = 220, easing = FastOutSlowInEasing)
            RethinkListGroup {
                val baseItems = if (isCountrySection) snapshotItems.take(5) else visibleItems
                val extraCountryItems = if (isCountrySection) snapshotItems.drop(5) else emptyList()
                val isExtraBlockPresent =
                    isCountrySection && extraCountryItems.isNotEmpty() &&
                        (showAllVisibilityState.currentState || showAllVisibilityState.targetState)
                val visibleLastIndex =
                    if (isExtraBlockPresent) snapshotItems.lastIndex else baseItems.lastIndex

                val renderRow: @Composable (index: Int, item: AppConnection) -> Unit = { index, item ->
                    val metricText = item.totalBytes?.takeIf { it > 0L }?.let { formatBytes(it) } ?: item.count.toString()
                    val countryName = if (isCountrySection) countryNameFromFlag(item.flag) else null
                    val countryHeadline = when {
                        item.appOrDnsName?.isNotBlank() == true && item.flag.isNotBlank() ->
                            "${item.flag} ${item.appOrDnsName}"
                        item.appOrDnsName?.isNotBlank() == true -> item.appOrDnsName
                        item.flag.isNotBlank() -> item.flag
                        else -> stringResource(id = R.string.network_log_app_name_unknown)
                    }
                    val headline = if (isCountrySection) {
                        countryName ?: countryHeadline
                    } else {
                        item.appOrDnsName?.takeIf { it.isNotBlank() } ?: item.ipAddress
                    }
                    val supporting = buildString {
                        append(stringResource(id = R.string.summary_connections_count, item.count))
                        item.totalBytes?.takeIf { it > 0L }?.let {
                            append(" \u00b7 ")
                            append(formatBytes(it))
                        }
                    }
                    val appIconPainter =
                        if (type.supportsAppIcon()) {
                            rememberStatisticsAppIconPainter(item.uid)
                        } else {
                            null
                        }
                    val hasTrueAppIcon = appIconPainter != null
                    val fallbackPainter =
                        if (isCountrySection && item.flag.isBlank()) {
                            painterResource(id = R.drawable.ic_flag_placeholder)
                        } else if (isCountrySection) {
                            null
                        } else {
                            painterResource(id = R.drawable.ic_app_info)
                        }
                    val customLeadingContent: (@Composable () -> Unit)? =
                        when {
                            isCountrySection && item.flag.isNotBlank() -> {
                                {
                                    Text(
                                        text = item.flag,
                                        style = MaterialTheme.typography.headlineMedium
                                    )
                                }
                            }
                            hasTrueAppIcon && appIconPainter != null -> {
                                {
                                    Icon(
                                        painter = appIconPainter,
                                        contentDescription = null,
                                        tint = Unspecified,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            else -> null
                        }
                    val isExpanded = isCountrySection && expandedCountryFlag == item.flag

                    RethinkListItem(
                        headline = headline.ifBlank { "-" },
                        supporting = if (isCountrySection) null else supporting,
                        leadingContent = customLeadingContent,
                        leadingIconPainter = if (customLeadingContent == null) appIconPainter ?: fallbackPainter else null,
                        leadingIconTint = when {
                            hasTrueAppIcon -> Unspecified
                            isCountrySection -> MaterialTheme.colorScheme.tertiary
                            else -> accentColor
                        },
                        leadingIconContainerColor = if (isCountrySection) {
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.32f)
                        } else if (hasTrueAppIcon) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            accentColor.copy(alpha = 0.14f)
                        },
                        position = cardPositionFor(index = index, lastIndex = visibleLastIndex),
                        showTrailingChevron = false,
                        onClick = if (isCountrySection) {
                            {
                                expandedCountryFlag = if (isExpanded) null else item.flag
                            }
                        } else {
                            null
                        },
                        trailing = {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(
                                    text = metricText,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = accentColor
                                )
                                if (isCountrySection) {
                                    Spacer(modifier = Modifier.size(Dimensions.spacingXs))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier
                                            .size(18.dp)
                                            .rotate(if (isExpanded) 90f else 0f)
                                    )
                                }
                            }
                        }
                    )

                    AnimatedVisibility(
                        visible = isExpanded && item.flag.isNotBlank(),
                        enter = expandVertically(
                            animationSpec = sizeSpec
                        ),
                        exit = shrinkVertically(
                            animationSpec = sizeSpec
                        )
                    ) {
                        CountryBreakdown(
                            flag = item.flag,
                            accentColor = accentColor,
                            viewModel = viewModel,
                            refreshToken = refreshToken
                        )
                    }
                }

                baseItems.forEachIndexed { index, item ->
                    renderRow(index, item)
                }

                if (isCountrySection && extraCountryItems.isNotEmpty()) {
                    AnimatedVisibility(
                        visibleState = showAllVisibilityState,
                        enter = expandVertically(animationSpec = sizeSpec),
                        exit = shrinkVertically(animationSpec = sizeSpec)
                    ) {
                        Column {
                            extraCountryItems.forEachIndexed { extraIndex, item ->
                                renderRow(baseItems.size + extraIndex, item)
                            }
                        }
                    }
                }
            }

            val shouldShowInlineCountriesToggle =
                isCountrySection && (showAllInlineCountries || pagingItems.itemCount > 5)
            val shouldShowDefaultSeeMore = !isCountrySection && pagingItems.itemCount > 5

            if (shouldShowInlineCountriesToggle || shouldShowDefaultSeeMore) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Dimensions.spacingSm),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(
                        onClick = {
                            if (isCountrySection) {
                                val nextShowAll = !showAllInlineCountries
                                if (!nextShowAll && expandedCountryFlag != null) {
                                    val isExpandedRowVisibleInCollapsed =
                                        snapshotItems.take(5).any { it.flag == expandedCountryFlag }
                                    if (!isExpandedRowVisibleInCollapsed) {
                                        expandedCountryFlag = null
                                    }
                                }
                                showAllInlineCountries = nextShowAll
                            } else {
                                onSeeMoreClick(type)
                            }
                        },
                        shape = RoundedCornerShape(Dimensions.cornerRadiusPill)
                    ) {
                        Text(
                            text = if (isCountrySection && showAllInlineCountries) {
                                stringResource(id = R.string.ssv_see_less)
                            } else {
                                stringResource(id = R.string.ssv_see_more)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

private fun shouldShowOptionalSection(pagingItems: LazyPagingItems<AppConnection>): Boolean {
    val isLoading = pagingItems.loadState.refresh is LoadState.Loading
    return isLoading || pagingItems.itemCount > 0
}

@Composable
private fun CountryBreakdown(
    flag: String,
    accentColor: Color,
    viewModel: SummaryStatisticsViewModel,
    refreshToken: TimeCategory
) {
    val apps by produceState<List<AppConnection>>(initialValue = emptyList(), flag, refreshToken) {
        value = viewModel.getTopAppsForCountry(flag, limit = 4)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimensions.spacingXs, bottom = Dimensions.spacingXs),
        shape = RoundedCornerShape(Dimensions.cornerRadiusLg),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Dimensions.spacingMd, vertical = Dimensions.spacingSm),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
        ) {
            Text(
                text = stringResource(id = R.string.ssv_app_network_activity_heading),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = accentColor
            )
            if (apps.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.lbl_no_logs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                apps.forEach { app ->
                    val iconPainter = rememberStatisticsAppIconPainter(app.uid)
                    val appName = app.appOrDnsName?.takeIf { it.isNotBlank() }
                        ?: stringResource(id = R.string.network_log_app_name_unknown)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier.size(20.dp)
                            ) {
                                if (iconPainter != null) {
                                    Icon(
                                        painter = iconPainter,
                                        contentDescription = null,
                                        tint = Unspecified,
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_app_info),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Text(
                                text = appName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                        }
                        Text(
                            text = app.count.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}
