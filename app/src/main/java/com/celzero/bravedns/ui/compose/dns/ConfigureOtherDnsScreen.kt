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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.asFlow
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.DnsCryptRow
import com.celzero.bravedns.adapter.DnsProxyEndpointRow
import com.celzero.bravedns.adapter.DoHEndpointRow
import com.celzero.bravedns.adapter.DoTEndpointRow
import com.celzero.bravedns.adapter.ODoHEndpointRow
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DnsCryptEndpoint
import com.celzero.bravedns.database.DnsCryptRelayEndpoint
import com.celzero.bravedns.database.DnsProxyEndpoint
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.database.DoTEndpoint
import com.celzero.bravedns.database.ODoHEndpoint
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.DnsCryptEndpointViewModel
import com.celzero.bravedns.viewmodel.DnsCryptRelayEndpointViewModel
import com.celzero.bravedns.viewmodel.DnsProxyEndpointViewModel
import com.celzero.bravedns.viewmodel.DoHEndpointViewModel
import com.celzero.bravedns.viewmodel.DoTEndpointViewModel
import com.celzero.bravedns.viewmodel.ODoHEndpointViewModel
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.MalformedURLException
import java.net.URL

enum class DnsScreenType(val index: Int) {
    DOH(0),
    DNS_PROXY(1),
    DNS_CRYPT(2),
    DOT(3),
    ODOH(4);

    companion object {
        fun fromIndex(index: Int): DnsScreenType {
            return entries.find { it.index == index } ?: DOH
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigureOtherDnsScreen(
    dnsType: DnsScreenType,
    appConfig: AppConfig,
    persistentState: PersistentState,
    dohViewModel: DoHEndpointViewModel,
    dotViewModel: DoTEndpointViewModel,
    dnsProxyViewModel: DnsProxyEndpointViewModel,
    dnsCryptViewModel: DnsCryptEndpointViewModel,
    dnsCryptRelayViewModel: DnsCryptRelayEndpointViewModel,
    oDohViewModel: ODoHEndpointViewModel,
    onBackClick: () -> Unit
) {
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RethinkLargeTopBar(
                title = getDnsTypeName(dnsType),
                subtitle = getDnsTypeSubtitle(dnsType),
                onBackClick = onBackClick,
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Dimensions.screenPaddingHorizontal)
        ) {
            OtherDnsListContent(
                dnsType = dnsType,
                paddingValues = PaddingValues(
                    horizontal = 0.dp,
                    vertical = Dimensions.spacingXs
                ),
                appConfig = appConfig,
                persistentState = persistentState,
                dohViewModel = dohViewModel,
                dotViewModel = dotViewModel,
                dnsProxyViewModel = dnsProxyViewModel,
                dnsCryptViewModel = dnsCryptViewModel,
                dnsCryptRelayViewModel = dnsCryptRelayViewModel,
                oDohViewModel = oDohViewModel,
                scope = scope
            )
        }
    }
}

@Composable
private fun getDnsTypeName(type: DnsScreenType): String {
    return when (type) {
        DnsScreenType.DOH -> stringResource(R.string.other_dns_list_tab1)
        DnsScreenType.DNS_CRYPT -> stringResource(R.string.dc_dns_crypt)
        DnsScreenType.DNS_PROXY -> stringResource(R.string.other_dns_list_tab3)
        DnsScreenType.DOT -> stringResource(R.string.lbl_dot)
        DnsScreenType.ODOH -> stringResource(R.string.lbl_odoh)
    }
}

@Composable
private fun getDnsTypeSubtitle(type: DnsScreenType): String {
    return when (type) {
        DnsScreenType.DOH ->
            stringResource(R.string.cd_doh_dialog_resolver_url)
        DnsScreenType.DNS_CRYPT ->
            stringResource(R.string.cd_dns_crypt_dialog_stamp)
        DnsScreenType.DNS_PROXY ->
            stringResource(R.string.dns_proxy_ip_address)
        DnsScreenType.DOT ->
            stringResource(R.string.lbl_dot_abbr)
        DnsScreenType.ODOH ->
            stringResource(R.string.lbl_odoh_abbr)
    }
}

@Composable
private fun OtherDnsListContent(
    dnsType: DnsScreenType,
    paddingValues: PaddingValues,
    appConfig: AppConfig,
    persistentState: PersistentState,
    dohViewModel: DoHEndpointViewModel,
    dotViewModel: DoTEndpointViewModel,
    dnsProxyViewModel: DnsProxyEndpointViewModel,
    dnsCryptViewModel: DnsCryptEndpointViewModel,
    dnsCryptRelayViewModel: DnsCryptRelayEndpointViewModel,
    oDohViewModel: ODoHEndpointViewModel,
    scope: CoroutineScope
) {
    when (dnsType) {
        DnsScreenType.DOH -> DohListContent(paddingValues, appConfig, dohViewModel, scope)
        DnsScreenType.DNS_PROXY -> DnsProxyListContent(
            paddingValues,
            appConfig,
            persistentState,
            dnsProxyViewModel,
            scope
        )

        DnsScreenType.DNS_CRYPT -> DnsCryptListContent(
            paddingValues,
            appConfig,
            dnsCryptViewModel,
            dnsCryptRelayViewModel,
            scope
        )

        DnsScreenType.DOT -> DotListContent(paddingValues, appConfig, dotViewModel, scope)
        DnsScreenType.ODOH -> OdohListContent(paddingValues, appConfig, oDohViewModel, scope)
    }
}

@Composable
private fun <T : Any> DnsEndpointListWithFab(
    paddingValues: PaddingValues,
    items: LazyPagingItems<T>,
    onFabClick: () -> Unit,
    itemContent: @Composable (T) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = Dimensions.spacingXs, bottom = 84.dp),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingXs)
        ) {
            items(items.itemCount) { index ->
                val item = items[index] ?: return@items
                itemContent(item)
            }
        }
        ExtendedFloatingActionButton(
            onClick = onFabClick,
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.lbl_create)
            )
            Spacer(modifier = Modifier.width(Dimensions.spacingSm))
            Text(text = stringResource(R.string.lbl_create))
        }
    }
}

