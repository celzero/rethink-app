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
package com.celzero.bravedns.ui.compose.dns

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.ui.compose.theme.CardPosition
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.cardPositionFor
import com.celzero.bravedns.ui.compose.theme.RethinkListGroup
import com.celzero.bravedns.ui.compose.theme.RethinkListItem
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import com.celzero.bravedns.ui.compose.theme.RethinkActionListItem
import com.celzero.bravedns.ui.compose.theme.RethinkRadioListItem
import com.celzero.bravedns.ui.compose.theme.RethinkToggleListItem
import com.celzero.bravedns.ui.compose.theme.SectionHeader
import com.celzero.bravedns.ui.compose.theme.rememberReducedMotion
import kotlinx.coroutines.delay
import java.net.URI
import kotlin.math.roundToInt

private fun dnsFocusSectionIndex(focusKey: String): Int? {
    return when (focusKey) {
        "dns_mode",
        "dns_mode_system",
        "dns_mode_custom",
        "dns_mode_rethink",
        "dns_mode_smart" -> 0
        "dns_blocklist",
        "dns_block_local",
        "dns_block_custom_downloader",
        "dns_block_periodic_updates" -> 1
        "dns_filtering",
        "dns_filter_alg",
        "dns_filter_split",
        "dns_filter_rules_as_firewall",
        "dns_filter_record_types" -> 2
        "dns_advanced",
        "dns_advanced_favicon",
        "dns_advanced_cache",
        "dns_advanced_proxy_dns",
        "dns_advanced_undelegated",
        "dns_advanced_fallback",
        "dns_advanced_leaks" -> 3
        else -> null
    }
}

private fun dnsFocusTarget(
    focusKey: String,
    isShowSplitDns: Boolean,
    isShowBypassDnsBlock: Boolean
): Pair<Int, Int>? {
    val rowHeight = 82
    val groupStart = 62

    fun groupOffset(row: Int): Int = groupStart + (rowHeight * row)

    val modeRow =
        when (focusKey) {
            "dns_mode_system" -> 0
            "dns_mode_custom" -> 1
            "dns_mode_rethink" -> 2
            "dns_mode_smart" -> 3
            else -> null
        }
    if (modeRow != null) return 0 to groupOffset(modeRow)

    val blockRow =
        when (focusKey) {
            "dns_block_local" -> 0
            "dns_block_custom_downloader" -> 1
            "dns_block_periodic_updates" -> 2
            else -> null
        }
    if (blockRow != null) return 1 to groupOffset(blockRow)

    val filteringRow =
        when (focusKey) {
            "dns_filter_alg" -> 0
            "dns_filter_split" -> if (isShowSplitDns) 1 else null
            "dns_filter_rules_as_firewall" ->
                when {
                    isShowSplitDns && isShowBypassDnsBlock -> 2
                    !isShowSplitDns && isShowBypassDnsBlock -> 1
                    else -> null
                }
            "dns_filter_record_types" ->
                when {
                    isShowSplitDns && isShowBypassDnsBlock -> 3
                    isShowSplitDns || isShowBypassDnsBlock -> 2
                    else -> 1
                }
            else -> null
        }
    if (filteringRow != null) return 2 to groupOffset(filteringRow)

    val advancedRow =
        when (focusKey) {
            "dns_advanced_favicon" -> 0
            "dns_advanced_cache" -> 1
            "dns_advanced_proxy_dns" -> 2
            "dns_advanced_undelegated" -> 3
            "dns_advanced_fallback" -> 4
            "dns_advanced_leaks" -> 5
            else -> null
        }
    if (advancedRow != null) return 3 to groupOffset(advancedRow)

    return null
}

private fun connectedDnsDisplayName(raw: String): String {
    val name = raw.substringBefore(",").trim()
    return if (name.isBlank()) "--" else name
}

private fun connectedDnsEndpoint(raw: String): String {
    return raw.substringAfter(",", "").trim()
}

private fun endpointHost(value: String): String {
    if (value.isBlank()) return ""
    return runCatching { URI(value).host.orEmpty() }.getOrDefault("")
}

