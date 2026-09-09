/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.apps

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.tv.ui.common.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Immutable snapshot of one app's RPN-bypass state. Mirrors the
 * projection pattern of [AppRow]: `AppInfo` rows are mutated in place
 * upstream, so the UI works off structural copies.
 */
private data class RpnBypassRow(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val isBypassed: Boolean,
)

private enum class Filter { ALL, BYPASSED, NOT_BYPASSED }

private enum class BulkAction { BYPASS_ALL, CLEAR_ALL }

/**
 * TV counterpart of the phone's `RpnBypassAppsActivity`.
 *
 * Lists all tracked apps and lets the user mark them as **excluded
 * from RPN (Rethink Private Network) proxies**. Excluded apps skip
 * Rethink Proxy servers and use the direct connection.
 *
 * Writes go through [FirewallManager.updateIsProxyExcluded] — the same
 * public mutator the phone screen calls — which updates the DB and the
 * shared-UID cache, so the VPN engine picks the change up immediately.
 *
 * Reachability: RPN destinations are intentionally excluded from the
 * TV nav in v1 (the F-Droid channel cannot ship billing — see
 * [com.celzero.bravedns.tv.ui.nav.TvDestination]). This screen is
 * surfaced as a low-key entry on the Proxy screen's "Other proxies"
 * band so the bypass list is manageable the moment a tunnel/proxy
 * exists, without exposing any purchase flow.
 *
 * Ten-foot UX notes (same rationale as
 * [com.celzero.bravedns.tv.ui.proxy.WgIncludeAppsScreen]):
 *
 *  * No text search; filter chips (All / Bypassed / Not bypassed) only.
 *  * Whole row is one D-pad-focusable Surface; center-press toggles.
 *  * Bulk actions use a two-step inline confirmation instead of a
 *    View-based `MaterialAlertDialogBuilder`.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnBypassAppsScreen() {
    val appInfoRepository = koinInject<AppInfoRepository>()
    val scope = rememberCoroutineScope()

    // Bumped after every mutation so the produceState below re-reads
    // the repository (updateIsProxyExcluded does update the applist
    // LiveData, but this screen reads the repository directly).
    var reloadKey by remember { mutableStateOf(0) }

    val allApps by produceState<List<AppInfo>>(initialValue = emptyList(), reloadKey) {
        value = withContext(Dispatchers.IO) {
            try {
                appInfoRepository.getAppInfo()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    val rows = remember(allApps) {
        allApps
            .map { app ->
                RpnBypassRow(
                    uid = app.uid,
                    packageName = app.packageName,
                    appName = app.appName,
                    isSystemApp = app.isSystemApp,
                    isBypassed = app.isProxyExcluded,
                )
            }
            .sortedWith(compareBy({ it.isSystemApp }, { it.appName.lowercase() }))
    }

    var filter by remember { mutableStateOf(Filter.ALL) }
    val visible = remember(rows, filter) {
        when (filter) {
            Filter.ALL -> rows
            Filter.BYPASSED -> rows.filter { it.isBypassed }
            Filter.NOT_BYPASSED -> rows.filter { !it.isBypassed }
        }
    }

    // Two-step bulk confirmation: first press arms, second press fires.
    var armedBulk by remember { mutableStateOf<BulkAction?>(null) }
    // true while a bulk write is in flight; blocks re-entry.
    var bulkBusy by remember { mutableStateOf(false) }

    fun toggleApp(row: RpnBypassRow, bypassed: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching { FirewallManager.updateIsProxyExcluded(row.uid, bypassed) }
            withContext(Dispatchers.Main) { reloadKey++ }
        }
    }

    fun runBulk(action: BulkAction) {
        if (bulkBusy) return
        bulkBusy = true
        armedBulk = null
        scope.launch(Dispatchers.IO) {
            runCatching {
                val target = action == BulkAction.BYPASS_ALL
                rows.forEach { row ->
                    if (row.isBypassed != target) {
                        FirewallManager.updateIsProxyExcluded(row.uid, target)
                    }
                }
            }
            withContext(Dispatchers.Main) {
                bulkBusy = false
                reloadKey++
            }
        }
    }

    val bypassedCount = rows.count { it.isBypassed }

    TvScreenScaffold(
        title = "RPN bypass apps",
        subtitle = if (rows.isEmpty()) {
            "Discovering installed apps…"
        } else if (bypassedCount == 0) {
            "No apps bypass Rethink Private Network. Press center on a row to exclude it."
        } else {
            "$bypassedCount of ${rows.size} apps bypass Rethink Private Network " +
                "(use the direct connection). Press center on a row to change it."
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(label = "All", selected = filter == Filter.ALL,
                    onClick = { filter = Filter.ALL })
                FilterChip(label = "Bypassed", selected = filter == Filter.BYPASSED,
                    onClick = { filter = Filter.BYPASSED })
                FilterChip(label = "Not bypassed", selected = filter == Filter.NOT_BYPASSED,
                    onClick = { filter = Filter.NOT_BYPASSED })
                Spacer(Modifier.weight(1f))
                BulkChip(
                    label = when {
                        bulkBusy -> "Working…"
                        armedBulk == BulkAction.BYPASS_ALL -> "Press again to confirm"
                        else -> "Bypass all"
                    },
                    enabled = !bulkBusy,
                    armed = armedBulk == BulkAction.BYPASS_ALL,
                    onClick = {
                        if (armedBulk == BulkAction.BYPASS_ALL) runBulk(BulkAction.BYPASS_ALL)
                        else armedBulk = BulkAction.BYPASS_ALL
                    },
                )
                BulkChip(
                    label = when {
                        bulkBusy -> "Working…"
                        armedBulk == BulkAction.CLEAR_ALL -> "Press again to confirm"
                        else -> "Clear all"
                    },
                    enabled = !bulkBusy,
                    armed = armedBulk == BulkAction.CLEAR_ALL,
                    onClick = {
                        if (armedBulk == BulkAction.CLEAR_ALL) runBulk(BulkAction.CLEAR_ALL)
                        else armedBulk = BulkAction.CLEAR_ALL
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
            SettingSectionHeader("Apps (${visible.size})")

            if (visible.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 40.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Text(
                        text = "No apps match this filter.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 32.dp, end = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = { it.uid.toString() + "/" + it.packageName }) { row ->
                    AppToggleRow(
                        row = row,
                        onToggle = { bypassed -> toggleApp(row, bypassed) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
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
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BulkChip(label: String, enabled: Boolean, armed: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (armed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (armed) MaterialTheme.colorScheme.onError
            else MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = if (armed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = if (armed) MaterialTheme.colorScheme.onError
            else MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = if (armed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = if (armed) MaterialTheme.colorScheme.onError
            else MaterialTheme.colorScheme.onPrimaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

/**
 * One focusable app row; pressing center toggles RPN bypass for the
 * app. State flips after [FirewallManager.updateIsProxyExcluded]
 * completes (reloadKey bump).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppToggleRow(row: RpnBypassRow, onToggle: (Boolean) -> Unit) {
    Surface(
        onClick = { onToggle(!row.isBypassed) },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(packageName = row.packageName, size = 48)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.appName.ifBlank { row.packageName },
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = row.packageName,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(16.dp))
            BypassTile(bypassed = row.isBypassed)
        }
    }
}

/** Trailing state pill for [AppToggleRow]. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BypassTile(bypassed: Boolean) {
    val container = if (bypassed) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surface
    androidx.tv.material3.Surface(
        shape = RoundedCornerShape(50),
        colors = SurfaceDefaults.colors(containerColor = container),
    ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(
                text = if (bypassed) "Bypassed" else "Proxied",
                color = if (bypassed) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}
