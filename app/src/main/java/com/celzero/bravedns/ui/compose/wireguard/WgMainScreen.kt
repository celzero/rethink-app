/*
 * Copyright 2025 RethinkDNS and its authors
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

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.OneWgConfigRow
import com.celzero.bravedns.adapter.WgConfigRow
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import com.celzero.bravedns.ui.compose.theme.RethinkTwoOptionSegmentedRow
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.WgConfigViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val EMPTY_ALPHA = 0.7f

enum class WgTab {
    ONE,
    GENERAL
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WgMainScreen(
    wgConfigViewModel: WgConfigViewModel,
    persistentState: PersistentState,
    appConfig: AppConfig,
    eventLogger: EventLogger,
    onBackClick: () -> Unit,
    onCreateClick: () -> Unit,
    onImportClick: () -> Unit,
    onQrScanClick: () -> Unit,
    onConfigDetailClick: (Int, WgType) -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val navBarBottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val splitFabBottomPadding = navBarBottomInset + 12.dp
    val scope = rememberCoroutineScope()
    val wireguardDisclaimerText = stringResource(R.string.wireguard_disclaimer)
    val fallbackDnsLabel = stringResource(R.string.lbl_fallback)
    val wireguardDisableFailure = stringResource(R.string.wireguard_disable_failure)
    val wireguardDisableFailureRelay = stringResource(R.string.wireguard_disable_failure_relay)

    var selectedTab by remember {
        mutableStateOf(
            if (WireguardManager.isAnyWgActive() && !WireguardManager.oneWireGuardEnabled()) {
                WgTab.GENERAL
            } else {
                WgTab.ONE
            }
        )
    }
    var isFabMenuExpanded by remember { mutableStateOf(false) }
    var showDisableDialog by remember { mutableStateOf(false) }
    var disableDialogIsOneWgToggle by remember { mutableStateOf(false) }
    var disclaimerText by remember { mutableStateOf("") }

    val configCount by wgConfigViewModel.configCount().asFlow()
        .collectAsStateWithLifecycle(initialValue = 0)
    val showEmpty = configCount == 0

    // Observe connected DNS for non-OneWG mode
    val connectedDns by appConfig.getConnectedDnsObservable().asFlow()
        .collectAsStateWithLifecycle(initialValue = "")

    // DNS status listener callback - updates disclaimer text
    fun updateDisclaimerText() {
        val activeConfigs = WireguardManager.getActiveConfigs()
        disclaimerText = if (WireguardManager.oneWireGuardEnabled()) {
            val dnsName = activeConfigs.firstOrNull()?.getName() ?: ""
            String.format(wireguardDisclaimerText, dnsName)
        } else {
            var dnsNames = connectedDns
            if (persistentState.splitDns && activeConfigs.isNotEmpty()) {
                if (dnsNames.isNotEmpty()) {
                    dnsNames += ", "
                }
                dnsNames += activeConfigs.joinToString(",") { it.getName() }
            }
            if (persistentState.useFallbackDnsToBypass) {
                dnsNames += ", $fallbackDnsLabel"
            }
            String.format(wireguardDisclaimerText, dnsNames)
        }
    }

    // A counter to trigger disclaimer text refresh
    var dnsRefreshTrigger by remember { mutableStateOf(0) }

    // Initialize and update disclaimer text when tab, DNS, or refresh trigger changes
    LaunchedEffect(selectedTab, connectedDns, dnsRefreshTrigger) {
        updateDisclaimerText()
    }



    BackHandler(enabled = isFabMenuExpanded) {
        isFabMenuExpanded = false
    }

    if (showDisableDialog) {
        DisableConfigsDialog(
            onDismiss = { showDisableDialog = false },
            onConfirm = {
                showDisableDialog = false
                val isOneWgToggle = disableDialogIsOneWgToggle
                scope.launch(Dispatchers.IO) {
                    if (WireguardManager.canDisableAllActiveConfigs()) {
                        WireguardManager.disableAllActiveConfigs()
                        logEvent(
                            eventLogger,
                            "Wireguard disable",
                            "all configs from toggle switch; isOneWgToggle: $isOneWgToggle"
                        )
                        withContext(Dispatchers.Main) {
                            dnsRefreshTrigger++
                            selectedTab = if (isOneWgToggle) WgTab.ONE else WgTab.GENERAL
                        }
                    } else {
                        val configs = WireguardManager.getActiveCatchAllConfig()
                        withContext(Dispatchers.Main) {
                            val msgText = if (configs.isNotEmpty()) {
                                wireguardDisableFailure
                            } else {
                                wireguardDisableFailureRelay
                            }
                            Utilities.showToastUiCentered(
                                context,
                                msgText,
                                Toast.LENGTH_LONG
                            )
                        }
                    }
                }
            }
        )
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            RethinkLargeTopBar(
                title = stringResource(id = R.string.lbl_wireguard),
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (showEmpty) {
                EmptyState(bottomInset = navBarBottomInset)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    WireguardOverviewCard(disclaimerText = disclaimerText)
                    WgConfigContent(
                        selectedTab = selectedTab,
                        wgConfigViewModel = wgConfigViewModel,
                        eventLogger = eventLogger,
                        onDnsStatusChanged = { dnsRefreshTrigger++ },
                        onConfigDetailClick = onConfigDetailClick,
                        bottomInset = navBarBottomInset,
                        modifier = Modifier.weight(1f),
                        onOneWgToggleClick = {
                            val activeConfigs = WireguardManager.getActiveConfigs()
                            val isAnyConfigActive = activeConfigs.isNotEmpty()
                            val isOneWgEnabled = WireguardManager.oneWireGuardEnabled()
                            if (isAnyConfigActive && !isOneWgEnabled) {
                                disableDialogIsOneWgToggle = true
                                showDisableDialog = true
                            } else {
                                selectedTab = WgTab.ONE
                            }
                        },
                        onGeneralToggleClick = {
                            if (WireguardManager.oneWireGuardEnabled()) {
                                disableDialogIsOneWgToggle = false
                                showDisableDialog = true
                            } else {
                                selectedTab = WgTab.GENERAL
                            }
                        }
                    )
                }
            }

            WgSplitFab(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = splitFabBottomPadding),
                expanded = isFabMenuExpanded,
                onExpandedChange = { isFabMenuExpanded = it },
                onCreateClick = {
                    isFabMenuExpanded = false
                    onCreateClick()
                },
                onImportClick = {
                    isFabMenuExpanded = false
                    onImportClick()
                },
                onQrClick = {
                    isFabMenuExpanded = false
                    onQrScanClick()
                }
            )
        }
    }
}

@Composable
private fun WgConfigContent(
    selectedTab: WgTab,
    wgConfigViewModel: WgConfigViewModel,
    eventLogger: EventLogger,
    onDnsStatusChanged: () -> Unit,
    onConfigDetailClick: (Int, WgType) -> Unit,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
    onOneWgToggleClick: () -> Unit,
    onGeneralToggleClick: () -> Unit
) {
    Column(modifier = modifier.fillMaxSize()) {
        ToggleRow(
            selectedTab = selectedTab,
            onOneWgClick = onOneWgToggleClick,
            onGeneralClick = onGeneralToggleClick
        )

        Box(modifier = Modifier.fillMaxSize()) {
            val items = wgConfigViewModel.interfaces.asFlow().collectAsLazyPagingItems()
            val padding = PaddingValues(bottom = 84.dp + bottomInset)

            if (selectedTab == WgTab.GENERAL) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = padding
                ) {
                    items(count = items.itemCount) { index ->
                        val item = items[index] ?: return@items
                        WgConfigRow(
                            config = item,
                            eventLogger = eventLogger,
                            onDnsStatusChanged = onDnsStatusChanged,
                            onConfigDetailClick = onConfigDetailClick
                        )
                    }
                }
            }

            if (selectedTab == WgTab.ONE) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = padding
                ) {
                    items(count = items.itemCount) { index ->
                        val item = items[index] ?: return@items
                        OneWgConfigRow(
                            config = item,
                            eventLogger = eventLogger,
                            onDnsStatusChanged = onDnsStatusChanged,
                            onConfigDetailClick = onConfigDetailClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    selectedTab: WgTab,
    onOneWgClick: () -> Unit,
    onGeneralClick: () -> Unit
) {
    RethinkTwoOptionSegmentedRow(
        leftLabel = stringResource(id = R.string.rt_list_simple_btn_txt),
        rightLabel = stringResource(id = R.string.lbl_advanced),
        leftSelected = selectedTab == WgTab.ONE,
        onLeftClick = onOneWgClick,
        onRightClick = onGeneralClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, start = 16.dp, end = 16.dp)
    )
}

@Composable
private fun DisableConfigsDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    RethinkConfirmDialog(
        onDismissRequest = onDismiss,
        title = stringResource(id = R.string.wireguard_disable_title),
        message = stringResource(id = R.string.wireguard_disable_message),
        confirmText = stringResource(id = R.string.always_on_dialog_positive),
        dismissText = stringResource(id = R.string.lbl_cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

@Composable
private fun EmptyState(bottomInset: Dp) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 24.dp + bottomInset),
        shape = RoundedCornerShape(Dimensions.cornerRadius2xl),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.illustrations_no_record),
                contentDescription = null,
                modifier = Modifier.size(200.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(id = R.string.wireguard_no_config_msg),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun WireguardOverviewCard(disclaimerText: String) {
    WgCardSurface(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WgIconBadge()
            Text(
                text = disclaimerText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WgSplitFab(
    modifier: Modifier,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCreateClick: () -> Unit,
    onImportClick: () -> Unit,
    onQrClick: () -> Unit
) {
    val createLabel = stringResource(R.string.lbl_create)
    val moreDescription = stringResource(R.string.wireguard_fab_more_actions)
    val expandedStateLabel = stringResource(R.string.wireguard_fab_expanded)
    val collapsedStateLabel = stringResource(R.string.wireguard_fab_collapsed)
    Box(
        modifier = modifier.wrapContentSize()
    ) {
        SplitButtonLayout(
            leadingButton = {
                SplitButtonDefaults.LeadingButton(
                    onClick = onCreateClick,
                    modifier = Modifier
                        .height(56.dp)
                        .semantics { contentDescription = createLabel }
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_add),
                        modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.size(ButtonDefaults.IconSpacing))
                    Text(text = stringResource(id = R.string.lbl_create))
                }
            },
            trailingButton = {
                Box {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                            TooltipAnchorPosition.Above
                        ),
                        tooltip = { PlainTooltip { Text(text = moreDescription) } },
                        state = rememberTooltipState()
                    ) {
                        SplitButtonDefaults.TrailingButton(
                            checked = expanded,
                            onCheckedChange = onExpandedChange,
                            modifier = Modifier
                                .height(56.dp)
                                .semantics {
                                    stateDescription = if (expanded) {
                                        expandedStateLabel
                                    } else {
                                        collapsedStateLabel
                                    }
                                    contentDescription = moreDescription
                                }
                        ) {
                            val rotation by animateFloatAsState(
                                targetValue = if (expanded) 180f else 0f,
                                label = "wgSplitArrowRotation"
                            )
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                modifier = Modifier
                                    .size(SplitButtonDefaults.TrailingIconSize)
                                    .graphicsLayer { rotationZ = rotation },
                                contentDescription = null
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { onExpandedChange(false) }
                    ) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(id = R.string.lbl_import)) },
                            onClick = onImportClick,
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_import_conf),
                                    contentDescription = null
                                )
                            }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(text = stringResource(id = R.string.lbl_qr_code)) },
                            onClick = onQrClick,
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_qr_code_scanner),
                                    contentDescription = null
                                )
                            }
                        )
                    }
                }
            }
        )
    }
}

private fun logEvent(eventLogger: EventLogger, msg: String, details: String) {
    eventLogger.log(EventType.PROXY_SWITCH, Severity.LOW, msg, EventSource.UI, false, details)
}
