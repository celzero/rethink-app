/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.proxy

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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.navigation.NavController
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.tv.ui.common.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.tv.ui.apps.AppIcon
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Immutable snapshot of one app's inclusion state for a proxy.
 * Mirrors the projection pattern used by
 * [com.celzero.bravedns.tv.ui.apps.AppRow]: upstream caches
 * (`ProxyManager.pamSet`, `AppInfo`) are mutated in place, so the UI
 * works off structural copies keyed by (uid, packageName).
 */
private data class WgAppRow(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val included: Boolean,
)

private enum class Filter { ALL, INCLUDED, NOT_INCLUDED }

private enum class BulkAction { INCLUDE_ALL, REMOVE_ALL }

/**
 * TV counterpart of the phone's `WgIncludeAppsActivity`.
 *
 * Lets the user pick which apps route through a specific WireGuard
 * tunnel. Reached from [WgDetailScreen] ("Included apps" row), it is
 * the per-proxy app-mapping surface the phone opens via
 * `WgIncludeAppsActivity.newIntent(context, ID_WG_BASE + configId, name)`.
 *
 * All reads/writes go through the same upstream singletons the phone
 * screen uses, so mappings made here are honoured by the VPN engine
 * immediately:
 *
 *  * read: [AppInfoRepository.getAppInfo] + [ProxyManager.getProxyIdsForApp]
 *  * toggle: [ProxyManager.addProxyToApp] / [ProxyManager.removeProxyFromApp]
 *  * bulk: [ProxyManager.setProxyIdForAllApps] /
 *    [ProxyManager.setNoProxyForAllAppsForProxy]
 *
 * Ten-foot UX notes:
 *
 *  * No text search — TV remotes can't type comfortably, and every
 *    other TV screen defers text entry. Filter chips (All / Included /
 *    Not included) are the only narrowing mechanism.
 *  * The whole app row is a single D-pad-focusable Surface; pressing
 *    center toggles inclusion (same pattern as
 *    [com.celzero.bravedns.tv.ui.common.SettingToggleRow]).
 *  * Bulk actions use a two-step inline confirmation ("Press again to
 *    confirm") instead of a modal dialog — no View-based
 *    `MaterialAlertDialogBuilder` is used anywhere in the TV UI.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WgIncludeAppsScreen(configId: Int, navController: NavController? = null) {
    val appInfoRepository = koinInject<AppInfoRepository>()
    val scope = rememberCoroutineScope()

    val proxyId = ProxyManager.ID_WG_BASE + configId
    var proxyName by remember { mutableStateOf("tunnel #$configId") }

    // Bumped after every mutation so the produceState below re-reads
    // ProxyManager's in-memory cache (pamSet), which does not emit.
    var reloadKey by remember { mutableStateOf(0) }

    // Load the tunnel name for display + proxy-name bookkeeping.
    LaunchedEffect(configId) {
        val mapping = withContext(Dispatchers.IO) {
            WireguardManager.getConfigFilesById(configId)
        }
        proxyName = mapping?.name?.ifBlank { "tunnel #$configId" } ?: "tunnel #$configId"
    }

    val allApps by produceState<List<com.celzero.bravedns.database.AppInfo>>(
        initialValue = emptyList(), configId, reloadKey,
    ) {
        value = withContext(Dispatchers.IO) {
            try {
                appInfoRepository.getAppInfo()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    val rows = remember(allApps, proxyId) {
        allApps
            .map { app ->
                WgAppRow(
                    uid = app.uid,
                    packageName = app.packageName,
                    appName = app.appName,
                    isSystemApp = app.isSystemApp,
                    included = runCatching {
                        ProxyManager.getProxyIdsForApp(app.uid, app.packageName).contains(proxyId)
                    }.getOrDefault(false),
                )
            }
            .sortedWith(compareBy({ it.isSystemApp }, { it.appName.lowercase() }))
    }

    var filter by remember { mutableStateOf(Filter.ALL) }
    val visible = remember(rows, filter) {
        when (filter) {
            Filter.ALL -> rows
            Filter.INCLUDED -> rows.filter { it.included }
            Filter.NOT_INCLUDED -> rows.filter { !it.included }
        }
    }

    // Two-step bulk confirmation: first press arms, second press fires.
    var armedBulk by remember { mutableStateOf<BulkAction?>(null) }
    // true while a bulk write is in flight; blocks re-entry.
    var bulkBusy by remember { mutableStateOf(false) }

    fun toggleApp(row: WgAppRow, include: Boolean) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (include) {
                    ProxyManager.addProxyToApp(row.uid, row.packageName, proxyId, proxyName)
                } else {
                    ProxyManager.removeProxyFromApp(row.uid, row.packageName, proxyId)
                }
            }
            withContext(Dispatchers.Main) { reloadKey++ }
        }
    }

    fun runBulk(action: BulkAction) {
        if (bulkBusy) return
        bulkBusy = true
        armedBulk = null
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (action == BulkAction.INCLUDE_ALL) {
                    ProxyManager.setProxyIdForAllApps(proxyId, proxyName)
                } else {
                    ProxyManager.setNoProxyForAllAppsForProxy(proxyId)
                }
            }
            withContext(Dispatchers.Main) {
                bulkBusy = false
                reloadKey++
            }
        }
    }

    val includedCount = rows.count { it.included }

    TvScreenScaffold(
        title = "Included apps",
        subtitle = if (rows.isEmpty()) {
            "Discovering installed apps…"
        } else {
            "$includedCount of ${rows.size} apps route through \"$proxyName\". " +
                "Press center on a row to include or remove it."
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Filter chips + bulk actions share one D-pad-passable row band.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(label = "All", selected = filter == Filter.ALL,
                    onClick = { filter = Filter.ALL })
                FilterChip(label = "Included", selected = filter == Filter.INCLUDED,
                    onClick = { filter = Filter.INCLUDED })
                FilterChip(label = "Not included", selected = filter == Filter.NOT_INCLUDED,
                    onClick = { filter = Filter.NOT_INCLUDED })
                Spacer(Modifier.weight(1f))
                BulkChip(
                    label = when {
                        bulkBusy -> "Working…"
                        armedBulk == BulkAction.INCLUDE_ALL -> "Press again to confirm"
                        else -> "Include all"
                    },
                    enabled = !bulkBusy,
                    armed = armedBulk == BulkAction.INCLUDE_ALL,
                    onClick = {
                        if (armedBulk == BulkAction.INCLUDE_ALL) runBulk(BulkAction.INCLUDE_ALL)
                        else armedBulk = BulkAction.INCLUDE_ALL
                    },
                )
                BulkChip(
                    label = when {
                        bulkBusy -> "Working…"
                        armedBulk == BulkAction.REMOVE_ALL -> "Press again to confirm"
                        else -> "Remove all"
                    },
                    enabled = !bulkBusy,
                    armed = armedBulk == BulkAction.REMOVE_ALL,
                    onClick = {
                        if (armedBulk == BulkAction.REMOVE_ALL) runBulk(BulkAction.REMOVE_ALL)
                        else armedBulk = BulkAction.REMOVE_ALL
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
                        onToggle = { include -> toggleApp(row, include) },
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
 * One focusable app row; pressing center toggles this tunnel's
 * inclusion for the app. State flips after ProxyManager's write
 * completes (reloadKey bump), so no local mirror is needed — writes
 * are fast (in-memory cache + single DB upsert/delete).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppToggleRow(row: WgAppRow, onToggle: (Boolean) -> Unit) {
    Surface(
        onClick = { onToggle(!row.included) },
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
            IncludedTile(included = row.included)
        }
    }
}

/** Trailing state pill for [AppToggleRow]. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun IncludedTile(included: Boolean) {
    val container = if (included) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surface
    androidx.tv.material3.Surface(
        shape = RoundedCornerShape(50),
        colors = SurfaceDefaults.colors(containerColor = container),
    ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(
                text = if (included) "Included" else "Excluded",
                color = if (included) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}