@Composable
private fun DohListContent(
    paddingValues: PaddingValues,
    appConfig: AppConfig,
    dohViewModel: DoHEndpointViewModel,
    scope: CoroutineScope
) {
    val context = LocalContext.current
    val items = dohViewModel.dohEndpointList.asFlow().collectAsLazyPagingItems()
    var showDialog by remember { mutableStateOf(false) }
    val heading = stringResource(R.string.cd_doh_dialog_heading)
    val nameLabel = stringResource(R.string.cd_doh_dialog_resolver_name)
    val urlLabel = stringResource(R.string.cd_doh_dialog_resolver_url)
    val defaultName = stringResource(R.string.cd_custom_doh_url_name_default)
    val checkboxLabel = stringResource(R.string.cd_doh_dialog_checkbox_desc)
    val invalidUrlMessage = stringResource(R.string.custom_url_error_invalid_url)

    DnsEndpointListWithFab(
        paddingValues = paddingValues,
        items = items,
        onFabClick = { showDialog = true }
    ) { endpoint ->
        DoHEndpointRow(endpoint, appConfig)
    }

    if (showDialog) {
            FullWidthDialog(onDismiss = { showDialog = false }) {
            val dohNameTemplate = stringResource(R.string.cd_custom_doh_url_name)
            CustomDohDialogContent(
                title = heading,
                nameLabel = nameLabel,
                urlLabel = urlLabel,
                defaultName = defaultName,
                initialUrl = "https://",
                checkboxLabel = checkboxLabel,
                loadNextIndex = { appConfig.getDohCount().plus(1) },
                nameForIndex = { index ->
                    String.format(dohNameTemplate, index.toString())
                },
                onSubmit = { name, url, isSecure ->
                    if (checkUrl(url)) {
                        scope.launch(Dispatchers.IO) {
                            insertDoHEndpoint(appConfig, name, url, isSecure)
                        }
                        showDialog = false
                        null
                    } else {
                        invalidUrlMessage
                    }
                },
                invalidUrlMessage = invalidUrlMessage,
                onDismiss = { showDialog = false }
            )
        }
    }
}

private suspend fun insertDoHEndpoint(appConfig: AppConfig, name: String, url: String, isSecure: Boolean) {
    var dohName = name
    if (name.isBlank()) {
        dohName = url
    }
    val doHEndpoint = DoHEndpoint(
        id = 0,
        dohName,
        url,
        dohExplanation = "",
        isSelected = false,
        isCustom = true,
        isSecure = isSecure,
        modifiedDataTime = 0,
        latency = 0
    )
    appConfig.insertDohEndpoint(doHEndpoint)
}

