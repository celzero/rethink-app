/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.ezelab.rethinktv.ui.common.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.database.DnsLogDAO
import com.celzero.bravedns.service.FirewallManager
import com.ezelab.rethinktv.ui.common.TvScreenScaffold
import com.ezelab.rethinktv.ui.common.rememberAsImmutableState
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ALL_APPS_UID = -1

private enum class LogTab(val label: String) {
    CONNECTIONS("Connections"),
    DNS("DNS queries"),
    BLOCKED("Blocked only"),
}

private data class LogAppFilter(
    val uid: Int,
    val appName: String,
)

private fun AppInfo.toLogAppFilter(): LogAppFilter {
    val label = appName.ifBlank { packageName }.ifBlank { "uid $uid" }
    return LogAppFilter(uid = uid, appName = label)
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
 * Three tabs:
 *
 *  * **Connections** — every TCP / UDP connection Rethink has seen,
 *    most-recent first (DAO orders DESC and caps at MAX_LOGS).
 *  * **DNS queries** — every DNS lookup Rethink resolved, with
 *    latency and resolver IP.
 *  * **Blocked only** — same data as Connections filtered to
 *    `isBlocked = 1` rows; useful for audits.
 *
 * Search text and per-app filtering are lifted into this top-level
 * screen state so switching tabs preserves the current filter context.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LogsScreen() {
    var activeTab by rememberSaveable { mutableStateOf(LogTab.CONNECTIONS) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedUid by rememberSaveable { mutableStateOf(ALL_APPS_UID) }
    var showAppPicker by rememberSaveable { mutableStateOf(false) }

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

    TvScreenScaffold(
        title = "Logs",
        subtitle = "Search by text and optionally scope results to one app.",
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SearchControls(
                    query = query,
                    selectedAppName = selectedAppName,
                    onQueryChange = { query = it },
                    onOpenAppPicker = { showAppPicker = true },
                )
                LogTabs(active = activeTab, onSelect = { activeTab = it })

                when (activeTab) {
                    LogTab.CONNECTIONS -> ConnectionsList(
                        blockedOnly = false,
                        query = query,
                        selectedUid = selectedUid,
                    )
                    LogTab.BLOCKED -> ConnectionsList(
                        blockedOnly = true,
                        query = query,
                        selectedUid = selectedUid,
                    )
                    LogTab.DNS -> DnsList(
                        query = query,
                        selectedUid = selectedUid,
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
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchControls(
    query: String,
    selectedAppName: String,
    onQueryChange: (String) -> Unit,
    onOpenAppPicker: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchField(
            query = query,
            onQueryChange = onQueryChange,
            modifier = Modifier.weight(1f),
        )
        Surface(
            onClick = onOpenAppPicker,
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
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
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
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
        shape = RoundedCornerShape(12.dp),
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            colors = SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .fillMaxHeight(0.8f),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Choose app",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
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
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
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
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = options, key = { it.uid }) { option ->
                        val selected = option.uid == selectedUid
                        Surface(
                            onClick = { onSelect(option.uid) },
                            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (selected) "✓" else " ",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
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
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LogTabs(active: LogTab, onSelect: (LogTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LogTab.entries.forEach { tab ->
            val selected = tab == active
            Surface(
                onClick = { onSelect(tab) },
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionsList(
    blockedOnly: Boolean,
    query: String,
    selectedUid: Int,
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
        emptyText = if (query.isBlank() && selectedUid == ALL_APPS_UID) {
            "No log entries yet. Start protection to begin recording traffic."
        } else {
            "No connection logs match the current search and app filter."
        },
    ) { row ->
        ConnectionRow(row)
    }
}

@Composable
private fun DnsList(
    query: String,
    selectedUid: Int,
) {
    val dao = koinInject<DnsLogDAO>()
    val searchTerm = remember(query) {
        query.trim().takeIf { it.isNotEmpty() }?.let { "%$it%" }
    }
    val pager = remember(searchTerm, selectedUid) {
        Pager(
            config = PagingConfig(
                pageSize = 50,
                enablePlaceholders = false,
                initialLoadSize = 100,
            ),
        ) {
            when {
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
        emptyText = if (query.isBlank() && selectedUid == ALL_APPS_UID) {
            "No DNS log entries yet. Start protection to begin recording traffic."
        } else {
            "No DNS logs match the current search and app filter."
        },
    ) { row ->
        DnsRow(row)
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
                .padding(bottom = 24.dp),
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
private fun ConnectionRow(c: ConnectionTracker) {
    Surface(
        onClick = { /* row details TBD in polish phase */ },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BlockChip(blocked = c.isBlocked)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = c.appName.ifBlank { "uid ${c.uid}" } +
                        "  →  " + c.ipAddress + (if (c.port > 0) ":${c.port}" else ""),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
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
            Spacer(Modifier.width(12.dp))
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
private fun DnsRow(d: DnsLog) {
    Surface(
        onClick = { /* row details TBD in polish phase */ },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BlockChip(blocked = d.isBlocked)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = d.queryStr.ifBlank { "(empty query)" },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
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
            Spacer(Modifier.width(12.dp))
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
    val color = if (blocked) Color(0xFFC62828) else Color(0xFF2E7D32)
    val label = if (blocked) "BLK" else "OK"
    Box(
        modifier = Modifier
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
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
