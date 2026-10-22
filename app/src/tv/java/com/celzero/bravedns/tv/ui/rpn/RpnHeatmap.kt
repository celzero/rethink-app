/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.rpn

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.DnsLogDAO
import com.celzero.bravedns.database.RethinkLogDao
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.ln

/**
 * RPN activity heat map: 24h wall of 10-minute buckets, the TV
 * counterpart of the phone ServerSelectionFragment's heatmap. Bucket
 * totals are merged from the connection, DNS and rethink-log DAOs —
 * the same three sources the phone sums.
 *
 * Cells are D-pad focusable; activating one opens the per-interval
 * activity screen for that exact 10-minute window.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun RpnHeatmapSection(onCellActivate: (start: Long, end: Long) -> Unit) {
    val connDao: ConnectionTrackerDAO = koinInject()
    val dnsLogDao: DnsLogDAO = koinInject()
    val rethinkLogDao: RethinkLogDao = koinInject()

    val heatmap by produceState(emptyList<Int>() to 0L, key1 = Unit) {
        value = withContext(Dispatchers.IO) {
            val bucketMs = RPN_HEATMAP_BUCKET_MS
            val now = System.currentTimeMillis()
            val rangeEnd = (now / bucketMs + 1) * bucketMs
            val rangeStart = rangeEnd - RPN_HEATMAP_WINDOW_MS
            val totals = LongArray(RPN_HEATMAP_SLOTS)
            runCatching {
                (connDao.getRpnActivityBuckets(RPN_ID_FILTER, rangeStart, rangeEnd, bucketMs) +
                    dnsLogDao.getRpnActivityBuckets(RPN_ID_FILTER, rangeStart, rangeEnd, bucketMs) +
                    rethinkLogDao.getRpnActivityBuckets(RPN_ID_FILTER, rangeStart, rangeEnd, bucketMs))
            }.getOrDefault(emptyList()).forEach { row ->
                val idx = row.bucketIndex.toInt()
                if (idx in totals.indices) totals[idx] += row.total
            }
            val max = totals.max()
            val levels = totals.map { total ->
                if (total <= 0L || max <= 0L) {
                    0
                } else {
                    ((ln(total.toDouble()) / ln(max.toDouble())) * (INTENSITY_LEVELS - 1)).toInt() + 1
                }
            }
            levels to rangeStart
        }
    }
    val (levels, rangeStart) = heatmap

    SettingSectionHeader("Activity (last 24h)")
    if (levels.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(RPN_HEATMAP_ROWS) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(RPN_HEATMAP_COLS) { col ->
                    val idx = row * RPN_HEATMAP_COLS + col
                    val level = levels.getOrElse(idx) { 0 }
                    val start = rangeStart + idx * RPN_HEATMAP_BUCKET_MS
                    HeatCell(
                        level = level,
                        onClick = { onCellActivate(start, start + RPN_HEATMAP_BUCKET_MS) },
                    )
                }
            }
        }
        Text(
            text = "Each cell is 10 minutes. Press a cell for per-app activity.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private const val RPN_HEATMAP_BUCKET_MS = 10L * 60L * 1000L
private const val RPN_HEATMAP_WINDOW_MS = 24L * 60L * 60L * 1000L
private const val RPN_HEATMAP_SLOTS = (RPN_HEATMAP_WINDOW_MS / RPN_HEATMAP_BUCKET_MS).toInt()
private const val RPN_HEATMAP_COLS = 24
private const val RPN_HEATMAP_ROWS = RPN_HEATMAP_SLOTS / RPN_HEATMAP_COLS
private const val INTENSITY_LEVELS = 5

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeatCell(level: Int, onClick: () -> Unit) {
    val container = if (level == 0) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f + 0.2f * level)
    }
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(4.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = container,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.size(22.dp),
    ) {}
}

/**
 * Per-interval RPN activity — TV counterpart of
 * `RpnLogActivityIntervalBottomSheet`: per-app allowed/blocked counts
 * for one 10-minute window, expandable to per-domain rows.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnIntervalScreen(start: Long, end: Long) {
    val repo: ConnectionTrackerRepository = koinInject()

    val rows by produceState<List<RpnIntervalScreenRow>>(emptyList(), start, end) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                repo.getRpnAppActivity(RPN_ID_FILTER, start, end, 25).map {
                    RpnIntervalScreenRow(
                        uid = it.uid,
                        appName = it.appName,
                        total = it.total,
                        blocked = it.blocked,
                        start = start,
                        end = end,
                    )
                }
            }.getOrDefault(emptyList())
        }
    }

    TvScreenScaffold(
        title = "RPN activity",
        subtitle = "${formatRelative(start)} – ${formatRelative(end)}",
    ) {
        if (rows.isEmpty()) {
            Text(
                text = "No RPN-routed activity in this interval.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@TvScreenScaffold
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(rows, key = { it.uid }) { row ->
                IntervalAppRow(repo, row)
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun IntervalAppRow(
    repo: ConnectionTrackerRepository,
    row: RpnIntervalScreenRow,
) {
    var expanded by remember { mutableStateOf(false) }
    val domains by produceState<List<String>>(emptyList(), row.uid, expanded) {
        if (!expanded) {
            value = emptyList()
        } else {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    repo.getRpnDomainActivityForUid(
                        RPN_ID_FILTER, row.start, row.end, row.uid, 20,
                    ).map { it.label }
                }.getOrDefault(emptyList())
            }
        }
    }
    Surface(
        onClick = { expanded = !expanded },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
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
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.appName.ifBlank { "uid ${row.uid}" },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "${row.total} conn · ${row.blocked} blocked",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                if (domains.isEmpty()) {
                    Text(
                        text = "No domain detail.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    domains.take(10).forEach { label ->
                        Text(
                            text = "· $label",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Flat row model so the expandable composable stays parameterisable. */
internal data class RpnIntervalScreenRow(
    val uid: Int,
    val appName: String,
    val total: Long,
    val blocked: Long,
    val start: Long,
    val end: Long,
)
