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
package com.celzero.bravedns.ui.compose.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.Drawable
import android.text.format.DateUtils
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.net.toUri
import androidx.lifecycle.asFlow
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import com.celzero.bravedns.ui.compose.theme.RethinkListGroup
import com.celzero.bravedns.ui.compose.theme.RethinkListItem
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.RethinkMultiActionDialog
import com.celzero.bravedns.ui.compose.theme.RethinkToggleListItem
import com.celzero.bravedns.ui.compose.theme.SectionHeader
import com.celzero.bravedns.ui.compose.theme.CardPosition
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.rememberDrawablePainter
import androidx.compose.ui.res.painterResource
import com.celzero.bravedns.util.OrbotHelper
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getIcon
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.RouterStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private const val REFRESH_TIMEOUT_MS = 4000L

private data class ProxyScreenState(
    val canEnableProxy: Boolean,
    val socks5Enabled: Boolean,
    val httpEnabled: Boolean,
    val orbotEnabled: Boolean,
    val wireguardDescription: String,
    val socks5Description: String,
    val httpDescription: String,
    val orbotDescription: String
)

private data class Socks5DialogState(
    val host: String,
    val port: String,
    val username: String,
    val password: String,
    val selectedAppPackage: String,
    val appOptions: List<ProxyDialogAppOption>,
    val udpBlocked: Boolean,
    val includeProxyApps: Boolean,
    val lockdown: Boolean,
    val error: String? = null
)

private data class HttpDialogState(
    val host: String,
    val selectedAppPackage: String,
    val appOptions: List<ProxyDialogAppOption>,
    val includeProxyApps: Boolean,
    val lockdown: Boolean,
    val error: String? = null
)

private data class ProxyDialogAppOption(
    val packageName: String,
    val label: String,
    val iconLookupName: String = label
)

private object ProxyDialogAppIconCache {
    private const val CACHE_SIZE = 192
    private val cache = LruCache<String, Drawable>(CACHE_SIZE)

    fun get(key: String): Drawable? = cache.get(key)

