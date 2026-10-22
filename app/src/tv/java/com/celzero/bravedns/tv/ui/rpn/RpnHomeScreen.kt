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

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import androidx.tv.material3.Text
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject

/**
 * RPN destination — TV counterpart of the phone's
 * `ServerSelectionFragment`.
 *
 * Hero status + start/stop, the AUTO + selected-locations grid
 * (max [MAX_SELECTIONS] non-AUTO locations, per
 * [RpnProxyManager]), the 24h activity heat map, and entries to
 * add-location / settings / stats / bypass-apps / subscription.
 *
 * Selection semantics follow the phone: optimistic add with a
 * tunnel-watch poller that clears the "Connecting…" state once
 * `VpnController.getWinByKey` sees the tunnel peer. All writes go
 * through the same `RpnProxyManager` mutators the phone fragment
 * calls.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnHomeScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Bumped after any mutation that changes manager state so the
    // produceState readers re-query.
    var reloadKey by remember { mutableStateOf(0) }
    // Location keys whose enableWinServer() is still not visible in
    // the tunnel ("Connecting…" state), mirroring the phone's
    // tunnel-watch loading set.
    var pendingKeys by remember { mutableStateOf(emptySet<String>()) }

    var statusTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            statusTick++
        }
    }

    val meta by produceState(RpnHomeMeta(), reloadKey, statusTick, pendingKeys) {
        value = withContext(Dispatchers.IO) {
            RpnHomeMeta(
                hasTunnel = runCatching { VpnController.hasTunnel() }.getOrDefault(false),
                rpnActive = runCatching { RpnProxyManager.isRpnActive() }.getOrDefault(false),
                activeSince = runCatching { activeSinceLabel() }.getOrNull(),
                selected = runCatching {
                    RpnProxyManager.getEnabledConfigs()
                        .sortedWith(compareByDescending<CountryConfig> { it.id == RpnProxyManager.AUTO_SERVER_ID }
                            .thenBy { it.serverLocation.lowercase() })
                }.getOrDefault(emptyList()),
                fetchFailed = false,
            )
        }
    }

    // Tunnel watch: poll until each pending key materialises as a
    // tunnel peer (or 10s deadline, same as the phone).
    LaunchedEffect(pendingKeys) {
        if (pendingKeys.isEmpty()) return@LaunchedEffect
        val deadline = System.currentTimeMillis() + 10_000
        var remaining = pendingKeys
        while (remaining.isNotEmpty() && System.currentTimeMillis() < deadline) {
            delay(1500)
            remaining = remaining.filter { key ->
                withContext(Dispatchers.IO) {
                    runCatching { VpnController.getWinByKey(rpnProxyId(key)) }.getOrNull() == null
                }
            }.toSet()
            pendingKeys = remaining
        }
        if (remaining.isNotEmpty()) {
            pendingKeys = emptySet()
            reloadKey++
        }
    }

    var resetInFlight by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<CountryConfig?>(null) }

    fun refreshTunnelProxy() {
        scope.launch(Dispatchers.IO) {
            runCatching { VpnController.refreshRpnProxy(Backend.RpnWin) }
            withContext(Dispatchers.Main) { reloadKey++ }
        }
    }

    fun resetAll() {
        if (resetInFlight) return
        resetInFlight = true
        scope.launch(Dispatchers.IO) {
            runCatching {
                withTimeoutOrNull(20_000) {
                    RpnProxyManager.resetAndRefetchRpn()
                } ?: RpnProxyManager.ResetResult.Failure("Timed out")
            }.onSuccess { result ->
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        if (result is RpnProxyManager.ResetResult.Success) {
                            "RPN restored to defaults"
                        } else {
                            "RPN reset failed — ${(result as RpnProxyManager.ResetResult.Failure).reason}"
                        },
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            withContext(Dispatchers.Main) {
                resetInFlight = false
                reloadKey++
            }
        }
    }

    fun toggleLocation(config: CountryConfig) {
        if (!meta.hasTunnel) {
            Toast.makeText(context, "Start Rethink to manage locations", Toast.LENGTH_SHORT).show()
            return
        }
        if (config.id == RpnProxyManager.AUTO_SERVER_ID) {
            // AUTO cross-tap reconnects, same as the phone's card.
            scope.launch(Dispatchers.IO) {
                runCatching { VpnController.reconnectRpnProxy(Backend.RpnWin) }
                withContext(Dispatchers.Main) { reloadKey++ }
            }
            return
        }
        if (config.isEnabled) {
            confirmRemove = config
            return
        }
        val selectedCount = meta.selected.count { it.id != RpnProxyManager.AUTO_SERVER_ID }
        if (selectedCount >= MAX_SELECTIONS) {
            Toast.makeText(context, "Up to $MAX_SELECTIONS locations can be active", Toast.LENGTH_LONG).show()
            return
        }
        pendingKeys = pendingKeys + config.key
        scope.launch(Dispatchers.IO) {
            runCatching { RpnProxyManager.enableWinServer(config.key) }
            withContext(Dispatchers.Main) { reloadKey++ }
        }
    }

    val isStopped = isProxyStopped()

    TvScreenScaffold(
        title = "RPN",
        subtitle = when {
            !meta.hasTunnel -> "Start Rethink to use Rethink Private Network."
            isStopped -> "RPN is stopped — start it to route traffic via RPN servers."
            else -> meta.activeSince ?: "Rethink Private Network."
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeroRow(
                meta = meta,
                pendingCount = pendingKeys.size,
                busy = resetInFlight,
                onStartStop = {
                    if (!meta.hasTunnel) {
                        runCatching { VpnController.start(context) }
                        return@HeroRow
                    }
                    if (isStopped) {
                        scope.launch(Dispatchers.IO) {
                            runCatching { RpnProxyManager.startProxy() }
                            withContext(Dispatchers.Main) { reloadKey++ }
                        }
                    } else {
                        confirmStop = true
                    }
                },
                onPlus = if (!Utilities.isFdroidFlavour()) {
                    { navController.navigate("rpn/plus") }
                } else {
                    null
                },
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickTile("Add location") { navController.navigate("rpn/add") }
                QuickTile("Settings") { navController.navigate("rpn/settings") }
                QuickTile("Stats") { navController.navigate("rpn/stats") }
                QuickTile("Bypass apps") { navController.navigate("proxy/rpn-bypass") }
                QuickTile("Refresh") { refreshTunnelProxy() }
            }

            Spacer(Modifier.height(8.dp))
            if (!meta.hasTunnel) {
                FocusableActionCard(
                    title = "Rethink is not running",
                    subtitle = "RPN needs the VPN tunnel. Start protection from Home, then come back.",
                    valueText = null,
                    onClick = { runCatching { VpnController.start(context) } },
                )
            } else {
                val autoCount = meta.selected.count { it.id != RpnProxyManager.AUTO_SERVER_ID }
                SettingSectionHeader(
                    "Locations (AUTO + $autoCount of $MAX_SELECTIONS)",
                )
                if (meta.selected.isEmpty()) {
                    FocusableActionCard(
                        title = "No locations selected",
                        subtitle = "AUTO is always on. Add a location to pin your exit country.",
                        valueText = "Add ›",
                        onClick = { navController.navigate("rpn/add") },
                    )
                } else {
                    meta.selected.forEach { config ->
                        val isAuto = config.id == RpnProxyManager.AUTO_SERVER_ID
                        val connecting = !isAuto && config.key in pendingKeys
                        LocationCard(
                            config = config,
                            isAuto = isAuto,
                            connecting = connecting,
                            onClick = { toggleLocation(config) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                RpnHeatmapSection { start, end ->
                    navController.navigate("rpn/heatmap/$start/$end")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmStop) {
        TvConfirmDialog(
            title = "Stop RPN?",
            message = "Traffic will stop exiting via RPN servers until you start it again.",
            confirmLabel = "Stop",
            onConfirm = {
                confirmStop = false
                scope.launch(Dispatchers.IO) {
                    runCatching { RpnProxyManager.stopProxy() }
                    withContext(Dispatchers.Main) { reloadKey++ }
                }
            },
            onDismiss = { confirmStop = false },
        )
    }
    confirmRemove?.let { config ->
        TvConfirmDialog(
            title = "Remove ${config.serverLocation}?",
            message = "Traffic for this location falls back to AUTO.",
            confirmLabel = "Remove",
            onConfirm = {
                confirmRemove = null
                pendingKeys = pendingKeys - config.key
                scope.launch(Dispatchers.IO) {
                    runCatching { RpnProxyManager.disableWinServer(config.key) }
                    withContext(Dispatchers.Main) { reloadKey++ }
                }
            },
            onDismiss = { confirmRemove = null },
        )
    }
}

private data class RpnHomeMeta(
    val hasTunnel: Boolean = false,
    val rpnActive: Boolean = false,
    val activeSince: String? = null,
    val selected: List<CountryConfig> = emptyList(),
    val fetchFailed: Boolean = false,
)

internal const val MAX_SELECTIONS = 5

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HeroRow(
    meta: RpnHomeMeta,
    pendingCount: Int,
    busy: Boolean,
    onStartStop: () -> Unit,
    onPlus: (() -> Unit)?,
) {
    val status = deriveRpnUiStatus(meta.hasTunnel, meta.rpnActive, pendingCount > 0)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RpnStatusPill(status)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when {
                    pendingCount > 0 -> "Connecting to new location…"
                    meta.rpnActive -> "Rethink Private Network"
                    else -> "RPN is not active"
                },
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onPlus != null) {
            Button(onClick = onPlus) {
                Text("Get Plus", style = MaterialTheme.typography.labelLarge)
            }
        }
        Button(onClick = onStartStop, enabled = !busy) {
            Text(
                text = when {
                    !meta.hasTunnel -> "Start Rethink"
                    isProxyStopped() -> "Start RPN"
                    else -> "Stop RPN"
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QuickTile(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun LocationCard(
    config: CountryConfig,
    isAuto: Boolean,
    connecting: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
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
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(50),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isAuto) "\uD83C\uDF10" else config.flagEmoji,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isAuto) "AUTO" else config.serverLocation,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        connecting -> "Connecting…"
                        isAuto -> "Always-on automatic location · tap to reconnect"
                        else -> "Load ${config.load}% · tap to remove"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (connecting) {
                RpnStatusPill(RpnUiStatus.CONNECTING)
            } else {
                Text(
                    text = if (isAuto) "Open ›" else "Remove ✕",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
