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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.tv.ui.common.TvSelectableChip
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.common.TvSectionHeader
import com.celzero.bravedns.tv.ui.common.TvStatTile
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.firestack.backend.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

private const val TAG = "RpnTvStats"

/**
 * RPN stats — window rx/tx, connection / blocked / app counts, and top
 * apps by usage, queried with the same DAO the phone sheet uses
 * (`proxyDetails LIKE '%RPNWIN%'`). The phone offers 1h (pulse card)
 * and 24h; the TV screen exposes both plus 6h via the window chip row.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnStatsScreen(navController: NavController) {
    val connDao: ConnectionTrackerDAO = koinInject()

    var timeWindowMs by remember { mutableStateOf(DEFAULT_WINDOW_MS) }

    data class Stats(
        val queriedWindowMs: Long = 0L,
        val error: Boolean = false,
        val connections: Int = 0,
        val download: Long = 0L,
        val upload: Long = 0L,
        val blocked: Int = 0,
        val apps: Int = 0,
        val topApps: List<String> = emptyList(),
    )

    val stats by produceState(Stats(), timeWindowMs) {
        value = withContext(Dispatchers.IO) {
            val since = System.currentTimeMillis() - timeWindowMs
            val summaryResult = runCatching {
                connDao.getRpnConnStats(Backend.RpnWin, since)
            }
            val topAppsResult = runCatching {
                connDao.getRpnTopAppsForProxy(Backend.RpnWin, since, 10)
            }
            if (summaryResult.isFailure) {
                Logger.w(LOG_TAG_UI, "$TAG: getRpnConnStats failed: ${summaryResult.exceptionOrNull()?.message}")
            }
            if (topAppsResult.isFailure) {
                Logger.w(LOG_TAG_UI, "$TAG: getRpnTopAppsForProxy failed: ${topAppsResult.exceptionOrNull()?.message}")
            }
            val summary = summaryResult.getOrNull()
            val topApps = topAppsResult.getOrDefault(emptyList())
            Stats(
                queriedWindowMs = timeWindowMs,
                error = summaryResult.isFailure || topAppsResult.isFailure,
                connections = summary?.connectionsCount ?: 0,
                download = summary?.totalDownload ?: 0L,
                upload = summary?.totalUpload ?: 0L,
                blocked = summary?.blockedCount ?: 0,
                apps = summary?.appCount ?: 0,
                topApps = topApps.mapNotNull { app ->
                    app.appOrDnsName?.takeIf { it.isNotBlank() }
                },
            )
        }
    }

    val windowHours = timeWindowMs / HOUR_MS
    // results not yet loaded for the selected window → placeholders
    val stale = stats.queriedWindowMs != timeWindowMs

    TvScreenScaffold(
        title = stringResource(R.string.tv_rpn_stats_title),
        subtitle = stringResource(R.string.tv_rpn_stats_subtitle_fmt, windowHours),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.CardGap)) {
                WINDOW_OPTIONS.forEach { windowMs ->
                    TvSelectableChip(
                        label = stringResource(
                            R.string.tv_rpn_stats_window_fmt,
                            windowMs / HOUR_MS,
                        ),
                        selected = timeWindowMs == windowMs,
                        onClick = { timeWindowMs = windowMs },
                    )
                }
            }
            if (stats.error && !stale) {
                Text(
                    text = stringResource(R.string.subscription_action_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.CardGap)) {
                TvStatTile(
                    label = stringResource(R.string.rpn_stats_connections),
                    value = if (stale) "—" else stats.connections.toString(),
                    modifier = Modifier.weight(1f),
                )
                TvStatTile(
                    label = stringResource(R.string.lbl_blocked),
                    value = if (stale) "—" else stats.blocked.toString(),
                    modifier = Modifier.weight(1f),
                )
                TvStatTile(
                    label = stringResource(R.string.apps_info_title),
                    value = if (stale) "—" else stats.apps.toString(),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.CardGap)) {
                TvStatTile(
                    label = stringResource(R.string.rpn_stats_downloaded),
                    value = if (stale) "—" else formatBytes(stats.download),
                    modifier = Modifier.weight(1f),
                )
                TvStatTile(
                    label = stringResource(R.string.rpn_stats_uploaded),
                    value = if (stale) "—" else formatBytes(stats.upload),
                    modifier = Modifier.weight(1f),
                )
            }

            TvSectionHeader(stringResource(R.string.tv_rpn_stats_top_apps_fmt, windowHours))
            if (stale) {
                // wait for the fresh window before showing any rows
            } else if (stats.topApps.isEmpty()) {
                Text(
                    text = stringResource(R.string.tv_rpn_stats_empty_fmt, windowHours),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                stats.topApps.forEach { app ->
                    Text(
                        text = app,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            RpnActionRow(
                label = stringResource(R.string.rpn_stats_view_logs),
                support = stringResource(R.string.tv_rpn_stats_logs_subtitle),
                trailing = stringResource(R.string.tv_open_action),
                onClick = { navController.navigate("logs") },
            )

            Spacer(Modifier.height(TvSpacing.SectionGap))
        }
    }
}

private const val HOUR_MS = 60L * 60L * 1000L
private const val DEFAULT_WINDOW_MS = 24L * HOUR_MS

private val WINDOW_OPTIONS = listOf(
    HOUR_MS,
    6L * HOUR_MS,
    DEFAULT_WINDOW_MS,
)

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
