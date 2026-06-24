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
package com.celzero.bravedns.ui.compose.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.compose.theme.CardPosition
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkActionListItem
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.RethinkListGroup
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import com.celzero.bravedns.ui.compose.theme.RethinkToggleListItem
import com.celzero.bravedns.ui.compose.theme.SectionHeader
import com.celzero.bravedns.ui.dialog.CustomLanIpSheet
import com.celzero.bravedns.ui.dialog.NetworkReachabilitySheet
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.InternetProtocol
import com.celzero.bravedns.util.NewSettingsManager
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600
private const val POLICY_AUTO = 0
private const val POLICY_SENSITIVE = 1
private const val POLICY_RELAXED = 2
private const val POLICY_FIXED = 3
private const val IP_DIALOG_POS_IPV4 = 0
private const val IP_DIALOG_POS_IPV6 = 1
private const val IP_DIALOG_POS_ALWAYS_V46 = 2
private const val IP_DIALOG_POS_V46 = 3

private data class NetworkPolicyOption(val title: String, val description: String)

private fun tunnelFocusTarget(
    focusKey: String,
    isLockdown: Boolean,
    showConnectivityChecksOption: Boolean,
    showPingIps: Boolean,
    showAllowIncoming: Boolean,
    showVpnMetered: Boolean
): Pair<Int, Int>? {
    val networkIndex = if (isLockdown) 2 else 1
    val advancedIndex = if (isLockdown) 3 else 2
    val timeoutIndex = if (isLockdown) 4 else 3
    val rowHeight = 82
    val groupStart = 62

    fun groupOffset(row: Int): Int = groupStart + (rowHeight * row)

    val networkRow =
        when (focusKey) {
            "network_allow_bypass" -> 0
            "network_fail_open" -> 1
            "network_allow_lan" -> 2
            "network_all_networks" -> 3
            "network_exclude_apps_proxy" -> 4
            "network_protocol_translation" -> 5
            else -> null
        }

    val advancedRows = mutableMapOf<String, Int>()
    var row = 0
    fun addAdvancedRow(key: String, visible: Boolean = true) {
        if (!visible) return
        advancedRows[key] = row
        row++
    }

    addAdvancedRow("network_default_dns")
    addAdvancedRow("network_vpn_policy")
    addAdvancedRow("network_ip_protocol")
    addAdvancedRow("network_connectivity_checks", showConnectivityChecksOption)
    addAdvancedRow("network_ping_ips", showPingIps)
    addAdvancedRow("network_mobile_metered")
    addAdvancedRow("network_wg_listen_port")
    addAdvancedRow("network_wg_lockdown")
    addAdvancedRow("network_endpoint_independence")
    addAdvancedRow("network_allow_incoming_wg", showAllowIncoming)
    addAdvancedRow("network_tcp_keep_alive")
    addAdvancedRow("network_jumbo_packets")
    addAdvancedRow("network_vpn_metered", showVpnMetered)
    addAdvancedRow("network_custom_lan_ip")
    val advancedRow = advancedRows[focusKey]

    return when (focusKey) {
        "network_core" -> networkIndex to 0
        "network_advanced" -> advancedIndex to 0
        "network_dial_timeout" -> timeoutIndex to 0
        else -> {
            when {
                networkRow != null -> networkIndex to groupOffset(networkRow)
                advancedRow != null -> advancedIndex to groupOffset(advancedRow)
                else -> null
            }
        }
    }
}

