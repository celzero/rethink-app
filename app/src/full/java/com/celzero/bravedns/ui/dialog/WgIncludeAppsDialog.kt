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
package com.celzero.bravedns.ui.dialog

import android.graphics.Color as AndroidColor
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.IncludeAppRow
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.database.ProxyApplicationMapping
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.ui.compose.firewall.IndexedFastScroller
import com.celzero.bravedns.ui.compose.theme.CardPosition
import com.celzero.bravedns.ui.compose.theme.CompactEmptyState
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkSearchField
import com.celzero.bravedns.ui.compose.theme.RethinkTopBar
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Locale

@Composable
fun WgIncludeAppsDialog(
    viewModel: ProxyAppsMappingViewModel,
    proxyId: String,
    proxyName: String,
    onDismiss: () -> Unit
) {
    WgDialog(onDismissRequest = onDismiss) {
        WgIncludeAppsDialogScreen(
            viewModel = viewModel,
            proxyId = proxyId,
            proxyName = proxyName,
            onDismiss = onDismiss
        )
    }
}

@Composable
fun WgIncludeAppsScreen(
    viewModel: ProxyAppsMappingViewModel,
    proxyId: String,
    proxyName: String,
    onDismiss: () -> Unit
) {
    WgIncludeAppsDialogScreen(
        viewModel = viewModel,
        proxyId = proxyId,
        proxyName = proxyName,
        onDismiss = onDismiss,
        inDialog = false
    )
}

private const val REFRESH_TIMEOUT: Long = 4000
private val FAST_SCROLLER_LIST_END_PADDING = 32.dp
private val DONE_FAB_CLEARANCE = 112.dp

enum class TopLevelFilter(val id: Int) {
    ALL_APPS(0),
    SELECTED_APPS(1),
    UNSELECTED_APPS(2);

