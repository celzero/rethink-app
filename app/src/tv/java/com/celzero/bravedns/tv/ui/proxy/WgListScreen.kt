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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.theme.TvColors
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WireGuard tunnel list — the Proxy screen's WireGuard card drills in
 * here. Each card shows the tunnel name, status pills (Active /
 * Catch-all / Lockdown / One-WG), and opens the per-tunnel detail
 * screen. "+ Add tunnel" lands on the `wg/import` screen.
 *
 * WARP/SEC_WARP residue rows (left behind by pre-v055o upgrades) are
 * filtered out with the same id+name predicate
 * `WireguardManager.deleteResidueWgs()` uses; the TV launcher also
 * deletes them at startup.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WgListScreen(navController: NavController) {
    // Reload key bumped after returning from detail/import screens so
    // we re-read the immutable list from the manager.
    var reloadKey by remember { mutableStateOf(0) }

    // Make sure mappings are loaded — first visit to the screen may
    // hit before WireguardManager.load() ran via the boot flow.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            WireguardManager.load(false)
        }
        reloadKey++
    }

    val tunnels by produceState(
        initialValue = emptyList<WgConfigFilesImmutable>(),
        reloadKey,
    ) {
        value = loadTunnels()
    }

    TvScreenScaffold(
        title = "WireGuard tunnels",
        subtitle = "Select a tunnel to manage its peers and behaviour.",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingSectionHeader(
                    text = "Tunnels (${tunnels.size})",
                    modifier = Modifier.weight(1f),
                )
                AddTunnelButton(onClick = { navController.navigate("wg/import") })
            }
            if (tunnels.isEmpty()) {
                Text(
                    text = "No WireGuard tunnels yet. Use \"+ Add tunnel\" above to paste a .conf you copied from your phone, or sideload from the phone build.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            } else {
                tunnels.forEach { wg ->
                    TunnelCard(
                        wg = wg,
                        onClick = { navController.navigate("wg/${wg.id}") },
                    )
                }
            }

            Spacer(Modifier.height(TvSpacing.SectionGap))
        }
    }
}

// Drop a row only when it matches the WARP/SEC_WARP id+name residue
// predicate deleteResidueWgs() uses; a user tunnel merely named
// "WARP" with a different id stays visible.
internal fun WgConfigFilesImmutable.isWarpResidue(): Boolean {
    val warpId = id == WireguardManager.SEC_WARP_ID || id == WireguardManager.WARP_ID
    val warpName = name == WireguardManager.SEC_WARP_NAME || name == WireguardManager.WARP_NAME
    return warpId && warpName
}

internal suspend fun loadTunnels(): List<WgConfigFilesImmutable> {
    return withContext(Dispatchers.IO) {
        WireguardManager.getAllMappings()
            .filter { !it.isWarpResidue() }
            .sortedWith(
                compareByDescending<WgConfigFilesImmutable> { it.isActive }
                    .thenBy { it.name.lowercase() },
            )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddTunnelButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Text(
                text = "+ Add tunnel",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TunnelCard(wg: WgConfigFilesImmutable, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = TvSurfaceStyles.interactiveShape(),
        colors = TvSurfaceStyles.rowColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = TvSpacing.ContentHorizontal,
                vertical = TvSpacing.ContentVertical,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActiveTile(active = wg.isActive)
            Spacer(Modifier.width(TvSpacing.IconTextGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = wg.name.ifBlank { "tunnel #${wg.id}" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (wg.isCatchAll) TagPill("catch-all")
                    if (wg.isLockdown) TagPill("lockdown")
                    if (wg.oneWireGuard) TagPill("one-WG")
                    if (wg.useOnlyOnMetered) TagPill("metered-only")
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActiveTile(active: Boolean) {
    // Tertiary carries the positive/"good" status accent in this theme.
    val container = if (active) TvColors.Success else MaterialTheme.colorScheme.surface
    val content = if (active) TvColors.OnSuccess else MaterialTheme.colorScheme.onSurfaceVariant
    val text = if (active) "ON" else "OFF"
    Box(
        modifier = Modifier
            .background(container, TvShapes.Pill)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TagPill(label: String) {
    Box(
        modifier = Modifier
            .background(TvColors.Success, TvShapes.Pill)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            color = TvColors.OnSuccess,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
