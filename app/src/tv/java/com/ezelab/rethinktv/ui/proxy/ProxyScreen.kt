/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.proxy

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.service.WireguardManager
import com.ezelab.rethinktv.ui.common.SettingSectionHeader
import com.ezelab.rethinktv.ui.common.TvScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Proxy destination — WireGuard tunnels + SOCKS5/HTTP/Orbot status.
 *
 * Layout:
 *
 *  1. **WireGuard tunnels** — list of [WgConfigFilesImmutable]
 *     returned by `WireguardManager.getAllMappings()`. Each card
 *     shows the tunnel name, status pills (Active / Catch-all /
 *     Lockdown / One-WG), and acts as an enable/disable toggle.
 *  2. **Other proxies** — read-only summary card for the legacy
 *     SOCKS5 / HTTP / Orbot proxy state (the phone surfaces a full
 *     form for these; on TV the editor will land alongside the
 *     custom-DNS editor in the polish phase).
 *
 * Writes go through the same `WireguardManager.enableConfig` /
 * `disableConfig` paths upstream's per-card toggles use; these are
 * the only public mutators that also re-bind `VpnController` and
 * the `AppConfig.ProxyProvider` flag.
 *
 * Out of scope for v1 (noted in code):
 *
 *  * WG add/import (file picker + clipboard paste — needs TV-friendly
 *    text-entry dialog).
 *  * WG detail (peers, allowed IPs, DNS, per-app mapping).
 *  * TCP / anti-censorship sub-screens.
 *  * SOCKS5 / HTTP credentials editor (form needs paste-friendly
 *    TextField; TBD).
 *  * Orbot enable/disable (we surface its state, not the toggle).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProxyScreen() {
    val appConfig = koinInject<AppConfig>()
    val scope = rememberCoroutineScope()

    // Reload key bumped after each enable/disable write so we re-read
    // the immutable list from the manager.
    var reloadKey by remember { mutableStateOf(0) }

    // Make sure mappings are loaded — first visit to the screen may
    // hit before WireguardManager.load() ran via the boot flow.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (!WireguardManager.isLoaded()) WireguardManager.load(false)
        }
        reloadKey++
    }

    val tunnels by produceState(
        initialValue = emptyList<WgConfigFilesImmutable>(),
        reloadKey,
    ) {
        value = withContext(Dispatchers.IO) {
            WireguardManager.getAllMappings()
                .sortedWith(compareByDescending<WgConfigFilesImmutable> { it.isActive }
                    .thenBy { it.name.lowercase() })
        }
    }

    val proxyDetails by produceState(initialValue = ProxyDetails(), reloadKey) {
        value = withContext(Dispatchers.IO) {
            ProxyDetails(
                socks5 = appConfig.getSocks5ProxyDetails()?.let { "${it.proxyIP}:${it.proxyPort}" },
                http = appConfig.getHttpProxyDetails()?.let { "${it.proxyIP}:${it.proxyPort}" },
                orbot = appConfig.isOrbotProxyEnabled(),
            )
        }
    }

    TvScreenScaffold(
        title = "Proxy",
        subtitle = "WireGuard tunnels, plus SOCKS5 / HTTP / Orbot status.",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingSectionHeader("WireGuard tunnels (${tunnels.size})")
            if (tunnels.isEmpty()) {
                Text(
                    text = "No WireGuard tunnels yet. The TV file-picker import flow will land in a follow-up — for now sideload from the phone build.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            } else {
                tunnels.forEach { wg ->
                    TunnelCard(
                        wg = wg,
                        onToggle = {
                            scope.launch(Dispatchers.IO) {
                                if (wg.isActive) {
                                    WireguardManager.disableConfig(wg)
                                } else {
                                    WireguardManager.enableConfig(wg)
                                }
                                withContext(Dispatchers.Main) { reloadKey++ }
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SettingSectionHeader("Other proxies")
            ProxyStatusCard(
                label = "SOCKS5",
                value = proxyDetails.socks5,
            )
            ProxyStatusCard(
                label = "HTTP",
                value = proxyDetails.http,
            )
            ProxyStatusCard(
                label = "Orbot",
                value = if (proxyDetails.orbot) "Active" else null,
            )

            Spacer(Modifier.height(24.dp))
            Text(
                text = "WG import, per-tunnel detail, and SOCKS5/HTTP/Orbot editors will land here next.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

private data class ProxyDetails(
    val socks5: String? = null,
    val http: String? = null,
    val orbot: Boolean = false,
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TunnelCard(wg: WgConfigFilesImmutable, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
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
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActiveTile(active = wg.isActive)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = wg.name.ifBlank { "tunnel #${wg.id}" },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
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
    val container = if (active) Color(0xFF2E7D32) else MaterialTheme.colorScheme.surface
    val text = if (active) "ON" else "OFF"
    Box(
        modifier = Modifier
            .background(container, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TagPill(label: String) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onTertiary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProxyStatusCard(label: String, value: String?) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.width(96.dp),
            )
            Text(
                text = value ?: "Not configured",
                color = if (value != null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
