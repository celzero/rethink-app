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
package com.celzero.bravedns.ui.compose.wireguard

import android.app.Activity
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.WgPeerRow
import com.celzero.bravedns.data.SsidItem
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_OTHER_WG_ACTIVE
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_VPN_NOT_ACTIVE
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_VPN_NOT_FULL
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_WG_INVALID
import com.celzero.bravedns.service.WireguardManager.INVALID_CONF_ID
import com.celzero.bravedns.service.WireguardManager.WG_UPTIME_THRESHOLD
import com.celzero.bravedns.ui.compose.theme.CardPosition
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkActionListItem
import com.celzero.bravedns.ui.compose.theme.RethinkListGroup
import com.celzero.bravedns.ui.compose.theme.RethinkToggleListItem
import com.celzero.bravedns.ui.dialog.WgAddPeerDialog
import com.celzero.bravedns.ui.dialog.WgHopDialog
import com.celzero.bravedns.ui.dialog.WgIncludeAppsDialog
import com.celzero.bravedns.ui.dialog.WgSsidDialog
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import com.celzero.bravedns.ui.compose.theme.SectionHeader
import com.celzero.bravedns.util.SsidPermissionManager
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.Peer
import com.celzero.bravedns.wireguard.WgHopManager
import com.celzero.firestack.backend.RouterStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WgConfigDetailScreen(
    configId: Int,
    wgType: WgType,
    persistentState: PersistentState,
    eventLogger: EventLogger,
    mappingViewModel: ProxyAppsMappingViewModel,
    onEditConfig: (Int, WgType) -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val socks5VpnDisabledErrorText = stringResource(R.string.settings_socks5_vpn_disabled_error)
    val wireguardEnabledFailureText = stringResource(R.string.wireguard_enabled_failure)
    val configAddSuccessToast = stringResource(R.string.config_add_success_toast)
    val lblSsidsText = stringResource(R.string.lbl_ssids)

    // State variables
    var configFiles by remember { mutableStateOf<WgConfigFilesImmutable?>(null) }
    var config by remember { mutableStateOf<Config?>(null) }
    var peers by remember { mutableStateOf<List<Peer>>(emptyList()) }
    var statusText by remember { mutableStateOf("") }
    var statusColor by remember { mutableStateOf<Int?>(null) }
    var catchAllEnabled by remember { mutableStateOf(false) }
    var useMobileEnabled by remember { mutableStateOf(false) }
    var ssidEnabled by remember { mutableStateOf(false) }
    var ssids by remember { mutableStateOf<List<SsidItem>>(emptyList()) }
    var showInvalidConfigDialog by remember { mutableStateOf(false) }
    var showDeleteInterfaceDialog by remember { mutableStateOf(false) }
    var showSsidPermissionDialog by remember { mutableStateOf(false) }
    var showAddPeerDialog by remember { mutableStateOf(false) }
    var showHopDialog by remember { mutableStateOf(false) }
    var hopDialogConfigs by remember { mutableStateOf<List<Config>>(emptyList()) }
    var hopDialogSelectedId by remember { mutableStateOf(INVALID_CONF_ID) }
    var showSsidDialog by remember { mutableStateOf(false) }
    var ssidDialogCurrent by remember { mutableStateOf("") }
    var showIncludeAppsDialog by remember { mutableStateOf(false) }
    var includeAppsProxyId by remember { mutableStateOf("") }
    var includeAppsProxyName by remember { mutableStateOf("") }
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val errorColor = MaterialTheme.colorScheme.error.toArgb()
    val tertiaryColor = MaterialTheme.colorScheme.tertiary.toArgb()
    val statusFailingText =
        stringResource(id = R.string.status_failing).replaceFirstChar(Char::titlecase)
    val statusDisabledText =
        stringResource(id = R.string.lbl_disabled).replaceFirstChar(Char::titlecase)
    val statusWaitingText = stringResource(id = R.string.status_waiting)
    val statusTextById = mutableMapOf<Long, String>().apply {
        for (status in UIUtils.ProxyStatus.entries) {
            put(
                status.id,
                stringResource(id = UIUtils.getProxyStatusStringRes(status.id)).replaceFirstChar(Char::titlecase)
            )
        }
    }
    val wireguardVersionTemplate = stringResource(id = R.string.about_version_install_source)

    val appsCount by mappingViewModel
        .getAppCountById(ID_WG_BASE + configId)
        .asFlow()
        .collectAsState(initial = 0)

    // Refresh config on launch
    LaunchedEffect(configId) {
        val cfg = WireguardManager.getConfigById(configId)
        val mapping = WireguardManager.getConfigFilesById(configId)
        if (cfg == null || mapping == null) {
            showInvalidConfigDialog = true
            return@LaunchedEffect
        }
        config = cfg
        configFiles = mapping
        peers = cfg.getPeers() ?: emptyList()
        catchAllEnabled = mapping.isCatchAll
        useMobileEnabled = mapping.useOnlyOnMetered
        ssidEnabled = mapping.ssidEnabled
        ssids = SsidItem.parseStorageList(mapping.ssids)
    }

    // Update status UI when config changes
    LaunchedEffect(configFiles?.isActive, configFiles?.id) {
        updateStatusUi(
            id = configId,
            persistentState = persistentState,
            onSurfaceVariantColor = onSurfaceVariantColor,
            errorColor = errorColor,
            tertiaryColor = tertiaryColor,
            statusTextById = statusTextById,
            statusFailingText = statusFailingText,
            statusDisabledText = statusDisabledText,
            statusWaitingText = statusWaitingText,
            wireguardVersionTemplate = wireguardVersionTemplate,
            onStatusUpdate = { text, color ->
                statusText = text
                statusColor = color
            }
        )
    }

    // Helper functions
    fun logEvent(msg: String, details: String) {
        eventLogger.log(
            type = EventType.PROXY_SWITCH,
            severity = Severity.LOW,
            message = msg,
            source = EventSource.MANAGER,
            userAction = true,
            details = details
        )
    }

    suspend fun refreshConfig() {
        val cfg = WireguardManager.getConfigById(configId)
        val mapping = WireguardManager.getConfigFilesById(configId)
        if (cfg == null || mapping == null) {
            showInvalidConfigDialog = true
            return
        }
        config = cfg
        configFiles = mapping
        peers = cfg.getPeers() ?: emptyList()
        catchAllEnabled = mapping.isCatchAll
        useMobileEnabled = mapping.useOnlyOnMetered
        ssidEnabled = mapping.ssidEnabled
        ssids = SsidItem.parseStorageList(mapping.ssids)
    }

    fun updateUseOnMobileNetwork(enabled: Boolean) {
        useMobileEnabled = enabled
        scope.launch(Dispatchers.IO) {
            WireguardManager.updateUseOnMobileNetworkConfig(configId, enabled)
        }
        logEvent(
            "WireGuard Use on Mobile Networks",
            "User ${if (enabled) "enabled" else "disabled"} use on mobile networks for WireGuard config with id $configId"
        )
    }

    fun updateCatchAll(enabled: Boolean) {
        catchAllEnabled = enabled
        scope.launch(Dispatchers.IO) {
            if (!VpnController.hasTunnel()) {
                withContext(Dispatchers.Main) {
                    catchAllEnabled = !enabled
                    Utilities.showToastUiCentered(
                        context,
                        ERR_CODE_VPN_NOT_ACTIVE + socks5VpnDisabledErrorText,
                        Toast.LENGTH_LONG
                    )
                }
                return@launch
            }

            if (!WireguardManager.canEnableProxy()) {
                withContext(Dispatchers.Main) {
                    catchAllEnabled = false
                    Utilities.showToastUiCentered(
                        context,
                        ERR_CODE_VPN_NOT_FULL + wireguardEnabledFailureText,
                        Toast.LENGTH_LONG
                    )
                }
                return@launch
            }

            if (WireguardManager.oneWireGuardEnabled()) {
                withContext(Dispatchers.Main) {
                    catchAllEnabled = false
                    Utilities.showToastUiCentered(
                        context,
                        ERR_CODE_OTHER_WG_ACTIVE + wireguardEnabledFailureText,
                        Toast.LENGTH_LONG
                    )
                }
                return@launch
            }

            val cfg = WireguardManager.getConfigFilesById(configId)
            if (cfg == null) {
                withContext(Dispatchers.Main) {
                    catchAllEnabled = false
                    Utilities.showToastUiCentered(
                        context,
                        ERR_CODE_WG_INVALID + wireguardEnabledFailureText,
                        Toast.LENGTH_LONG
                    )
                }
                return@launch
            }

            WireguardManager.updateCatchAllConfig(configId, enabled)
            logEvent(
                "WireGuard Catch All apps",
                "User ${if (enabled) "enabled" else "disabled"} catch all apps for WireGuard config with id $configId"
            )
        }
    }

    fun toggleSsid(enabled: Boolean) {
        ssidEnabled = enabled
        val activity = context as? Activity
        if (activity == null || !SsidPermissionManager.hasRequiredPermissions(activity) || !SsidPermissionManager.isLocationEnabled(
                activity
            )
        ) {
            ssidEnabled = false
            showSsidPermissionDialog = true
            return
        }
        scope.launch(Dispatchers.IO) {
            WireguardManager.updateSsidEnabled(configId, enabled)
        }
    }

    fun openAppsDialog(proxyName: String) {
        val proxyId = ID_WG_BASE + configId
        includeAppsProxyId = proxyId
        includeAppsProxyName = proxyName
        showIncludeAppsDialog = true
    }

    fun openHopDialog() {
        scope.launch(Dispatchers.IO) {
            val hopables = WgHopManager.getHopableWgs(configId)
            val selectedId = convertStringIdToId(WgHopManager.getHop(configId))
            withContext(Dispatchers.Main) {
                hopDialogConfigs = hopables
                hopDialogSelectedId = selectedId
                showHopDialog = true
            }
        }
    }

    fun openSsidDialog() {
        ssidDialogCurrent = WireguardManager.getConfigFilesById(configId)?.ssids.orEmpty()
        showSsidDialog = true
    }

    // Dialogs
    if (showInvalidConfigDialog) {
        RethinkConfirmDialog(
            onDismissRequest = {},
            title = stringResource(R.string.lbl_wireguard),
            message = stringResource(R.string.config_invalid_desc),
            confirmText = stringResource(R.string.lbl_delete),
            dismissText = stringResource(R.string.fapps_info_dialog_positive_btn),
            onConfirm = {
                showInvalidConfigDialog = false
                WireguardManager.deleteConfig(configId)
            },
            onDismiss = {
                showInvalidConfigDialog = false
                onBackClick()
            },
            isConfirmDestructive = true
        )
    }

    if (showDeleteInterfaceDialog) {
        val delText = stringResource(
            R.string.two_argument_space,
            stringResource(R.string.config_delete_dialog_title),
            stringResource(R.string.lbl_wireguard)
        )
        RethinkConfirmDialog(
            onDismissRequest = { showDeleteInterfaceDialog = false },
            title = delText,
            message = stringResource(R.string.config_delete_dialog_desc),
            confirmText = delText,
            dismissText = stringResource(R.string.lbl_cancel),
            onConfirm = {
                showDeleteInterfaceDialog = false
                scope.launch(Dispatchers.IO) {
                    WireguardManager.deleteConfig(configId)
                    withContext(Dispatchers.Main) {
                        Utilities.showToastUiCentered(
                            context,
                            configAddSuccessToast,
                            Toast.LENGTH_SHORT
                        )
                        onBackClick()
                    }
                    logEvent(
                        "Delete WireGuard config",
                        "User deleted WireGuard config with id $configId"
                    )
                }
            },
            onDismiss = { showDeleteInterfaceDialog = false },
            isConfirmDestructive = true
        )
    }

    if (showSsidPermissionDialog) {
        RethinkConfirmDialog(
            onDismissRequest = { showSsidPermissionDialog = false },
            title = stringResource(R.string.lbl_ssid),
            message = SsidPermissionManager.getPermissionExplanation(context),
            confirmText = stringResource(R.string.fapps_info_dialog_positive_btn),
            dismissText = stringResource(R.string.lbl_cancel),
            onConfirm = {
                showSsidPermissionDialog = false
                val activity = context as? Activity
                if (activity != null) {
                    SsidPermissionManager.requestSsidPermissions(activity)
                }
            },
            onDismiss = {
                showSsidPermissionDialog = false
                scope.launch(Dispatchers.IO) {
                    WireguardManager.updateSsidEnabled(configId, false)
                }
            }
        )
    }

    if (showAddPeerDialog) {
        WgAddPeerDialog(
            configId = configId,
            wgPeer = null,
            onDismiss = {
                showAddPeerDialog = false
                scope.launch { refreshConfig() }
            }
        )
    }

    if (showHopDialog) {
        WgHopDialog(
            srcId = configId,
            hopables = hopDialogConfigs,
            selectedId = hopDialogSelectedId,
            onDismiss = { showHopDialog = false }
        )
    }

    if (showSsidDialog) {
        WgSsidDialog(
            currentSsids = ssidDialogCurrent,
            onSave = { newSsids ->
                scope.launch(Dispatchers.IO) {
                    WireguardManager.updateSsids(configId, newSsids)
                    val cfg = WireguardManager.getConfigFilesById(configId)
                    withContext(Dispatchers.Main) {
                        ssids = SsidItem.parseStorageList(cfg?.ssids ?: "")
                    }
                }
            },
            onDismiss = { showSsidDialog = false }
        )
    }

    if (showIncludeAppsDialog) {
        WgIncludeAppsDialog(
            viewModel = mappingViewModel,
            proxyId = includeAppsProxyId,
            proxyName = includeAppsProxyName,
            onDismiss = { showIncludeAppsDialog = false }
        )
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            RethinkLargeTopBar(
                title = stringResource(R.string.lbl_wireguard),
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        if (config == null || configFiles == null) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(Dimensions.screenPaddingHorizontal),
                verticalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)
            ) {
                Text(text = stringResource(id = R.string.config_invalid_desc))
                Button(onClick = onBackClick) {
                    Text(text = stringResource(id = R.string.fapps_info_dialog_positive_btn))
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            contentPadding =
                PaddingValues(
                    start = Dimensions.screenPaddingHorizontal,
                    end = Dimensions.screenPaddingHorizontal,
                    bottom = Dimensions.spacing3xl
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingMd)
        ) {
            item {
                WgConfigOverviewCard(
                    name = config?.getName().orEmpty(),
                    status = statusText.ifEmpty {
                        stringResource(R.string.single_argument_parenthesis, configId.toString())
                    },
                    statusColor = statusColor
                )
            }

            if (wgType.isOneWg()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(Dimensions.cornerRadius2xl),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = stringResource(id = R.string.one_wg_apps_added),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        )
                    }
                }
            }

            item {
                SectionHeader(title = stringResource(id = R.string.lbl_configure))
                RethinkListGroup {
                    RethinkActionListItem(
                        title = stringResource(id = R.string.lbl_add),
                        description = stringResource(id = R.string.lbl_peer),
                        icon = Icons.Rounded.Add,
                        position = CardPosition.First,
                        onClick = { showAddPeerDialog = true }
                    )
                    RethinkActionListItem(
                        title = stringResource(id = R.string.rt_edit_dialog_positive),
                        description = stringResource(id = R.string.lbl_wireguard),
                        icon = Icons.Rounded.Edit,
                        position = CardPosition.Middle,
                        onClick = { onEditConfig(configId, wgType) }
                    )
                    RethinkActionListItem(
                        title = stringResource(id = R.string.lbl_delete),
                        description = stringResource(id = R.string.config_delete_dialog_title),
                        icon = Icons.Rounded.Delete,
                        accentColor = MaterialTheme.colorScheme.error,
                        position = CardPosition.Last,
                        onClick = { showDeleteInterfaceDialog = true }
                    )
                }
            }

            if (wgType.isDefault()) {
                item {
                    SectionHeader(title = stringResource(id = R.string.lbl_apps))
                    RethinkListGroup {
                        RethinkActionListItem(
                            title = stringResource(R.string.add_remove_apps, appsCount.toString()),
                            icon = Icons.Rounded.Apps,
                            position = CardPosition.First,
                            onClick = { openAppsDialog(config?.getName().orEmpty()) },
                            enabled = !catchAllEnabled
                        )
                        RethinkActionListItem(
                            title = stringResource(id = R.string.hop_add_remove_title),
                            icon = Icons.AutoMirrored.Rounded.ArrowForward,
                            position = CardPosition.Last,
                            onClick = { openHopDialog() }
                        )
                    }
                }
            }

            item {
                SectionHeader(title = stringResource(id = R.string.lbl_advanced))
                RethinkListGroup {
                    RethinkToggleListItem(
                        title = stringResource(id = R.string.catch_all_wg_dialog_title),
                        description = stringResource(id = R.string.catch_all_wg_dialog_desc),
                        iconRes = R.drawable.ic_firewall_shield,
                        checked = catchAllEnabled,
                        onCheckedChange = { enabled -> updateCatchAll(enabled) },
                        position = CardPosition.First,
                    )
                    RethinkToggleListItem(
                        title = stringResource(id = R.string.wg_setting_use_on_mobile),
                        description = stringResource(id = R.string.wg_setting_use_on_mobile_desc),
                        iconRes = R.drawable.ic_meter_mobile_only,
                        checked = useMobileEnabled,
                        onCheckedChange = { enabled -> updateUseOnMobileNetwork(enabled) },
                        position = if (SsidPermissionManager.isDeviceSupported(context)) CardPosition.Middle else CardPosition.Last,
                    )
                    if (SsidPermissionManager.isDeviceSupported(context)) {
                        val ssidSubtitle =
                            buildString {
                                append(
                                    stringResource(R.string.wg_setting_ssid_desc, lblSsidsText)
                                )
                                if (ssids.isNotEmpty()) {
                                    append("\n")
                                    append(ssids.joinToString { it.name })
                                }
                            }
                        RethinkToggleListItem(
                            title = stringResource(id = R.string.wg_setting_ssid_title),
                            description = ssidSubtitle,
                            iconRes = R.drawable.ic_firewall_wifi_on,
                            checked = ssidEnabled,
                            onCheckedChange = { enabled -> toggleSsid(enabled) },
                            position = CardPosition.Middle,
                        )
                        RethinkActionListItem(
                            title = stringResource(id = R.string.rt_edit_dialog_positive),
                            description = stringResource(id = R.string.lbl_ssids),
                            icon = Icons.Rounded.Edit,
                            position = CardPosition.Last,
                            onClick = { openSsidDialog() }
                        )
                    }
                }
            }

            item {
                SectionHeader(title = stringResource(id = R.string.lbl_peer))
            }

            items(peers) { peer ->
                WgPeerRow(
                    context = context,
                    configId = configId,
                    wgPeer = peer,
                    onPeerChanged = { scope.launch { refreshConfig() } }
                )
            }
        }
    }
}