private fun checkUrl(url: String): Boolean {
    return try {
        val parsed = URL(url)
        parsed.protocol == "https" &&
                parsed.host.isNotEmpty() &&
                parsed.path.isNotEmpty() &&
                parsed.query == null &&
                parsed.ref == null
    } catch (e: MalformedURLException) {
        false
    }
}

@Composable
private fun DotListContent(
    paddingValues: PaddingValues,
    appConfig: AppConfig,
    dotViewModel: DoTEndpointViewModel,
    scope: CoroutineScope
) {
    val context = LocalContext.current
    val items = dotViewModel.dohEndpointList.asFlow().collectAsLazyPagingItems()
    var showDialog by remember { mutableStateOf(false) }
    val heading = stringResource(
        R.string.two_argument_space,
        stringResource(R.string.lbl_add).replaceFirstChar(Char::titlecase),
        stringResource(R.string.lbl_dot)
    )
    val nameLabel = stringResource(R.string.cd_doh_dialog_resolver_name)
    val urlLabel = stringResource(R.string.cd_doh_dialog_resolver_url)
    val dotName = stringResource(R.string.lbl_dot)
    val checkboxLabel = stringResource(R.string.cd_doh_dialog_checkbox_desc)

    DnsEndpointListWithFab(
        paddingValues = paddingValues,
        items = items,
        onFabClick = { showDialog = true }
    ) { endpoint ->
        DoTEndpointRow(endpoint, appConfig)
    }

    if (showDialog) {
        val title = heading
        FullWidthDialog(onDismiss = { showDialog = false }) {
            CustomDohDialogContent(
                title = title,
                nameLabel = nameLabel,
                urlLabel = urlLabel,
                defaultName = dotName,
                initialUrl = "",
                checkboxLabel = checkboxLabel,
                loadNextIndex = { appConfig.getDoTCount().plus(1) },
                nameForIndex = { index -> dotName + index.toString() },
                onSubmit = { name, url, isSecure ->
                    scope.launch(Dispatchers.IO) {
                        insertDotEndpoint(appConfig, name, url, isSecure)
                    }
                    showDialog = false
                    null
                },
                invalidUrlMessage = "",
                onDismiss = { showDialog = false }
            )
        }
    }
}

private suspend fun insertDotEndpoint(appConfig: AppConfig, name: String, url: String, isSecure: Boolean) {
    var dotName = name
    if (name.isBlank()) {
        dotName = url
    }
    val endpoint = DoTEndpoint(
        id = 0,
        dotName,
        url,
        desc = "",
        isSelected = false,
        isCustom = true,
        isSecure = isSecure,
        modifiedDataTime = 0,
        latency = 0
    )
    appConfig.insertDoTEndpoint(endpoint)
}

@Composable
private fun DnsProxyListContent(
    paddingValues: PaddingValues,
    appConfig: AppConfig,
    persistentState: PersistentState,
    dnsProxyViewModel: DnsProxyEndpointViewModel,
    scope: CoroutineScope
) {
    val context = LocalContext.current
    val items = dnsProxyViewModel.dnsProxyEndpointList.asFlow().collectAsLazyPagingItems()
    var showDialog by remember { mutableStateOf(false) }
    var appNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var nextIndex by remember { mutableStateOf(0) }
    val defaultAppName = stringResource(R.string.settings_app_list_default_app)

    DnsEndpointListWithFab(
        paddingValues = paddingValues,
        items = items,
        onFabClick = {
            scope.launch {
                val names = withContext(Dispatchers.IO) {
                    val list: MutableList<String> = ArrayList()
                    list.add(defaultAppName)
                    list.addAll(FirewallManager.getAllAppNamesSortedByVpnPermission(context))
                    list
                }
                appNames = names
                nextIndex = appConfig.getDnsProxyCount().plus(1)
                showDialog = true
            }
        }
    ) { endpoint ->
        DnsProxyEndpointRow(endpoint, appConfig)
    }

    if (showDialog && appNames.isNotEmpty()) {
        FullWidthDialog(onDismiss = { showDialog = false }) {
            DnsProxyDialogContent(
                appNames = appNames,
                nextIndex = nextIndex,
                appConfig = appConfig,
                persistentState = persistentState,
                scope = scope,
                onDismiss = { showDialog = false }
            )
        }
    }
}