private fun tunnelFocusIndex(focusKey: String, isLockdown: Boolean): Int? {
    val networkIndex = if (isLockdown) 2 else 1
    val advancedIndex = if (isLockdown) 3 else 2
    val timeoutIndex = if (isLockdown) 4 else 3
    return when (focusKey) {
        "network_core",
        "network_allow_bypass",
        "network_fail_open",
        "network_allow_lan",
        "network_all_networks",
        "network_exclude_apps_proxy",
        "network_protocol_translation" -> networkIndex
        "network_advanced",
        "network_default_dns",
        "network_vpn_policy",
        "network_ip_protocol",
        "network_connectivity_checks",
        "network_ping_ips",
        "network_mobile_metered",
        "network_wg_listen_port",
        "network_wg_lockdown",
        "network_endpoint_independence",
        "network_allow_incoming_wg",
        "network_tcp_keep_alive",
        "network_jumbo_packets",
        "network_vpn_metered",
        "network_custom_lan_ip" -> advancedIndex
        "network_dial_timeout" -> timeoutIndex
        else -> null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelSettingsScreen(
    persistentState: PersistentState,
    appConfig: AppConfig,
    eventLogger: EventLogger,
    onOpenVpnProfile: () -> Unit,
    initialFocusKey: String? = null,
    onBackClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val disabledText = stringResource(R.string.lbl_disabled)
    val protocolTranslationInactiveText = stringResource(R.string.settings_protocol_translation_dns_inactive)
    val socks5VpnDisabledErrorText = stringResource(R.string.settings_socks5_vpn_disabled_error)

    var isLockdown by remember { mutableStateOf(VpnController.isVpnLockdown()) }
    var allowBypass by remember { mutableStateOf(persistentState.allowBypass) }
    var allowBypassLoading by remember { mutableStateOf(false) }
    var useMultipleNetworks by remember { mutableStateOf(persistentState.useMultipleNetworks) }
    var routeLan by remember { mutableStateOf(persistentState.privateIps) }
    var excludeApps by remember { mutableStateOf(!persistentState.excludeAppsInProxy) }
    var stallNoNetwork by remember { mutableStateOf(persistentState.stallOnNoNetwork) }
    var protocolTranslation by remember { mutableStateOf(persistentState.protocolTranslationType) }
    var meteredOnlyMobile by remember { mutableStateOf(persistentState.treatOnlyMobileNetworkAsMetered) }
    var listenPortFixed by remember { mutableStateOf(!persistentState.randomizeListenPort) }
    var wgLockdown by remember { mutableStateOf(persistentState.wgGlobalLockdown) }
    var endpointIndependence by remember { mutableStateOf(persistentState.endpointIndependence) }
    var allowIncoming by remember { mutableStateOf(persistentState.nwEngExperimentalFeatures) }
    var tcpKeepAlive by remember { mutableStateOf(persistentState.tcpKeepAlive) }
    var useMaxMtu by remember { mutableStateOf(persistentState.useMaxMtu) }
    var tunnelMetered by remember { mutableStateOf(persistentState.setVpnBuilderToMetered) }
    var dialTimeoutMin by remember { mutableIntStateOf(persistentState.dialTimeoutSec / SECONDS_PER_MINUTE) }
    var internetProtocol by remember { mutableIntStateOf(persistentState.internetProtocolType) }
    var vpnPolicy by remember { mutableIntStateOf(persistentState.vpnBuilderPolicy) }
    var connectivityChecks by remember { mutableStateOf(persistentState.connectivityChecks) }
    var showCustomLanIpSheet by remember { mutableStateOf(false) }
    var showReachabilitySheet by remember { mutableStateOf(false) }
    var showDefaultDnsDialog by remember { mutableStateOf(false) }
    var showVpnPolicyDialog by remember { mutableStateOf(false) }
    var showIpDialog by remember { mutableStateOf(false) }
    var showConnectivityChecksDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val initialFocus = initialFocusKey?.trim().orEmpty()
    var pendingFocusKey by rememberSaveable(initialFocus) { mutableStateOf(initialFocus) }
    var activeFocusKey by rememberSaveable(initialFocus) {
        mutableStateOf(initialFocus.ifBlank { null })
    }

    val canModify = !isLockdown
    val showPtrans = internetProtocol == InternetProtocol.IPv6.id
    val showConnectivityChecksOption = internetProtocol == InternetProtocol.IPv46.id
    val showPingIps = showConnectivityChecksOption && connectivityChecks

    fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.TUN_ESTABLISHED, Severity.LOW, msg, EventSource.UI, false, details)
    }

    fun formatTimeShort(totalSeconds: Int, disabledText: String): String {
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        val parts = mutableListOf<String>()
        if (hours > 0) parts.add("${hours}h")
        if (minutes > 0) parts.add("${minutes}m")
        if (seconds > 0) parts.add("${seconds}s")
        return if (parts.isEmpty()) disabledText else parts.joinToString(" ")
    }

    val ipDesc = when (internetProtocol) {
        InternetProtocol.IPv4.id -> stringResource(R.string.settings_ip_text_ipv4)
        InternetProtocol.IPv6.id -> stringResource(R.string.settings_ip_text_ipv6)
        InternetProtocol.IPv46.id -> stringResource(R.string.settings_ip_text_ipv46)
        InternetProtocol.ALWAYSv46.id -> stringResource(R.string.settings_ip_text_ipv4) + " & " + stringResource(R.string.settings_ip_text_ipv6)
        else -> stringResource(R.string.settings_ip_text_ipv4)
    }

    val vpnPolicyDesc = when (vpnPolicy) {
        POLICY_AUTO -> stringResource(R.string.settings_ip_text_ipv46)
        POLICY_SENSITIVE -> stringResource(R.string.vpn_policy_sensitive)
        POLICY_RELAXED -> stringResource(R.string.vpn_policy_relaxed)
        POLICY_FIXED -> stringResource(R.string.vpn_policy_fixed)
        else -> stringResource(R.string.settings_ip_text_ipv46)
    }

    val dialTimeoutDesc = formatTimeShort(dialTimeoutMin * SECONDS_PER_MINUTE, disabledText)
    val topBarSubtitle =
        stringResource(
            R.string.two_argument_colon,
            stringResource(R.string.vpn_policy_title),
            vpnPolicyDesc
        )

    // Default DNS Dialog
    if (showDefaultDnsDialog) {
        DefaultDnsDialog(
            persistentState = persistentState,
            onDismiss = { showDefaultDnsDialog = false },
            onConfirm = { logEvent("default dns changed", "Default DNS changed") }
        )
    }

    // VPN Policy Dialog
    if (showVpnPolicyDialog) {
        VpnPolicyDialog(
            persistentState = persistentState,
            onDismiss = { showVpnPolicyDialog = false },
            onConfirm = { selectedIndex ->
                if (selectedIndex == POLICY_FIXED) {
                    persistentState.enableStabilityDependentSettings(context)
                    persistentState.useMaxMtu = true
                    useMaxMtu = true
                    persistentState.internetProtocolType = InternetProtocol.ALWAYSv46.id
                    internetProtocol = InternetProtocol.ALWAYSv46.id
                }
                persistentState.vpnBuilderPolicy = selectedIndex
                vpnPolicy = selectedIndex
                logEvent("vpn policy changed", "VPN builder network policy changed to: $selectedIndex")
            }
        )
    }

    // IP Dialog
    if (showIpDialog) {
        IpProtocolDialog(
            persistentState = persistentState,
            context = context,
            onDismiss = { showIpDialog = false },
            onConfirm = { selectedProtocol ->
                internetProtocol = selectedProtocol
                logEvent("ip protocol changed", "Internet protocol changed to: $selectedProtocol")
            }
        )
    }

    // Connectivity Checks Dialog
    if (showConnectivityChecksDialog) {
        ConnectivityChecksDialog(
            persistentState = persistentState,
            onDismiss = { showConnectivityChecksDialog = false },
            onConfirm = { enabled ->
                connectivityChecks = enabled
                logEvent("connectivity checks", "Connectivity checks changed")
            }
        )
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(
        pendingFocusKey,
        isLockdown,
        showConnectivityChecksOption,
        showPingIps,
        endpointIndependence
    ) {
        val key = pendingFocusKey.trim()
        if (key.isBlank()) return@LaunchedEffect
        activeFocusKey = key
        val target =
            tunnelFocusTarget(
                focusKey = key,
                isLockdown = isLockdown,
                showConnectivityChecksOption = showConnectivityChecksOption,
                showPingIps = showPingIps,
                showAllowIncoming = endpointIndependence,
                showVpnMetered = isAtleastQ()
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

        val index = tunnelFocusIndex(key, isLockdown)
        if (index != null) {
            listState.animateScrollToItem(index, 0)
            delay(750)
            if (activeFocusKey == key) {
                activeFocusKey = null
            }
        }
        pendingFocusKey = ""
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            RethinkLargeTopBar(
                title = stringResource(R.string.lbl_network),
                subtitle = topBarSubtitle,
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding =
                PaddingValues(
                    start = Dimensions.screenPaddingHorizontal,
                    end = Dimensions.screenPaddingHorizontal,
                    top = Dimensions.spacingSm,
                    bottom = Dimensions.spacing3xl
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingLg)
        ) {
            if (isLockdown) {
                item {
                    RethinkListGroup {
                        RethinkActionListItem(
                            title = stringResource(R.string.settings_lock_down_mode_desc),
                            iconPainter = painterResource(id = R.drawable.ic_firewall_lockdown_on),
                            position = CardPosition.Single,
                            onClick = { onOpenVpnProfile() }
                        )
                    }
                }
            }

            item {
                RethinkListGroup {
                    RethinkToggleListItem(
                        title = stringResource(R.string.settings_allow_bypass_heading),
                        description = stringResource(R.string.settings_allow_bypass_desc),
                        icon = Icons.Filled.Settings,
                        position = CardPosition.First,
                        highlighted = activeFocusKey == "network_allow_bypass",
                        checked = allowBypass,
                        enabled = canModify && !Utilities.isPlayStoreFlavour(),
                        onRowClick = {
                            if (Utilities.isPlayStoreFlavour()) return@RethinkToggleListItem
                            val checked = !allowBypass
                            allowBypass = checked
                            persistentState.allowBypass = checked
                            allowBypassLoading = true
                            scope.launch {
                                delay(1000L)
                                allowBypassLoading = false
                            }
                            logEvent("allow bypass", "Allow bypass VPN: $checked")
                        },
                        onCheckedChange = { checked ->
                            if (Utilities.isPlayStoreFlavour()) return@RethinkToggleListItem
                            allowBypass = checked
                            persistentState.allowBypass = checked
                            allowBypassLoading = true
                            scope.launch {
                                delay(1000L)
                                allowBypassLoading = false
                            }
                            logEvent("allow bypass", "Allow bypass VPN: $checked")
                        }
                    )

                    RethinkToggleListItem(
                        title = stringResource(R.string.fail_open_network_title),
                        description = stringResource(R.string.fail_open_network_desc),
                        icon = Icons.Filled.Tune,
                        position = CardPosition.Middle,
                        highlighted = activeFocusKey == "network_fail_open",
                        checked = stallNoNetwork,
                        onRowClick = {
                            val checked = !stallNoNetwork
                            stallNoNetwork = checked
                            persistentState.stallOnNoNetwork = checked
                            logEvent("stall on no network", "Stall on no network: $checked")
                        },
                        onCheckedChange = { checked ->
                            stallNoNetwork = checked
                            persistentState.stallOnNoNetwork = checked
                            logEvent("stall on no network", "Stall on no network: $checked")
                        }
                    )

                    RethinkToggleListItem(
                        title = stringResource(R.string.settings_allow_lan_heading),
                        description = stringResource(R.string.settings_allow_lan_desc),
                        icon = Icons.Filled.Tune,
                        position = CardPosition.Middle,
                        highlighted = activeFocusKey == "network_allow_lan",
                        checked = routeLan,
                        enabled = canModify,
                        onRowClick = {
                            if (canModify) {
                                val checked = !routeLan
                                routeLan = checked
                                persistentState.privateIps = checked
                                if (checked) persistentState.enableStabilityDependentSettings(context)
                                logEvent("route lan traffic", "Route LAN traffic: $checked")
                            }
                        },
                        onCheckedChange = { checked ->
                            routeLan = checked
                            persistentState.privateIps = checked
                            if (checked) persistentState.enableStabilityDependentSettings(context)
                            logEvent("route lan traffic", "Route LAN traffic: $checked")
                        }
                    )

                    RethinkToggleListItem(
                        title = stringResource(R.string.settings_network_all_networks),
                        description = stringResource(R.string.settings_network_all_networks_desc),
                        icon = Icons.Filled.Tune,
                        position = CardPosition.Middle,
                        highlighted = activeFocusKey == "network_all_networks",
                        checked = useMultipleNetworks,
                        enabled = canModify,
                        onRowClick = {
                            if (canModify) {
                                val checked = !useMultipleNetworks
                                useMultipleNetworks = checked
                                persistentState.useMultipleNetworks = checked
                                if (checked) persistentState.enableStabilityDependentSettings(context)
                                if (!checked && persistentState.routeRethinkInRethink) {
                                    persistentState.routeRethinkInRethink = false
                                }
                                logEvent("use all networks", "Use all networks for VPN: $checked")
                            }
                        },
                        onCheckedChange = { checked ->
                            useMultipleNetworks = checked
                            persistentState.useMultipleNetworks = checked
                            if (checked) persistentState.enableStabilityDependentSettings(context)
                            if (!checked && persistentState.routeRethinkInRethink) {
                                persistentState.routeRethinkInRethink = false
                            }
                            logEvent("use all networks", "Use all networks for VPN: $checked")
                        }
                    )

                    RethinkToggleListItem(
                        title = stringResource(R.string.settings_exclude_apps_in_proxy),
                        description = stringResource(R.string.settings_exclude_apps_in_proxy_desc),
                        icon = Icons.Filled.Tune,
                        position = CardPosition.Middle,
                        highlighted = activeFocusKey == "network_exclude_apps_proxy",
                        checked = excludeApps,
                        enabled = canModify,
                        onRowClick = {
                            if (canModify) {
                                val checked = !excludeApps
                                excludeApps = checked
                                persistentState.excludeAppsInProxy = !checked
                                logEvent("exclude apps in proxy", "Exclude apps in proxy: ${!checked}")
                            }
                        },
                        onCheckedChange = { checked ->
                            excludeApps = checked
                            persistentState.excludeAppsInProxy = !checked
                            logEvent("exclude apps in proxy", "Exclude apps in proxy: ${!checked}")
                        }
                    )

                    RethinkToggleListItem(
                        title = stringResource(R.string.settings_protocol_translation),
                        description = stringResource(R.string.settings_protocol_translation_desc),
                        icon = Icons.Filled.Tune,
                        position = CardPosition.Last,
                        highlighted = activeFocusKey == "network_protocol_translation",
                        checked = protocolTranslation,
                        enabled = showPtrans,
                        onRowClick = {
                            if (showPtrans) {
                                val checked = !protocolTranslation
                                if (appConfig.getBraveMode().isDnsActive()) {
                                    protocolTranslation = checked
                                    persistentState.protocolTranslationType = checked
                                } else {
                                    protocolTranslation = false
                                    showToastUiCentered(
                                        context,
                                        protocolTranslationInactiveText,
                                        Toast.LENGTH_SHORT
                                    )
                                }
                                logEvent("protocol translation", "Protocol translation: $checked")
                            }
                        },
                        onCheckedChange = { checked ->
                            if (appConfig.getBraveMode().isDnsActive()) {
                                protocolTranslation = checked
                                persistentState.protocolTranslationType = checked
                            } else {
                                protocolTranslation = false
                                showToastUiCentered(
                                    context,
                                    protocolTranslationInactiveText,
                                    Toast.LENGTH_SHORT
                                )
                            }
                            logEvent("protocol translation", "Protocol translation: $checked")
                        }
                    )
                }
            }

            item {
                SectionHeader(title = stringResource(R.string.lbl_advanced))
                RethinkListGroup {
                    RethinkActionListItem(
                        title = stringResource(R.string.settings_default_dns_heading),
                        description = stringResource(R.string.settings_default_dns_desc),
                        icon = Icons.Filled.Settings,
                        position = CardPosition.First,
                        highlighted = activeFocusKey == "network_default_dns",
                        onClick = { showDefaultDnsDialog = true }
                    )

                    RethinkActionListItem(
                        title = stringResource(R.string.vpn_policy_title),
                        description = vpnPolicyDesc,
                        icon = Icons.Filled.Settings,
                        position = CardPosition.Middle,
                        highlighted = activeFocusKey == "network_vpn_policy",
                        onClick = { showVpnPolicyDialog = true }
                    )

                    RethinkActionListItem(
                        title = stringResource(R.string.settings_ip_dialog_title),
                        description = stringResource(R.string.settings_selected_ip_desc, ipDesc),
                        icon = Icons.Filled.Settings,
                        position = CardPosition.Middle,
                        highlighted = activeFocusKey == "network_ip_protocol",
                        onClick = { if (vpnPolicy != POLICY_FIXED) showIpDialog = true }
                    )

                    if (showConnectivityChecksOption) {
                        RethinkActionListItem(
                            title = stringResource(R.string.settings_connectivity_checks),
                            description = stringResource(R.string.settings_connectivity_checks_desc),
                            icon = Icons.Filled.Settings,
                            position = CardPosition.Middle,
                            highlighted = activeFocusKey == "network_connectivity_checks",
                            onClick = { showConnectivityChecksDialog = true }
                        )
                    }

                    if (showPingIps) {
                        RethinkActionListItem(
                            title = stringResource(R.string.settings_ping_ips),
                            icon = Icons.Filled.NetworkCheck,
                            position = CardPosition.Middle,
                            highlighted = activeFocusKey == "network_ping_ips",
                            onClick = {
                                if (!VpnController.hasTunnel()) {
                                    showToastUiCentered(
                                        context,
                                        socks5VpnDisabledErrorText,
                                        Toast.LENGTH_SHORT
                                    )
                                } else {
                                    showReachabilitySheet = true
                                }
                            }
                        )
                    }

                    RethinkToggleListItem(
                        title = stringResource(R.string.settings_treat_mobile_metered),
                        description = stringResource(R.string.settings_treat_mobile_metered_desc),
                        icon = Icons.Filled.Tune,
                        position = CardPosition.Middle,
                        highlighted = activeFocusKey == "network_mobile_metered",
                        checked = meteredOnlyMobile,
                        onRowClick = {
                            val checked = !meteredOnlyMobile
                            meteredOnlyMobile = checked
                            persistentState.treatOnlyMobileNetworkAsMetered = checked
                            logEvent("mobile metered", "Treat mobile as metered: $checked")
                        },
                        onCheckedChange = { checked ->
                            meteredOnlyMobile = checked
                            persistentState.treatOnlyMobileNetworkAsMetered = checked
                            logEvent("mobile metered", "Treat mobile as metered: $checked")
                        }
                    )

                    RethinkToggleListItem(
                        title = stringResource(R.string.settings_wg_listen_port),
                        description = stringResource(R.string.settings_wg_listen_port_desc),
                        icon = Icons.Filled.Tune,
                        position = CardPosition.Middle,
                        highlighted = activeFocusKey == "network_wg_listen_port",
                        checked = listenPortFixed,
                        onRowClick = {
                            val checked = !listenPortFixed
                            listenPortFixed = checked
                            persistentState.randomizeListenPort = !checked
                            logEvent("listen port", "Randomize listen port: ${!checked}")
                        },
                        onCheckedChange = { checked ->
                            listenPortFixed = checked
                            persistentState.randomizeListenPort = !checked
                            logEvent("listen port", "Randomize listen port: ${!checked}")
                        }
                    )

                    RethinkToggleListItem(
                        title = stringResource(R.string.settings_wg_lockdown),
                        description = stringResource(R.string.settings_wg_lockdown_desc),
                        icon = Icons.Filled.Tune,
                        position = CardPosition.Middle,
                        highlighted = activeFocusKey == "network_wg_lockdown",
                        checked = wgLockdown,
                        onRowClick = {
                            val checked = !wgLockdown
                            wgLockdown = checked
                            persistentState.wgGlobalLockdown = checked
                            NewSettingsManager.markSettingSeen(NewSettingsManager.WG_GLOBAL_LOCKDOWN_MODE_SETTING)
                            logEvent("wg lockdown", "WG global lockdown: $checked")
                        },
                        onCheckedChange = { checked ->
                            wgLockdown = checked
                            persistentState.wgGlobalLockdown = checked
                            NewSettingsManager.markSettingSeen(NewSettingsManager.WG_GLOBAL_LOCKDOWN_MODE_SETTING)
                            logEvent("wg lockdown", "WG global lockdown: $checked")
                        }
                    )

                    RethinkToggleListItem(
                        title = stringResource(R.string.settings_endpoint_independence),
                        description = stringResource(R.string.settings_endpoint_independence_desc),
                        icon = Icons.Filled.Tune,
                        position = CardPosition.Middle,
                        highlighted = activeFocusKey == "network_endpoint_independence",
                        checked = endpointIndependence,
                        onRowClick = {
                            val checked = !endpointIndependence
                            endpointIndependence = checked
                            persistentState.endpointIndependence = checked
                            if (!checked) {
                                allowIncoming = false
                                persistentState.nwEngExperimentalFeatures = false
                            } else {
                                allowIncoming = persistentState.nwEngExperimentalFeatures
                            }
                            logEvent("endpoint independence", "Endpoint independence: $checked")
                        },
                        onCheckedChange = { checked ->
                            endpointIndependence = checked
                            persistentState.endpointIndependence = checked
                            if (!checked) {
                                allowIncoming = false
                                persistentState.nwEngExperimentalFeatures = false
                            } else {
                                allowIncoming = persistentState.nwEngExperimentalFeatures
                            }
                            logEvent("endpoint independence", "Endpoint independence: $checked")
                        }
                    )

                    if (endpointIndependence) {
                        RethinkToggleListItem(
                            title = stringResource(R.string.settings_allow_incoming_wg_packets),
                            description = stringResource(R.string.settings_allow_incoming_wg_packets_desc),
                            icon = Icons.Filled.Tune,
                            position = CardPosition.Middle,
                            highlighted = activeFocusKey == "network_allow_incoming_wg",
                            checked = allowIncoming,
                            onRowClick = {
                                val checked = !allowIncoming
                                allowIncoming = checked
                                persistentState.nwEngExperimentalFeatures = checked
                                logEvent("allow incoming", "Allow incoming WG packets: $checked")
                            },
                            onCheckedChange = { checked ->
                                allowIncoming = checked
                                persistentState.nwEngExperimentalFeatures = checked
                                logEvent("allow incoming", "Allow incoming WG packets: $checked")
                            }
                        )
                    }

                    RethinkToggleListItem(
                        title = stringResource(R.string.settings_tcp_keep_alive),
                        description = stringResource(R.string.settings_tcp_keep_alive_desc),
                        icon = Icons.Filled.Tune,
                        position = CardPosition.Middle,
                        highlighted = activeFocusKey == "network_tcp_keep_alive",
                        checked = tcpKeepAlive,
                        onRowClick = {
                            val checked = !tcpKeepAlive
                            tcpKeepAlive = checked
                            persistentState.tcpKeepAlive = checked
                            logEvent("tcp keep alive", "TCP keep alive: $checked")
                        },
                        onCheckedChange = { checked ->
                            tcpKeepAlive = checked
                            persistentState.tcpKeepAlive = checked
                            logEvent("tcp keep alive", "TCP keep alive: $checked")
                        }
                    )

                    RethinkToggleListItem(
                        title = stringResource(R.string.settings_jumbo_packets),
                        description = stringResource(R.string.settings_jumbo_packets_desc),
                        icon = Icons.Filled.Tune,
                        position = CardPosition.Middle,
                        highlighted = activeFocusKey == "network_jumbo_packets",
                        checked = useMaxMtu,
                        enabled = vpnPolicy != POLICY_FIXED && !persistentState.routeRethinkInRethink,
                        onRowClick = {
                            if (vpnPolicy != POLICY_FIXED && !persistentState.routeRethinkInRethink) {
                                val checked = !useMaxMtu
                                useMaxMtu = checked
                                persistentState.useMaxMtu = checked
                                logEvent("jumbo packets", "Use jumbo packets: $checked")
                            }
                        },
                        onCheckedChange = { checked ->
                            useMaxMtu = checked
                            persistentState.useMaxMtu = checked
                            logEvent("jumbo packets", "Use jumbo packets: $checked")
                        }
                    )

                    if (isAtleastQ()) {
                        RethinkToggleListItem(
                            title = stringResource(R.string.settings_vpn_builder_metered),
                            description = stringResource(R.string.settings_vpn_builder_metered_desc),
                            icon = Icons.Filled.Tune,
                            position = CardPosition.Middle,
                            highlighted = activeFocusKey == "network_vpn_metered",
                            checked = tunnelMetered,
                            onRowClick = {
                                val checked = !tunnelMetered
                                tunnelMetered = checked
                                persistentState.setVpnBuilderToMetered = checked
                                logEvent("vpn metered", "VPN builder metered: $checked")
                            },
                            onCheckedChange = { checked ->
                                tunnelMetered = checked
                                persistentState.setVpnBuilderToMetered = checked
                                logEvent("vpn metered", "VPN builder metered: $checked")
                            }
                        )
                    }

                    RethinkActionListItem(
                        title = stringResource(R.string.custom_lan_ip_title),
                        description = stringResource(R.string.custom_lan_ip_desc),
                        icon = Icons.Filled.Settings,
                        position = CardPosition.Last,
                        highlighted = activeFocusKey == "network_custom_lan_ip",
                        onClick = { showCustomLanIpSheet = true }
                    )
                }
            }

            // Dial Timeout Slider
            item {
                RethinkListGroup {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = if (activeFocusKey == "network_dial_timeout") {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(Dimensions.cornerRadiusLg)
                    ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(Dimensions.cardPadding)) {
                        Text(
                            text = stringResource(R.string.settings_dial_timeout),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = dialTimeoutDesc,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(Dimensions.spacingSm))
                        Slider(
                            value = dialTimeoutMin.toFloat(),
                            onValueChange = { value ->
                                dialTimeoutMin = value.toInt()
                                persistentState.dialTimeoutSec = dialTimeoutMin * SECONDS_PER_MINUTE
                            },
                            valueRange = 0f..60f,
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        )
                    }
                    }
                }
            }
        }
    }

    if (showCustomLanIpSheet) {
        CustomLanIpSheet(
            persistentState = persistentState,
            onDismiss = { showCustomLanIpSheet = false }
        )
    }
    if (showReachabilitySheet) {
        NetworkReachabilitySheet(
            persistentState = persistentState,
            onDismiss = { showReachabilitySheet = false }
        )
    }
}

@Composable
private fun DefaultDnsDialog(
    persistentState: PersistentState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val options = Constants.DEFAULT_DNS_LIST
    val checkedItem = options.firstOrNull { it.url == persistentState.defaultDnsUrl }?.let { options.indexOf(it) } ?: 0
    var selectedIndex by remember { mutableIntStateOf(checkedItem) }

    RethinkConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_default_dns_heading),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEachIndexed { index, item ->
                    DialogRadioOptionRow(
                        title = item.name,
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index }
                    )
                }
            }
        },
        confirmText = stringResource(R.string.fapps_info_dialog_positive_btn),
        dismissText = stringResource(R.string.lbl_cancel),
        onConfirm = {
            persistentState.defaultDnsUrl = options[selectedIndex].url
            onConfirm()
            onDismiss()
        },
        onDismiss = onDismiss
    )
}

