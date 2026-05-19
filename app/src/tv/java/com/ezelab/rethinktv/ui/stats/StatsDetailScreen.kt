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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.ezelab.rethinktv.ui.common.TvScreenScaffold
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detail drill-down from [StatsScreen] for a single domain or IP.
 *
 * Mirrors a thin slice of the phone's
 * `DomainConnectionsActivity` / `DetailedStatisticsActivity`: a
 * paged list of every [ConnectionTracker] entry whose searchable
 * columns (`dnsQuery` for domains, `ipAddress` for IPs) match
 * [value]. We reuse the existing wildcard query rather than adding
 * a new DAO method — the LIKE pattern hits `dnsQuery` *and*
 * `ipAddress`, which is exactly what we want for a "show
 * everything we know about this name" view.
 *
 * @param value the raw text to match — passed verbatim from the
 *   row tapped on StatsScreen.
 * @param kind whether [value] is a domain or an IP, used only for
 *   the subtitle.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StatsDetailScreen(value: String, kind: StatsDetailKind) {
    val dao = koinInject<ConnectionTrackerDAO>()
    val searchTerm = remember(value) { "%${value.trim()}%" }

    val pager = remember(searchTerm) {
        Pager(
            config = PagingConfig(
                pageSize = 50,
                enablePlaceholders = false,
                initialLoadSize = 100,
            ),
        ) { dao.getConnectionTrackerByName(searchTerm) }
    }
    val items = pager.flow.collectAsLazyPagingItems()

    TvScreenScaffold(
        title = value.ifBlank { kind.fallbackTitle },
        subtitle = "Recent connections — ${kind.subtitle}.",
    ) {
        if (items.itemCount == 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 32.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Text(
                    text = "No connections logged for this ${kind.noun} yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@TvScreenScaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(
                count = items.itemCount,
                key = items.itemKey { "${it.id}" },
            ) { i ->
                items[i]?.let { ConnRow(it) }
            }
        }
    }
}

enum class StatsDetailKind(
    val fallbackTitle: String,
    val subtitle: String,
    val noun: String,
) {
    DOMAIN("(unknown domain)", "newest first", "domain"),
    IP("(unknown ip)", "newest first", "address"),
}

private val ConnTimeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

@Composable
private fun ConnRow(c: ConnectionTracker) {
    val blocked = c.isBlocked
    val bg = if (blocked) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.18f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = ConnTimeFmt.format(Date(c.timeStamp)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.width(130.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (blocked) "BLOCKED" else "ALLOWED",
                color = if (blocked) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.width(72.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = c.appName.ifBlank { "uid ${c.uid}" },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val ipPort = "${c.ipAddress}:${c.port}"
        val dns = c.dnsQuery?.takeIf { it.isNotBlank() }
        Text(
            text = if (dns != null) "$dns → $ipPort" else ipPort,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