@Composable
private fun DnsProxyDialogContent(
    appNames: List<String>,
    nextIndex: Int,
    appConfig: AppConfig,
    persistentState: PersistentState,
    scope: CoroutineScope,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var selectedAppIndex by remember { mutableStateOf(0) }
    var appMenuExpanded by remember { mutableStateOf(false) }
    val dnsProxyDefaultTemplate = stringResource(R.string.cd_custom_dns_proxy_name)
    val dnsProxyDefaultIp = stringResource(R.string.cd_custom_dns_proxy_default_ip)
    val modeInternal = stringResource(R.string.cd_dns_proxy_mode_internal)
    var proxyName by remember {
        mutableStateOf(String.format(dnsProxyDefaultTemplate, nextIndex.toString()))
    }
    var ipAddress by remember { mutableStateOf(dnsProxyDefaultIp) }
    var portText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var excludeAppsChecked by remember { mutableStateOf(!persistentState.excludeAppsInProxy) }
    val headerText = stringResource(R.string.dns_proxy_dialog_header_dns)
    val lockdownModeText = stringResource(R.string.settings_lock_down_mode_desc)
    val appText = stringResource(R.string.settings_dns_proxy_dialog_app)
    val dnsProxyNameLabel = stringResource(R.string.dns_proxy_name)
    val dnsProxyIpAddressLabel = stringResource(R.string.dns_proxy_ip_address)
    val dnsProxyPortLabel = stringResource(R.string.dns_proxy_port)
    val excludeHeading = stringResource(R.string.settings_exclude_proxy_apps_heading)
    val cancelText = stringResource(R.string.lbl_cancel)
    val addText = stringResource(R.string.lbl_add)
    val modeExternal = stringResource(R.string.cd_dns_proxy_mode_external)
    val errorText1 = stringResource(R.string.cd_dns_proxy_error_text_1)
    val errorText2 = stringResource(R.string.cd_dns_proxy_error_text_2)
    val errorText3 = stringResource(R.string.cd_dns_proxy_error_text_3)

    val lockdown = VpnController.isVpnLockdown()

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = headerText,
            style = MaterialTheme.typography.titleMedium
        )

        if (lockdown) {
            TextButton(onClick = { onDismiss(); UIUtils.openVpnProfile(context) }) {
                Text(text = lockdownModeText)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = appText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(0.3f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(0.7f)) {
                TextButton(onClick = { appMenuExpanded = true }) {
                    Text(text = appNames.getOrNull(selectedAppIndex) ?: "")
                }
                DropdownMenu(expanded = appMenuExpanded, onDismissRequest = { appMenuExpanded = false }) {
                    appNames.forEachIndexed { index, name ->
                        DropdownMenuItem(
                            text = { Text(text = name) },
                            onClick = {
                                selectedAppIndex = index
                                appMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = proxyName,
            onValueChange = { proxyName = it },
            label = { Text(text = dnsProxyNameLabel) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = ipAddress,
            onValueChange = { ipAddress = it },
            label = { Text(text = dnsProxyIpAddressLabel) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = portText,
            onValueChange = { portText = it },
            label = { Text(text = dnsProxyPortLabel) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        if (errorText.isNotBlank()) {
            Text(text = errorText, color = MaterialTheme.colorScheme.error)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = excludeHeading)
            Checkbox(
                checked = excludeAppsChecked,
                onCheckedChange = { if (!lockdown) excludeAppsChecked = it },
                enabled = !lockdown
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) {
                Text(text = cancelText)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val mode = modeExternal
                    val appName = appNames.getOrNull(selectedAppIndex).orEmpty()
                    val ipAddresses = ipAddress.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (ipAddresses.isEmpty()) {
                        errorText = errorText1
                        return@Button
                    }

                    val invalidIps = mutableListOf<String>()
                    val validIps = mutableListOf<String>()
                    for (ip in ipAddresses) {
                        if (IPAddressString(ip).isIPAddress) {
                            validIps.add(ip)
                        } else {
                            invalidIps.add(ip)
                        }
                    }

                    if (invalidIps.isNotEmpty()) {
                        errorText = errorText1 +
                                ": ${invalidIps.joinToString(", ")}"
                        return@Button
                    }

                    val port = portText.toIntOrNull()
                    if (port == null) {
                        errorText = errorText3
                        return@Button
                    }

                    var isPortValid = true
                    for (ip in validIps) {
                        if (Utilities.isLanIpv4(ip) && !Utilities.isValidLocalPort(port)) {
                            isPortValid = false
                            break
                        }
                    }

                    if (!isPortValid) {
                        errorText = errorText2
                        return@Button
                    }

                    val ipString = validIps.joinToString(",")
                    val isModeInternal = mode == modeInternal
                    scope.launch(Dispatchers.IO) {
                        insertDNSProxyEndpointDB(
                            context,
                            appConfig,
                            mode,
                            proxyName,
                            appName,
                            ipString,
                            port,
                            defaultApp = appNames.firstOrNull().orEmpty(),
                            isInternalMode = isModeInternal
                        )
                    }
                    persistentState.excludeAppsInProxy = !excludeAppsChecked
                    onDismiss()
                }
            ) {
                Text(text = addText)
            }
        }
    }
}

private suspend fun insertDNSProxyEndpointDB(
    context: android.content.Context,
    appConfig: AppConfig,
    mode: String,
    name: String,
    appName: String?,
    ip: String,
    port: Int,
    defaultApp: String,
    isInternalMode: Boolean
) {
    if (appName == null) return

    val packageName = if (appName == defaultApp) {
        ""
    } else {
        FirewallManager.getPackageNameByAppName(appName) ?: ""
    }
    var proxyName = name
    if (proxyName.isBlank()) {
        proxyName = if (isInternalMode) {
            appName
        } else ip
    }
    val endpoint = DnsProxyEndpoint(
        id = 0,
        proxyName,
        mode,
        packageName,
        ip,
        port,
        isSelected = false,
        isCustom = true,
        modifiedDataTime = 0L,
        latency = 0
    )
    appConfig.insertDnsproxyEndpoint(endpoint)
}

@Composable
private fun DnsCryptListContent(
    paddingValues: PaddingValues,
    appConfig: AppConfig,
    dnsCryptViewModel: DnsCryptEndpointViewModel,
    dnsCryptRelayViewModel: DnsCryptRelayEndpointViewModel,
    scope: CoroutineScope
) {
    val items = dnsCryptViewModel.dnsCryptEndpointList.asFlow().collectAsLazyPagingItems()
    var showDialog by remember { mutableStateOf(false) }
    var showRelaysDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(paddingValues),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.cd_dns_crypt_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Surface(
            shape = RoundedCornerShape(Dimensions.cornerRadiusXl),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            onClick = { showRelaysDialog = true }
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.cd_dnscrypt_relay_heading),
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = stringResource(R.string.lbl_configure),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        DnsEndpointListWithFab(
            paddingValues = PaddingValues(0.dp),
            items = items,
            onFabClick = { showDialog = true }
        ) { endpoint ->
            DnsCryptRow(endpoint, appConfig)
        }
    }

    if (showDialog) {
        FullWidthDialog(onDismiss = { showDialog = false }) {
            DnsCryptDialogContent(
                appConfig = appConfig,
                scope = scope,
                onDismiss = { showDialog = false }
            )
        }
    }

    if (showRelaysDialog) {
        com.celzero.bravedns.ui.dialog.DnsCryptRelaysDialog(
            appConfig = appConfig,
            relays = dnsCryptRelayViewModel.dnsCryptRelayEndpointList,
            onDismiss = { showRelaysDialog = false }
        )
    }
}

@Composable
private fun DnsCryptDialogContent(
    appConfig: AppConfig,
    scope: CoroutineScope,
    onDismiss: () -> Unit
) {
    var isServer by remember { mutableStateOf(true) }
    var dnscryptNextIndex by remember { mutableStateOf(0) }
    var relayNextIndex by remember { mutableStateOf(0) }
    val dnsCryptServerNameTemplate = stringResource(R.string.cd_dns_crypt_name)
    val dnsCryptRelayNameTemplate = stringResource(R.string.cd_dns_crypt_relay_name)
    val dnsCryptNameDefault = stringResource(R.string.cd_dns_crypt_name_default)
    var name by remember { mutableStateOf(dnsCryptNameDefault) }
    var url by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        dnscryptNextIndex = appConfig.getDnscryptCount().plus(1)
        relayNextIndex = appConfig.getDnscryptRelayCount().plus(1)
    }

    LaunchedEffect(isServer, dnscryptNextIndex, relayNextIndex) {
        name = if (isServer) {
            String.format(dnsCryptServerNameTemplate, dnscryptNextIndex.toString())
        } else {
            String.format(dnsCryptRelayNameTemplate, relayNextIndex.toString())
        }
    }
    val dialogHeading = stringResource(R.string.cd_dns_crypt_dialog_heading)
    val resolverHeading = stringResource(R.string.cd_dns_crypt_resolver_heading)
    val relayHeading = stringResource(R.string.cd_dns_crypt_relay_heading)
    val dialogNameLabel = stringResource(R.string.cd_dns_crypt_dialog_name)
    val dialogStampLabel = stringResource(R.string.cd_dns_crypt_dialog_stamp)
    val dialogDescLabel = stringResource(R.string.cd_dns_crypt_dialog_desc)
    val cancelText = stringResource(R.string.lbl_cancel)
    val addText = stringResource(R.string.lbl_add)
    val invalidUrlError = stringResource(R.string.custom_url_error_invalid_url)

    Column(
        modifier = Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = dialogHeading,
            style = MaterialTheme.typography.titleMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { isServer = true }) {
                Text(text = resolverHeading)
            }
            Spacer(modifier = Modifier.width(10.dp))
            TextButton(onClick = { isServer = false }) {
                Text(text = relayHeading)
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(text = dialogNameLabel) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(text = dialogStampLabel) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = desc,
            onValueChange = { desc = it },
            label = { Text(text = dialogDescLabel) },
            modifier = Modifier.fillMaxWidth()
        )

        if (errorText.isNotBlank()) {
            Text(text = errorText, color = MaterialTheme.colorScheme.error)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) {
                Text(text = cancelText)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (name.isBlank() || url.isBlank()) {
                        errorText = invalidUrlError
                        return@Button
                    }

                    if (isServer) {
                        scope.launch(Dispatchers.IO) {
                            insertDnsCrypt(appConfig, name, url, desc)
                        }
                    } else {
                        scope.launch(Dispatchers.IO) {
                            insertDnsCryptRelay(appConfig, name, url, desc)
                        }
                    }
                    onDismiss()
                }
            ) {
                Text(text = addText)
            }
        }
    }
}

private suspend fun insertDnsCrypt(appConfig: AppConfig, name: String, url: String, desc: String) {
    var dnscryptName = name
    if (name.isBlank()) {
        dnscryptName = url
    }
    val endpoint = DnsCryptEndpoint(
        id = 0,
        dnscryptName,
        url,
        desc,
        isSelected = false,
        isCustom = true,
        modifiedDataTime = 0,
        latency = 0
    )
    appConfig.insertDnscryptEndpoint(endpoint)
}

private suspend fun insertDnsCryptRelay(appConfig: AppConfig, name: String, url: String, desc: String) {
    var relayName = name
    if (name.isBlank()) {
        relayName = url
    }
    val endpoint = DnsCryptRelayEndpoint(
        id = 0,
        relayName,
        url,
        desc,
        isSelected = false,
        isCustom = true,
        modifiedDataTime = 0,
        latency = 0
    )
    appConfig.insertDnscryptRelayEndpoint(endpoint)
}

@Composable
private fun OdohListContent(
    paddingValues: PaddingValues,
    appConfig: AppConfig,
    oDohViewModel: ODoHEndpointViewModel,
    scope: CoroutineScope
) {
    val context = LocalContext.current
    val items = oDohViewModel.dohEndpointList.asFlow().collectAsLazyPagingItems()
    var showDialog by remember { mutableStateOf(false) }
    val title = stringResource(
        R.string.two_argument_space,
        stringResource(R.string.lbl_add).replaceFirstChar(Char::uppercase),
        stringResource(R.string.lbl_odoh)
    )
    val nameLabel = stringResource(R.string.cd_doh_dialog_resolver_name)
    val proxyLabel = stringResource(R.string.settings_proxy_header) + stringResource(R.string.lbl_optional)
    val resolverLabel = stringResource(R.string.cd_doh_dialog_resolver_url)
    val defaultName = stringResource(R.string.lbl_odoh)
    val invalidUrlMessage = stringResource(R.string.custom_url_error_invalid_url)

    DnsEndpointListWithFab(
        paddingValues = paddingValues,
        items = items,
        onFabClick = { showDialog = true }
    ) { endpoint ->
        ODoHEndpointRow(endpoint, appConfig)
    }

    if (showDialog) {
        FullWidthDialog(onDismiss = { showDialog = false }) {
            CustomOdohDialogContent(
                title = title,
                nameLabel = nameLabel,
                proxyLabel = proxyLabel,
                resolverLabel = resolverLabel,
                defaultName = defaultName,
                initialResolver = "https://",
                loadNextIndex = { appConfig.getODoHCount().plus(1) },
                invalidUrlMessage = invalidUrlMessage,
                onSubmit = { name, proxy, resolver ->
                    if (checkUrl(resolver)) {
                        scope.launch(Dispatchers.IO) {
                            insertOdoh(appConfig, name, proxy, resolver)
                        }
                        showDialog = false
                        null
                    } else {
                        invalidUrlMessage
                    }
                },
                onDismiss = { showDialog = false }
            )
        }
    }
}

@Composable
private fun FullWidthDialog(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp
        ) {
            content()
        }
    }
}

