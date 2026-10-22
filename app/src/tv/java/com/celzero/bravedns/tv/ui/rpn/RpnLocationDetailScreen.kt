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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.data.SsidItem
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.SettingToggleRow
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import androidx.tv.material3.Text
import com.celzero.firestack.backend.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Per-location RPN detail — TV counterpart of
 * `RpnConfigDetailActivity`: live stats, client exit IP, and the
 * Relay / Catch-all / Lockdown / Use-on-mobile / SSID toggles, all
 * driven through the same `RpnProxyManager` mutators as the phone.
 *
 * Deliberately omitted vs. the phone screen: the SSID
 * permission/location-off recovery flows (TV has no usable Wi-Fi
 * location UX — errors are surfaced as text), and the "Configure
 * Hops" stub (upstream shows a "Coming Soon" toast).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnLocationDetailScreen(key: String, navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val proxyId = rpnProxyId(key)

    val config by produceState<CountryConfig?>(null, key) {
        value = withContext(Dispatchers.IO) {
            runCatching { RpnProxyManager.getCountryConfigByKey(key) }.getOrNull()
        }
    }

    // 2s live poll, same cadence as the phone detail screen.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            tick++
        }
    }

    val live by produceState(RpnLiveStats(), key, tick) {
        value = withContext(Dispatchers.IO) {
            val status = runCatching { VpnController.getProxyStatusById(proxyId) }
                .getOrNull()?.second ?: "Unknown"
            val stats = runCatching { VpnController.getProxyStats(proxyId) }.getOrNull()
            val meta = runCatching { VpnController.getRpnClientInfoById(proxyId) }.getOrNull()?.iP4()
            val apps = runCatching { ProxyManager.getAppsCountForProxy(proxyId) }.getOrDefault(0)
            RpnLiveStats(
                statusText = status,                rx = stats?.rx ?: 0L,
                tx = stats?.tx ?: 0L,
                since = stats?.since ?: 0L,
                lastOk = stats?.lastOK ?: 0L,
                exitIp = meta?.ip ?: "",
                exitOrg = listOfNotNull(meta?.asnOrg, meta?.city).filter { it.isNotBlank() }
                    .joinToString(" · "),
                appCount = apps,
            )
        }
    }

    var confirmRelay by remember { mutableStateOf(false) }
    var ssidDraft by remember { mutableStateOf("") }

    val cfg = config
    val isAuto = cfg?.id == RpnProxyManager.AUTO_SERVER_ID

    fun mutate(label: String, block: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            runCatching { block() }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (cfg == null) {
        TvScreenScaffold(title = "RPN location", subtitle = "Loading location…") {}
        return
    }

    TvScreenScaffold(
        title = "${cfg.flagEmoji} ${cfg.serverLocation}",
        subtitle = "Load ${cfg.load}% · key ${cfg.key.take(12)}…",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatsCard(live)

            SettingSectionHeader("Routing behaviour")
            if (!isAuto) {
                SettingToggleRow(
                    title = "Relay (chain via AUTO)",
                    description = "Route this location's traffic through the AUTO entry first.",
                    checked = cfg.hopEnabled,
                    onCheckedChange = { enabled ->
                    if (enabled) {
                        scope.launch(Dispatchers.IO) {
                            val automationOn = runCatching {
                                RpnProxyManager.isAutoAutomationEnabled()
                            }.getOrDefault(false)
                            withContext(Dispatchers.Main) {
                                if (automationOn) {
                                    confirmRelay = true
                                } else {
                                    mutate("Relay updated") {
                                        RpnProxyManager.setHopForWinServer(key, true)
                                    }
                                }
                            }
                        }
                    } else {
                        mutate("Relay off") { RpnProxyManager.setHopForWinServer(key, false) }
                    }
                    },
                )
            }
            SettingToggleRow(
                title = "All apps (catch-all)",
                description = "Route every app through this location. Overrides per-app selection.",
                checked = cfg.catchAll,
                enabled = !cfg.ssidBased,
                onCheckedChange = { enabled ->
                    mutate("Catch-all updated") { RpnProxyManager.setCatchAllForWinServer(key, enabled) }
                },
            )
            SettingToggleRow(
                title = "Lockdown",
                description = "Only allow traffic through this location; block the rest.",
                checked = cfg.lockdown,
                onCheckedChange = { enabled ->
                    mutate("Lockdown updated") { RpnProxyManager.setLockdownForWinServer(key, enabled) }
                },
            )
            SettingToggleRow(
                title = "Use on mobile network (experimental)",
                description = if (isAuto) {
                    "AUTO stays on mobile data only when enabled."
                } else {
                    "Keep this location active on metered networks."
                },
                checked = cfg.mobileOnly,
                onCheckedChange = { enabled ->
                    mutate("Mobile preference updated") {
                        RpnProxyManager.setMobileOnlyForWinServer(key, enabled)
                    }
                },
            )

            SettingSectionHeader("SSID filter (experimental)")
            SettingToggleRow(
                title = "SSID-based",
                description = "Enable this location only on the networks listed below.",
                checked = cfg.ssidBased,
                onCheckedChange = { enabled ->
                    mutate("SSID filter updated") { RpnProxyManager.updateSsidBased(key, enabled) }
                },
            )
            val ssids = remember(cfg.ssids) { SsidItem.parseStorageList(cfg.ssids) }
            if (ssids.isEmpty()) {
                Text(
                    text = "All SSIDs. Add one to restrict this location to specific networks.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                ssids.forEach { ssid ->
                    FocusableActionCard(
                        title = ssid.name,
                        subtitle = ssid.type.name.lowercase().replace('_', ' '),
                        valueText = "Remove ✕",
                        onClick = {
                            val next = ssids.filterNot { it == ssid }
                            mutate("SSID removed") {
                                RpnProxyManager.updateSsids(key, SsidItem.toStorageList(next))
                            }
                        },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = ssidDraft,
                    onValueChange = { ssidDraft = it },
                    label = { Text("Add SSID (wildcard match)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = {
                        val name = ssidDraft.trim()
                        if (name.isNotEmpty()) {
                            ssidDraft = ""
                            val next = ssids +
                                SsidItem(name, SsidItem.SsidType.EQUAL_WILDCARD)
                            mutate("SSID added") {
                                RpnProxyManager.updateSsids(key, SsidItem.toStorageList(next))
                            }
                        }
                    },
                ) { Text("Add") }
            }

            Spacer(Modifier.height(8.dp))
            SettingSectionHeader("More")
            FocusableActionCard(
                title = "Apps routed here",
                subtitle = if (cfg.catchAll) {
                    "All apps (catch-all is on)"
                } else {
                    "${live.appCount} apps route through this location."
                },
                valueText = "Manage ›",
                onClick = { navController.navigate("rpn/apps/$key") },
            )
            FocusableActionCard(
                title = "Connection logs",
                subtitle = "Open the Logs tab to inspect RPN-routed connections.",
                valueText = "Open ›",
                onClick = { navController.navigate("logs") },
            )
            FocusableActionCard(
                title = "Refresh / reconnect",
                subtitle = "Re-fetch this location's configuration and reconnect.",
                valueText = "Refresh ›",
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        runCatching { VpnController.refreshRpnProxy(Backend.RpnWin) }
                    }
                },
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmRelay) {
        TvConfirmDialog(
            title = "Enable relay?",
            message = "AUTO automation is on. Relaying through AUTO chains this " +
                "location behind the automatic entry — continue?",
            confirmLabel = "Relay",
            onConfirm = {
                confirmRelay = false
                mutate("Relay on") { RpnProxyManager.setHopForWinServer(key, true) }
            },
            onDismiss = { confirmRelay = false },
        )
    }
}

/** 2-second live stats strip: status, counters, exit IP. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatsCard(live: RpnLiveStats) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = live.statusText,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Down ${formatBytes(live.rx)} · Up ${formatBytes(live.tx)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (live.since > 0L) {
                Text(
                    text = "Active ${formatRelative(live.since)} · last ok ${formatRelative(live.lastOk)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (live.exitIp.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                val exitLine = buildString {
                    append("Exit IP ")
                    append(live.exitIp)
                    if (live.exitOrg.isNotBlank()) {
                        append(" · ")
                        append(live.exitOrg)
                    }
                }
                Text(
                    text = exitLine,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Snapshot of the per-location live poll (2s cadence). */
private data class RpnLiveStats(
    val statusText: String = "Checking…",
    val rx: Long = 0L,
    val tx: Long = 0L,
    val since: Long = 0L,
    val lastOk: Long = 0L,
    val exitIp: String = "",
    val exitOrg: String = "",
    val appCount: Int = 0,
)
