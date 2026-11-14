/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.logs

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.tv.ui.common.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.celzero.bravedns.tv.ui.theme.TvColors
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.database.DnsLogDAO
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.tv.ui.common.TvButton
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.common.TvSelectableChip
import com.celzero.bravedns.tv.ui.common.TvTabRow
import com.celzero.bravedns.tv.ui.common.rememberAsImmutableState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ALL_APPS_UID = -1

// mirrors phone R.string.ci_dialog_error_invalid_cidr; TV sources keep hardcoded-English copy
private const val INVALID_CIDR_MSG =
    "Not a valid CIDR. IP ranges (e.g. 1.1.1.1-55) and non-aligned wildcards are not supported. " +
        "Use CIDR notation like 1.1.1.0/24."

private enum class LogTab(val label: String) {
    CONNECTIONS("Connections"),
    DNS("DNS queries"),
}

private data class LogAppFilter(
    val uid: Int,
    val appName: String,
)

private fun AppInfo.toLogAppFilter(): LogAppFilter {
    val label = appName.ifBlank { packageName }.ifBlank { "uid $uid" }
    return LogAppFilter(uid = uid, appName = label)
}

/** Everything the rule sheet needs, captured at row-click time. */
private data class RuleSheetEntry(
    val title: String,
    val appName: String,
    val uid: Int,
    val domain: String?,
    val ip: String?,
    val port: Int,
)

private fun buildRuleSheetEntry(c: ConnectionTracker): RuleSheetEntry {
    val domain = c.dnsQuery?.takeIf { it.isNotBlank() }
    val ip = c.ipAddress.takeIf { it.isNotBlank() }
    return RuleSheetEntry(
        title = domain ?: ip ?: "uid ${c.uid}",
        appName = c.appName.ifBlank { "uid ${c.uid}" },
        uid = c.uid,
        domain = domain,
        ip = ip,
        port = c.port,
    )
}

private fun buildRuleSheetEntry(d: DnsLog): RuleSheetEntry {
    val domain = d.queryStr.takeIf { it.isNotBlank() }
    val ip = d.responseIps.split(",").firstOrNull { it.isNotBlank() }?.trim()
    return RuleSheetEntry(
        title = domain ?: ip ?: "(empty query)",
        appName = d.appName.ifBlank { "uid ${d.uid}" },
        uid = d.uid,
        domain = domain,
        ip = ip,
        port = 0,
    )
}