    fun getLabelId(): Int {
        return when (this) {
            ALL_APPS -> R.string.lbl_all
            SELECTED_APPS -> R.string.rt_filter_parent_selected
            UNSELECTED_APPS -> R.string.lbl_unselected
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun WgIncludeAppsDialogScreen(
    viewModel: ProxyAppsMappingViewModel,
    proxyId: String,
    proxyName: String,
    onDismiss: () -> Unit,
    inDialog: Boolean = true
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val refreshDatabase = remember { RefreshDatabaseProvider.get() }
    var query by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(TopLevelFilter.ALL_APPS) }
    val apps by viewModel.apps.collectAsState(initial = emptyList())
    val allApps by viewModel.allApps.collectAsState(initial = emptyList())
    val listState = rememberLazyListState()
    var isDialogVisible by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var excludedUids by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val density = LocalDensity.current
    val navBarBottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
    val showFastScroller = apps.size >= 8
    val fastScrollerKeys = remember(apps) { buildFastScrollerIndexKeys(apps) }

    if (inDialog) {
        TransparentDialogSystemBars()
    } else {
        BackHandler(onBack = onDismiss)
    }

    fun updateInterfaceDetails(mapping: com.celzero.bravedns.database.ProxyApplicationMapping, include: Boolean) {
        scope.launch(Dispatchers.IO) {
            if (FirewallManager.isAppExcludedFromProxy(mapping.uid)) {
                withContext(Dispatchers.Main) {
                    Utilities.showToastUiCentered(
                        context,
                        context.getString(R.string.exclude_apps_from_proxy_failure_toast),
                        Toast.LENGTH_LONG
                    )
                }
                return@launch
            }
            if (include) {
                ProxyManager.updateProxyIdForPackage(mapping.uid, mapping.packageName, proxyId, proxyName)
            } else {
                ProxyManager.setNoProxyForPackage(mapping.uid, mapping.packageName)
            }
        }
    }

    fun selectAllApps() {
        val appSnapshot = allApps.distinctBy { it.uid to it.packageName }
        val excludedSnapshot = excludedUids
        scope.launch(Dispatchers.IO) {
            // Apply selection in one DB/cache update so the UI reflects quickly.
            ProxyManager.setProxyIdForAllApps(proxyId, proxyName)

            // Keep excluded apps out of proxy routing.
            if (excludedSnapshot.isNotEmpty()) {
                appSnapshot
                    .asSequence()
                    .filter { excludedSnapshot.contains(it.uid) }
                    .forEach { mapping ->
                        ProxyManager.setNoProxyForPackage(mapping.uid, mapping.packageName)
                    }
            } else {
                appSnapshot.forEach { mapping ->
                    if (FirewallManager.isAppExcludedFromProxy(mapping.uid)) {
                        ProxyManager.setNoProxyForPackage(mapping.uid, mapping.packageName)
                    }
                }
            }
        }
    }

    fun unselectAllApps() {
        val appSnapshot = allApps.distinctBy { it.uid to it.packageName }
        scope.launch(Dispatchers.IO) {
            ProxyManager.removeProxyId(proxyId)
            // Sweep per app to clear any stale/legacy Orbot mappings missed by id-only bulk update.
            appSnapshot.forEach { mapping ->
                val isMappedToCurrentProxy =
                    mapping.proxyId.equals(proxyId, ignoreCase = true) ||
                        mapping.proxyName.equals(proxyName, ignoreCase = true)
                if (isMappedToCurrentProxy) {
                    ProxyManager.setNoProxyForPackage(mapping.uid, mapping.packageName)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { isDialogVisible = false }
    }

    LaunchedEffect(query, selectedFilter) {
        viewModel.setFilter(query, selectedFilter, proxyId)
    }

    LaunchedEffect(allApps) {
        val snapshot = allApps
        excludedUids =
            withContext(Dispatchers.IO) {
                val excluded = mutableSetOf<Int>()
                snapshot.forEach { mapping ->
                    if (FirewallManager.isAppExcludedFromProxy(mapping.uid)) {
                        excluded.add(mapping.uid)
                    }
                }
                excluded
            }
    }

    fun refreshApps() {
        if (isRefreshing) return
        isRefreshing = true
        scope.launch(Dispatchers.IO) {
            refreshDatabase.refresh(RefreshDatabase.ACTION_REFRESH_INTERACTIVE)
        }
        scope.launch {
            delay(REFRESH_TIMEOUT)
            if (isDialogVisible) {
                isRefreshing = false
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.refresh_complete),
                    Toast.LENGTH_SHORT
                )
            }
        }
    }

    val allAppsSelected =
        allApps.isNotEmpty() &&
            allApps.all { mapping ->
                excludedUids.contains(mapping.uid) ||
                    mapping.proxyId.equals(proxyId, ignoreCase = true) ||
                    mapping.proxyName.equals(proxyName, ignoreCase = true)
            }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = if (inDialog) Color.Transparent else MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButtonPosition = FabPosition.Center,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onDismiss,
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null
                    )
                },
                text = { Text(text = stringResource(R.string.lbl_done)) },
                modifier = Modifier.padding(bottom = navBarBottomInset + Dimensions.spacingSm)
            )
        },
        topBar = {
            RethinkTopBar(
                title = proxyName,
                onBackClick = onDismiss,
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surface,
                actions = {
                    ProxyAppsTopBarFilterGroup(
                        selectedFilter = selectedFilter,
                        onFilterChange = { selectedFilter = it }
                    )
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.cd_more)
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false }
                    ) {
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = null
                                )
                            },
                            text = {
                                Text(
                                    text =
                                        if (isRefreshing) {
                                            stringResource(R.string.lbl_loading)
                                        } else {
                                            stringResource(R.string.cd_refresh)
                                        }
                                )
                            },
                            enabled = !isRefreshing,
                            onClick = {
                                showOverflowMenu = false
                                refreshApps()
                            }
                        )
                        DropdownMenuItem(
                            leadingIcon = {
                                Icon(
                                    imageVector =
                                        if (allAppsSelected) {
                                            Icons.Filled.Clear
                                        } else {
                                            Icons.Filled.Check
                                        },
                                    contentDescription = null
                                )
                            },
                            text = {
                                Text(
                                    text =
                                        if (allAppsSelected) {
                                            stringResource(R.string.lbl_unselect_all)
                                        } else {
                                            stringResource(R.string.lbl_select_all)
                                        }
                                )
                            },
                            onClick = {
                                showOverflowMenu = false
                                if (allAppsSelected) {
                                    unselectAllApps()
                                } else {
                                    selectAllApps()
                                }
                            }
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = Dimensions.screenPaddingHorizontal),
                contentPadding =
                    PaddingValues(
                        end = if (showFastScroller) FAST_SCROLLER_LIST_END_PADDING else 0.dp,
                        bottom = DONE_FAB_CLEARANCE + navBarBottomInset
                    ),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                item {
                    ProxyAppsControlDeck(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = Dimensions.spacingSm),
                        query = query,
                        onQueryChange = { query = it }
                    )
                }

                if (apps.isEmpty()) {
                    item {
                        CompactEmptyState(
                            message = stringResource(R.string.fapps_empty_subtitle)
                        )
                    }
                }
                for (index in apps.indices) {
                    val item = apps[index]
                    val currentInitial = appInitial(item.appName, item.packageName)
                    val previousInitial =
                        if (index > 0) {
                            appInitial(apps[index - 1].appName, apps[index - 1].packageName)
                        } else {
                            null
                        }
                    val nextInitial =
                        if (index < apps.size - 1) {
                            appInitial(apps[index + 1].appName, apps[index + 1].packageName)
                        } else {
                            null
                        }
                    val isFirstInGroup = previousInitial == null || currentInitial != previousInitial
                    val isLastInGroup = nextInitial == null || currentInitial != nextInitial

                    if (isFirstInGroup) {
                        stickyHeader(key = "proxy_header_$currentInitial") {
                            ProxyAppsLetterHeader(letter = currentInitial)
                        }
                    }

                    item(key = "proxy_app_${item.uid}_${item.packageName}") {
                        IncludeAppRow(
                            mapping = item,
                            proxyId = proxyId,
                            position =
                                when {
                                    isFirstInGroup && isLastInGroup -> CardPosition.Single
                                    isFirstInGroup -> CardPosition.First
                                    isLastInGroup -> CardPosition.Last
                                    else -> CardPosition.Middle
                                },
                            onInterfaceUpdate = { mapping, include ->
                                updateInterfaceDetails(mapping, include)
                            }
                        )
                    }
                }
            }

            if (showFastScroller) {
                IndexedFastScroller(
                    items = fastScrollerKeys,
                    listState = listState,
                    getIndexKey = { it },
                    scrollItemOffset = 2,
                    minItemCount = 8,
                    modifier =
                        Modifier
                            .align(Alignment.CenterEnd)
                            .padding(top = Dimensions.spacingSm, bottom = Dimensions.spacingSm + navBarBottomInset)
                            .padding(end = 2.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProxyAppsTopBarFilterGroup(
    selectedFilter: TopLevelFilter,
    onFilterChange: (TopLevelFilter) -> Unit
) {
    val options = listOf(TopLevelFilter.ALL_APPS, TopLevelFilter.SELECTED_APPS)
    val selectedOption =
        if (selectedFilter == TopLevelFilter.UNSELECTED_APPS) {
            TopLevelFilter.ALL_APPS
        } else {
            selectedFilter
        }

    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selectedOption
            ToggleButton(
                checked = isSelected,
                onCheckedChange = { checked ->
                    if (checked && !isSelected) onFilterChange(option)
                },
                shapes =
                    when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                colors =
                    ToggleButtonDefaults.toggleButtonColors(
                        checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                border = null,
                modifier =
                    Modifier
                        .heightIn(min = 34.dp)
                        .semantics { role = Role.RadioButton }
            ) {
                Text(
                    text = stringResource(option.getLabelId()),
                    maxLines = 1,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}

private fun buildFastScrollerIndexKeys(loadedItems: List<ProxyApplicationMapping>): List<String> {
    val indexKeys = mutableListOf<String>()
    var previousInitial: String? = null

    loadedItems.forEach { item ->
        val initial = appInitial(item.appName, item.packageName)
        if (initial != previousInitial) {
            indexKeys.add(initial)
            previousInitial = initial
        }
        indexKeys.add(item.appName.ifBlank { item.packageName })
    }

    return indexKeys
}

@Composable
private fun TransparentDialogSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            val originalNavBarColor = window.navigationBarColor
            val originalNavBarDividerColor =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.navigationBarDividerColor
                } else {
                    null
                }
            val originalContrastEnforced =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced
                } else {
                    null
                }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.navigationBarColor = AndroidColor.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.navigationBarDividerColor = AndroidColor.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
            onDispose {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                window.navigationBarColor = originalNavBarColor
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && originalNavBarDividerColor != null) {
                    window.navigationBarDividerColor = originalNavBarDividerColor
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && originalContrastEnforced != null) {
                    window.isNavigationBarContrastEnforced = originalContrastEnforced
                }
            }
        } else {
            onDispose {}
        }
    }
}

