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
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.tv.ui.apps.AppIcon
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * TV counterpart of the phone's per-RPN-location app mapping
 * (`WgIncludeAppsActivity` opened with `ProxyManager.ID_RPN_WIN +
 * key` from `RpnConfigDetailActivity`). Picks which apps route
 * through the location; mappings use the same `RPNWIN` proxy-id
 * convention the engine expects.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnAppsScreen(key: String) {
    val appInfoRepository = koinInject<AppInfoRepository>()
    val scope = rememberCoroutineScope()

    val proxyId = rpnProxyId(key)

    val proxyName by produceState("location $key", key) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                RpnProxyManager.getCountryConfigByKey(key)?.serverLocation
            }.getOrNull() ?: "location $key"
        }
    }

    // ProxyManager's pamSet cache does not emit; bump to re-read.
    var reloadKey by remember { mutableStateOf(0) }

    val allApps by produceState<List<AppInfo>>(initialValue = emptyList(), key, reloadKey) {
        value = withContext(Dispatchers.IO) {
            runCatching { appInfoRepository.getAppInfo() }.getOrDefault(emptyList())
        }
    }

    val rows = remember(allApps, proxyId) {
        allApps
            .map { app ->
                RpnAppsScreenRow(
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

    var filterAll by remember { mutableStateOf(true) }
    val visible = if (filterAll) rows else rows.filter { it.included }

    var armedClear by remember { mutableStateOf(false) }
    var bulkBusy by remember { mutableStateOf(false) }

    fun toggleApp(row: RpnAppsScreenRow, include: Boolean) {
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

    val includedCount = rows.count { it.included }

    TvScreenScaffold(
        title = "Apps via $proxyName",
        subtitle = if (rows.isEmpty()) {
            "Discovering installed apps…"
        } else {
            "$includedCount of ${rows.size} apps route through \"$proxyName\". " +
                "Press center on a row to include or remove it."
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(label = "All", selected = filterAll, onClick = { filterAll = true })
                FilterChip(label = "Routed", selected = !filterAll, onClick = { filterAll = false })
                Spacer(Modifier.weight(1f))
                BulkChip(
                    label = when {
                        bulkBusy -> "Working…"
                        armedClear -> "Press again to confirm"
                        else -> "Route all apps"
                    },
                    enabled = !bulkBusy,
                    armed = armedClear,
                    onClick = {
                        if (armedClear) {
                            armedClear = false
                            if (bulkBusy) return@BulkChip
                            bulkBusy = true
                            scope.launch(Dispatchers.IO) {
                                runCatching { ProxyManager.setProxyIdForAllApps(proxyId, proxyName) }
                                withContext(Dispatchers.Main) {
                                    bulkBusy = false
                                    reloadKey++
                                }
                            }
                        } else {
                            armedClear = true
                        }
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
                    AppToggleRow(row = row, onToggle = { include -> toggleApp(row, include) })
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppToggleRow(
    row: RpnAppsScreenRow,
    onToggle: (Boolean) -> Unit,
) {
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
            val container = if (row.included) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surface
            androidx.tv.material3.Surface(
                shape = RoundedCornerShape(50),
                colors = androidx.tv.material3.SurfaceDefaults.colors(containerColor = container),
            ) {
                Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(
                        text = if (row.included) "Routed" else "Direct",
                        color = if (row.included) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}

/** Flat row model for [AppToggleRow]. */
private data class RpnAppsScreenRow(
    val uid: Int,
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val included: Boolean,
)