@Composable
private fun WgConfigOverviewCard(name: String, status: String, statusColor: Int?) {
    WgCardSurface {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            WgIconBadge()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (statusColor != null) {
                Surface(
                    shape = RoundedCornerShape(Dimensions.cornerRadiusFull),
                    color = Color(statusColor).copy(alpha = 0.14f)
                ) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(statusColor),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

private suspend fun updateStatusUi(
    id: Int,
    persistentState: PersistentState,
    onSurfaceVariantColor: Int,
    errorColor: Int,
    tertiaryColor: Int,
    statusTextById: Map<Long, String>,
    statusFailingText: String,
    statusDisabledText: String,
    statusWaitingText: String,
    wireguardVersionTemplate: String,
    onStatusUpdate: (String, Int?) -> Unit
) {
    val mapping = WireguardManager.getConfigFilesById(id)
    val cid = ID_WG_BASE + id
    if (mapping?.isActive == true) {
        val statusPair = VpnController.getProxyStatusById(cid)
        val stats = VpnController.getProxyStats(cid)
        val ps = UIUtils.ProxyStatus.entries.find { it.id == statusPair.first }
        val dnsStatusId = if (persistentState.splitDns) {
            VpnController.getDnsStatus(cid)
        } else {
            null
        }
        withContext(Dispatchers.Main) {
            if (dnsStatusId != null && isDnsError(dnsStatusId)) {
                val text = statusFailingText
                val color = errorColor
                onStatusUpdate(text, color)
                return@withContext
            }
            val text =
                getStatusText(
                    statusTextById = statusTextById,
                    statusFailingText = statusFailingText,
                    statusWaitingText = statusWaitingText,
                    wireguardVersionTemplate = wireguardVersionTemplate,
                    status = ps,
                    handshakeTime = getHandshakeTime(stats).toString(),
                    stats = stats,
                    errMsg = statusPair.second
                )
            val color = getStrokeColorForStatus(ps, stats, onSurfaceVariantColor, errorColor, tertiaryColor)
            onStatusUpdate(text, color)
        }
    } else {
        withContext(Dispatchers.Main) {
            val text = statusDisabledText
            onStatusUpdate(text, null)
        }
    }
}

private fun isDnsError(statusId: Long?): Boolean {
    if (statusId == null) return true

    val s = Transaction.Status.fromId(statusId)
    return s == Transaction.Status.BAD_QUERY ||
            s == Transaction.Status.BAD_RESPONSE ||
            s == Transaction.Status.NO_RESPONSE ||
            s == Transaction.Status.SEND_FAIL ||
            s == Transaction.Status.CLIENT_ERROR ||
            s == Transaction.Status.INTERNAL_ERROR ||
            s == Transaction.Status.TRANSPORT_ERROR
}

private fun getStatusText(
    statusTextById: Map<Long, String>,
    statusFailingText: String,
    statusWaitingText: String,
    wireguardVersionTemplate: String,
    status: UIUtils.ProxyStatus?,
    handshakeTime: String? = null,
    stats: RouterStats?,
    errMsg: String? = null
): String {
    if (status == null) {
        val txt =
            if (!errMsg.isNullOrEmpty()) {
                "$statusWaitingText ($errMsg)"
            } else {
                statusWaitingText
            }
        return txt.replaceFirstChar(Char::titlecase)
    }

    if (status == UIUtils.ProxyStatus.TPU) {
        return statusTextById.getValue(status.id)
    }

    val now = System.currentTimeMillis()
    val lastOk = stats?.lastOK ?: 0L
    val since = stats?.since ?: 0L
    if (now - since > WG_UPTIME_THRESHOLD && lastOk == 0L) {
        return statusFailingText
    }

    val baseText = statusTextById.getValue(status.id)

    return if (stats?.lastOK != 0L && handshakeTime != null) {
        String.format(wireguardVersionTemplate, baseText, handshakeTime)
    } else {
        baseText
    }
}

private fun getHandshakeTime(stats: RouterStats?): CharSequence {
    if (stats == null) {
        return ""
    }
    if (stats.lastOK == 0L) {
        return ""
    }
    val now = System.currentTimeMillis()
    return DateUtils.getRelativeTimeSpanString(
        stats.lastOK,
        now,
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    )
}

private fun getStrokeColorForStatus(
    status: UIUtils.ProxyStatus?,
    stats: RouterStats?,
    onSurfaceVariantColor: Int,
    errorColor: Int,
    tertiaryColor: Int
): Int {
    val now = System.currentTimeMillis()
    val lastOk = stats?.lastOK ?: 0L
    val since = stats?.since ?: 0L
    val isFailing = now - since > WG_UPTIME_THRESHOLD && lastOk == 0L
    return when (status) {
        UIUtils.ProxyStatus.TOK ->
            if (isFailing) {
                onSurfaceVariantColor
            } else {
                tertiaryColor
            }

        UIUtils.ProxyStatus.TUP,
        UIUtils.ProxyStatus.TZZ,
        UIUtils.ProxyStatus.TNT -> onSurfaceVariantColor

        else -> errorColor
    }
}

private fun convertStringIdToId(id: String): Int {
    return try {
        id.removePrefix(ID_WG_BASE).toIntOrNull() ?: INVALID_CONF_ID
    } catch (_: Exception) {
        INVALID_CONF_ID
    }
}