@Composable
private fun ProxyAppsControlDeck(
    modifier: Modifier = Modifier,
    query: String,
    onQueryChange: (String) -> Unit
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RethinkSearchField(
            modifier = Modifier.fillMaxWidth(),
            query = query,
            onQueryChange = onQueryChange,
            placeholder = stringResource(R.string.search_proxy_add_apps),
            onClearQuery = { onQueryChange("") },
            clearQueryContentDescription = stringResource(R.string.cd_clear_search),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            iconSize = 18.dp,
            trailingIconSize = 16.dp,
            trailingIconButtonSize = 32.dp
        )
    }
}

private object RefreshDatabaseProvider : KoinComponent {
    val refreshDatabase: RefreshDatabase by inject()

    fun get(): RefreshDatabase = refreshDatabase
}

@Composable
private fun ProxyAppsLetterHeader(letter: String) {
    androidx.compose.foundation.layout.Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 20.dp, top = 20.dp, bottom = 4.dp)
    ) {
        Text(
            text = letter,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun appInitial(appName: String, packageName: String): String {
    val source = appName.ifBlank { packageName }.trim()
    if (source.isEmpty()) return "#"
    val first = source.first()
    return if (first.isLetter()) {
        first.uppercaseChar().toString()
    } else {
        source.first().toString().uppercase(Locale.getDefault())
    }
}
