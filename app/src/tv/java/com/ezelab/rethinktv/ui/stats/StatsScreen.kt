/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.navigation.NavController
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.ezelab.rethinktv.ui.common.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.StatsSummaryDao
import com.ezelab.rethinktv.ui.common.TvScreenScaffold
import kotlinx.coroutines.flow.Flow
import org.koin.compose.koinInject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Stats destination — summary of who used the tunnel, who got
 * blocked, and where bytes went over a chosen time window.
 *
 * Tabs (one [Pager] each):
 *
 *  * **Top apps** — `StatsSummaryDao.getMostAllowedApps(to)` —
 *    foreground apps sorted by total bytes (down + up).
 *  * **Top domains** — `StatsSummaryDao.getMostContactedDomains(to)`.
 *  * **Top IPs** — `ConnectionTrackerDAO.getMostContactedIps(to)`.
 *  * **Blocked apps** — `StatsSummaryDao.getMostBlockedApps(to)`.
 *  * **Blocked domains** — `StatsSummaryDao.getMostBlockedDomains(to)`.
 *
 * Time-window selector (top of the screen, before the tabs):
 *
 *  * 1 h (default)
 *  * 24 h
 *  * 7 d
 *
 * The window maps to a `to` Long upstream's DAO queries treat as
 * `WHERE … timestamp > :to`. The phone's
 * `SummaryStatisticsViewModel` does exactly the same arithmetic.
 *
 * Rows show app/domain/IP + an aggregate count from
 * [AppConnection.count] (or bytes for the allowed-apps query — the
 * value column is whatever the underlying query optimises for, so
 * we just format the int).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StatsScreen(navController: NavController? = null) {
    val statsDao = koinInject<StatsSummaryDao>()
    val connDao = koinInject<ConnectionTrackerDAO>()

    var window by remember { mutableStateOf(StatsWindow.ONE_HOUR) }
    var tab by remember { mutableStateOf(StatsTab.TOP_APPS) }
    val to = System.currentTimeMillis() - window.millis

    TvScreenScaffold(
        title = "Stats",
        subtitle = "Top apps, domains, IPs, and what got blocked.",
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            WindowSelector(current = window, onSelect = { window = it })
            Spacer(Modifier.height(12.dp))
            TabChipRow(active = tab, onSelect = { tab = it })
            Spacer(Modifier.height(12.dp))

            val flow = remember(tab, to) {
                pagerFlowFor(tab, to, statsDao, connDao)
            }
            val items = flow.collectAsLazyPagingItems()
            StatsList(tab = tab, items = items, navController = navController)
        }
    }
}