/**
 * Logs destination — connection-level diagnostics.
 *
 * Powered by androidx.paging:paging-compose, which lets us hand the
 * existing upstream `PagingSource` from `ConnectionTrackerDAO` and
 * `DnsLogDAO` straight into Compose without recreating any of the
 * paging pipeline. Each row is focus-able as its own TV-material
 * surface, so D-pad navigation walks the list naturally.
 *
 * Two tabs:
 *
 *  * **Connections** — every TCP / UDP connection Rethink has seen,
 *    most-recent first (DAO orders DESC and caps at MAX_LOGS).
 *  * **DNS queries** — every DNS lookup Rethink resolved, with
 *    latency and resolver IP.
 *
 * An All/Blocked chip filter applies to whichever tab is active.
 * Search text and per-app filtering are lifted into this top-level
 * screen state so switching tabs preserves the current filter context.
 * Selecting a row opens a rule sheet to block/trust the row's
 * domain or IP.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LogsScreen() {
    var activeTab by rememberSaveable { mutableStateOf(LogTab.CONNECTIONS) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedUid by rememberSaveable { mutableStateOf(ALL_APPS_UID) }
    var blockedOnly by rememberSaveable { mutableStateOf(false) }
    var showAppPicker by rememberSaveable { mutableStateOf(false) }
    var sheetEntry by remember { mutableStateOf<RuleSheetEntry?>(null) }

    val appFilters by rememberAsImmutableState(
        liveData = FirewallManager.getApplistObserver(),
        initial = listOf(LogAppFilter(uid = ALL_APPS_UID, appName = "All apps")),
    ) { apps ->
        buildList {
            add(LogAppFilter(uid = ALL_APPS_UID, appName = "All apps"))
            val deduped = linkedMapOf<Int, LogAppFilter>()
            apps.orEmpty().forEach { app ->
                val option = app.toLogAppFilter()
                val existing = deduped[option.uid]
                if (existing == null || option.appName.lowercase(Locale.ROOT) < existing.appName.lowercase(Locale.ROOT)) {
                    deduped[option.uid] = option
                }
            }
            addAll(deduped.values.sortedBy { it.appName.lowercase(Locale.ROOT) })
        }
    }

    val selectedAppName = appFilters.firstOrNull { it.uid == selectedUid }?.appName
        ?: if (selectedUid == ALL_APPS_UID) "All apps" else "uid $selectedUid"

    val overlayVisible = showAppPicker || sheetEntry != null

    TvScreenScaffold(
        title = "Logs",
        subtitle = "Search by text and optionally scope results to one app.",
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // trap D-pad focus away from the list while an overlay is up
                    .then(
                        if (overlayVisible) Modifier.focusProperties { canFocus = false }
                        else Modifier
                    ),
                verticalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
            ) {
                SearchControls(
                    query = query,
                    blockedOnly = blockedOnly,
                    selectedAppName = selectedAppName,
                    onQueryChange = { query = it },
                    onBlockedOnlyChange = { blockedOnly = it },
                    onOpenAppPicker = { showAppPicker = true },
                )
                LogTabs(active = activeTab, onSelect = { activeTab = it })

                when (activeTab) {
                    LogTab.CONNECTIONS -> ConnectionsList(
                        blockedOnly = blockedOnly,
                        query = query,
                        selectedUid = selectedUid,
                        onRowClick = { sheetEntry = buildRuleSheetEntry(it) },
                    )
                    LogTab.DNS -> DnsList(
                        blockedOnly = blockedOnly,
                        query = query,
                        selectedUid = selectedUid,
                        onRowClick = { sheetEntry = buildRuleSheetEntry(it) },
                    )
                }
            }

            if (showAppPicker) {
                AppFilterPicker(
                    options = appFilters,
                    selectedUid = selectedUid,
                    onSelect = {
                        selectedUid = it
                        showAppPicker = false
                    },
                    onDismiss = { showAppPicker = false },
                )
            }

            sheetEntry?.let { entry ->
                LogRuleSheet(
                    entry = entry,
                    onDismiss = { sheetEntry = null },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchControls(
    query: String,
    blockedOnly: Boolean,
    selectedAppName: String,
    onQueryChange: (String) -> Unit,
    onBlockedOnlyChange: (Boolean) -> Unit,
    onOpenAppPicker: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchField(
            query = query,
            onQueryChange = onQueryChange,
            modifier = Modifier.weight(1f),
        )
        TvSelectableChip(
            label = "All",
            selected = !blockedOnly,
            onClick = { onBlockedOnlyChange(false) },
        )
        TvSelectableChip(
            label = "Blocked",
            selected = blockedOnly,
            onClick = { onBlockedOnlyChange(true) },
        )
        Surface(
            onClick = onOpenAppPicker,
            shape = TvSurfaceStyles.interactiveShape(),
            colors = TvSurfaceStyles.rowColors(),
            modifier = Modifier.width(260.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = "App filter",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = selectedAppName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hasFocus by remember { mutableStateOf(false) }
    Surface(
        shape = TvShapes.Small,
        colors = SurfaceDefaults.colors(
            containerColor = if (hasFocus) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (hasFocus) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = modifier,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = if (hasFocus) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .focusable()
                .onFocusChanged { hasFocus = it.isFocused }
                .padding(horizontal = 16.dp, vertical = 16.dp),
            decorationBox = { innerTextField ->
                if (query.isBlank()) {
                    Text(
                        text = "Search app, domain, IP, rule…",
                        color = if (hasFocus) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                innerTextField()
            },
        )
    }
}

/**
 * Shared overlay chrome: scrim + centered card over the log list.
 * Callers place their own header/list inside [content]. D-pad focus
 * stays inside because the background column is made unfocusable via
 * focusProperties while any overlay is visible.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OverlayBackdrop(
    cardWidthFraction: Float = 0.62f,
    cardHeightFraction: Float? = 0.8f,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Scrim.copy(alpha = 0.72f))
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = TvShapes.Medium,
            colors = SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier
                .fillMaxWidth(cardWidthFraction)
                .then(
                    if (cardHeightFraction != null) Modifier.fillMaxHeight(cardHeightFraction)
                    else Modifier.heightIn(max = 720.dp)
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // unconstrained card scrolls; the fixed-height one fills
                    .then(
                        if (cardHeightFraction != null) Modifier.fillMaxSize()
                        else Modifier.verticalScroll(rememberScrollState())
                    )
                    .padding(20.dp),
                content = content,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppFilterPicker(
    options: List<LogAppFilter>,
    selectedUid: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val closeFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        closeFocusRequester.requestFocus()
    }

    OverlayBackdrop {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Choose app",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Scope logs to one app uid or keep showing all apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Surface(
                onClick = onDismiss,
                shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Pill),
                colors = TvSurfaceStyles.rowColors(),
                modifier = Modifier.focusRequester(closeFocusRequester),
            ) {
                Text(
                    text = "Close",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize().clip(TvShapes.Small),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
        ) {
            items(items = options, key = { it.uid }) { option ->
                val selected = option.uid == selectedUid
                Surface(
                    onClick = { onSelect(option.uid) },
                    shape = TvSurfaceStyles.interactiveShape(),
                    colors = TvSurfaceStyles.railItemColors(selected = selected),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (selected) "✓" else " ",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.width(24.dp),
                        )
                        Text(
                            text = option.appName,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LogTabs(active: LogTab, onSelect: (LogTab) -> Unit) {
    TvTabRow(
        tabs = LogTab.entries.toList(),
        selected = active,
        label = { it.label },
        onSelect = onSelect,
    )
}

@Composable
private fun ConnectionsList(
    blockedOnly: Boolean,
    query: String,
    selectedUid: Int,
    onRowClick: (ConnectionTracker) -> Unit,
) {
    val dao = koinInject<ConnectionTrackerDAO>()
    val searchTerm = remember(query) {
        query.trim().takeIf { it.isNotEmpty() }?.let { "%$it%" }
    }

    val pager = remember(blockedOnly, searchTerm, selectedUid) {
        Pager(
            config = PagingConfig(
                pageSize = 50,
                enablePlaceholders = false,
                initialLoadSize = 100,
            ),
        ) {
            when {
                blockedOnly && selectedUid != ALL_APPS_UID && searchTerm != null ->
                    dao.getBlockedConnections(searchTerm, selectedUid)
                blockedOnly && selectedUid != ALL_APPS_UID ->
                    dao.getBlockedConnections(selectedUid)
                blockedOnly && searchTerm != null ->
                    dao.getBlockedConnections(searchTerm)
                blockedOnly -> dao.getBlockedConnections()
                selectedUid != ALL_APPS_UID && searchTerm != null ->
                    dao.getConnectionTrackerByName(searchTerm, selectedUid)
                selectedUid != ALL_APPS_UID ->
                    dao.getConnectionTrackerByName(selectedUid)
                searchTerm != null ->
                    dao.getConnectionTrackerByName(searchTerm)
                else -> dao.getConnectionTrackerByName()
            }
        }
    }
    val items = pager.flow.collectAsLazyPagingItems()
    PagedList(
        items = items,
        emptyText = when {
            blockedOnly -> "No blocked connection logs match the current filters."
            query.isBlank() && selectedUid == ALL_APPS_UID ->
                "No log entries yet. Start protection to begin recording traffic."
            else -> "No connection logs match the current search and app filter."
        },
    ) { row ->
        ConnectionRow(row, onClick = { onRowClick(row) })
    }
}

@Composable
private fun DnsList(
    blockedOnly: Boolean,
    query: String,
    selectedUid: Int,
    onRowClick: (DnsLog) -> Unit,
) {
    val dao = koinInject<DnsLogDAO>()
    val searchTerm = remember(query) {
        query.trim().takeIf { it.isNotEmpty() }?.let { "%$it%" }
    }
    val pager = remember(blockedOnly, searchTerm, selectedUid) {
        Pager(
            config = PagingConfig(
                pageSize = 50,
                enablePlaceholders = false,
                initialLoadSize = 100,
            ),
        ) {
            when {
                blockedOnly && selectedUid != ALL_APPS_UID && searchTerm != null ->
                    dao.getBlockedDnsLogsByName(searchTerm, selectedUid)
                blockedOnly && selectedUid != ALL_APPS_UID ->
                    dao.getBlockedDnsLogs(selectedUid)
                blockedOnly && searchTerm != null ->
                    dao.getBlockedDnsLogsByName(searchTerm)
                blockedOnly -> dao.getBlockedDnsLogs()
                selectedUid != ALL_APPS_UID && searchTerm != null ->
                    dao.getDnsLogsByName(searchTerm, selectedUid)
                selectedUid != ALL_APPS_UID ->
                    dao.getAllDnsLogs(selectedUid)
                searchTerm != null ->
                    dao.getDnsLogsByName(searchTerm)
                else -> dao.getAllDnsLogs()
            }
        }
    }
    val items = pager.flow.collectAsLazyPagingItems()
    PagedList(
        items = items,
        emptyText = when {
            blockedOnly -> "No blocked DNS logs match the current filters."
            query.isBlank() && selectedUid == ALL_APPS_UID ->
                "No DNS log entries yet. Start protection to begin recording traffic."
            else -> "No DNS logs match the current search and app filter."
        },
    ) { row ->
        DnsRow(row, onClick = { onRowClick(row) })
    }
}

@Composable
private fun <T : Any> PagedList(
    items: LazyPagingItems<T>,
    emptyText: String,
    row: @Composable (T) -> Unit,
) {
    val isLoading = items.loadState.refresh is LoadState.Loading
    val isEmpty = items.loadState.refresh is LoadState.NotLoading && items.itemCount == 0

    when {
        isLoading -> CenterLabel("Loading…")
        isEmpty -> CenterLabel(emptyText)
        else -> LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = TvSpacing.ScreenVertical),
        ) {
            items(
                count = items.itemCount,
                key = items.itemKey { keyFor(it) },
            ) { index ->
                val item = items[index] ?: return@items
                row(item)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CenterLabel(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 32.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private fun keyFor(item: Any): Any = when (item) {
    is ConnectionTracker -> "ct/${item.id}"
    is DnsLog -> "dns/${item.id}"
    else -> item.hashCode()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConnectionRow(c: ConnectionTracker, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.ExtraSmall),
        colors = TvSurfaceStyles.rowColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BlockChip(blocked = c.isBlocked)
            Spacer(Modifier.width(TvSpacing.GroupGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = c.appName.ifBlank { "uid ${c.uid}" } +
                        "  →  " + c.ipAddress + (if (c.port > 0) ":${c.port}" else ""),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(c.dnsQuery.orEmpty().ifBlank { protocolName(c.protocol) })
                        if (c.blockedByRule.isNotBlank()) {
                            append("  ·  ")
                            append(c.blockedByRule)
                        }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(TvSpacing.GroupGap))
            Text(
                text = formatTs(c.timeStamp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DnsRow(d: DnsLog, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.ExtraSmall),
        colors = TvSurfaceStyles.rowColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BlockChip(blocked = d.isBlocked)
            Spacer(Modifier.width(TvSpacing.GroupGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = d.queryStr.ifBlank { "(empty query)" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(d.typeName.ifBlank { "?" })
                        append("  ·  ")
                        append(d.resolver.ifBlank { d.serverIP.ifBlank { "—" } })
                        if (d.latency > 0) {
                            append("  ·  ")
                            append(d.latency).append(" ms")
                        }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(TvSpacing.GroupGap))
            Text(
                text = formatTs(d.time),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BlockChip(blocked: Boolean) {
    val color = if (blocked) MaterialTheme.colorScheme.error else TvColors.Success
    val onColor = if (blocked) MaterialTheme.colorScheme.onError else TvColors.OnSuccess
    val label = if (blocked) "BLK" else "OK"
    Box(
        modifier = Modifier
            .background(color, TvShapes.Pill)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            color = onColor,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * Overlay sheet to block/trust/clear the domain and IP of a log row.
 * Mirrors the phone's ConnTracker/DnsBlocklist bottom sheets with
 * explicit buttons instead of tap-to-cycle; state is read and written
 * through the in-memory rule managers.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LogRuleSheet(
    entry: RuleSheetEntry,
    onDismiss: () -> Unit,
) {
    val eventLogger = koinInject<EventLogger>()
    val closeFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { closeFocusRequester.requestFocus() }
    }

    val realApp = entry.uid != Constants.INVALID_UID && entry.uid != Constants.UID_EVERYBODY

    OverlayBackdrop(cardHeightFraction = null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${entry.appName}  ·  uid ${entry.uid}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Surface(
                onClick = onDismiss,
                shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Pill),
                colors = TvSurfaceStyles.rowColors(),
                modifier = Modifier.focusRequester(closeFocusRequester),
            ) {
                Text(
                    text = "Close",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        entry.domain?.let { domain ->
            Spacer(Modifier.height(TvSpacing.GroupGap))
            SheetSectionLabel("Domain rule")
            if (realApp) {
                DomainRuleRow(
                    domain = domain,
                    scopeUid = entry.uid,
                    scopeLabel = "This app",
                    eventLogger = eventLogger,
                )
                Spacer(Modifier.height(TvSpacing.RowGap))
            }
            DomainRuleRow(
                domain = domain,
                scopeUid = Constants.UID_EVERYBODY,
                scopeLabel = "All apps",
                eventLogger = eventLogger,
            )
        }

        if (entry.ip != null && realApp) {
            Spacer(Modifier.height(TvSpacing.GroupGap))
            SheetSectionLabel("IP rule")
            IpRuleRow(
                ip = entry.ip,
                uid = entry.uid,
                port = entry.port,
                scopeLabel = "This app",
                eventLogger = eventLogger,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(bottom = TvSpacing.HeaderGap),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DomainRuleRow(
    domain: String,
    scopeUid: Int,
    scopeLabel: String,
    eventLogger: EventLogger,
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<DomainRulesManager.Status?>(null) }
    var pending by remember { mutableStateOf(false) }

    LaunchedEffect(domain, scopeUid) {
        status = withContext(Dispatchers.IO) {
            DomainRulesManager.getDomainRule(domain, scopeUid)
        }
    }

    fun apply(next: DomainRulesManager.Status) {
        if (pending || status == null) return
        pending = true
        scope.launch(Dispatchers.IO) {
            try {
                DomainRulesManager.addDomainRule(
                    domain,
                    next,
                    DomainRulesManager.DomainType.DOMAIN,
                    scopeUid,
                )
                eventLogger.log(
                    EventType.FW_RULE_MODIFIED,
                    Severity.LOW,
                    "Domain rule changed",
                    EventSource.UI,
                    false,
                    "Domain: $domain, UID: $scopeUid, DomainRuleStatus: ${next.name}",
                )
                val current = DomainRulesManager.getDomainRule(domain, scopeUid)
                withContext(Dispatchers.Main) { status = current }
            } finally {
                withContext(Dispatchers.Main) { pending = false }
            }
        }
    }

    RuleActionRow(
        scopeLabel = scopeLabel,
        identifier = domain,
        stateText = status?.let { domainStateLabel(it) } ?: "…",
        stateColor = status?.let { domainStateColor(it) }
            ?: MaterialTheme.colorScheme.onSurfaceVariant,
        buttonsEnabled = !pending && status != null,
        onBlock = { apply(DomainRulesManager.Status.BLOCK) },
        onTrust = { apply(DomainRulesManager.Status.TRUST) },
        onClear = { apply(DomainRulesManager.Status.NONE) },
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun IpRuleRow(
    ip: String,
    uid: Int,
    port: Int,
    scopeLabel: String,
    eventLogger: EventLogger,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<IpRulesManager.IpRuleStatus?>(null) }
    var pending by remember { mutableStateOf(false) }

    LaunchedEffect(ip, uid, port) {
        status = withContext(Dispatchers.IO) {
            IpRulesManager.hasRule(uid, ip, port)
        }
    }

    fun apply(next: IpRulesManager.IpRuleStatus) {
        if (pending || status == null) return
        pending = true
        scope.launch(Dispatchers.IO) {
            try {
                val ipPair = IpRulesManager.getIpNetPort(ip)
                val net = ipPair.first
                if (net == null || !IpRulesManager.isCidrEnforceable(net)) {
                    withContext(Dispatchers.Main) {
                        Utilities.showToastUiCentered(
                            context,
                            INVALID_CIDR_MSG,
                            Toast.LENGTH_SHORT,
                        )
                    }
                    return@launch
                }
                IpRulesManager.addIpRule(
                    uid,
                    net,
                    port,
                    next,
                    proxyId = "",
                    proxyCC = "",
                )
                eventLogger.log(
                    EventType.FW_RULE_MODIFIED,
                    Severity.LOW,
                    "IP rule changed",
                    EventSource.UI,
                    false,
                    "UID: $uid, IP: $net, Port: $port, IpRuleStatus: ${next.name}",
                )
                val current = IpRulesManager.hasRule(uid, ip, port)
                withContext(Dispatchers.Main) { status = current }
            } finally {
                withContext(Dispatchers.Main) { pending = false }
            }
        }
    }

    // bypass-universal is read-only here; the ip-rules screen owns it
    val bypass = status == IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL
    RuleActionRow(
        scopeLabel = scopeLabel,
        identifier = ip,
        stateText = status?.let { ipStateLabel(it) } ?: "…",
        stateColor = status?.let { ipStateColor(it) }
            ?: MaterialTheme.colorScheme.onSurfaceVariant,
        buttonsEnabled = !pending && status != null && !bypass,
        onBlock = { apply(IpRulesManager.IpRuleStatus.BLOCK) },
        onTrust = { apply(IpRulesManager.IpRuleStatus.TRUST) },
        onClear = { apply(IpRulesManager.IpRuleStatus.NONE) },
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RuleActionRow(
    scopeLabel: String,
    identifier: String,
    stateText: String,
    stateColor: Color,
    buttonsEnabled: Boolean,
    onBlock: () -> Unit,
    onTrust: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(
        shape = TvShapes.Small,
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = scopeLabel,
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = identifier,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stateText,
                    color = stateColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.width(TvSpacing.GroupGap))
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.HeaderGap)) {
                SheetActionButton("Block", enabled = buttonsEnabled, destructive = true, onClick = onBlock)
                SheetActionButton("Trust", enabled = buttonsEnabled, onClick = onTrust)
                SheetActionButton("Clear", enabled = buttonsEnabled, onClick = onClear)
            }
        }
    }
}

@Composable
private fun SheetActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    TvButton(
        text = label,
        onClick = onClick,
        enabled = enabled,
        destructive = destructive,
        containerColor = if (!destructive && label == "Trust") TvColors.SuccessContainer
            else null,
        contentColor = if (!destructive && label == "Trust") TvColors.OnSuccessContainer
            else null,
        textStyle = MaterialTheme.typography.labelMedium,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    )
}

private fun domainStateLabel(status: DomainRulesManager.Status): String = when (status) {
    DomainRulesManager.Status.NONE -> "No rule"
    DomainRulesManager.Status.BLOCK -> "Blocked"
    DomainRulesManager.Status.TRUST -> "Trusted"
}

@Composable
private fun domainStateColor(status: DomainRulesManager.Status): Color = when (status) {
    DomainRulesManager.Status.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    DomainRulesManager.Status.BLOCK -> MaterialTheme.colorScheme.error
    DomainRulesManager.Status.TRUST -> TvColors.Success
}

private fun ipStateLabel(status: IpRulesManager.IpRuleStatus): String = when (status) {
    IpRulesManager.IpRuleStatus.NONE -> "No rule"
    IpRulesManager.IpRuleStatus.BLOCK -> "Blocked"
    IpRulesManager.IpRuleStatus.TRUST -> "Trusted"
    IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> "Bypass universal"
}

@Composable
private fun ipStateColor(status: IpRulesManager.IpRuleStatus): Color = when (status) {
    IpRulesManager.IpRuleStatus.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    IpRulesManager.IpRuleStatus.BLOCK -> MaterialTheme.colorScheme.error
    IpRulesManager.IpRuleStatus.TRUST -> TvColors.Success
    IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

private val tsFmt: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
    SimpleDateFormat("HH:mm:ss", Locale.getDefault())
}

private fun formatTs(ms: Long): String = tsFmt.get()!!.format(Date(ms))

private fun protocolName(protocol: Int): String = when (protocol) {
    6 -> "TCP"
    17 -> "UDP"
    1 -> "ICMP"
    else -> "proto $protocol"
}