    fun put(key: String, icon: Drawable?) {
        if (key.isBlank() || icon == null) return
        cache.put(key, icon)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxySettingsScreen(
    appConfig: AppConfig,
    persistentState: PersistentState,
    eventLogger: EventLogger,
    mappingViewModel: ProxyAppsMappingViewModel? = null,
    initialFocusKey: String? = null,
    onWireguardClick: (() -> Unit)? = null,
    onOpenOrbotApps: (() -> Unit)? = null,
    onNavigateToDns: (() -> Unit)? = null,
    onBackClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val providerName =
        persistentState.proxyProvider.lowercase().replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
    val lockDownProxyDesc = stringResource(R.string.settings_lock_down_proxy_desc)
    val refreshToast = stringResource(R.string.dc_refresh_toast)
    val socks5VpnDisabledError = stringResource(R.string.settings_socks5_vpn_disabled_error)
    val socks5DisabledError = stringResource(R.string.settings_socks5_disabled_error, providerName)
    val httpDisabledError = stringResource(R.string.settings_https_disabled_error, providerName)
    val orbotDisabledError = stringResource(R.string.settings_orbot_disabled_error)
    val orbotInstallError = stringResource(R.string.orbot_install_activity_error)
    val orbotWebsiteLink = stringResource(R.string.orbot_website_link)
    val orbotNoAppToast = stringResource(R.string.orbot_no_app_toast)
    val httpProxyHostEmptyError = stringResource(R.string.settings_http_proxy_error_text3)
    val httpProxyInvalidPortError = stringResource(R.string.settings_http_proxy_error_text2)
    val httpProxyRangePortError = stringResource(R.string.settings_http_proxy_error_text1)
    val httpProxyToastSuccess = stringResource(R.string.settings_http_proxy_toast_success)
    val defaultAppLabel = stringResource(R.string.settings_app_list_default_app)
    val orbotStopTitle = stringResource(R.string.orbot_stop_dialog_title)
    val orbotStopMessage = stringResource(R.string.orbot_stop_dialog_message)
    val orbotStopDnsMessage = stringResource(R.string.orbot_stop_dialog_dns_message)
    val orbotStopMessageCombo =
        stringResource(
            R.string.orbot_stop_dialog_message_combo,
            orbotStopMessage,
            orbotStopDnsMessage
        )
    val defaultWireguardDescription = stringResource(R.string.wireguard_description)
    val defaultSocks5Description = stringResource(R.string.settings_socks_forwarding_default_desc)
    val defaultHttpDescription = stringResource(R.string.settings_https_desc)
    val defaultOrbotDescription = stringResource(R.string.orbot_bs_status_4)
    val defaultSocks5NoAppTemplate = stringResource(R.string.settings_socks_forwarding_desc_no_app)
    val defaultSocks5WithAppTemplate = stringResource(R.string.settings_socks_forwarding_desc)
    val httpProxyDescriptionTemplate = stringResource(R.string.settings_http_proxy_desc)
    val orbotStatus2Description = stringResource(R.string.orbot_bs_status_2)
    val orbotStatus1Template = stringResource(R.string.orbot_bs_status_1)
    val orbotStatus3Template = stringResource(R.string.orbot_bs_status_3)
    val orbotStatusArgDns = stringResource(R.string.orbot_status_arg_3)
    val orbotStatusArgProxy = stringResource(R.string.orbot_status_arg_2)
    val wireguardStatusFailingText = stringResource(R.string.status_failing).replaceFirstChar(Char::titlecase)
    val wireguardStatusWaitingText = stringResource(R.string.status_waiting)
    val wireguardVersionTemplate = stringResource(R.string.about_version_install_source)
    val wireguardIpLabelTemplate = stringResource(R.string.ci_ip_label)
    val proxyStatusLabelById = mutableMapOf<Long, String>().apply {
        for (status in UIUtils.ProxyStatus.entries) {
            put(
                status.id,
                stringResource(UIUtils.getProxyStatusStringRes(status.id)).replaceFirstChar(Char::titlecase)
            )
        }
    }

    val orbotHelper = remember(context, persistentState, appConfig) {
        OrbotHelper(context, persistentState, appConfig)
    }
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val initialFocus = initialFocusKey?.trim().orEmpty()
    var pendingFocusKey by rememberSaveable(initialFocus) { mutableStateOf(initialFocus) }
    var activeFocusKey by rememberSaveable(initialFocus) {
        mutableStateOf(initialFocus.ifBlank { null })
    }

    var refreshTick by remember { mutableIntStateOf(0) }
    var isRefreshing by remember { mutableStateOf(false) }

    var canEnableProxy by remember { mutableStateOf(appConfig.canEnableProxy()) }
    var socks5Enabled by remember { mutableStateOf(appConfig.isCustomSocks5Enabled()) }
    var httpEnabled by remember { mutableStateOf(appConfig.isCustomHttpProxyEnabled()) }
    var orbotEnabled by remember { mutableStateOf(appConfig.isOrbotProxyEnabled()) }
    val orbotConnecting =
        remember { persistentState.orbotConnectionStatus.asFlow() }.collectAsState(initial = false).value

    var wireguardDescription by remember(defaultWireguardDescription) { mutableStateOf(defaultWireguardDescription) }
    var socks5Description by remember(defaultSocks5Description) { mutableStateOf(defaultSocks5Description) }
    var httpDescription by remember(defaultHttpDescription) { mutableStateOf(defaultHttpDescription) }
    var orbotDescription by remember(defaultOrbotDescription) { mutableStateOf(defaultOrbotDescription) }

    var showOrbotInstallDialog by remember { mutableStateOf(false) }
    var showOrbotModeDialog by remember { mutableStateOf(false) }
    var showOrbotStopDialog by remember { mutableStateOf(false) }
    var showOrbotInfoDialog by remember { mutableStateOf(false) }
    var orbotStopHasDnsHint by remember { mutableStateOf(false) }
    var selectedOrbotMode by remember { mutableStateOf(AppConfig.ProxyType.SOCKS5.name) }

    var socks5DialogState by remember { mutableStateOf<Socks5DialogState?>(null) }
    var httpDialogState by remember { mutableStateOf<HttpDialogState?>(null) }
    val orbotAppCount =
        if (mappingViewModel != null) {
            mappingViewModel.getAppCountById(ProxyManager.ID_ORBOT_BASE)
                .asFlow()
                .collectAsState(initial = 0)
                .value
        } else {
            0
        }

    fun logEvent(details: String) {
        eventLogger.log(
            type = EventType.UI_SETTING_CHANGED,
            severity = Severity.LOW,
            message = "Proxy settings",
            source = EventSource.UI,
            userAction = true,
            details = details
        )
    }

    fun reloadUi() {
        refreshTick++
    }

    fun showProxyDisabledToast() {
        Utilities.showToastUiCentered(
            context,
            lockDownProxyDesc,
            Toast.LENGTH_SHORT
        )
    }

    fun refreshWireguard() {
        if (isRefreshing) return
        scope.launch {
            isRefreshing = true
            withContext(Dispatchers.IO) {
                VpnController.refreshOrPauseOrResumeOrReAddProxies()
            }
            delay(REFRESH_TIMEOUT_MS)
            Utilities.showToastUiCentered(
                context,
                refreshToast,
                Toast.LENGTH_SHORT
            )
            isRefreshing = false
            reloadUi()
        }
    }

    fun openSocksDialog() {
        scope.launch {
            socks5DialogState =
                withContext(Dispatchers.IO) {
                    buildSocks5DialogState(
                        context = context,
                        appConfig = appConfig,
                        persistentState = persistentState,
                        defaultApp = defaultAppLabel
                    )
                }
        }
    }

    fun openHttpDialog() {
        scope.launch {
            httpDialogState =
                withContext(Dispatchers.IO) {
                    buildHttpDialogState(
                        context = context,
                        appConfig = appConfig,
                        persistentState = persistentState,
                        defaultApp = defaultAppLabel
                    )
                }
        }
    }

    fun tryOpenCustomProxyDialog(
        canEnableSpecificProxy: () -> Boolean,
        disabledError: String,
        openDialog: () -> Unit
    ) {
        if (!canEnableProxy) {
            showProxyDisabledToast()
            reloadUi()
            return
        }

        if (appConfig.getBraveMode().isDnsMode()) {
            Utilities.showToastUiCentered(context, socks5VpnDisabledError, Toast.LENGTH_SHORT)
            reloadUi()
            return
        }

        if (!canEnableSpecificProxy()) {
            Utilities.showToastUiCentered(context, disabledError, Toast.LENGTH_SHORT)
            reloadUi()
            return
        }

        openDialog()
    }

    fun disableCustomProxy(type: AppConfig.ProxyType, logMessage: String) {
        scope.launch {
            withContext(Dispatchers.IO) {
                appConfig.removeProxy(type, AppConfig.ProxyProvider.CUSTOM)
            }
            logEvent(logMessage)
            reloadUi()
        }
    }

    fun enableOrbotFlow() {
        scope.launch {
            if (!canEnableProxy) {
                showProxyDisabledToast()
                reloadUi()
                return@launch
            }

            val isInstalled = withContext(Dispatchers.IO) { FirewallManager.isOrbotInstalled() }
            if (!isInstalled) {
                showOrbotInstallDialog = true
                return@launch
            }

            if (!appConfig.canEnableOrbotProxy()) {
                val msg =
                    if (providerName.equals(AppConfig.ProxyProvider.CUSTOM.name, ignoreCase = true)) {
                        orbotDisabledError
                    } else {
                        socks5DisabledError
                    }
                Utilities.showToastUiCentered(context, msg, Toast.LENGTH_SHORT)
                reloadUi()
                return@launch
            }

            if (!VpnController.hasTunnel()) {
                Utilities.showToastUiCentered(
                    context,
                    socks5VpnDisabledError,
                    Toast.LENGTH_SHORT
                )
                reloadUi()
                return@launch
            }

            selectedOrbotMode =
                if (orbotEnabled) appConfig.getProxyType() else AppConfig.ProxyType.SOCKS5.name
            showOrbotModeDialog = true
        }
    }

    fun stopOrbotForwarding(showDialog: Boolean) {
        scope.launch {
            val hasDnsHint =
                withContext(Dispatchers.IO) {
                    val isOrbotDns = appConfig.isOrbotDns()
                    appConfig.removeAllProxies()
                    orbotHelper.stopOrbot(isInteractive = true)
                    isOrbotDns
                }
            if (showDialog) {
                orbotStopHasDnsHint = hasDnsHint
                showOrbotStopDialog = true
            }
            logEvent("Orbot proxy disabled")
            reloadUi()
        }
    }

    LaunchedEffect(refreshTick) {
        val state =
            withContext(Dispatchers.IO) {
                buildProxyScreenState(
                    context = context,
                    appConfig = appConfig,
                    defaultSocks5Description = defaultSocks5Description,
                    defaultHttpDescription = defaultHttpDescription,
                    httpProxyDescriptionTemplate = httpProxyDescriptionTemplate,
                    defaultSocks5DescriptionNoApp = defaultSocks5NoAppTemplate,
                    defaultSocks5DescriptionWithApp = defaultSocks5WithAppTemplate,
                    orbotDisabledDescription = defaultOrbotDescription,
                    orbotStatus2Description = orbotStatus2Description,
                    orbotStatus1Template = orbotStatus1Template,
                    orbotStatus3Template = orbotStatus3Template,
                    orbotStatusArgDns = orbotStatusArgDns,
                    orbotStatusArgProxy = orbotStatusArgProxy,
                    defaultWireguardDescription = defaultWireguardDescription,
                    statusTextById = proxyStatusLabelById,
                    statusFailingText = wireguardStatusFailingText,
                    statusWaitingText = wireguardStatusWaitingText,
                    wireguardVersionTemplate = wireguardVersionTemplate,
                    ciIpLabelTemplate = wireguardIpLabelTemplate
                )
            }

        canEnableProxy = state.canEnableProxy
        socks5Enabled = state.socks5Enabled
        httpEnabled = state.httpEnabled
        orbotEnabled = state.orbotEnabled
        wireguardDescription = state.wireguardDescription
        socks5Description = state.socks5Description
        httpDescription = state.httpDescription
        orbotDescription = state.orbotDescription
    }

    LaunchedEffect(pendingFocusKey, canEnableProxy, onWireguardClick != null) {
        val key = pendingFocusKey.trim()
        if (key.isBlank()) return@LaunchedEffect
        activeFocusKey = key

        val showWarning = !canEnableProxy
        var index = 0
        if (showWarning && key == "proxy_warning") {
            listState.animateScrollToItem(0)
            delay(900)
            if (activeFocusKey == key) {
                activeFocusKey = null
            }
            pendingFocusKey = ""
            return@LaunchedEffect
        }
        if (showWarning) index++

        val hasWireguard = onWireguardClick != null
        if (hasWireguard && key == "proxy_wireguard") {
            listState.animateScrollToItem(index)
            delay(900)
            if (activeFocusKey == key) {
                activeFocusKey = null
            }
            pendingFocusKey = ""
            return@LaunchedEffect
        }
        if (hasWireguard) index++

        if (key == "proxy_socks") {
            listState.animateScrollToItem(index)
            delay(900)
            if (activeFocusKey == key) {
                activeFocusKey = null
            }
            pendingFocusKey = ""
            return@LaunchedEffect
        }
        index++

        if (key == "proxy_http") {
            listState.animateScrollToItem(index)
            delay(900)
            if (activeFocusKey == key) {
                activeFocusKey = null
            }
            pendingFocusKey = ""
            return@LaunchedEffect
        }
        index++

        val orbotOffsetDp =
            when (key) {
                "proxy_orbot" -> 0
                "proxy_orbot_apps" -> 70
                "proxy_orbot_open_app",
                "proxy_orbot_notification" -> 70
                "proxy_orbot_info" -> if (mappingViewModel != null) 132 else 70
                else -> null
            }

        if (orbotOffsetDp != null) {
            val offsetPx = with(density) { orbotOffsetDp.dp.toPx().roundToInt() }
            listState.animateScrollToItem(index, offsetPx)
            delay(900)
            if (activeFocusKey == key) {
                activeFocusKey = null
            }
        }
        pendingFocusKey = ""
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            RethinkLargeTopBar(
                title = stringResource(R.string.settings_proxy_header),
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior,
                titleStartPadding = Dimensions.spacingSm,
                actions = {
                    if (canEnableProxy) {
                        IconButton(
                            onClick = { refreshWireguard() },
                            enabled = !isRefreshing
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(Dimensions.spacingSm),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(R.string.dc_refresh_toast)
                                )
                            }
                        }
                    }
                }
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
                    top = Dimensions.spacingMd,
                    bottom = Dimensions.spacing3xl
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingLg)
        ) {
            if (!canEnableProxy) {
                item {
                    val warningFocused = activeFocusKey == "proxy_warning"
                    Surface(
                        shape = RoundedCornerShape(Dimensions.cornerRadiusXl),
                        color =
                            if (warningFocused) {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f)
                            } else {
                                MaterialTheme.colorScheme.errorContainer
                            }
                    ) {
                        Text(
                            text = stringResource(R.string.settings_lock_down_proxy_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier =
                                Modifier.padding(
                                    horizontal = Dimensions.spacingMd,
                                    vertical = Dimensions.spacingSmMd
                                )
                        )
                    }
                }
            }

            // Network Proxy (WireGuard)
            if (onWireguardClick != null) {
                item {
                    SectionHeader(title = stringResource(R.string.setup_wireguard))
                    RethinkListGroup {
                        RethinkListItem(
                            headline = stringResource(R.string.setup_wireguard),
                            supporting = wireguardDescription,
                            leadingIconPainter = painterResource(id = R.drawable.ic_wireguard_icon),
                            position = CardPosition.Single,
                            highlighted = activeFocusKey == "proxy_wireguard",
                            onClick = {
                                if (!canEnableProxy) {
                                    showProxyDisabledToast()
                                } else {
                                    onWireguardClick()
                                }
                            }
                        )
                    }
                }
            }

            // SOCKS5
            item {
                SectionHeader(title = stringResource(R.string.settings_socks5_heading))
                RethinkListGroup {
                    RethinkToggleListItem(
                        title = stringResource(R.string.settings_socks5_heading),
                        description = socks5Description,
                        iconRes = R.drawable.ic_socks5,
                        checked = socks5Enabled,
                        position = CardPosition.Single,
                        highlighted = activeFocusKey == "proxy_socks",
                        onRowClick = {
                            if (canEnableProxy && socks5Enabled) {
                                openSocksDialog()
                            } else if (!socks5Enabled) {
                                tryOpenCustomProxyDialog(
                                    canEnableSpecificProxy = { appConfig.canEnableSocks5Proxy() },
                                    disabledError = socks5DisabledError,
                                    openDialog = ::openSocksDialog
                                )
                            }
                        },
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                tryOpenCustomProxyDialog(
                                    canEnableSpecificProxy = { appConfig.canEnableSocks5Proxy() },
                                    disabledError = socks5DisabledError,
                                    openDialog = ::openSocksDialog
                                )
                            } else {
                                disableCustomProxy(
                                    type = AppConfig.ProxyType.SOCKS5,
                                    logMessage = "Custom SOCKS5 disabled"
                                )
                            }
                        }
                    )
                }
            }

            // HTTP
            item {
                SectionHeader(title = stringResource(R.string.settings_https_heading))
                RethinkListGroup {
                    RethinkToggleListItem(
                        title = stringResource(R.string.settings_https_heading),
                        description = httpDescription,
                        iconRes = R.drawable.ic_http,
                        checked = httpEnabled,
                        position = CardPosition.Single,
                        highlighted = activeFocusKey == "proxy_http",
                        onRowClick = {
                            if (canEnableProxy && httpEnabled) {
                                openHttpDialog()
                            } else if (!httpEnabled) {
                                tryOpenCustomProxyDialog(
                                    canEnableSpecificProxy = { appConfig.canEnableHttpProxy() },
                                    disabledError = httpDisabledError,
                                    openDialog = ::openHttpDialog
                                )
                            }
                        },
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                tryOpenCustomProxyDialog(
                                    canEnableSpecificProxy = { appConfig.canEnableHttpProxy() },
                                    disabledError = httpDisabledError,
                                    openDialog = ::openHttpDialog
                                )
                            } else {
                                disableCustomProxy(
                                    type = AppConfig.ProxyType.HTTP,
                                    logMessage = "Custom HTTP disabled"
                                )
                            }
                        }
                    )
                }
            }

            // Orbot
            item {
                SectionHeader(title = stringResource(R.string.orbot))
                val isOrbotInteractive = canEnableProxy && !orbotConnecting
                OrbotSettingsPanel(
                    title = stringResource(R.string.orbot),
                    description =
                        if (orbotConnecting) {
                            stringResource(R.string.orbot_bs_status_trying_connect)
                        } else {
                            orbotDescription
                        },
                    checked = orbotEnabled,
                    connecting = orbotConnecting,
                    enabled = isOrbotInteractive,
                    appCount = if (mappingViewModel != null) orbotAppCount else null,
                    highlightedKey = activeFocusKey,
                    onMainClick = {
                        if (isOrbotInteractive) {
                            enableOrbotFlow()
                        }
                    },
                    onCheckedChange = { checked ->
                        if (checked) {
                            enableOrbotFlow()
                        } else {
                            stopOrbotForwarding(showDialog = true)
                        }
                    },
                    onAppsClick =
                        if (mappingViewModel != null && onOpenOrbotApps != null) {
                            { onOpenOrbotApps() }
                        } else {
                            null
                        },
                    onOpenAppClick = { orbotHelper.openOrbotApp() },
                    onInfoClick = { showOrbotInfoDialog = true }
                )
            }
        }
    }

    if (showOrbotInfoDialog) {
        RethinkConfirmDialog(
            onDismissRequest = { showOrbotInfoDialog = false },
            title = stringResource(R.string.orbot_title),
            message = stringResource(R.string.orbot_explanation),
            confirmText = stringResource(R.string.lbl_dismiss),
            onConfirm = { showOrbotInfoDialog = false }
        )
    }

    if (showOrbotInstallDialog) {
        RethinkMultiActionDialog(
            onDismissRequest = { showOrbotInstallDialog = false },
            title = stringResource(R.string.orbot_install_dialog_title),
            message = stringResource(R.string.orbot_install_dialog_message),
            primaryText = stringResource(R.string.orbot_install_dialog_positive),
            onPrimary = {
                showOrbotInstallDialog = false
                val installIntent = orbotHelper.getIntentForDownload()
                if (installIntent == null) {
                    Utilities.showToastUiCentered(
                        context,
                        orbotInstallError,
                        Toast.LENGTH_SHORT
                    )
                    return@RethinkMultiActionDialog
                }

                try {
                    context.startActivity(installIntent)
                } catch (_: ActivityNotFoundException) {
                    Utilities.showToastUiCentered(
                        context,
                        orbotInstallError,
                        Toast.LENGTH_SHORT
                    )
                }
            },
            secondaryText = stringResource(R.string.orbot_install_dialog_neutral),
            onSecondary = {
                showOrbotInstallDialog = false
                try {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            orbotWebsiteLink.toUri()
                        )
                    )
                } catch (_: ActivityNotFoundException) {
                    Utilities.showToastUiCentered(
                        context,
                        orbotInstallError,
                        Toast.LENGTH_SHORT
                    )
                }
            },
            tertiaryText = stringResource(R.string.lbl_dismiss),
            onTertiary = { showOrbotInstallDialog = false }
        )
    }

    if (showOrbotModeDialog) {
        OrbotModeDialog(
            supportsHttp = Utilities.isAtleastQ(),
            selectedMode = selectedOrbotMode,
            onSelectedMode = { selectedOrbotMode = it },
            onDismiss = { showOrbotModeDialog = false },
            onConfirm = {
                scope.launch {
                    showOrbotModeDialog = false

                    if (selectedOrbotMode == AppConfig.ProxyType.NONE.name) {
                        stopOrbotForwarding(showDialog = true)
                        return@launch
                    }

                    if (!ProxyManager.isAnyAppSelected(ProxyManager.ID_ORBOT_BASE)) {
                        Utilities.showToastUiCentered(
                            context,
                            orbotNoAppToast,
                            Toast.LENGTH_SHORT
                        )
                        reloadUi()
                        return@launch
                    }

                    if (!VpnController.hasTunnel()) {
                        Utilities.showToastUiCentered(
                            context,
                            socks5VpnDisabledError,
                            Toast.LENGTH_SHORT
                        )
                        reloadUi()
                        return@launch
                    }

                    withContext(Dispatchers.IO) {
                        persistentState.orbotConnectionStatus.postValue(true)
                        orbotHelper.startOrbot(selectedOrbotMode)
                    }
                    logEvent("Orbot mode updated: $selectedOrbotMode")
                    reloadUi()
                }
            }
        )
    }

    if (showOrbotStopDialog) {
        RethinkMultiActionDialog(
            onDismissRequest = { showOrbotStopDialog = false },
            title = orbotStopTitle,
            text = {
                Text(
                    text = if (orbotStopHasDnsHint) orbotStopMessageCombo else orbotStopMessage
                )
            },
            primaryText = stringResource(R.string.lbl_dismiss),
            onPrimary = { showOrbotStopDialog = false },
            secondaryText = if (orbotStopHasDnsHint && onNavigateToDns != null) stringResource(R.string.orbot_stop_dialog_neutral) else null,
            onSecondary = if (orbotStopHasDnsHint && onNavigateToDns != null) {
                {
                    showOrbotStopDialog = false
                    onNavigateToDns()
                }
            } else {
                null
            },
            tertiaryText = stringResource(R.string.orbot_stop_dialog_negative),
            onTertiary = {
                showOrbotStopDialog = false
                orbotHelper.openOrbotApp()
            }
        )
    }

    socks5DialogState?.let { state ->
        Socks5Dialog(
            state = state,
            onStateChange = { socks5DialogState = it },
            onCancel = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        appConfig.removeProxy(
                            AppConfig.ProxyType.SOCKS5,
                            AppConfig.ProxyProvider.CUSTOM
                        )
                    }
                    socks5DialogState = null
                    reloadUi()
                }
            },
            onConfirm = {
                val current = socks5DialogState ?: return@Socks5Dialog
                scope.launch {
                    val validationError =
                        validateSocks5Input(
                            current.host,
                            current.port,
                            httpProxyHostEmptyError,
                            httpProxyInvalidPortError,
                            httpProxyRangePortError
                        )
                    if (validationError != null) {
                        socks5DialogState = current.copy(error = validationError)
                        return@launch
                    }

                    withContext(Dispatchers.IO) {
                        var endpoint = appConfig.getSocks5ProxyDetails()
                        if (endpoint == null) {
                            appConfig.addProxy(
                                AppConfig.ProxyType.SOCKS5,
                                AppConfig.ProxyProvider.CUSTOM
                            )
                            endpoint = appConfig.getSocks5ProxyDetails()
                        }

                        endpoint?.let {
                            it.proxyIP = current.host.trim()
                            it.proxyPort = current.port.toInt()
                            it.userName = current.username.ifBlank { null }
                            it.password = current.password.ifBlank { null }
                            it.proxyAppName = current.selectedAppPackage
                            it.isUDP = current.udpBlocked
                            appConfig.updateCustomSocks5Proxy(it)
                        }

                        persistentState.setUdpBlocked(current.udpBlocked)
                        persistentState.excludeAppsInProxy = !current.includeProxyApps
                    }

                    logEvent(
                        "Custom SOCKS5 updated: ${current.host}:${current.port}, app=${current.selectedAppLabel()}, udp=${current.udpBlocked}"
                    )
                    socks5DialogState = null
                    reloadUi()
                }
            }
        )
    }

    httpDialogState?.let { state ->
        HttpDialog(
            state = state,
            onStateChange = { httpDialogState = it },
            onCancel = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        appConfig.removeProxy(
                            AppConfig.ProxyType.HTTP,
                            AppConfig.ProxyProvider.CUSTOM
                        )
                    }
                    httpDialogState = null
                    reloadUi()
                }
            },
            onConfirm = {
                val current = httpDialogState ?: return@HttpDialog
                scope.launch {
                    if (current.host.isBlank()) {
                        httpDialogState =
                            current.copy(
                                error = httpProxyHostEmptyError
                            )
                        return@launch
                    }

                    withContext(Dispatchers.IO) {
                        var endpoint = appConfig.getHttpProxyDetails()
                        if (endpoint == null) {
                            appConfig.addProxy(
                                AppConfig.ProxyType.HTTP,
                                AppConfig.ProxyProvider.CUSTOM
                            )
                            endpoint = appConfig.getHttpProxyDetails()
                        }

                        endpoint?.let {
                            it.proxyIP = current.host.trim()
                            it.proxyPort = 0
                            it.userName = null
                            it.password = null
                            it.proxyAppName = current.selectedAppPackage
                            appConfig.updateCustomHttpProxy(it)
                        }

                        persistentState.excludeAppsInProxy = !current.includeProxyApps
                    }

                    Utilities.showToastUiCentered(
                        context,
                        httpProxyToastSuccess,
                        Toast.LENGTH_SHORT
                    )
                    logEvent(
                        "Custom HTTP updated: ${current.host}, app=${current.selectedAppLabel()}"
                    )
                    httpDialogState = null
                    reloadUi()
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrbotSettingsPanel(
    title: String,
    description: String,
    checked: Boolean,
    connecting: Boolean,
    enabled: Boolean,
    appCount: Int?,
    highlightedKey: String?,
    onMainClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    onAppsClick: (() -> Unit)?,
    onOpenAppClick: () -> Unit,
    onInfoClick: () -> Unit
) {
    val mainHighlighted = highlightedKey == "proxy_orbot"
    val cardColor =
        if (mainHighlighted) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }

    val statusText: String
    val statusColor: Color
    val statusBackground: Color
    if (connecting) {
        statusText = stringResource(R.string.status_waiting)
        statusColor = MaterialTheme.colorScheme.primary
        statusBackground = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else if (checked) {
        statusText = stringResource(R.string.lbl_active)
        statusColor = MaterialTheme.colorScheme.tertiary
        statusBackground = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
    } else {
        statusText = stringResource(R.string.lbl_inactive)
        statusColor = MaterialTheme.colorScheme.onSurfaceVariant
        statusBackground = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
    }

    val iconTint = if (connecting || checked) statusColor else MaterialTheme.colorScheme.onSurfaceVariant
    val showActions = enabled

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimensions.cornerRadius3xl),
        color = cardColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f))
    ) {
        Column(
            modifier =
                Modifier.padding(
                    horizontal = Dimensions.spacingMd,
                    vertical = Dimensions.spacingSmMd
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = enabled, onClick = onMainClick),
                horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSmMd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = statusBackground,
                    modifier = Modifier.size(34.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_orbot),
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Surface(
                        shape = RoundedCornerShape(Dimensions.buttonCornerRadius),
                        color = statusBackground
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = statusColor,
                            modifier =
                                Modifier.padding(
                                    horizontal = 8.dp,
                                    vertical = 2.dp
                                )
                        )
                    }
                }

                Switch(
                    checked = checked,
                    enabled = enabled,
                    onCheckedChange = onCheckedChange
                )
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (showActions) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.34f))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm),
                    verticalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
                ) {
                    if (appCount != null && onAppsClick != null) {
                        OrbotAppsActionChip(
                            appCount = appCount,
                            highlighted = highlightedKey == "proxy_orbot_apps",
                            onClick = onAppsClick
                        )
                    }
                    OrbotActionChip(
                        label = stringResource(R.string.settings_orbot_notification_action),
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
                        highlighted =
                            highlightedKey == "proxy_orbot_open_app" ||
                                highlightedKey == "proxy_orbot_notification",
                        onClick = onOpenAppClick
                    )
                    OrbotActionChip(
                        label = stringResource(R.string.lbl_info),
                        icon = Icons.Rounded.Info,
                        highlighted = highlightedKey == "proxy_orbot_info",
                        onClick = onInfoClick
                    )
                }
            }
        }
    }
}