@Composable
private fun VpnPolicyDialog(
    persistentState: PersistentState,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val conservativeTxt = stringResource(R.string.vpn_policy_fixed) + " " + stringResource(R.string.lbl_experimental)
    val options = listOf(
        NetworkPolicyOption(
            stringResource(R.string.settings_ip_text_ipv46),
            stringResource(R.string.vpn_policy_auto_desc)
        ),
        NetworkPolicyOption(
            stringResource(R.string.vpn_policy_sensitive),
            stringResource(R.string.vpn_policy_sensitive_desc)
        ),
        NetworkPolicyOption(
            stringResource(R.string.vpn_policy_relaxed),
            stringResource(R.string.vpn_policy_relaxed_desc)
        ),
        NetworkPolicyOption(conservativeTxt, stringResource(R.string.vpn_policy_fixed_desc))
    )
    var selectedIndex by remember { mutableIntStateOf(persistentState.vpnBuilderPolicy) }

    RethinkConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.vpn_policy_title),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEachIndexed { index, option ->
                    DialogRadioOptionRow(
                        title = option.title,
                        description = option.description,
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index }
                    )
                }
            }
        },
        confirmText = stringResource(R.string.fapps_info_dialog_positive_btn),
        dismissText = stringResource(R.string.lbl_cancel),
        onConfirm = {
            onConfirm(selectedIndex)
            onDismiss()
        },
        onDismiss = onDismiss
    )
}

