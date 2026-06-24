/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.compose.app

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MobileOff
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.CloseConnsDialog
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_NONE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.bottomsheet.AppDomainRulesSheet
import com.celzero.bravedns.ui.bottomsheet.AppIpRulesSheet
import com.celzero.bravedns.ui.compose.apps.DiagonalWipeIcon
import com.celzero.bravedns.ui.compose.rememberDrawablePainter
import com.celzero.bravedns.ui.compose.theme.CardPosition
import com.celzero.bravedns.ui.compose.theme.CompactEmptyState
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkActionListItem
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import com.celzero.bravedns.ui.compose.theme.RethinkListGroup
import com.celzero.bravedns.ui.compose.theme.RethinkListItem
import com.celzero.bravedns.ui.compose.theme.RethinkToggleListItem
import com.celzero.bravedns.ui.compose.theme.SectionHeader
import com.celzero.bravedns.ui.compose.theme.cardPositionFor
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Constants.Companion.RETHINK_PACKAGE
import com.celzero.bravedns.util.UIUtils.openAndroidAppInfo
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.viewmodel.AppConnectionsViewModel
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import com.celzero.bravedns.viewmodel.CustomIpViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppInfoScreen(
    uid: Int,
    eventLogger: EventLogger,
    ipRulesViewModel: CustomIpViewModel,
    domainRulesViewModel: CustomDomainViewModel,
    networkLogsViewModel: AppConnectionsViewModel,
    onBackClick: () -> Unit,
    onAppWiseIpLogsClick: (Int, Boolean) -> Unit,
    onCustomIpRulesClick: (Int) -> Unit,
    onCustomDomainRulesClick: (Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var appInfo by remember(uid) { mutableStateOf<AppInfo?>(null) }
    var appStatus by remember(uid) { mutableStateOf(FirewallManager.FirewallStatus.NONE) }
    var connStatus by remember(uid) { mutableStateOf(FirewallManager.ConnectionStatus.ALLOW) }
    var baselineConnStatus by remember(uid) { mutableStateOf(FirewallManager.ConnectionStatus.ALLOW) }
    var firewallStatusText by remember(uid) { mutableStateOf("") }
    var firewallUpdateVersion by remember(uid) { mutableStateOf(0) }
    var isProxyExcluded by remember(uid) { mutableStateOf(false) }
    var isTempAllowed by remember(uid) { mutableStateOf(false) }
    var proxyDetails by remember(uid) { mutableStateOf("") }
    var showNoAppFoundDialog by remember(uid) { mutableStateOf(false) }

    var showDomainRulesSheet by remember { mutableStateOf(false) }
    var selectedDomain by remember { mutableStateOf("") }
    var showIpRulesSheet by remember { mutableStateOf(false) }
    var selectedIp by remember { mutableStateOf("") }
    var selectedDomains by remember { mutableStateOf("") }

    var refreshToken by remember(uid) { mutableStateOf(0) }
    var closeDialogConn by remember(uid) { mutableStateOf<com.celzero.bravedns.data.AppConnection?>(null) }

    val wireguardAppsProxyMapDesc = stringResource(R.string.wireguard_apps_proxy_map_desc)
    val excludeNoPackageErrToast = stringResource(R.string.exclude_no_package_err_toast)
    val adaAppStatusBlockMd = stringResource(R.string.ada_app_status_block_md)
    val adaAppStatusBlockWifi = stringResource(R.string.ada_app_status_block_wifi)
    val adaAppStatusBlock = stringResource(R.string.ada_app_status_block)
    val adaAppStatusAllow = stringResource(R.string.ada_app_status_allow)
    val adaAppStatusExclude = stringResource(R.string.ada_app_status_exclude)
    val adaAppStatusWhitelist = stringResource(R.string.ada_app_status_whitelist)
    val adaAppStatusIsolate = stringResource(R.string.ada_app_status_isolate)
    val adaAppStatusBypassDnsFirewall = stringResource(R.string.ada_app_status_bypass_dns_firewall)
    val adaAppStatusUnknown = stringResource(R.string.ada_app_status_unknown)
    val getFirewallStatusText: (FirewallManager.FirewallStatus, FirewallManager.ConnectionStatus) -> String =
        { firewallStatus, connectionStatus ->
            when (firewallStatus) {
                FirewallManager.FirewallStatus.NONE -> {
                    when (connectionStatus) {
                        FirewallManager.ConnectionStatus.METERED -> adaAppStatusBlockMd
                        FirewallManager.ConnectionStatus.UNMETERED -> adaAppStatusBlockWifi
                        FirewallManager.ConnectionStatus.BOTH -> adaAppStatusBlock
                        FirewallManager.ConnectionStatus.ALLOW -> adaAppStatusAllow
                    }
                }
                FirewallManager.FirewallStatus.EXCLUDE -> adaAppStatusExclude
                FirewallManager.FirewallStatus.BYPASS_UNIVERSAL -> adaAppStatusWhitelist
                FirewallManager.FirewallStatus.ISOLATE -> adaAppStatusIsolate
                FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL -> adaAppStatusBypassDnsFirewall
                FirewallManager.FirewallStatus.UNTRACKED -> adaAppStatusUnknown
            }
        }

    LaunchedEffect(uid) {
        if (uid == INVALID_UID) {
            showNoAppFoundDialog = true
            return@LaunchedEffect
        }
        ipRulesViewModel.setUid(uid)
        domainRulesViewModel.setUid(uid)
        networkLogsViewModel.setUid(uid)
        loadAppInfo(
            uid = uid,
            wireguardAppsProxyMapDesc = wireguardAppsProxyMapDesc,
            getFirewallStatusText = getFirewallStatusText,
            onLoaded = {
                appInfo = it.info
                appStatus = it.appStatus
                connStatus = it.connStatus
                if (it.appStatus == FirewallManager.FirewallStatus.NONE) {
                    baselineConnStatus = it.connStatus
                }
                isProxyExcluded = it.isProxyExcluded
                isTempAllowed = it.isTempAllowed
                proxyDetails = it.proxyDetails
                firewallStatusText = it.firewallStatusText
            },
            onMissing = { showNoAppFoundDialog = true }
        )
    }

    // CloseConnsDialog displayed when user long-presses an active connection
    closeDialogConn?.let { conn ->
        CloseConnsDialog(
            conn = conn,
            onConfirm = {
                closeDialogConn = null
                refreshToken++
            },
            onDismiss = { closeDialogConn = null }
        )
    }

    if (showNoAppFoundDialog) {
        RethinkConfirmDialog(
            onDismissRequest = { showNoAppFoundDialog = false },
            title = stringResource(id = R.string.ada_noapp_dialog_title),
            message = stringResource(id = R.string.ada_noapp_dialog_message),
            confirmText = stringResource(id = R.string.fapps_info_dialog_positive_btn),
            onConfirm = {
                showNoAppFoundDialog = false
                onBackClick()
            }
        )
    }

    if (showDomainRulesSheet && selectedDomain.isNotEmpty()) {
        AppDomainRulesSheet(
            uid = uid,
            domain = selectedDomain,
            eventLogger = eventLogger,
            onDismiss = { showDomainRulesSheet = false },
            onUpdated = { refreshToken++ }
        )
    }
    if (showIpRulesSheet && selectedIp.isNotEmpty()) {
        AppIpRulesSheet(
            uid = uid,
            ipAddress = selectedIp,
            domains = selectedDomains,
            eventLogger = eventLogger,
            onDismiss = { showIpRulesSheet = false },
            onUpdated = { refreshToken++ }
        )
    }

    val isRethink = appInfo?.packageName == RETHINK_PACKAGE
    val uptime = VpnController.uptimeMs()
    val activeConns =
        if (isRethink) {
            networkLogsViewModel.getRethinkActiveConnsLimited(uptime)
        } else {
            networkLogsViewModel.fetchTopActiveConnections(uid, uptime)
        }
    val activeItems = activeConns.asFlow().collectAsLazyPagingItems()
    val domainItems =
        if (isRethink) {
            networkLogsViewModel.getRethinkDomainLogsLimited().asFlow().collectAsLazyPagingItems()
        } else {
            networkLogsViewModel.getDomainLogsLimited(uid).asFlow().collectAsLazyPagingItems()
        }
    val ipItems =
        if (isRethink) {
            networkLogsViewModel.getRethinkIpLogsLimited().asFlow().collectAsLazyPagingItems()
        } else {
            networkLogsViewModel.getIpLogsLimited(uid).asFlow().collectAsLazyPagingItems()
        }
    val activePreview =
        remember(activeItems.itemSnapshotList.items, refreshToken) {
            activeItems.itemSnapshotList.items.take(8)
        }
    val domainPreview =
        remember(domainItems.itemSnapshotList.items, refreshToken) {
            domainItems.itemSnapshotList.items.take(8)
        }
    val ipPreview =
        remember(ipItems.itemSnapshotList.items, refreshToken) {
            ipItems.itemSnapshotList.items.take(8)
        }
    val density = LocalDensity.current
    val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val info = appInfo
    val title = info?.appName?.takeIf { it.isNotBlank() } ?: stringResource(id = R.string.bsct_app_info)
    val subtitle = info?.packageName?.takeIf { it.isNotBlank() }
    val wifiBlocked =
        connStatus == FirewallManager.ConnectionStatus.UNMETERED ||
            connStatus == FirewallManager.ConnectionStatus.BOTH
    val mobileBlocked =
        connStatus == FirewallManager.ConnectionStatus.METERED ||
            connStatus == FirewallManager.ConnectionStatus.BOTH
    val isIsolated = appStatus == FirewallManager.FirewallStatus.ISOLATE
    val isBypassDnsFirewall = appStatus == FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL
    val isBypassUniversal = appStatus == FirewallManager.FirewallStatus.BYPASS_UNIVERSAL
    val isExcluded = appStatus == FirewallManager.FirewallStatus.EXCLUDE
    var appIcon by remember(uid) { mutableStateOf<Drawable?>(null) }

    LaunchedEffect(info?.packageName, info?.appName) {
        if (info == null) {
            appIcon = null
            return@LaunchedEffect
        }
        appIcon =
            withContext(Dispatchers.IO) {
                Utilities.getIcon(context, info.packageName, info.appName)
            }
    }

    fun applyFirewallRule(
        firewallStatus: FirewallManager.FirewallStatus,
        connectionStatus: FirewallManager.ConnectionStatus
    ) {
        val requestVersion = firewallUpdateVersion + 1
        firewallUpdateVersion = requestVersion

        // Optimistic update to keep UI deterministic and avoid stale rapid-tap states.
        val optimisticText = getFirewallStatusText(firewallStatus, connectionStatus)
        firewallStatusText = optimisticText
        appStatus = firewallStatus
        connStatus = connectionStatus
        if (firewallStatus == FirewallManager.FirewallStatus.NONE) {
            baselineConnStatus = connectionStatus
        }

        updateFirewallStatus(
            scope = scope,
            context = context,
            uid = uid,
            appInfo = info,
            aStat = firewallStatus,
            cStat = connectionStatus,
            eventLogger = eventLogger,
            excludeNoPackageErrToast = excludeNoPackageErrToast,
            getFirewallStatusText = getFirewallStatusText
        ) { statusText, updatedAppStatus, updatedConnStatus ->
            if (requestVersion != firewallUpdateVersion) return@updateFirewallStatus
            firewallStatusText = statusText
            appStatus = updatedAppStatus
            connStatus = updatedConnStatus
            if (updatedAppStatus == FirewallManager.FirewallStatus.NONE) {
                baselineConnStatus = updatedConnStatus
            }
        }
    }

    fun toggleExclusiveStatus(target: FirewallManager.FirewallStatus) {
        val turningOff = appStatus == target
        if (!turningOff && appStatus == FirewallManager.FirewallStatus.NONE) {
            baselineConnStatus = connStatus
        }
        val nextStatus =
            if (turningOff) {
                FirewallManager.FirewallStatus.NONE
            } else {
                target
            }
        val nextConnStatus =
            if (nextStatus == FirewallManager.FirewallStatus.NONE) {
                baselineConnStatus
            } else {
                FirewallManager.ConnectionStatus.ALLOW
            }
        applyFirewallRule(nextStatus, nextConnStatus)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            RethinkLargeTopBar(
                title = title,
                subtitle = subtitle,
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior,
                titleLeading = {
                    val iconPainter =
                        rememberDrawablePainter(appIcon ?: Utilities.getDefaultIcon(context))
                    iconPainter?.let { painter ->
                        Image(
                            painter = painter,
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .size(Dimensions.iconSizeXl)
                                    .clip(RoundedCornerShape(Dimensions.cornerRadiusMd))
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            contentPadding =
                PaddingValues(
                    start = Dimensions.screenPaddingHorizontal,
                    end = Dimensions.screenPaddingHorizontal,
                    top = Dimensions.spacingSm,
                    bottom = Dimensions.screenPaddingHorizontal + bottomInset
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)
        ) {
            if (info == null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(Dimensions.cornerRadius2xl),
                        color = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CompactEmptyState(message = stringResource(id = R.string.ada_noapp_dialog_message))
                            RethinkActionListItem(
                                title = stringResource(id = R.string.ada_noapp_dialog_positive),
                                iconRes = R.drawable.ic_arrow_back_24,
                                position = CardPosition.Single,
                                onClick = onBackClick
                            )
                        }
                    }
                }
                return@LazyColumn
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(Dimensions.cornerRadius3xl),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(id = R.string.lbl_status),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppInfoStatusBadge(
                                label = firewallStatusText,
                                active = true
                            )
                            if (isTempAllowed) {
                                AppInfoStatusBadge(
                                    label = stringResource(id = R.string.temp_allow_label),
                                    active = true
                                )
                            }
                        }
                        if (proxyDetails.isNotBlank()) {
                            Text(
                                text = proxyDetails,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item { SectionHeader(title = stringResource(id = R.string.lbl_firewall)) }
            item {
                AppFirewallPairRow(
                    leftTitle = stringResource(id = R.string.ada_app_unmetered),
                    leftDescription = stringResource(id = R.string.firewall_status_block_unmetered),
                    leftEnabled = wifiBlocked,
                    leftAllowedIcon = Icons.Rounded.Wifi,
                    leftBlockedIcon = Icons.Rounded.WifiOff,
                    onLeftClick = {
                        val newConnStatus =
                            when (connStatus) {
                                FirewallManager.ConnectionStatus.UNMETERED -> FirewallManager.ConnectionStatus.ALLOW
                                FirewallManager.ConnectionStatus.BOTH -> FirewallManager.ConnectionStatus.METERED
                                FirewallManager.ConnectionStatus.METERED -> FirewallManager.ConnectionStatus.BOTH
                                FirewallManager.ConnectionStatus.ALLOW -> FirewallManager.ConnectionStatus.UNMETERED
                            }
                        applyFirewallRule(FirewallManager.FirewallStatus.NONE, newConnStatus)
                    },
                    rightTitle = stringResource(id = R.string.lbl_mobile_data),
                    rightDescription = stringResource(id = R.string.firewall_status_block_metered),
                    rightEnabled = mobileBlocked,
                    rightAllowedIcon = Icons.Rounded.PhoneAndroid,
                    rightBlockedIcon = Icons.Rounded.MobileOff,
                    onRightClick = {
                        val newConnStatus =
                            when (connStatus) {
                                FirewallManager.ConnectionStatus.METERED -> FirewallManager.ConnectionStatus.ALLOW
                                FirewallManager.ConnectionStatus.UNMETERED -> FirewallManager.ConnectionStatus.BOTH
                                FirewallManager.ConnectionStatus.BOTH -> FirewallManager.ConnectionStatus.UNMETERED
                                FirewallManager.ConnectionStatus.ALLOW -> FirewallManager.ConnectionStatus.METERED
                            }
                        applyFirewallRule(FirewallManager.FirewallStatus.NONE, newConnStatus)
                    }
                )
            }
            item {
                RethinkListGroup {
                    RethinkActionListItem(
                        title = stringResource(id = R.string.ada_app_isolate),
                        description = stringResource(id = R.string.firewall_status_isolate),
                        iconRes = R.drawable.ic_firewall_lockdown_off,
                        accentColor = MaterialTheme.colorScheme.error,
                        position = cardPositionFor(0, 3),
                        trailing = {
                            AppInfoStatusBadge(
                                label =
                                    stringResource(
                                        id = if (isIsolated) R.string.lbbs_enabled else R.string.lbl_disabled
                                    ),
                                active = isIsolated
                            )
                        },
                        onClick = {
                            toggleExclusiveStatus(FirewallManager.FirewallStatus.ISOLATE)
                        }
                    )
                    RethinkActionListItem(
                        title = stringResource(id = R.string.ada_app_bypass_dns_firewall),
                        description = stringResource(id = R.string.firewall_status_bypass_dns_firewall),
                        iconRes = R.drawable.ic_bypass_dns_firewall_off,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        position = cardPositionFor(1, 3),
                        trailing = {
                            AppInfoStatusBadge(
                                label =
                                    stringResource(
                                        id = if (isBypassDnsFirewall) R.string.lbbs_enabled else R.string.lbl_disabled
                                    ),
                                active = isBypassDnsFirewall
                            )
                        },
                        onClick = {
                            toggleExclusiveStatus(FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL)
                        }
                    )
                    RethinkActionListItem(
                        title = stringResource(id = R.string.ada_app_bypass_univ),
                        description = stringResource(id = R.string.firewall_status_whitelisted),
                        iconRes = R.drawable.ic_firewall_bypass_off,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        position = cardPositionFor(2, 3),
                        trailing = {
                            AppInfoStatusBadge(
                                label =
                                    stringResource(
                                        id = if (isBypassUniversal) R.string.lbbs_enabled else R.string.lbl_disabled
                                    ),
                                active = isBypassUniversal
                            )
                        },
                        onClick = {
                            toggleExclusiveStatus(FirewallManager.FirewallStatus.BYPASS_UNIVERSAL)
                        }
                    )
                    RethinkActionListItem(
                        title = stringResource(id = R.string.ada_app_exclude),
                        description = stringResource(id = R.string.firewall_status_excluded),
                        iconRes = R.drawable.ic_firewall_exclude_off,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        position = cardPositionFor(3, 3),
                        trailing = {
                            AppInfoStatusBadge(
                                label =
                                    stringResource(
                                        id = if (isExcluded) R.string.lbbs_enabled else R.string.lbl_disabled
                                    ),
                                active = isExcluded
                            )
                        },
                        onClick = {
                            toggleExclusiveStatus(FirewallManager.FirewallStatus.EXCLUDE)
                        }
                    )
                }
            }

            item { SectionHeader(title = stringResource(id = R.string.lbl_advanced)) }
            item {
                RethinkListGroup {
                    RethinkToggleListItem(
                        title = stringResource(id = R.string.exclude_apps_from_proxy),
                        description = stringResource(id = R.string.settings_exclude_proxy_apps_desc),
                        checked = isProxyExcluded,
                        onCheckedChange = { enabled ->
                            isProxyExcluded = enabled
                            scope.launch(Dispatchers.IO) {
                                FirewallManager.updateIsProxyExcluded(uid, enabled)
                            }
                        },
                        iconRes = R.drawable.ic_proxy,
                        accentColor = MaterialTheme.colorScheme.secondary,
                        position = cardPositionFor(0, 1)
                    )
                    RethinkToggleListItem(
                        title = stringResource(id = R.string.temp_allow_label),
                        description = stringResource(id = R.string.temp_allow_desc),
                        checked = isTempAllowed,
                        onCheckedChange = { enabled ->
                            isTempAllowed = enabled
                            scope.launch(Dispatchers.IO) {
                                FirewallManager.updateTempAllow(uid, enabled)
                            }
                        },
                        iconRes = R.drawable.ic_timeout,
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        position = cardPositionFor(1, 1)
                    )
                }
            }

            item { SectionHeader(title = stringResource(id = R.string.lbl_rules)) }
            item {
                RethinkListGroup {
                    RethinkActionListItem(
                        title = stringResource(id = R.string.about_settings_app_info),
                        iconRes = R.drawable.ic_app_info,
                        position = cardPositionFor(0, 2),
                        trailing = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_right_arrow_small),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { openAndroidAppInfo(context, info.packageName) }
                    )
                    RethinkActionListItem(
                        title = stringResource(id = R.string.lbl_ip_rules),
                        iconRes = R.drawable.ic_ip_info,
                        position = cardPositionFor(1, 2),
                        trailing = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_right_arrow_small),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { onCustomIpRulesClick(uid) }
                    )
                    RethinkActionListItem(
                        title = stringResource(id = R.string.lbl_domain_rules),
                        iconRes = R.drawable.ic_dns_rules_as_firewall,
                        position = cardPositionFor(2, 2),
                        trailing = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_right_arrow_small),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { onCustomDomainRulesClick(uid) }
                    )
                }
            }

            item {
                LogSectionCard(
                    title = stringResource(id = R.string.top_active_conns),
                    badgeCount = activeItems.itemCount,
                    onClick = { onAppWiseIpLogsClick(uid, false) }
                ) {
                    if (activeItems.loadState.refresh is LoadState.Loading && activeItems.itemCount == 0) {
                        CompactEmptyState(message = stringResource(id = R.string.lbl_loading))
                    } else if (activeItems.itemCount == 0) {
                        CompactEmptyState(message = stringResource(id = R.string.fapps_empty_subtitle))
                    } else {
                        AppInfoLogPreviewList(
                            items = activePreview,
                            title = { beautifyCommaSeparated(it.ipAddress) },
                            subtitle = { beautifyCommaSeparated(it.appOrDnsName) },
                            onClick = { closeDialogConn = it }
                        )
                    }
                }
            }

            item {
                LogSectionCard(
                    title = stringResource(id = R.string.ssv_most_contacted_domain_heading),
                    badgeCount = domainItems.itemCount,
                    onClick = { onAppWiseIpLogsClick(uid, false) }
                ) {
                    if (domainItems.loadState.refresh is LoadState.Loading && domainItems.itemCount == 0) {
                        CompactEmptyState(message = stringResource(id = R.string.lbl_loading))
                    } else if (domainItems.itemCount == 0) {
                        CompactEmptyState(message = stringResource(id = R.string.fapps_empty_subtitle))
                    } else {
                        AppInfoLogPreviewList(
                            items = domainPreview,
                            title = {
                                val domain = beautifyCommaSeparated(it.appOrDnsName)
                                if (domain.isNotBlank()) domain else beautifyCommaSeparated(it.ipAddress)
                            },
                            subtitle = {
                                val ip = beautifyCommaSeparated(it.ipAddress)
                                ip.takeIf { value -> value.isNotBlank() && value != beautifyCommaSeparated(it.appOrDnsName) }
                            },
                            onClick = {
                                selectedDomain = it.appOrDnsName.orEmpty()
                                showDomainRulesSheet = true
                            }
                        )
                    }
                }
            }

            item {
                LogSectionCard(
                    title = stringResource(id = R.string.ssv_most_contacted_ips_heading),
                    badgeCount = ipItems.itemCount,
                    onClick = { onAppWiseIpLogsClick(uid, false) }
                ) {
                    if (ipItems.loadState.refresh is LoadState.Loading && ipItems.itemCount == 0) {
                        CompactEmptyState(message = stringResource(id = R.string.lbl_loading))
                    } else if (ipItems.itemCount == 0) {
                        CompactEmptyState(message = stringResource(id = R.string.fapps_empty_subtitle))
                    } else {
                        AppInfoLogPreviewList(
                            items = ipPreview,
                            title = { beautifyCommaSeparated(it.ipAddress) },
                            subtitle = { beautifyCommaSeparated(it.appOrDnsName) },
                            onClick = {
                                selectedIp = it.ipAddress
                                selectedDomains = it.appOrDnsName.orEmpty()
                                showIpRulesSheet = true
                            }
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(Dimensions.spacingSm)) }
        }
    }
}

@Composable
private fun AppInfoStatusBadge(
    label: String,
    active: Boolean
) {
    Surface(
        shape = RoundedCornerShape(100.dp),
        color =
            if (active) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color =
                if (active) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun AppFirewallPairRow(
    leftTitle: String,
    leftDescription: String,
    leftEnabled: Boolean,
    leftAllowedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    leftBlockedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onLeftClick: () -> Unit,
    rightTitle: String,
    rightDescription: String,
    rightEnabled: Boolean,
    rightAllowedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    rightBlockedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onRightClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        AppFirewallTile(
            modifier = Modifier.weight(1f),
            title = leftTitle,
            description = leftDescription,
            enabled = leftEnabled,
            allowedIcon = leftAllowedIcon,
            blockedIcon = leftBlockedIcon,
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 8.dp, bottomStart = 22.dp, bottomEnd = 8.dp),
            onClick = onLeftClick
        )
        AppFirewallTile(
            modifier = Modifier.weight(1f),
            title = rightTitle,
            description = rightDescription,
            enabled = rightEnabled,
            allowedIcon = rightAllowedIcon,
            blockedIcon = rightBlockedIcon,
            shape = RoundedCornerShape(topStart = 8.dp, topEnd = 22.dp, bottomStart = 8.dp, bottomEnd = 22.dp),
            onClick = onRightClick
        )
    }
}

@Composable
private fun AppFirewallTile(
    modifier: Modifier = Modifier,
    title: String,
    description: String,
    enabled: Boolean,
    allowedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    blockedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    shape: RoundedCornerShape,
    onClick: () -> Unit
) {
    val blockedTint = MaterialTheme.colorScheme.error
    val allowedTint = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier =
                    Modifier
                        .size(28.dp),
                contentAlignment = Alignment.Center
            ) {
                DiagonalWipeIcon(
                    blocked = enabled,
                    allowedIcon = allowedIcon,
                    blockedIcon = blockedIcon,
                    allowedTint = allowedTint,
                    blockedTint = blockedTint,
                    contentDescription = title,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            AppInfoStatusBadge(
                label = stringResource(id = if (enabled) R.string.lbbs_enabled else R.string.lbl_disabled),
                active = enabled
            )
        }
    }
}

@Composable
private fun LogSectionCard(
    title: String,
    badgeCount: Int = 0,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimensions.cornerRadius3xl),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onClick)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (badgeCount > 0) {
                        AppInfoStatusBadge(
                            label = badgeCount.toString(),
                            active = true
                        )
                    }
                }
                Icon(
                    painter = painterResource(id = R.drawable.ic_right_arrow_small),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun AppInfoLogPreviewList(
    items: List<AppConnection>,
    title: (AppConnection) -> String,
    subtitle: (AppConnection) -> String?,
    onClick: (AppConnection) -> Unit
) {
    RethinkListGroup {
        items.forEachIndexed { index, conn ->
            AppInfoLogPreviewRow(
                title = title(conn),
                subtitle = subtitle(conn),
                count = conn.count,
                flag = conn.flag,
                position = cardPositionFor(index, items.lastIndex),
                onClick = { onClick(conn) }
            )
        }
    }
}

@Composable
private fun AppInfoLogPreviewRow(
    title: String,
    subtitle: String?,
    count: Int,
    flag: String,
    position: CardPosition,
    onClick: () -> Unit
) {
    RethinkListItem(
        headline = title.ifBlank { "-" },
        supporting = subtitle?.takeIf { it.isNotBlank() },
        position = position,
        leadingContent = {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = flag.takeIf { it.isNotBlank() } ?: "\u2022",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        },
        trailing = {
            AppInfoStatusBadge(
                label = count.toString(),
                active = false
            )
        },
        onClick = onClick
    )
}

private fun beautifyCommaSeparated(value: String?): String {
    if (value.isNullOrBlank()) return ""
    return value
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(", ")
}

private data class AppInfoLoad(
    val info: AppInfo,
    val appStatus: FirewallManager.FirewallStatus,
    val connStatus: FirewallManager.ConnectionStatus,
    val isProxyExcluded: Boolean,
    val isTempAllowed: Boolean,
    val proxyDetails: String,
    val firewallStatusText: String
)

private suspend fun loadAppInfo(
    uid: Int,
    wireguardAppsProxyMapDesc: String,
    getFirewallStatusText: (FirewallManager.FirewallStatus, FirewallManager.ConnectionStatus) -> String,
    onLoaded: (AppInfoLoad) -> Unit,
    onMissing: () -> Unit
) {
    val info = withContext(Dispatchers.IO) { FirewallManager.getAppInfoByUid(uid) }
    if (info == null || uid == INVALID_UID || info.tombstoneTs > 0) {
        onMissing()
        return
    }
    val status = FirewallManager.appStatus(info.uid)
    val conn = FirewallManager.connectionStatus(info.uid)
    val proxy =
        ProxyManager.getProxyIdForApp(uid).takeIf { it.isNotEmpty() && it != ID_NONE }
            ?.let { wireguardAppsProxyMapDesc.format(it) }
            .orEmpty()
    val firewallStatusText = getFirewallStatusText(status, conn)
    onLoaded(
        AppInfoLoad(
            info = info,
            appStatus = status,
            connStatus = conn,
            isProxyExcluded = info.isProxyExcluded,
            isTempAllowed = FirewallManager.isTempAllowed(info.uid),
            proxyDetails = proxy,
            firewallStatusText = firewallStatusText
        )
    )
}

private fun updateFirewallStatus(
    scope: CoroutineScope,
    context: Context,
    uid: Int,
    appInfo: AppInfo?,
    aStat: FirewallManager.FirewallStatus,
    cStat: FirewallManager.ConnectionStatus,
    eventLogger: EventLogger,
    excludeNoPackageErrToast: String,
    getFirewallStatusText: (FirewallManager.FirewallStatus, FirewallManager.ConnectionStatus) -> String,
    onUpdated: (String, FirewallManager.FirewallStatus, FirewallManager.ConnectionStatus) -> Unit
) {
    val info = appInfo ?: return
    if (aStat == FirewallManager.FirewallStatus.EXCLUDE && FirewallManager.isUnknownPackage(uid)) {
        showToastUiCentered(context, excludeNoPackageErrToast, Toast.LENGTH_LONG)
        return
    }
    scope.launch(Dispatchers.IO) {
        FirewallManager.updateFirewallStatus(info.uid, aStat, cStat)
        val statusText = getFirewallStatusText(aStat, cStat)
        withContext(Dispatchers.Main) {
            onUpdated(statusText, aStat, cStat)
        }
        eventLogger.log(
            type = EventType.FW_RULE_MODIFIED,
            severity = Severity.LOW,
            message = "Firewall status changed",
            source = EventSource.MANAGER,
            userAction = true,
            details = "Firewall status changed for ${info.appName} (${info.uid}), new status: $aStat, conn status: $cStat"
        )
    }
}