@Composable
private fun OrbotAppsActionChip(
    appCount: Int,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    val containerColor =
        if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    val contentColor =
        if (highlighted) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val badgeColor =
        if (highlighted) {
            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        }

    Surface(
        modifier =
            Modifier
                .heightIn(min = Dimensions.touchTargetSm)
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(Dimensions.cornerRadiusMdLg),
        color = containerColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
    ) {
        Row(
            modifier =
                Modifier.padding(
                    horizontal = Dimensions.spacingMd,
                    vertical = Dimensions.spacingXs
                ),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingXs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Apps,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = stringResource(R.string.lbl_apps),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = contentColor
            )
            Surface(
                shape = RoundedCornerShape(Dimensions.buttonCornerRadius),
                color = badgeColor
            ) {
                Text(
                    text = appCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp)
                )
            }
        }
    }
}

@Composable
private fun OrbotActionChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    val containerColor =
        if (highlighted) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    val contentColor =
        if (highlighted) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        }

    Surface(
        modifier =
            Modifier
                .heightIn(min = Dimensions.touchTargetSm)
                .clickable(onClick = onClick),
        shape = RoundedCornerShape(Dimensions.cornerRadiusMdLg),
        color = containerColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
    ) {
        Row(
            modifier =
                Modifier.padding(
                    horizontal = Dimensions.spacingMd,
                    vertical = Dimensions.spacingXs
                ),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingXs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ProxyOverviewCard(
    canEnableProxy: Boolean,
    socks5Enabled: Boolean,
    httpEnabled: Boolean,
    orbotEnabled: Boolean
) {
    val activeCount = listOf(socks5Enabled, httpEnabled, orbotEnabled).count { it }

    Surface(
        shape = RoundedCornerShape(Dimensions.cardCornerRadiusLarge),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = Dimensions.Elevation.low,
        border =
            androidx.compose.foundation.BorderStroke(
                Dimensions.dividerThicknessBold,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)
            )
    ) {
        Column(
            modifier = Modifier.padding(Dimensions.spacingXl),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
        ) {
            Text(
                text = stringResource(R.string.settings_proxy_header),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.settings_exclude_proxy_apps_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Active: $activeCount / 3",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            if (!canEnableProxy) {
                Text(
                    text = stringResource(R.string.settings_lock_down_proxy_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun WireguardEntryCard(
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(Dimensions.spacingLg),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(Dimensions.spacingSm))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.75f else 0.45f)
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null
            )
        }
    }
}

@Composable
private fun ProxyCard(
    title: String,
    description: String,
    enabled: Boolean,
    cardEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onConfigureClick: (() -> Unit)?
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(Dimensions.spacingLg)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Switch(
                    checked = enabled,
                    enabled = cardEnabled,
                    onCheckedChange = onEnabledChange
                )
            }

            Spacer(modifier = Modifier.height(Dimensions.spacingSm))

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (cardEnabled) 0.75f else 0.45f)
            )

            if (onConfigureClick != null && cardEnabled) {
                Spacer(modifier = Modifier.height(Dimensions.spacingSm))
                TextButton(onClick = onConfigureClick) {
                    Text(text = stringResource(R.string.lbl_configure))
                }
            }
        }
    }
}