@Composable
private fun CustomDohDialogContent(
    title: String,
    nameLabel: String,
    urlLabel: String,
    defaultName: String,
    initialUrl: String,
    checkboxLabel: String,
    loadNextIndex: suspend () -> Int,
    nameForIndex: (Int) -> String,
    onSubmit: (String, String, Boolean) -> String?,
    invalidUrlMessage: String,
    onDismiss: () -> Unit
    ) {
    var name by remember { mutableStateOf(defaultName) }
    var url by remember { mutableStateOf(initialUrl) }
    var insecureChecked by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf("") }
    val cancelText = stringResource(R.string.lbl_cancel)
    val addText = stringResource(R.string.lbl_add)

    LaunchedEffect(Unit) {
        val nextIndex = withContext(Dispatchers.IO) { loadNextIndex() }
        name = nameForIndex(nextIndex)
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(text = nameLabel) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(text = urlLabel) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = insecureChecked, onCheckedChange = { insecureChecked = it })
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = checkboxLabel)
        }
        if (errorText.isNotBlank()) {
            Text(text = errorText, color = MaterialTheme.colorScheme.error)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) {
                Text(text = cancelText)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val isSecure = !insecureChecked
                    val error = onSubmit(name, url, isSecure)
                    if (error != null) {
                        errorText = error.ifBlank { invalidUrlMessage }
                    }
                }
            ) {
                Text(text = addText)
            }
        }
    }
}

