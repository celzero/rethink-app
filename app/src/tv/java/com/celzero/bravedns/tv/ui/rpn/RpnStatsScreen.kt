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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import androidx.tv.material3.Text
import com.celzero.firestack.backend.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * RPN stats — TV counterpart of `RpnStatsBottomSheet`: window rx/tx,
 * connection / blocked / app counts, and top apps by usage, queried
 * with the same DAO the phone sheet uses (`proxyDetails LIKE
 * '%RPNWIN%'`).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnStatsScreen(navController: NavController, timeWindowMs: Long = DEFAULT_WINDOW_MS) {
    val connDao: ConnectionTrackerDAO = koinInject()

    data class Stats(
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
            val summary = runCatching {
                connDao.getRpnConnStats(Backend.RpnWin, since)
            }.getOrNull()
            val topApps = runCatching {
                connDao.getRpnTopAppsForProxy(Backend.RpnWin, since, 10)
            }.getOrDefault(emptyList())
            Stats(
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

    TvScreenScaffold(
        title = "RPN stats",
        subtitle = "Last ${timeWindowMs / (60L * 60L * 1000L)}h of RPN-routed traffic.",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatTile("Connections", stats.connections.toString())
                StatTile("Blocked", stats.blocked.toString())
                StatTile("Apps", stats.apps.toString())
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatTile("Download", formatBytes(stats.download))
                StatTile("Upload", formatBytes(stats.upload))
            }

            SettingSectionHeader("Top apps by usage")
            if (stats.topApps.isEmpty()) {
                Text(
                    text = "No RPN-routed app traffic in this window.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(stats.topApps) { app ->
                        FocusableActionCard(
                            title = app,
                            subtitle = null,
                            valueText = null,
                            onClick = null,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            FocusableActionCard(
                title = "Connection logs",
                subtitle = "Inspect individual RPN-routed connections.",
                valueText = "Open ›",
                onClick = { navController.navigate("logs") },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

private const val DEFAULT_WINDOW_MS = 24L * 60L * 60L * 1000L

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatTile(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.width(180.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