@Composable
private fun IpProtocolDialog(
    persistentState: PersistentState,
    context: Context,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val alwaysv46Txt =
        stringResource(R.string.settings_ip_text_ipv4) + " & " + stringResource(R.string.settings_ip_text_ipv6) + " " + stringResource(
            R.string.lbl_experimental
        )
    val items = listOf(
        stringResource(R.string.settings_ip_dialog_ipv4),
        stringResource(R.string.settings_ip_dialog_ipv6),
        alwaysv46Txt,
        stringResource(R.string.settings_ip_dialog_ipv46)
    )
    val chosenProtocol = persistentState.internetProtocolType
    val checkedItem = when (chosenProtocol) {
        InternetProtocol.ALWAYSv46.id -> IP_DIALOG_POS_ALWAYS_V46
        InternetProtocol.IPv46.id -> IP_DIALOG_POS_V46
        InternetProtocol.IPv4.id -> IP_DIALOG_POS_IPV4
        InternetProtocol.IPv6.id -> IP_DIALOG_POS_IPV6
        else -> IP_DIALOG_POS_IPV4
    }
    var selectedIndex by remember { mutableIntStateOf(checkedItem) }

    RethinkConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_ip_dialog_title),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEachIndexed { index, label ->
                    DialogRadioOptionRow(
                        title = label,
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index }
                    )
                }
            }
        },
        confirmText = stringResource(R.string.fapps_info_dialog_positive_btn),
        dismissText = stringResource(R.string.lbl_cancel),
        onConfirm = {
            val selectedItem = when (selectedIndex) {
                IP_DIALOG_POS_V46 -> InternetProtocol.IPv46.id
                IP_DIALOG_POS_ALWAYS_V46 -> InternetProtocol.ALWAYSv46.id
                else -> selectedIndex
            }
            if (persistentState.internetProtocolType != selectedItem) {
                val protocolType = InternetProtocol.getInternetProtocol(selectedItem)
                persistentState.internetProtocolType = protocolType.id
                if (protocolType.id == InternetProtocol.IPv6.id ||
                    protocolType.id == InternetProtocol.IPv46.id ||
                    protocolType.id == InternetProtocol.ALWAYSv46.id
                ) {
                    persistentState.enableStabilityDependentSettings(context)
                }
                onConfirm(protocolType.id)
            }
            onDismiss()
        },
        onDismiss = onDismiss
    )
}