@Composable
private fun CustomOdohDialogContent(
    title: String,
    nameLabel: String,
    proxyLabel: String,
    resolverLabel: String,
    defaultName: String,
    initialResolver: String,
    loadNextIndex: suspend () -> Int,
    invalidUrlMessage: String,
    onSubmit: (String, String, String) -> String?,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(defaultName) }
    var proxy by remember { mutableStateOf("") }
    var resolver by remember { mutableStateOf(initialResolver) }
    var errorText by remember { mutableStateOf("") }
    val cancelText = stringResource(R.string.lbl_cancel)
    val addText = stringResource(R.string.lbl_add)

    LaunchedEffect(Unit) {
        val nextIndex = withContext(Dispatchers.IO) { loadNextIndex() }
        name = defaultName + nextIndex.toString()
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(text = nameLabel) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = proxy,
            onValueChange = { proxy = it },
            label = { Text(text = proxyLabel) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = resolver,
            onValueChange = { resolver = it },
            label = { Text(text = resolverLabel) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        if (errorText.isNotBlank()) {
            Text(text = errorText, color = MaterialTheme.colorScheme.error)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) {
                Text(text = cancelText)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val error = onSubmit(name, proxy, resolver)
                    if (error != null) {
                        errorText = error.ifBlank { invalidUrlMessage }
                    }
                }
            ) {
                Text(text = addText)
            }
        }
    }
}

private suspend fun insertOdoh(appConfig: AppConfig, name: String, proxy: String, resolver: String) {
    var odohName = name
    if (name.isBlank()) {
        odohName = resolver
    }
    val endpoint = ODoHEndpoint(
        id = 0,
        odohName,
        proxy,
        resolver,
        proxyIps = "",
        desc = "",
        isSelected = false,
        isCustom = true,
        modifiedDataTime = 0,
        latency = 0
    )
    appConfig.insertODoHEndpoint(endpoint)
}