private fun pagerFlowFor(
    tab: StatsTab,
    to: Long,
    statsDao: StatsSummaryDao,
    connDao: ConnectionTrackerDAO,
): Flow<PagingData<AppConnection>> {
    val config = PagingConfig(pageSize = 25, enablePlaceholders = false)
    return Pager(config) {
        when (tab) {
            StatsTab.TOP_APPS -> statsDao.getMostAllowedApps(to)
            StatsTab.TOP_DOMAINS -> statsDao.getMostContactedDomains(to)
            StatsTab.TOP_IPS -> connDao.getMostContactedIps(to)
            StatsTab.BLOCKED_APPS -> statsDao.getMostBlockedApps(to)
            StatsTab.BLOCKED_DOMAINS -> statsDao.getMostBlockedDomains(to)
        }
    }.flow
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TabChipRow(active: StatsTab, onSelect: (StatsTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatsTab.entries.forEach { t ->
            val isActive = t == active
            Surface(
                onClick = { onSelect(t) },
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (isActive) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Text(
                    text = t.label,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    ),
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WindowSelector(current: StatsWindow, onSelect: (StatsWindow) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatsWindow.entries.forEach { w ->
            WindowChip(label = w.label, active = current == w, onClick = { onSelect(w) })
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WindowChip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (active) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatsList(
    tab: StatsTab,
    items: LazyPagingItems<AppConnection>,
    navController: NavController?,
) {
    val isEmpty = items.loadState.refresh is androidx.paging.LoadState.NotLoading &&
        items.itemCount == 0
    val isLoading = items.loadState.refresh is androidx.paging.LoadState.Loading

    when {
        isLoading -> CenterLabel("Loading…")
        isEmpty -> CenterLabel("No data for this window yet.")
        else -> LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(
                count = items.itemCount,
                key = items.itemKey { keyFor(it) },
            ) { index ->
                val row = items[index] ?: return@items
                StatsRow(
                    tab = tab,
                    row = row,
                    rank = index + 1,
                    onClick = { navigateForRow(navController, tab, row) },
                )
            }
        }
    }
}

private fun navigateForRow(nav: NavController?, tab: StatsTab, row: AppConnection) {
    nav ?: return
    when (tab) {
        StatsTab.TOP_APPS, StatsTab.BLOCKED_APPS -> {
            // Reuse the existing per-app firewall detail screen.
            if (row.uid >= 0) nav.navigate("apps/${row.uid}")
        }
        StatsTab.TOP_DOMAINS, StatsTab.BLOCKED_DOMAINS -> {
            val name = row.appOrDnsName?.takeIf { it.isNotBlank() } ?: return
            val encoded = URLEncoder.encode(name, StandardCharsets.UTF_8.name())
            nav.navigate("stats/detail/domain/$encoded")
        }
        StatsTab.TOP_IPS -> {
            val ip = row.ipAddress.takeIf { it.isNotBlank() } ?: return
            val encoded = URLEncoder.encode(ip, StandardCharsets.UTF_8.name())
            nav.navigate("stats/detail/ip/$encoded")
        }
    }
}

private fun keyFor(row: AppConnection): String =
    "${row.uid}|${row.appOrDnsName ?: ""}|${row.ipAddress}|${row.port}|${row.flag}"

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatsRow(tab: StatsTab, row: AppConnection, rank: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth().focusable(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RankTile(rank = rank)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = primaryLabel(tab, row),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val secondary = secondaryLabel(tab, row)
                if (secondary.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = trailing(tab, row),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RankTile(rank: Int) {
    Box(
        modifier = Modifier
            .width(36.dp)
            .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(6.dp))
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "#$rank",
            color = MaterialTheme.colorScheme.onTertiary,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CenterLabel(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(top = 32.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private fun primaryLabel(tab: StatsTab, row: AppConnection): String = when (tab) {
    StatsTab.TOP_APPS, StatsTab.BLOCKED_APPS ->
        row.appOrDnsName?.ifBlank { "uid ${row.uid}" } ?: "uid ${row.uid}"
    StatsTab.TOP_DOMAINS, StatsTab.BLOCKED_DOMAINS ->
        row.appOrDnsName?.ifBlank { "(unknown domain)" } ?: "(unknown domain)"
    StatsTab.TOP_IPS ->
        row.ipAddress.ifBlank { "(unknown ip)" }
}

private fun secondaryLabel(tab: StatsTab, row: AppConnection): String = when (tab) {
    StatsTab.TOP_APPS -> formatBytesOrCount(row)
    StatsTab.BLOCKED_APPS -> ""
    StatsTab.TOP_DOMAINS, StatsTab.BLOCKED_DOMAINS -> ""
    StatsTab.TOP_IPS -> row.flag.ifBlank { "" }
}

private fun trailing(tab: StatsTab, row: AppConnection): String = when (tab) {
    StatsTab.TOP_APPS -> {
        val bytes = row.totalBytes ?: 0L
        if (bytes > 0) humanBytes(bytes) else "${row.count}"
    }
    else -> "${row.count}"
}

private fun formatBytesOrCount(row: AppConnection): String {
    val bytes = row.totalBytes ?: 0L
    return if (bytes > 0) "${humanBytes(bytes)} • ${row.count} req" else "${row.count} req"
}

private fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024.0
    var i = 0
    while (v >= 1024.0 && i < units.size - 1) {
        v /= 1024.0
        i++
    }
    return String.format("%.1f %s", v, units[i])
}

private enum class StatsWindow(val label: String, val millis: Long) {
    ONE_HOUR("1 h", 60L * 60L * 1000L),
    TWENTY_FOUR_HOUR("24 h", 24L * 60L * 60L * 1000L),
    SEVEN_DAYS("7 d", 7L * 24L * 60L * 60L * 1000L),
}

private enum class StatsTab(val label: String) {
    TOP_APPS("Top apps"),
    TOP_DOMAINS("Top domains"),
    TOP_IPS("Top IPs"),
    BLOCKED_APPS("Blocked apps"),
    BLOCKED_DOMAINS("Blocked domains"),
}