@Composable
private fun ConnectivityChecksDialog(
    persistentState: PersistentState,
    onDismiss: () -> Unit,
    onConfirm: (Boolean) -> Unit
) {
    val items = listOf(
        stringResource(R.string.settings_app_list_default_app),
        stringResource(R.string.settings_ip_text_ipv46),
        stringResource(R.string.lbl_manual)
    )
    val type = persistentState.performAutoNetworkConnectivityChecks
    val enabled = persistentState.connectivityChecks
    val checkedItem = if (!enabled) 0 else if (type) 1 else 2
    var selectedIndex by remember { mutableIntStateOf(checkedItem) }

    RethinkConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_connectivity_checks),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEachIndexed { index, label ->
                    DialogRadioOptionRow(
                        title = label,
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index }
                    )
                }
            }
        },
        confirmText = stringResource(R.string.fapps_info_dialog_positive_btn),
        dismissText = stringResource(R.string.lbl_cancel),
        onConfirm = {
            when (selectedIndex) {
                0 -> {
                    persistentState.performAutoNetworkConnectivityChecks = true
                    persistentState.connectivityChecks = false
                    onConfirm(false)
                }

                1 -> {
                    persistentState.performAutoNetworkConnectivityChecks = true
                    persistentState.connectivityChecks = true
                    onConfirm(true)
                }

                2 -> {
                    persistentState.performAutoNetworkConnectivityChecks = false
                    persistentState.connectivityChecks = true
                    onConfirm(true)
                }
            }
            onDismiss()
        },
        onDismiss = onDismiss
    )
}

@Composable
private fun DialogRadioOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    description: String? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
