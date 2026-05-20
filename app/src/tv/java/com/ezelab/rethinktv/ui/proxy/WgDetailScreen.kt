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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.ezelab.rethinktv.ui.common.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.Peer
import com.ezelab.rethinktv.ui.common.SettingSectionHeader
import com.ezelab.rethinktv.ui.common.SettingToggleRow
import com.ezelab.rethinktv.ui.common.TvScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-tunnel WireGuard detail screen.
 *
 * Pushed onto the back-stack from [ProxyScreen] when the user
 * activates a tunnel card. Mirrors the phone's
 * `WgConfigDetailActivity` — interface info, the four secondary
 * toggles (catch-all / lockdown / one-WG / use-only-on-metered),
 * and the peers list.
 *
 * Reads:
 *  * [WireguardManager.getConfigFilesById] for the mutable mapping
 *    (status flags).
 *  * [WireguardManager.getConfigById] for the parsed [Config]
 *    (interface + peers).
 *
 * Writes:
 *  * [WireguardManager.updateCatchAllConfig]
 *  * [WireguardManager.updateLockdownConfig]
 *  * [WireguardManager.updateOneWireGuardConfig]
 *  * [WireguardManager.updateUseOnMobileNetworkConfig]
 *
 * Add / delete / edit peers is upstream's `WgConfigEditorActivity`
 * territory and is intentionally deferred — TV remotes can't type a
 * 44-char base64 public key without a Bluetooth keyboard, so peer
 * mutation is a follow-up that needs a file-picker import flow.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WgDetailScreen(configId: Int) {
    val scope = rememberCoroutineScope()
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        if (!WireguardManager.isLoaded()) {
            withContext(Dispatchers.IO) { WireguardManager.load(false) }
            reloadKey++
        }
    }

    val mapping by produceState<WgConfigFilesImmutable?>(initialValue = null, configId, reloadKey) {
        value = withContext(Dispatchers.IO) {
            WireguardManager.getConfigFilesById(configId)
        }
    }
    val config by produceState<Config?>(initialValue = null, configId, reloadKey) {
        value = withContext(Dispatchers.IO) {
            WireguardManager.getConfigById(configId)
        }
    }

    val name = mapping?.name?.ifBlank { "tunnel #$configId" } ?: "tunnel #$configId"
    val active = mapping?.isActive == true

    TvScreenScaffold(
        title = name,
        subtitle = if (active) "Active — traffic is currently flowing through this tunnel." else "Inactive.",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (mapping == null) {
                Text(
                    text = "Tunnel not found.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            InterfaceCard(config = config, mapping = mapping!!)

            SettingSectionHeader("Tunnel status")
            val m = mapping!!
            SettingToggleRow(
                title = "Active",
                description = "Route traffic through this WireGuard tunnel.",
                checked = m.isActive,
                onCheckedChange = { v ->
                    scope.launch(Dispatchers.IO) {
                        if (v) WireguardManager.enableConfig(m) else WireguardManager.disableConfig(m)
                        withContext(Dispatchers.Main) { reloadKey++ }
                    }
                },
            )

            Spacer(Modifier.height(16.dp))
            SettingSectionHeader("Tunnel behaviour")
            SettingToggleRow(
                title = "Catch-all",
                description = "Route every app whose proxy mapping is unset through this tunnel.",
                checked = m.isCatchAll,
                onCheckedChange = { v ->
                    scope.launch(Dispatchers.IO) {
                        WireguardManager.updateCatchAllConfig(configId, v)
                        withContext(Dispatchers.Main) { reloadKey++ }
                    }
                },
            )
            SettingToggleRow(
                title = "Lockdown",
                description = "Block any traffic when this tunnel is down.",
                checked = m.isLockdown,
                onCheckedChange = { v ->
                    scope.launch(Dispatchers.IO) {
                        WireguardManager.updateLockdownConfig(configId, v)
                        withContext(Dispatchers.Main) { reloadKey++ }
                    }
                },
            )
            SettingToggleRow(
                title = "Exclusive (one-WireGuard)",
                description = "Disable Rethink's other proxies so all VPN traffic is forced through this tunnel.",
                checked = m.oneWireGuard,
                onCheckedChange = { v ->
                    scope.launch(Dispatchers.IO) {
                        WireguardManager.updateOneWireGuardConfig(configId, v)
                        withContext(Dispatchers.Main) { reloadKey++ }
                    }
                },
            )
            SettingToggleRow(
                title = "Use only on metered networks",
                description = "Engage this tunnel only when the active network is metered (mobile data).",
                checked = m.useOnlyOnMetered,
                onCheckedChange = { v ->
                    scope.launch(Dispatchers.IO) {
                        WireguardManager.updateUseOnMobileNetworkConfig(configId, v)
                        withContext(Dispatchers.Main) { reloadKey++ }
                    }
                },
            )

            Spacer(Modifier.height(16.dp))
            val peers = config?.getPeers().orEmpty()
            SettingSectionHeader("Peers (${peers.size})")
            if (peers.isEmpty()) {
                Text(
                    text = "No peers in this tunnel. Editing peers from the TV will land alongside the import flow.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            } else {
                peers.forEachIndexed { i, peer ->
                    PeerCard(index = i + 1, peer = peer)
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Tap a peer for raw allowed-IPs. Editing peers requires a paste-friendly text-entry flow that will land alongside the WG import screen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InterfaceCard(config: Config?, mapping: WgConfigFilesImmutable) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActiveTile(active = mapping.isActive)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Interface",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
            }
            val iface = config?.getInterface()
            if (iface == null) {
                Text(
                    text = "Config not loaded.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }
            val addrs = iface.getAddresses().joinToString(", ") { it.toString() }
            val dns = iface.dnsServers.joinToString(", ") { it.hostAddress ?: it.toString() }
            val pub = iface.getKeyPair().getPublicKey().base64()
            InfoLine("Addresses", addrs.ifBlank { "—" })
            if (dns.isNotBlank()) InfoLine("DNS", dns)
            if (iface.mtu.isPresent) InfoLine("MTU", iface.mtu.get().toString())
            if (iface.listenPort.isPresent) InfoLine("Listen port", iface.listenPort.get().toString())
            InfoLine("Public key", pub, monospace = true)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PeerCard(index: Int, peer: Peer) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Peer #$index",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(2.dp))
            InfoLine("Public key", peer.getPublicKey().base64(), monospace = true)
            val allowed = peer.getAllowedIps().joinToString(", ") { it.toString() }
            InfoLine("Allowed IPs", allowed.ifBlank { "—" })
            val endpoint = peer.getEndpointText().orElse(null) ?: "—"
            InfoLine("Endpoint", endpoint)
            if (peer.persistentKeepalive.isPresent) {
                InfoLine("Keepalive", "${peer.persistentKeepalive.get()} s")
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InfoLine(label: String, value: String, monospace: Boolean = false) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Text(
            text = value,
            style = if (monospace) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActiveTile(active: Boolean) {
    val container = if (active) Color(0xFF2E7D32) else MaterialTheme.colorScheme.surface
    Box(
        modifier = Modifier
            .background(container, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = if (active) "ON" else "OFF",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        )
    }
}