@Composable
private fun OrbotModeDialog(
    supportsHttp: Boolean,
    selectedMode: String,
    onSelectedMode: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val options =
        buildList {
            add(AppConfig.ProxyType.SOCKS5.name to R.string.orbot_socks5)
            if (supportsHttp) {
                add(AppConfig.ProxyType.HTTP.name to R.string.orbot_http)
                add(AppConfig.ProxyType.HTTP_SOCKS5.name to R.string.orbot_both)
            }
            add(AppConfig.ProxyType.NONE.name to R.string.orbot_none)
        }

    RethinkConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.orbot_title),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimensions.spacingXs)) {
                options.forEach { (value, labelRes) ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelectedMode(value) }
                                .padding(vertical = Dimensions.spacingXs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedMode == value,
                            onClick = { onSelectedMode(value) }
                        )
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.lbl_save),
        dismissText = stringResource(R.string.lbl_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

@Composable
private fun Socks5Dialog(
    state: Socks5DialogState,
    onStateChange: (Socks5DialogState) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current

    RethinkConfirmDialog(
        onDismissRequest = {},
        title = stringResource(R.string.settings_dns_proxy_dialog_header),
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
            ) {
                OutlinedTextField(
                    value = state.host,
                    onValueChange = { onStateChange(state.copy(host = it, error = null)) },
                    label = { Text(text = stringResource(R.string.proxy_host_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.port,
                    onValueChange = { onStateChange(state.copy(port = it, error = null)) },
                    label = { Text(text = stringResource(R.string.proxy_port_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.username,
                    onValueChange = { onStateChange(state.copy(username = it, error = null)) },
                    label = { Text(text = stringResource(R.string.proxy_username_optional_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = { onStateChange(state.copy(password = it, error = null)) },
                    label = { Text(text = stringResource(R.string.proxy_password_optional_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.settings_dns_proxy_dialog_app_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                ProxyAppSelectorField(
                    selectedPackageName = state.selectedAppPackage,
                    options = state.appOptions,
                    onOptionSelected = { packageName ->
                        onStateChange(state.copy(selectedAppPackage = packageName, error = null))
                    }
                )

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onStateChange(
                                    state.copy(udpBlocked = !state.udpBlocked, error = null)
                                )
                            },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = state.udpBlocked,
                        onCheckedChange = {
                            onStateChange(state.copy(udpBlocked = it, error = null))
                        }
                    )
                    Text(
                        text = stringResource(R.string.settings_udp_block),
                        modifier = Modifier.padding(start = Dimensions.spacingSm)
                    )
                }

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.lockdown) {
                                onStateChange(
                                    state.copy(
                                        includeProxyApps = !state.includeProxyApps,
                                        error = null
                                    )
                                )
                            },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = state.includeProxyApps,
                        enabled = !state.lockdown,
                        onCheckedChange = {
                            onStateChange(state.copy(includeProxyApps = it, error = null))
                        }
                    )
                    Text(
                        text = stringResource(R.string.settings_exclude_proxy_apps_heading),
                        modifier = Modifier.padding(start = Dimensions.spacingSm)
                    )
                }

                if (state.lockdown) {
                    Text(
                        text = stringResource(R.string.settings_lock_down_mode_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { UIUtils.openVpnProfile(context) }
                    )
                }

                if (!state.error.isNullOrBlank()) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmText = stringResource(R.string.lbl_save),
        dismissText = stringResource(R.string.lbl_cancel),
        onConfirm = onConfirm,
        onDismiss = onCancel
    )
}

@Composable
private fun HttpDialog(
    state: HttpDialogState,
    onStateChange: (HttpDialogState) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    val context = LocalContext.current

    RethinkConfirmDialog(
        onDismissRequest = {},
        title = stringResource(R.string.http_proxy_dialog_heading),
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
            ) {
                Text(
                    text = stringResource(R.string.http_proxy_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = state.host,
                    onValueChange = { onStateChange(state.copy(host = it, error = null)) },
                    label = { Text(text = stringResource(R.string.proxy_host_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.settings_dns_proxy_dialog_app_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ProxyAppSelectorField(
                    selectedPackageName = state.selectedAppPackage,
                    options = state.appOptions,
                    onOptionSelected = { packageName ->
                        onStateChange(state.copy(selectedAppPackage = packageName, error = null))
                    }
                )

                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.lockdown) {
                                onStateChange(
                                    state.copy(
                                        includeProxyApps = !state.includeProxyApps,
                                        error = null
                                    )
                                )
                            },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = state.includeProxyApps,
                        enabled = !state.lockdown,
                        onCheckedChange = {
                            onStateChange(state.copy(includeProxyApps = it, error = null))
                        }
                    )
                    Text(
                        text = stringResource(R.string.settings_exclude_proxy_apps_heading),
                        modifier = Modifier.padding(start = Dimensions.spacingSm)
                    )
                }

                if (state.lockdown) {
                    Text(
                        text = stringResource(R.string.settings_lock_down_mode_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { UIUtils.openVpnProfile(context) }
                    )
                }

                if (!state.error.isNullOrBlank()) {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmText = stringResource(R.string.lbl_save),
        dismissText = stringResource(R.string.lbl_cancel),
        onConfirm = onConfirm,
        onDismiss = onCancel
    )
}

private fun Socks5DialogState.selectedAppLabel(): String {
    return selectedProxyAppLabel(selectedAppPackage, appOptions)
}

private fun HttpDialogState.selectedAppLabel(): String {
    return selectedProxyAppLabel(selectedAppPackage, appOptions)
}

private fun selectedProxyAppLabel(selectedPackageName: String, options: List<ProxyDialogAppOption>): String {
    return options.firstOrNull { it.packageName == selectedPackageName }?.label
        ?: options.firstOrNull()?.label
        ?: ""
}

@Composable
private fun ProxyAppSelectorField(
    selectedPackageName: String,
    options: List<ProxyDialogAppOption>,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }
    var anchorOffset by remember { mutableStateOf(IntOffset.Zero) }
    val density = LocalDensity.current
    val selectorShape = RoundedCornerShape(Dimensions.cornerRadiusMdLg)
    val selectedOption =
        options.firstOrNull { it.packageName == selectedPackageName } ?: options.firstOrNull()
    val containerColor =
        if (enabled) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            shape = selectorShape,
            color = containerColor,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        anchorSize = coordinates.size
                        val windowPos = coordinates.positionInWindow()
                        anchorOffset = IntOffset(windowPos.x.roundToInt(), windowPos.y.roundToInt())
                    }
                    .clip(selectorShape)
                    .clickable(enabled = enabled) { expanded = true }
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimensions.spacingMd, vertical = Dimensions.spacingSm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm)
            ) {
                ProxyDialogAppIcon(option = selectedOption, size = 24.dp)
                Text(
                    text = selectedOption?.label.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (expanded && enabled && anchorSize.width > 0) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(anchorOffset.x, anchorOffset.y + anchorSize.height),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true, clippingEnabled = true)
            ) {
                Surface(
                    shape = selectorShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shadowElevation = 8.dp,
                    tonalElevation = 6.dp,
                    modifier =
                        Modifier
                            .width(with(density) { anchorSize.width.toDp() })
                            .heightIn(max = 360.dp)
                            .clip(selectorShape)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(items = options) { option ->
                            DropdownMenuItem(
                                leadingIcon = { ProxyDialogAppIcon(option = option, size = 20.dp) },
                                text = {
                                    Text(
                                        text = option.label,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                onClick = {
                                    expanded = false
                                    onOptionSelected(option.packageName)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProxyDialogAppIcon(
    option: ProxyDialogAppOption?,
    size: androidx.compose.ui.unit.Dp
) {
    if (option == null || option.packageName.isBlank()) {
        ProxyDialogIconPlaceholder(size = size)
        return
    }

    val context = LocalContext.current
    val cacheKey = option.packageName.ifBlank { option.iconLookupName }
    var iconDrawable by
        remember(cacheKey) {
            mutableStateOf(
                if (cacheKey.isBlank()) {
                    null
                } else {
                    ProxyDialogAppIconCache.get(cacheKey)
                }
            )
        }

    LaunchedEffect(cacheKey, option.packageName, option.iconLookupName) {
        if (iconDrawable != null || option.packageName.isBlank()) return@LaunchedEffect
        val icon =
            withContext(Dispatchers.IO) {
                getIcon(context, option.packageName, option.iconLookupName)
            }
        iconDrawable = icon
        ProxyDialogAppIconCache.put(cacheKey, icon)
    }

    val painter = rememberDrawablePainter(iconDrawable)
    if (painter != null) {
        Image(
            painter = painter,
            contentDescription = null,
            modifier =
                Modifier
                    .size(size)
                    .clip(RoundedCornerShape(8.dp))
        )
    } else {
        ProxyDialogIconPlaceholder(size = size)
    }
}

@Composable
private fun ProxyDialogIconPlaceholder(size: androidx.compose.ui.unit.Dp) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
        modifier = Modifier.size(size)
    ) {}
}

private suspend fun buildProxyScreenState(
    context: android.content.Context,
    appConfig: AppConfig,
    defaultSocks5Description: String,
    defaultHttpDescription: String,
    httpProxyDescriptionTemplate: String,
    defaultSocks5DescriptionNoApp: String,
    defaultSocks5DescriptionWithApp: String,
    orbotDisabledDescription: String,
    orbotStatus2Description: String,
    orbotStatus1Template: String,
    orbotStatus3Template: String,
    orbotStatusArgDns: String,
    orbotStatusArgProxy: String,
    defaultWireguardDescription: String,
    statusTextById: Map<Long, String>,
    statusFailingText: String,
    statusWaitingText: String,
    wireguardVersionTemplate: String,
    ciIpLabelTemplate: String
): ProxyScreenState {
    val canEnableProxy = appConfig.canEnableProxy()
    val socks5Enabled = appConfig.isCustomSocks5Enabled()
    val httpEnabled = appConfig.isCustomHttpProxyEnabled()
    val orbotEnabled = appConfig.isOrbotProxyEnabled()

    val socks5Description =
        if (!socks5Enabled) {
            defaultSocks5Description
        } else {
            formatSocks5Description(
                context,
                runCatching { appConfig.getSocks5ProxyDetails() }.getOrNull(),
                defaultSocks5Description,
                defaultSocks5DescriptionNoApp,
                defaultSocks5DescriptionWithApp
            )
        }

    val httpDescription =
        if (!httpEnabled) {
            defaultHttpDescription
        } else {
            val endpoint = runCatching { appConfig.getHttpProxyDetails() }.getOrNull()
            val host = endpoint?.proxyIP ?: ""
            if (host.isBlank()) {
                defaultHttpDescription
            } else {
                String.format(httpProxyDescriptionTemplate, host)
            }
        }

    val orbotDescription =
        formatOrbotDescription(
            context,
            appConfig,
            orbotDisabledDescription,
            orbotStatus2Description,
            orbotStatus1Template,
            orbotStatus3Template,
            orbotStatusArgDns,
            orbotStatusArgProxy
        )
    val wireguardDescription =
        formatWireguardDescription(
            defaultWireguardDescription,
            statusTextById,
            statusWaitingText,
            statusFailingText,
            wireguardVersionTemplate,
            ciIpLabelTemplate
        )

    return ProxyScreenState(
        canEnableProxy = canEnableProxy,
        socks5Enabled = socks5Enabled,
        httpEnabled = httpEnabled,
        orbotEnabled = orbotEnabled,
        wireguardDescription = wireguardDescription,
        socks5Description = socks5Description,
        httpDescription = httpDescription,
        orbotDescription = orbotDescription
    )
}

private suspend fun formatSocks5Description(
    context: android.content.Context,
    endpoint: ProxyEndpoint?,
    defaultDescription: String,
    defaultDescriptionNoAppTemplate: String,
    defaultDescriptionWithAppTemplate: String
): String {
    val endpointValue = endpoint ?: return defaultDescription
    if (endpointValue.proxyIP.isNullOrBlank()) {
        return defaultDescription
    }

    val ip = endpointValue.proxyIP ?: return defaultDescription
    val port = endpointValue.proxyPort.toString()
    val packageName = endpointValue.proxyAppName
    if (packageName.isNullOrBlank()) {
        return String.format(defaultDescriptionNoAppTemplate, ip, port)
    }

    val appName = FirewallManager.getAppInfoByPackage(packageName)?.appName
    return if (appName.isNullOrBlank()) {
        String.format(defaultDescriptionNoAppTemplate, ip, port)
    } else {
        String.format(defaultDescriptionWithAppTemplate, ip, port, appName)
    }
}

private suspend fun formatOrbotDescription(
    context: android.content.Context,
    appConfig: AppConfig,
    orbotDisabledDescription: String,
    orbotStatus2Description: String,
    orbotStatus1Template: String,
    orbotStatus3Template: String,
    orbotStatusArgDns: String,
    orbotStatusArgProxy: String
): String {
    val isInstalled = FirewallManager.isOrbotInstalled()
    if (!isInstalled) {
        return orbotDisabledDescription
    }

    if (!appConfig.isOrbotProxyEnabled()) {
        return orbotDisabledDescription
    }

    val isOrbotDns = appConfig.isOrbotDns()
    return when (appConfig.getProxyType()) {
        AppConfig.ProxyType.HTTP.name -> {
            orbotStatus2Description
        }

        AppConfig.ProxyType.SOCKS5.name -> {
            if (isOrbotDns) {
                String.format(orbotStatus1Template, orbotStatusArgDns)
            } else {
                String.format(orbotStatus1Template, orbotStatusArgProxy)
            }
        }

        AppConfig.ProxyType.HTTP_SOCKS5.name -> {
            if (isOrbotDns) {
                String.format(orbotStatus3Template, orbotStatusArgDns)
            } else {
                String.format(orbotStatus3Template, orbotStatusArgProxy)
            }
        }

        else -> orbotDisabledDescription
    }
}

private suspend fun formatWireguardDescription(
    defaultDescription: String,
    statusTextById: Map<Long, String>,
    statusWaitingText: String,
    statusFailingText: String,
    wireguardVersionTemplate: String,
    ciIpLabelTemplate: String
): String {
    val activeWgs = WireguardManager.getActiveConfigs()
    if (activeWgs.isEmpty()) {
        return defaultDescription
    }

    val details = StringBuilder()
    activeWgs.forEach {
        val id = ProxyManager.ID_WG_BASE + it.getId()
        val statusPair = VpnController.getProxyStatusById(id)
        val stats = VpnController.getProxyStats(id)
        val dnsStatusId = VpnController.getDnsStatus(id)

        val statusText =
            if (statusPair.first == Backend.TPU) {
                statusTextById[UIUtils.ProxyStatus.TPU.id] ?: statusFailingText
            } else if (isDnsError(dnsStatusId)) {
                statusFailingText
            } else {
                getProxyStatusText(
                    statusPair = statusPair,
                    stats = stats,
                    statusTextById = statusTextById,
                    statusWaitingText = statusWaitingText,
                    statusFailingText = statusFailingText,
                    wireguardVersionTemplate = wireguardVersionTemplate
                )
            }

        details.append(
            String.format(
                ciIpLabelTemplate,
                it.getName(),
                statusText.padStart(1, ' ')
            )
        )
        details.append('\n')
    }

    return details.toString().trimEnd()
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

private fun getProxyStatusText(
    statusPair: Pair<Long?, String>,
    stats: RouterStats?,
    statusTextById: Map<Long, String>,
    statusWaitingText: String,
    statusFailingText: String,
    wireguardVersionTemplate: String
): String {
    val status = UIUtils.ProxyStatus.entries.find { it.id == statusPair.first }
    if (status == null) {
        val txt =
            if (!statusPair.second.isNullOrBlank()) {
                "$statusWaitingText (${statusPair.second})"
            } else {
                statusWaitingText
            }
        return txt.replaceFirstChar(Char::titlecase)
    }

    val now = System.currentTimeMillis()
    val lastOk = stats?.lastOK ?: 0L
    val since = stats?.since ?: 0L
    if (now - since > WireguardManager.WG_UPTIME_THRESHOLD && lastOk == 0L) {
        return statusFailingText
    }

    val baseText =
        statusTextById.getValue(status.id)
    val handshakeTime =
        if (stats != null && stats.lastOK > 0L) {
            DateUtils.getRelativeTimeSpanString(
                stats.lastOK,
                now,
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
                .toString()
        } else {
            null
        }

    return if (stats?.lastOK != 0L && handshakeTime != null) {
        String.format(wireguardVersionTemplate, baseText, handshakeTime)
    } else {
        baseText
    }
}

private suspend fun buildSocks5DialogState(
    context: android.content.Context,
    appConfig: AppConfig,
    persistentState: PersistentState,
    defaultApp: String
): Socks5DialogState {
    val endpoint = runCatching { appConfig.getSocks5ProxyDetails() }.getOrNull()
    val appOptions = buildProxyDialogAppOptions(context, defaultApp)
    val selectedPackage = endpoint?.proxyAppName.orEmpty()
    val normalizedSelectedPackage =
        if (appOptions.any { it.packageName == selectedPackage }) {
            selectedPackage
        } else {
            ""
        }

    return Socks5DialogState(
        host = endpoint?.proxyIP ?: com.celzero.bravedns.util.Constants.SOCKS_DEFAULT_IP,
        port =
            if ((endpoint?.proxyPort ?: 0) > 0) {
                endpoint?.proxyPort?.toString() ?: com.celzero.bravedns.util.Constants.SOCKS_DEFAULT_PORT.toString()
            } else {
                com.celzero.bravedns.util.Constants.SOCKS_DEFAULT_PORT.toString()
            },
        username = endpoint?.userName.orEmpty(),
        password = endpoint?.password.orEmpty(),
        selectedAppPackage = normalizedSelectedPackage,
        appOptions = appOptions,
        udpBlocked = persistentState.getUdpBlocked(),
        includeProxyApps = !persistentState.excludeAppsInProxy,
        lockdown = VpnController.isVpnLockdown()
    )
}

private suspend fun buildHttpDialogState(
    context: android.content.Context,
    appConfig: AppConfig,
    persistentState: PersistentState,
    defaultApp: String
): HttpDialogState {
    val endpoint = runCatching { appConfig.getHttpProxyDetails() }.getOrNull()
    val appOptions = buildProxyDialogAppOptions(context, defaultApp)

    val selectedPackage = endpoint?.proxyAppName.orEmpty()
    val normalizedSelectedPackage =
        if (appOptions.any { it.packageName == selectedPackage }) {
            selectedPackage
        } else {
            ""
        }

    return HttpDialogState(
        host = endpoint?.proxyIP ?: "http://127.0.0.1:8118",
        selectedAppPackage = normalizedSelectedPackage,
        appOptions = appOptions,
        includeProxyApps = !persistentState.excludeAppsInProxy,
        lockdown = VpnController.isVpnLockdown()
    )
}

private suspend fun buildProxyDialogAppOptions(
    context: android.content.Context,
    defaultApp: String
): List<ProxyDialogAppOption> {
    val sortedApps = FirewallManager.getAllAppsSortedByVpnPermission(context)
    val duplicateCountByName = sortedApps.groupingBy { it.appName }.eachCount()

    return buildList {
        add(ProxyDialogAppOption(packageName = "", label = defaultApp))
        sortedApps.forEach { appInfo ->
            val appName = appInfo.appName
            val packageName = appInfo.packageName.orEmpty()
            val label =
                if (duplicateCountByName[appName] ?: 0 > 1 && packageName.isNotBlank()) {
                    "$appName ($packageName)"
                } else {
                    appName
                }
            add(
                ProxyDialogAppOption(
                    packageName = packageName,
                    label = label,
                    iconLookupName = appName
                )
            )
        }
    }
}

private fun validateSocks5Input(
    host: String,
    portText: String,
    emptyHostError: String,
    invalidPortError: String,
    portRangeError: String
): String? {
    if (host.isBlank()) {
        return emptyHostError
    }

    val port =
        try {
            portText.toInt()
        } catch (_: NumberFormatException) {
            return invalidPortError
        }

    val valid =
        if (Utilities.isLanIpv4(host)) {
            Utilities.isValidLocalPort(port)
        } else {
            Utilities.isValidPort(port)
        }
    if (!valid) {
        return portRangeError
    }

    return null
}
