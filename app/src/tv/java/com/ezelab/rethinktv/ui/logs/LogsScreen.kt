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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.database.DnsLogDAO
import com.ezelab.rethinktv.ui.common.TvScreenScaffold
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogTab(val label: String) {
    CONNECTIONS("Connections"),
    DNS("DNS queries"),
    BLOCKED("Blocked only"),
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
 * Out of scope for v1 (noted in code):
 *
 *  * Free-text search (DAO supports it; TV-friendly text-entry will
 *    come in the polish phase).
 *  * Per-app filtering (already exposed via the Apps detail screen —
 *    a deep-link into Logs filtered by uid will land later).
 *  * Rethink-log subset (rarely needed outside diagnostics).
 *  * Row-level detail expansion / close-connection action.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LogsScreen() {
    var activeTab by remember { mutableStateOf(LogTab.CONNECTIONS) }

    TvScreenScaffold(
        title = "Logs",
        subtitle = "Most-recent first. Filtering & search will land in the polish phase.",
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogTabs(active = activeTab, onSelect = { activeTab = it })

            when (activeTab) {
                LogTab.CONNECTIONS -> ConnectionsList(blockedOnly = false)
                LogTab.BLOCKED -> ConnectionsList(blockedOnly = true)
                LogTab.DNS -> DnsList()
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
private fun ConnectionsList(blockedOnly: Boolean) {
    val dao = koinInject<ConnectionTrackerDAO>()

    val pager = remember(blockedOnly) {
        Pager(
            config = PagingConfig(
                pageSize = 50,
                enablePlaceholders = false,
                initialLoadSize = 100,
            ),
        ) {
            if (blockedOnly) dao.getBlockedConnections() else dao.getConnectionTrackerByName()
        }
    }
    val items = pager.flow.collectAsLazyPagingItems()
    PagedList(items = items, isEmpty = items.itemCount == 0) { row ->
        ConnectionRow(row)
    }
}

@Composable
private fun DnsList() {
    val dao = koinInject<DnsLogDAO>()
    val pager = remember {
        Pager(
            config = PagingConfig(
                pageSize = 50,
                enablePlaceholders = false,
                initialLoadSize = 100,
            ),
        ) {
            dao.getAllDnsLogs()
        }
    }
    val items = pager.flow.collectAsLazyPagingItems()
    PagedList(items = items, isEmpty = items.itemCount == 0) { row ->
        DnsRow(row)
    }
}

@Composable
private fun <T : Any> PagedList(
    items: LazyPagingItems<T>,
    isEmpty: Boolean,
    row: @Composable (T) -> Unit,
) {
    if (isEmpty) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 32.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = "No log entries yet. Start protection to begin recording traffic.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
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
