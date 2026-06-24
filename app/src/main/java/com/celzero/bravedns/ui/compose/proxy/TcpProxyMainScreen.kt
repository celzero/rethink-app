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
package com.celzero.bravedns.ui.compose.proxy

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.asFlow
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.TcpProxyHelper
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkAnimatedSection
import com.celzero.bravedns.ui.compose.theme.RethinkListItem
import com.celzero.bravedns.ui.compose.theme.RethinkTopBarLazyColumnScreen
import com.celzero.bravedns.ui.compose.theme.SectionHeaderWithSubtitle
import com.celzero.bravedns.ui.compose.theme.cardPositionFor
import com.celzero.bravedns.ui.dialog.WgIncludeAppsDialog
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TcpProxyMainScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TcpProxyMainScreen(
    appConfig: AppConfig,
    mappingViewModel: ProxyAppsMappingViewModel,
    onBackClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tcpProxySwitchChecked by remember { mutableStateOf(false) }
    var tcpProxyStatus by remember { mutableStateOf("") }
    var tcpProxyDesc by remember { mutableStateOf("") }
    var tcpErrorVisible by remember { mutableStateOf(false) }
    var tcpErrorText by remember { mutableStateOf("") }
    var enableUdpRelayChecked by remember { mutableStateOf(false) }
    var warpSwitchChecked by remember { mutableStateOf(false) }
    var showIncludeAppsDialog by remember { mutableStateOf(false) }
    var includeAppsProxyId by remember { mutableStateOf("") }
    var includeAppsProxyName by remember { mutableStateOf("") }
    val tcpProxyDefaultDesc = stringResource(R.string.settings_https_desc)
    val tcpProxyWarpActiveError = stringResource(R.string.tcp_proxy_warp_active_error)
    val tcpProxyNoAppsError = stringResource(R.string.tcp_proxy_no_apps_error)
    val tcpProxyDisabledDescription = stringResource(R.string.settings_https_desc)
    val tcpProxyDisabledErrorTemplate = stringResource(R.string.settings_https_disabled_error)
    val activeText = stringResource(R.string.lbl_active)
    val inactiveText = stringResource(R.string.lbl_inactive)
    val udpExperimentalDesc = stringResource(R.string.adv_set_experimental_desc)
    val appsText = stringResource(R.string.lbl_apps)

    val appCount by mappingViewModel.getAppCountById(ProxyManager.ID_TCP_BASE)
        .asFlow()
        .collectAsState(initial = 0)
    val appListSubtitleText = stringResource(R.string.add_remove_apps, appCount.toString())

    LaunchedEffect(Unit) {
        tcpProxyDesc = tcpProxyDefaultDesc
    }

    LaunchedEffect(Unit) {
        displayTcpProxyStatus(
            onStatusUpdate = { status, switchChecked, errorVisible, errorText ->
                tcpProxyStatus = status
                tcpProxySwitchChecked = switchChecked
                tcpErrorVisible = errorVisible
                tcpErrorText = errorText
            }
        )
    }

    fun onTcpProxySwitchChanged(checked: Boolean) {
        tcpProxySwitchChecked = checked
        scope.launch(Dispatchers.IO) {
            val isWarpActive = warpSwitchChecked
            withContext(Dispatchers.Main) {
                if (checked && isWarpActive) {
                    tcpProxySwitchChecked = false
                    Utilities.showToastUiCentered(
                        context,
                        tcpProxyWarpActiveError,
                        Toast.LENGTH_SHORT
                    )
                    return@withContext
                }

                val apps = ProxyManager.isAnyAppSelected(ProxyManager.ID_TCP_BASE)

                if (!apps) {
                    Utilities.showToastUiCentered(
                        context,
                        tcpProxyNoAppsError,
                        Toast.LENGTH_SHORT
                    )
                    warpSwitchChecked = false
                    tcpProxySwitchChecked = false
                    return@withContext
                }

                if (!checked) {
                    scope.launch(Dispatchers.IO) { TcpProxyHelper.disable() }
                    tcpProxyDesc = tcpProxyDisabledDescription
                    return@withContext
                }

                if (appConfig.getBraveMode().isDnsMode()) {
                    tcpProxySwitchChecked = false
                    return@withContext
                }

                if (!appConfig.canEnableTcpProxy()) {
                    val provider = appConfig.getProxyProvider().lowercase().replaceFirstChar(Char::titlecase)
                    Utilities.showToastUiCentered(
                        context,
                        String.format(tcpProxyDisabledErrorTemplate, provider),
                        Toast.LENGTH_SHORT
                    )
                    tcpProxySwitchChecked = false
                    return@withContext
                }

                scope.launch(Dispatchers.IO) { TcpProxyHelper.enable() }
            }
        }
    }

    fun openAppsDialog() {
        includeAppsProxyId = ProxyManager.ID_TCP_BASE
        includeAppsProxyName = ProxyManager.TCP_PROXY_NAME
        showIncludeAppsDialog = true
    }

    if (showIncludeAppsDialog) {
        WgIncludeAppsDialog(
            viewModel = mappingViewModel,
            proxyId = includeAppsProxyId,
            proxyName = includeAppsProxyName,
            onDismiss = { showIncludeAppsDialog = false }
        )
    }

    val listState = rememberLazyListState()
    val subtitle = if (tcpProxySwitchChecked) activeText else inactiveText

    RethinkTopBarLazyColumnScreen(
        title = stringResource(id = R.string.settings_https_heading),
        subtitle = subtitle,
        onBackClick = onBackClick,
        containerColor = MaterialTheme.colorScheme.surface,
        listState = listState,
        contentPadding = PaddingValues(
            start = Dimensions.screenPaddingHorizontal,
            end = Dimensions.screenPaddingHorizontal,
            top = Dimensions.spacingMd,
            bottom = Dimensions.spacing3xl
        )
    ) {
            item {
                RethinkAnimatedSection(index = 0) {
                    SectionHeaderWithSubtitle(
                        title = stringResource(id = R.string.tcp_proxy_rethink_proxy_title),
                        subtitle = stringResource(id = R.string.settings_https_desc)
                    )
                    Column {
                        val entries = 3
                        RethinkListItem(
                            headline = stringResource(id = R.string.tcp_proxy_rethink_proxy_title),
                            supporting = if (tcpErrorVisible) tcpErrorText else tcpProxyStatus.ifEmpty { tcpProxyDesc },
                            leadingIcon = Icons.Rounded.VpnKey,
                            position = cardPositionFor(index = 0, lastIndex = entries - 1),
                            onClick = { onTcpProxySwitchChanged(!tcpProxySwitchChecked) },
                            trailing = {
                                Switch(
                                    checked = tcpProxySwitchChecked,
                                    onCheckedChange = { onTcpProxySwitchChanged(it) }
                                )
                            }
                        )

                        RethinkListItem(
                            headline = stringResource(id = R.string.tcp_proxy_enable_udp_relay),
                            supporting = udpExperimentalDesc,
                            leadingIcon = Icons.Rounded.Settings,
                            position = cardPositionFor(index = 1, lastIndex = entries - 1),
                            onClick = { enableUdpRelayChecked = !enableUdpRelayChecked },
                            trailing = {
                                Switch(
                                    checked = enableUdpRelayChecked,
                                    onCheckedChange = { enableUdpRelayChecked = it }
                                )
                            }
                        )

                        RethinkListItem(
                            headline = appsText,
                            supporting = appListSubtitleText,
                            leadingIcon = Icons.Rounded.Apps,
                            position = cardPositionFor(index = 2, lastIndex = entries - 1),
                            onClick = { openAppsDialog() }
                        )
                    }
                }
            }

            item {
                RethinkAnimatedSection(index = 1) {
                    SectionHeaderWithSubtitle(
                        title = stringResource(id = R.string.tcp_proxy_cloudflare_warp_title),
                        subtitle = stringResource(id = R.string.tcp_proxy_cloudflare_warp_desc)
                    )
                    RethinkListItem(
                        headline = stringResource(id = R.string.tcp_proxy_cloudflare_warp_title),
                        supporting = stringResource(id = R.string.tcp_proxy_cloudflare_warp_desc),
                        leadingIcon = Icons.Rounded.VpnKey,
                        position = cardPositionFor(index = 0, lastIndex = 0),
                        onClick = { warpSwitchChecked = !warpSwitchChecked },
                        trailing = {
                            Switch(
                                checked = warpSwitchChecked,
                                onCheckedChange = { warpSwitchChecked = it }
                            )
                        }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(Dimensions.spacingSm))
            }
        }
}

private suspend fun displayTcpProxyStatus(
    onStatusUpdate: (status: String, switchChecked: Boolean, errorVisible: Boolean, errorText: String) -> Unit
) {
    withContext(Dispatchers.IO) {
        val tcpProxies = TcpProxyHelper.getActiveTcpProxy()
        withContext(Dispatchers.Main) {
            if (tcpProxies == null || !tcpProxies.isActive) {
                onStatusUpdate("Not active", false, true, "Something went wrong")
                return@withContext
            }

            Napier.i("$TAG displayTcpProxyUi: ${tcpProxies.name}, ${tcpProxies.url}")
            onStatusUpdate("Active", true, false, "")
        }
    }
}
