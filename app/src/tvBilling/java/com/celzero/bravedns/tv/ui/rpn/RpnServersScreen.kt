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

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.AUTO_SERVER_ID
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles
import androidx.tv.material3.Text
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ServerSelectionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "RpnTvServers"

/** Same cap as the phone ServerSelectionFragment. */
private const val MAX_SELECTIONS = 5

/**
 * One rendered location: either the always-on AUTO row or the best
 * (lowest-load) endpoint of a country group.
 */
private data class ServerRow(
    val cc: String,
    val countryName: String,
    val flagEmoji: String,
    val citySummary: String,
    val key: String,
    val load: Int,
    val link: Int,
    val isSelected: Boolean,
    val isAuto: Boolean = false,
)

private data class ServersUi(
    val autoRow: ServerRow?,
    val rows: List<ServerRow>,
    val selectedCount: Int,
    val expiryMs: Long,
)

/**
 * Reduced-scope TV counterpart of `ServerSelectionFragment`: AUTO row +
 * country-grouped location list with select/remove via the same
 * `RpnProxyManager.enableWinServer/disableWinServer` calls as the phone
 * screen, plus a server refresh driven by [ServerSelectionViewModel].
 *
 * Deliberately out of scope for the TV v1 (see the plan): onboarding
 * tour, search, per-city drill-down, stats bottom sheets, log-interval
 * picker, ping-test animations.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnServersScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vm: ServerSelectionViewModel = viewModel()

    var reloadKey by remember { mutableIntStateOf(0) }
    var busyKeys by remember { mutableStateOf(setOf<String>()) }
    var status by remember { mutableStateOf<String?>(null) }

    val ui by produceState(initialValue = ServersUi(null, emptyList(), 0, 0L), reloadKey) {
        value = loadServersUi()
    }

    LaunchedEffect(Unit) {
        vm.refreshState.collect { st ->
            when (st) {
                is ServerSelectionViewModel.RefreshState.InProgress -> {}
                is ServerSelectionViewModel.RefreshState.Done -> {
                    vm.onRefreshConsumed()
                    status = null
                    reloadKey++
                }
                is ServerSelectionViewModel.RefreshState.NeedsLoading -> {
                    vm.onRefreshConsumed()
                    status = "No servers yet — make sure the VPN is running, then refresh."
                }
                is ServerSelectionViewModel.RefreshState.NoTunnel -> {
                    vm.onRefreshConsumed()
                    status = context.getString(R.string.ssv_toast_start_rethink)
                }
                is ServerSelectionViewModel.RefreshState.Idle -> {}
            }
        }
    }

    fun toggle(row: ServerRow) {
        if (row.isAuto) {
            tvToast(context, context.getString(R.string.server_selection_auto_always_on))
            return
        }
        if (row.key in busyKeys) return
        busyKeys = busyKeys + row.key
        scope.launch {
            if (row.isSelected) {
                val res = withContext(Dispatchers.IO) {
                    runCatching { RpnProxyManager.disableWinServer(row.key) }
                    .getOrDefault(Pair(false, "error"))
                }
                if (!res.first) tvToast(context, "Failed to remove ${row.countryName}: ${res.second}")
            } else {
                // in-flight additions count toward the cap too
                if (ui.selectedCount + busyKeys.size >= MAX_SELECTIONS) {
                    tvToast(
                        context,
                        context.getString(R.string.server_selection_max_reached, MAX_SELECTIONS),
                    )
                } else {
                    val res = withContext(Dispatchers.IO) {
                        runCatching { RpnProxyManager.enableWinServer(row.key) }
                        .getOrDefault(Pair(false, "error"))
                    }
                    if (!res.first) tvToast(context, "Failed to add ${row.countryName}: ${res.second}")
                }
            }
            busyKeys = busyKeys - row.key
            reloadKey++
        }
    }

    val expiryText = remember(ui.expiryMs) {
        if (ui.expiryMs > 0) {
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(ui.expiryMs))
        } else {
            ""
        }
    }
    val subtitle = buildString {
        append("${ui.selectedCount} of $MAX_SELECTIONS locations selected")
        if (expiryText.isNotEmpty()) append(" · plan expires $expiryText")
    }

    com.celzero.bravedns.tv.ui.common.TvScreenScaffold(
        title = "RPN servers",
        subtitle = subtitle,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            status?.let {
                InfoBanner(text = it)
                Spacer(Modifier.height(TvSpacing.CardGap))
            }

            SettingSectionHeader("Settings")
            RpnActionRow(
                label = "Bypass apps",
                support = "Apps excluded from RPN proxying",
                onClick = { navController.navigate("proxy/rpn-bypass") },
            )
            Spacer(Modifier.height(TvSpacing.CardGap))

            if (ui.autoRow == null && ui.rows.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 48.dp),
                ) {
                    Text(
                        text = "No servers loaded yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RpnActionRow(
                        label = "Refresh servers",
                        icon = Icons.Filled.Autorenew,
                        onClick = { vm.refresh() },
                        modifier = Modifier.width(320.dp),
                    )
                }
                return@Column
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
                contentPadding = PaddingValues(bottom = 32.dp, end = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val auto = ui.autoRow
                if (auto != null) {
                    item(key = "auto") {
                        SettingSectionHeader("Automatic")
                        ServerRowCard(row = auto, busy = false, onToggle = { toggle(auto) })
                    }
                }
                if (ui.rows.isNotEmpty()) {
                    item(key = "header") { SettingSectionHeader("All locations (${ui.rows.size})") }
                }
                items(ui.rows, key = { it.key }) { row ->
                    ServerRowCard(
                        row = row,
                        busy = row.key in busyKeys,
                        onToggle = { toggle(row) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ServerRowCard(row: ServerRow, busy: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = TvSurfaceStyles.interactiveShape(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (row.isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (row.isSelected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = TvSpacing.ContentHorizontal),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = row.flagEmoji, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(TvSpacing.IconTextGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (row.isAuto) "Auto (best available)" else row.countryName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.citySummary.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = row.citySummary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(TvSpacing.IconTextGap))
            Text(
                text = when {
                    busy -> "…"
                    row.isAuto -> "Always on"
                    row.isSelected -> "Selected"
                    else -> "Load ${row.load}%"
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InfoBanner(text: String) {
    androidx.tv.material3.Surface(
        shape = TvShapes.Small,
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Builds the AUTO + country-grouped model from the RPN catalog. */
private suspend fun loadServersUi(): ServersUi = withContext(Dispatchers.IO) {
    val servers = try {
        RpnProxyManager.getWinServers()
    } catch (_: Exception) {
        emptyList()
    }
    val selected = try {
        RpnProxyManager.getEnabledConfigs()
    } catch (_: Exception) {
        emptySet<CountryConfig>()
    }
    val expiry = try {
        VpnController.getWinExpiryTs() ?: 0L
    } catch (_: Exception) {
        0L
    }

    val auto = servers.firstOrNull { it.id.equals(AUTO_SERVER_ID, ignoreCase = true) }
    val autoRow = auto?.let {
        ServerRow(
            cc = it.cc,
            countryName = it.countryName,
            flagEmoji = it.flagEmoji,
            citySummary = "",
            key = it.key,
            load = it.load,
            link = it.link,
            isSelected = true,
            isAuto = true,
        )
    }

    val grouped = servers
        .filter { !it.id.equals(AUTO_SERVER_ID, ignoreCase = true) }
        .groupBy { it.cc }

    val rows = grouped.map { (cc, group) ->
        val best = group.minByOrNull { it.load } ?: group.first()
        val isSelected = group.any { it.isEnabled } || selected.any { it.cc == cc }
        val cities = group.map { it.city }.filter { it.isNotBlank() }.distinct().take(3)
        ServerRow(
            cc = cc,
            countryName = best.countryName.ifBlank { cc },
            flagEmoji = best.flagEmoji,
            citySummary = cities.joinToString(" · "),
            // toggle ops target the enabled endpoint, not just the best-loaded one
            key = group.firstOrNull { it.isEnabled }?.key ?: best.key,
            load = best.load,
            link = best.link,
            isSelected = isSelected,
        )
    }.sortedWith(compareByDescending<ServerRow> { it.isSelected }.thenBy { it.countryName })

    ServersUi(
        autoRow = autoRow,
        rows = rows,
        // distinct enabled endpoint keys (excl. AUTO) — the backend's cap unit
        selectedCount = selected.count { !it.id.equals(AUTO_SERVER_ID, true) },
        expiryMs = expiry,
    )
}

private fun tvToast(ctx: Context, msg: String) {
    Utilities.showToastUiCentered(ctx, msg, Toast.LENGTH_LONG)
}
