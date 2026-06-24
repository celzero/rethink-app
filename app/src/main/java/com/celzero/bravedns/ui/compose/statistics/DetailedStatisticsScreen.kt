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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.data.SummaryStatisticsType
import com.celzero.bravedns.ui.compose.theme.CardPosition
import com.celzero.bravedns.ui.compose.theme.CompactEmptyState
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import com.celzero.bravedns.ui.compose.theme.RethinkListItem
import com.celzero.bravedns.ui.compose.theme.cardPositionFor
import com.celzero.bravedns.util.UIUtils.formatBytes
import com.celzero.bravedns.viewmodel.DetailedStatisticsViewModel
import com.celzero.bravedns.viewmodel.SummaryStatisticsViewModel.TimeCategory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailedStatisticsScreen(
    viewModel: DetailedStatisticsViewModel,
    type: SummaryStatisticsType,
    timeCategory: TimeCategory,
    onBackClick: () -> Unit
) {
    val pagingItems = when (type) {
        SummaryStatisticsType.TOP_ACTIVE_CONNS -> viewModel.getAllActiveConns
        SummaryStatisticsType.MOST_CONNECTED_APPS -> viewModel.getAllAllowedAppNetworkActivity
        SummaryStatisticsType.MOST_BLOCKED_APPS -> viewModel.getAllBlockedAppNetworkActivity
        SummaryStatisticsType.MOST_CONNECTED_ASN -> viewModel.getAllAllowedAsn
        SummaryStatisticsType.MOST_BLOCKED_ASN -> viewModel.getAllBlockedAsn
        SummaryStatisticsType.MOST_CONTACTED_DOMAINS -> viewModel.getAllContactedDomains
        SummaryStatisticsType.MOST_BLOCKED_DOMAINS -> viewModel.getAllBlockedDomains
        SummaryStatisticsType.MOST_CONTACTED_IPS -> viewModel.getAllContactedIps
        SummaryStatisticsType.MOST_BLOCKED_IPS -> viewModel.getAllBlockedIps
        SummaryStatisticsType.MOST_CONTACTED_COUNTRIES -> viewModel.getAllContactedCountries
    }.collectAsLazyPagingItems()

    LaunchedEffect(type, timeCategory) {
        viewModel.setData(type)
        viewModel.timeCategoryChanged(timeCategory)
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val timeSubtitle = if (type == SummaryStatisticsType.TOP_ACTIVE_CONNS) null else getTimeCategoryText(timeCategory)
    val density = LocalDensity.current
    val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            DetailedStatisticsTopBar(
                type = type,
                subtitle = timeSubtitle,
                itemCount = pagingItems.itemCount,
                scrollBehavior = scrollBehavior,
                onBackClick = onBackClick
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            when {
                pagingItems.loadState.refresh is LoadState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                pagingItems.itemCount == 0 -> {
                    CompactEmptyState(
                        message = stringResource(R.string.blocklist_update_check_failure),
                        icon = Icons.Default.Info,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = Dimensions.screenPaddingHorizontal,
                            end = Dimensions.screenPaddingHorizontal,
                            top = Dimensions.spacingSm,
                            bottom = Dimensions.spacingLg + bottomInset
                        ),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        items(pagingItems.itemCount) { index ->
                            pagingItems[index]?.let { item ->
                                DetailedStatListItem(
                                    item = item,
                                    type = type,
                                    position = cardPositionFor(index, pagingItems.itemCount - 1)
                                )
                            }
                        }

                        if (pagingItems.loadState.append is LoadState.Loading) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(Dimensions.spacingLg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(Dimensions.iconSizeMd)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailedStatisticsTopBar(
    type: SummaryStatisticsType,
    subtitle: String?,
    itemCount: Int,
    scrollBehavior: TopAppBarScrollBehavior,
    onBackClick: () -> Unit
) {
    RethinkLargeTopBar(
        title = stringResource(id = getTitleResId(type)),
        subtitle = subtitle,
        onBackClick = onBackClick,
        scrollBehavior = scrollBehavior,
        titleTextStyle = MaterialTheme.typography.headlineMedium,
        actions = {
            if (itemCount > 0) {
                Surface(
                    shape = RoundedCornerShape(Dimensions.cornerRadiusFull),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(end = Dimensions.spacingSm)
                ) {
                    Text(
                        text = itemCount.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    )
}

@Composable
private fun DetailedStatListItem(
    item: AppConnection,
    type: SummaryStatisticsType,
    position: CardPosition
) {
    val isCountryType = type == SummaryStatisticsType.MOST_CONTACTED_COUNTRIES
    val appIconPainter = if (type.supportsAppIcon()) rememberStatisticsAppIconPainter(item.uid) else null
    val hasTrueAppIcon = appIconPainter != null
    val countryName = if (isCountryType) countryNameFromFlag(item.flag) else null

    val titleText = when {
        item.appOrDnsName?.isNotBlank() == true -> item.appOrDnsName
        item.ipAddress.isNotBlank() -> item.ipAddress
        else -> stringResource(id = R.string.network_log_app_name_unknown)
    }

    val metricText = buildString {
        append(stringResource(id = R.string.summary_connections_count, item.count))
        item.totalBytes?.takeIf { it > 0L }?.let {
            append(" \u00b7 ")
            append(formatBytes(it))
        }
    }

    val leadingContent: (@Composable () -> Unit)? = when {
        isCountryType && item.flag.isNotBlank() -> {
            {
                Text(
                    text = item.flag,
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        }
        hasTrueAppIcon && appIconPainter != null -> {
            {
                androidx.compose.material3.Icon(
                    painter = appIconPainter,
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        else -> null
    }

    RethinkListItem(
        headline = if (isCountryType) metricText else titleText,
        supporting = if (isCountryType) (countryName ?: titleText) else metricText,
        leadingContent = leadingContent,
        leadingIconPainter = if (leadingContent == null) {
            if (isCountryType) {
                painterResource(id = R.drawable.ic_flag_placeholder)
            } else {
                painterResource(id = R.drawable.ic_app_info)
            }
        } else {
            null
        },
        leadingIconTint =
            when {
                hasTrueAppIcon -> Color.Unspecified
                isCountryType -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        leadingIconContainerColor =
            when {
                leadingContent != null -> Color.Transparent
                isCountryType -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.32f)
                else -> MaterialTheme.colorScheme.surfaceContainerHighest
            },
        position = position,
        showTrailingChevron = false,
        onClick = null
    )
}

private fun getTitleResId(type: SummaryStatisticsType): Int {
    return when (type) {
        SummaryStatisticsType.TOP_ACTIVE_CONNS -> R.string.top_active_conns
        SummaryStatisticsType.MOST_CONNECTED_APPS -> R.string.ssv_app_network_activity_heading
        SummaryStatisticsType.MOST_BLOCKED_APPS -> R.string.ssv_app_blocked_heading
        SummaryStatisticsType.MOST_CONNECTED_ASN -> R.string.most_contacted_asn
        SummaryStatisticsType.MOST_BLOCKED_ASN -> R.string.most_blocked_asn
        SummaryStatisticsType.MOST_CONTACTED_DOMAINS -> R.string.ssv_most_contacted_domain_heading
        SummaryStatisticsType.MOST_BLOCKED_DOMAINS -> R.string.ssv_most_blocked_domain_heading
        SummaryStatisticsType.MOST_CONTACTED_IPS -> R.string.ssv_most_contacted_ips_heading
        SummaryStatisticsType.MOST_BLOCKED_IPS -> R.string.ssv_most_blocked_ips_heading
        SummaryStatisticsType.MOST_CONTACTED_COUNTRIES -> R.string.ssv_most_contacted_countries_heading
    }
}

@Composable
private fun getTimeCategoryText(timeCategory: TimeCategory): String {
    val window = when (timeCategory) {
        TimeCategory.ONE_HOUR -> stringResource(id = R.string.time_window_one_hour_short)
        TimeCategory.TWENTY_FOUR_HOUR -> stringResource(id = R.string.time_window_twenty_four_hours_short)
        TimeCategory.SEVEN_DAYS -> stringResource(id = R.string.time_window_seven_days_short)
    }
    return "${stringResource(id = R.string.lbl_last)} $window"
}