private fun connectedProtocolLabel(dnsType: AppConfig.DnsType, endpoint: String): String {
    return when (dnsType) {
        AppConfig.DnsType.DOH,
        AppConfig.DnsType.RETHINK_REMOTE,
        AppConfig.DnsType.SMART_DNS -> {
            if (endpoint.startsWith("https://", true)) "HTTPS"
            else if (endpoint.startsWith("http://", true)) "HTTP"
            else "DNS"
        }
        AppConfig.DnsType.DOT -> "DoT"
        AppConfig.DnsType.ODOH -> "ODoH"
        AppConfig.DnsType.DNSCRYPT -> "DNSCrypt"
        AppConfig.DnsType.DNS_PROXY -> "DNS Proxy"
        AppConfig.DnsType.SYSTEM_DNS -> "System DNS"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsSettingsScreen(
    uiState: DnsSettingsUiState,
    initialFocusKey: String? = null,
    onRefreshClick: () -> Unit,
    onSystemDnsClick: () -> Unit,
    onSystemDnsInfoClick: () -> Unit,
    onCustomDnsClick: () -> Unit,
    onRethinkPlusDnsClick: () -> Unit,
    onSmartDnsClick: () -> Unit,
    onSmartDnsInfoClick: () -> Unit,
    onLocalBlocklistClick: () -> Unit,
    onCustomDownloaderChange: (Boolean) -> Unit,
    onPeriodicUpdateChange: (Boolean) -> Unit,
    onDnsAlgChange: (Boolean) -> Unit,
    onSplitDnsChange: (Boolean) -> Unit,
    onBypassDnsBlockChange: (Boolean) -> Unit,
    onAllowedRecordTypesClick: () -> Unit,
    onFavIconChange: (Boolean) -> Unit,
    onDnsCacheChange: (Boolean) -> Unit,
    onProxyDnsChange: (Boolean) -> Unit,
    onUndelegatedDomainsChange: (Boolean) -> Unit,
    onFallbackChange: (Boolean) -> Unit,
    onPreventLeaksChange: (Boolean) -> Unit
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val initialFocus = initialFocusKey?.trim().orEmpty()
    var pendingFocusKey by rememberSaveable(initialFocus) { mutableStateOf(initialFocus) }
    var activeFocusKey by rememberSaveable(initialFocus) {
        mutableStateOf(initialFocus.ifBlank { null })
    }
    val reducedMotion = rememberReducedMotion()
    val refreshRotation by animateFloatAsState(
        targetValue = if (uiState.isRefreshing && !reducedMotion) 360f else 0f,
        animationSpec = if (uiState.isRefreshing && !reducedMotion) {
            infiniteRepeatable(
                animation = tween(durationMillis = 1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        } else {
            tween(durationMillis = 0)
        },
        label = "dnsRefreshRotation"
    )

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(pendingFocusKey, uiState.isShowSplitDns, uiState.isShowBypassDnsBlock) {
        val key = pendingFocusKey.trim()
        if (key.isBlank()) return@LaunchedEffect
        activeFocusKey = key
        val target =
            dnsFocusTarget(
                focusKey = key,
                isShowSplitDns = uiState.isShowSplitDns,
                isShowBypassDnsBlock = uiState.isShowBypassDnsBlock
            )
        if (target != null) {
            val (index, offsetDp) = target
            val offsetPx = with(density) { offsetDp.dp.toPx().roundToInt() }
            listState.animateScrollToItem(index, offsetPx)
            delay(900)
            if (activeFocusKey == key) {
                activeFocusKey = null
            }
            pendingFocusKey = ""
            return@LaunchedEffect
        }

        val index = dnsFocusSectionIndex(key)
        if (index != null) {
            listState.animateScrollToItem(index)
            delay(750)
            if (activeFocusKey == key) {
                activeFocusKey = null
            }
        }
        pendingFocusKey = ""
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RethinkLargeTopBar(
                title = stringResource(id = R.string.lbl_dns),
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = onRefreshClick) {
                        Icon(
                            imageVector = ImageVector.vectorResource(id = R.drawable.ic_refresh_white),
                            contentDescription = stringResource(id = R.string.rules_load_failure_reload),
                            modifier = Modifier.rotate(refreshRotation)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(
                start = Dimensions.screenPaddingHorizontal,
                end = Dimensions.screenPaddingHorizontal,
                top = Dimensions.spacingMd,
                bottom = Dimensions.spacing2xl
            ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingLg)
        ) {
            item {
                SectionHeader(title = stringResource(id = R.string.dc_other_dns_heading))
                RethinkListGroup {
                    val dnsModeItemCount = 4

                    DnsRadioButtonItem(
                        title = stringResource(id = R.string.network_dns),
                        description = stringResource(id = R.string.dns_mode_system_desc),
                        selected = uiState.isSystemDnsEnabled,
                        onClick = onSystemDnsClick,
                        onInfoClick = onSystemDnsInfoClick,
                        iconId = R.drawable.ic_network,
                        highlighted = activeFocusKey == "dns_mode_system",
                        position = cardPositionFor(0, dnsModeItemCount - 1)
                    )
                    DnsRadioButtonItem(
                        title = stringResource(id = R.string.dc_custom_dns_radio),
                        description = stringResource(id = R.string.dns_mode_other_desc),
                        selected = !uiState.isSystemDnsEnabled && !uiState.isRethinkDnsConnected && !uiState.isSmartDnsEnabled,
                        onClick = onCustomDnsClick,
                        iconId = R.drawable.ic_filter,
                        highlighted = activeFocusKey == "dns_mode_custom",
                        position = cardPositionFor(1, dnsModeItemCount - 1)
                    )
                    DnsRadioButtonItem(
                        title = stringResource(id = R.string.dc_rethink_dns_radio),
                        description = stringResource(id = R.string.dns_mode_rethink_desc),
                        selected = uiState.isRethinkDnsConnected,
                        onClick = onRethinkPlusDnsClick,
                        iconId = R.drawable.ic_rethink_plus,
                        highlighted = activeFocusKey == "dns_mode_rethink",
                        position = cardPositionFor(2, dnsModeItemCount - 1)
                    )
                    DnsRadioButtonItem(
                        title = stringResource(id = R.string.smart_dns),
                        description = stringResource(id = R.string.dns_mode_smart_desc),
                        selected = uiState.isSmartDnsEnabled,
                        onClick = onSmartDnsClick,
                        onInfoClick = onSmartDnsInfoClick,
                        iconId = R.drawable.ic_dns_cache,
                        highlighted = activeFocusKey == "dns_mode_smart",
                        position = cardPositionFor(3, dnsModeItemCount - 1)
                    )
                }
            }

            if (uiState.isRethinkDnsConnected) {
                item {
                    RethinkDnsStatusCard(
                        connectedDnsRaw = uiState.connectedDnsName,
                        dnsType = uiState.dnsType,
                        latency = uiState.dnsLatency,
                        highlighted = activeFocusKey == "dns_mode_rethink"
                    )
                }
            }

            item {
                SectionHeader(title = stringResource(id = R.string.dc_block_heading))
                RethinkListGroup {
                    RethinkListItem(
                        headline = stringResource(id = R.string.dc_local_block_heading),
                        supporting = if (uiState.blocklistEnabled) {
                            stringResource(
                                id = R.string.settings_local_blocklist_in_use,
                                uiState.numberOfLocalBlocklists
                            )
                        } else {
                            stringResource(id = R.string.dc_local_block_desc_1)
                        },
                        leadingIconPainter = painterResource(id = R.drawable.ic_local_blocklist),
                        position = CardPosition.First,
                        highlighted = activeFocusKey == "dns_block_local",
                        onClick = onLocalBlocklistClick,
                        trailing = {
                            Text(
                                text = if (uiState.blocklistEnabled) {
                                    stringResource(id = R.string.dc_local_block_enabled)
                                } else {
                                    stringResource(id = R.string.lbl_disabled)
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (uiState.blocklistEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    )
                    ToggleListItem(
                        title = stringResource(id = R.string.settings_custom_downloader_heading),
                        description = stringResource(id = R.string.settings_custom_downloader_desc),
                        iconId = R.drawable.ic_update,
                        checked = uiState.useCustomDownloadManager,
                        position = CardPosition.Middle,
                        highlighted = activeFocusKey == "dns_block_custom_downloader",
                        onCheckedChange = onCustomDownloaderChange
                    )
                    ToggleListItem(
                        title = stringResource(id = R.string.dc_check_update_heading),
                        description = stringResource(id = R.string.dc_check_update_desc_compact),
                        iconId = R.drawable.ic_blocklist_update_check,
                        checked = uiState.periodicallyCheckBlocklistUpdate,
                        position = CardPosition.Last,
                        highlighted = activeFocusKey == "dns_block_periodic_updates",
                        onCheckedChange = onPeriodicUpdateChange
                    )
                }
            }

            item {
                SectionHeader(title = stringResource(id = R.string.dc_filtering_heading))
                RethinkListGroup {
                    ToggleListItem(
                        title = stringResource(id = R.string.cd_dns_alg_heading),
                        description = stringResource(id = R.string.cd_dns_alg_desc),
                        iconId = R.drawable.ic_adv_dns_filter,
                        checked = uiState.enableDnsAlg,
                        position = CardPosition.First,
                        highlighted = activeFocusKey == "dns_filter_alg",
                        onCheckedChange = onDnsAlgChange
                    )
                    if (uiState.isShowSplitDns) {
                        ToggleListItem(
                            title = stringResource(id = R.string.cd_split_dns_heading),
                            description = stringResource(id = R.string.cd_split_dns_desc),
                            iconId = R.drawable.ic_split_dns,
                            checked = uiState.splitDns,
                            position = CardPosition.Middle,
                            highlighted = activeFocusKey == "dns_filter_split",
                            onCheckedChange = onSplitDnsChange
                        )
                    }
                    if (uiState.isShowBypassDnsBlock) {
                        ToggleListItem(
                            title = stringResource(id = R.string.cd_treat_dns_rules_firewall_heading),
                            description = stringResource(id = R.string.cd_treat_dns_rules_firewall_desc),
                            iconId = R.drawable.ic_dns_rules_as_firewall,
                            checked = uiState.bypassBlockInDns,
                            position = CardPosition.Middle,
                            highlighted = activeFocusKey == "dns_filter_rules_as_firewall",
                            onCheckedChange = onBypassDnsBlockChange
                        )
                    }
                    RethinkActionListItem(
                        title = stringResource(id = R.string.cd_allowed_dns_record_types_heading),
                        description = stringResource(id = R.string.cd_allowed_dns_record_types_desc),
                        iconPainter = painterResource(id = R.drawable.ic_allow_dns_records),
                        position = CardPosition.Last,
                        highlighted = activeFocusKey == "dns_filter_record_types",
                        onClick = onAllowedRecordTypesClick,
                        trailing = {
                            Text(
                                text = if (uiState.dnsRecordTypesAutoMode) {
                                    stringResource(id = R.string.dns_record_types_auto_mode_status)
                                } else {
                                    uiState.allowedDnsRecordTypesSize.toString()
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    )
                }
            }

            item {
                SectionHeader(title = stringResource(id = R.string.lbl_advanced))
                RethinkListGroup {
                    ToggleListItem(
                        title = stringResource(id = R.string.dc_dns_website_heading),
                        description = stringResource(id = R.string.dc_dns_website_desc),
                        iconId = R.drawable.ic_fav_icon,
                        checked = uiState.fetchFavIcon,
                        position = CardPosition.First,
                        highlighted = activeFocusKey == "dns_advanced_favicon",
                        onCheckedChange = onFavIconChange
                    )
                    ToggleListItem(
                        title = stringResource(id = R.string.dc_setting_dns_cache_heading),
                        description = stringResource(id = R.string.dc_setting_dns_cache_desc),
                        iconId = R.drawable.ic_auto_start,
                        checked = uiState.enableDnsCache,
                        position = CardPosition.Middle,
                        highlighted = activeFocusKey == "dns_advanced_cache",
                        onCheckedChange = onDnsCacheChange
                    )
                    ToggleListItem(
                        title = stringResource(id = R.string.dc_proxy_dns_heading),
                        description = stringResource(id = R.string.dc_proxy_dns_desc),
                        iconId = R.drawable.ic_proxy,
                        checked = !uiState.proxyDns,
                        position = CardPosition.Middle,
                        highlighted = activeFocusKey == "dns_advanced_proxy_dns",
                        onCheckedChange = { onProxyDnsChange(!it) }
                    )
                    ToggleListItem(
                        title = stringResource(id = R.string.dc_use_sys_dns_undelegated_heading),
                        description = stringResource(id = R.string.dc_use_sys_dns_undelegated_desc),
                        iconId = R.drawable.ic_split_dns,
                        checked = uiState.useSystemDnsForUndelegatedDomains,
                        position = CardPosition.Middle,
                        highlighted = activeFocusKey == "dns_advanced_undelegated",
                        onCheckedChange = onUndelegatedDomainsChange
                    )
                    ToggleListItem(
                        title = stringResource(id = R.string.use_fallback_dns_to_bypass),
                        description = stringResource(id = R.string.use_fallback_dns_to_bypass_desc),
                        iconId = R.drawable.ic_use_fallback_bypass,
                        checked = uiState.useFallbackDnsToBypass,
                        position = CardPosition.Middle,
                        highlighted = activeFocusKey == "dns_advanced_fallback",
                        onCheckedChange = onFallbackChange
                    )
                    ToggleListItem(
                        title = stringResource(id = R.string.dc_dns_leaks_heading),
                        description = stringResource(id = R.string.dc_dns_leaks_desc),
                        iconId = R.drawable.ic_prevent_dns_leaks,
                        checked = uiState.preventDnsLeaks,
                        position = CardPosition.Last,
                        highlighted = activeFocusKey == "dns_advanced_leaks",
                        onCheckedChange = onPreventLeaksChange
                    )
                }
            }
        }
    }
}

@Composable
private fun RethinkDnsStatusCard(
    connectedDnsRaw: String,
    dnsType: AppConfig.DnsType,
    latency: String,
    highlighted: Boolean
) {
    val endpoint = connectedDnsEndpoint(connectedDnsRaw)
    val host = endpointHost(endpoint)
    val protocol = connectedProtocolLabel(dnsType, endpoint)
    val latencyText = latency.removePrefix("(").removeSuffix(")")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimensions.spacingSm, vertical = Dimensions.spacingXs),
        verticalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
    ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
            ) {
                Surface(
                    shape = RoundedCornerShape(Dimensions.iconContainerRadius),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_rethink_plus),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(8.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.dc_rethink_dns_radio),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (highlighted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Text(
                        text = connectedDnsDisplayName(connectedDnsRaw),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (latencyText.isNotBlank()) {
                    DnsStatusPill(
                        text = latencyText,
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            Text(
                text = stringResource(id = R.string.rethink_sky_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DnsStatusPill(
                    text = protocol,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
                if (host.isNotBlank()) {
                    DnsStatusPill(
                        text = host,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
    }
}

@Composable
private fun DnsStatusPill(
    text: String,
    containerColor: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = containerColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun DnsRadioButtonItem(
    title: String,
    description: String? = null,
    selected: Boolean,
    onClick: () -> Unit,
    iconId: Int,
    highlighted: Boolean = false,
    onInfoClick: (() -> Unit)? = null,
    position: CardPosition = CardPosition.Middle
) {
    RethinkRadioListItem(
        title = title,
        description = description,
        selected = selected,
        onSelect = onClick,
        iconRes = iconId,
        position = position,
        highlighted = highlighted,
        onInfoClick = onInfoClick
    )
}

@Composable
fun ToggleListItem(
    title: String,
    description: String,
    iconId: Int,
    checked: Boolean,
    highlighted: Boolean = false,
    position: CardPosition = CardPosition.Middle,
    onCheckedChange: (Boolean) -> Unit
) {
    RethinkToggleListItem(
        title = title,
        description = description,
        checked = checked,
        onCheckedChange = onCheckedChange,
        iconRes = iconId,
        position = position,
        highlighted = highlighted
    )
}
