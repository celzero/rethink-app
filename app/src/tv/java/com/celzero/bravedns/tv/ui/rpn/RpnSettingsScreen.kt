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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.SettingToggleRow
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import androidx.tv.material3.Text
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Backend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.koinInject

/**
 * RPN settings — TV counterpart of `ServerSettingsBottomSheet`:
 * RPN DNS filters (privacy / family / security, requires split DNS),
 * config handling (AUTO vs MANUAL with identity / port / permanent
 * config), auto-excluded countries, plus refresh and reset.
 *
 * All writes persist through [PersistentState] exactly as the sheet
 * does, then re-apply via [VpnController.onRpnOptsChange]. "No
 * filters" is stored as an empty `rpnDnsTunTypes` CSV (sheet
 * convention), not `DnsMode.tunTypesFromSet(emptySet())`.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnSettingsScreen(navController: NavController) {
    val context = LocalContext.current
    val persistentState: PersistentState = koinInject()
    val scope = rememberCoroutineScope()

    var reloadKey by remember { mutableStateOf(0) }
    var resetInFlight by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    // Re-read PersistentState after mutations (Krate-backed prefs).
    val modes by produceState(
        initialValue = RpnProxyManager.DnsMode.setFromCsv(persistentState.rpnDnsTunTypes),
        reloadKey,
    ) {
        value = RpnProxyManager.DnsMode.setFromCsv(persistentState.rpnDnsTunTypes)
    }

    fun applyOpts() {
        scope.launch(Dispatchers.IO) {
            runCatching { VpnController.onRpnOptsChange() }
        }
    }

    fun setDnsMode(mode: RpnProxyManager.DnsMode, enabled: Boolean) {
        val current = RpnProxyManager.DnsMode.setFromCsv(persistentState.rpnDnsTunTypes)
        val next = if (enabled) current + mode else current - mode
        // Sheet convention: empty set → empty CSV ("no filters").
        persistentState.rpnDnsTunTypes =
            if (next.isEmpty()) "" else RpnProxyManager.DnsMode.tunTypesFromSet(next)
        reloadKey++
        applyOpts()
    }

    fun resetAll() {
        if (resetInFlight) return
        resetInFlight = true
        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                withTimeoutOrNull(20_000) {
                    RpnProxyManager.resetAndRefetchRpn()
                } ?: RpnProxyManager.ResetResult.Failure("Timed out")
            }.getOrDefault(RpnProxyManager.ResetResult.Failure("Failed"))
            withContext(Dispatchers.Main) {
                resetInFlight = false
                Toast.makeText(
                    context,
                    if (result is RpnProxyManager.ResetResult.Success) {
                        "RPN restored to defaults"
                    } else {
                        "RPN reset failed"
                    },
                    Toast.LENGTH_LONG,
                ).show()
                reloadKey++
            }
        }
    }

    val splitDnsOn = persistentState.splitDns
    val manual = persistentState.rpnConfigHandlingManual

    TvScreenScaffold(
        title = "RPN settings",
        subtitle = "DNS filters, connection handling, and server maintenance.",
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingSectionHeader("DNS filters")
            if (!splitDnsOn) {
                FocusableActionCard(
                    title = "Split DNS is off",
                    subtitle = "RPN DNS filters need split DNS. Enable it to use them.",
                    valueText = "Enable ›",
                    onClick = {
                        persistentState.splitDns = true
                        // Mirror the sheet: ALG packet-handling is the
                        // pre-Q transport for split DNS.
                        if (!Utilities.isAtleastR()) persistentState.enableDnsAlg = true
                        reloadKey++
                        applyOpts()
                    },
                )
            } else {
                Text(
                    text = if (modes.isEmpty()) {
                        "No filters active — RPN resolver runs unfiltered."
                    } else {
                        "Active: ${modes.joinToString { it.tunType }}"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                SettingToggleRow(
                    title = "Privacy",
                    description = "Block ads, trackers, malware.",
                    checked = RpnProxyManager.DnsMode.PRIVACY in modes,
                    enabled = splitDnsOn,
                    onCheckedChange = { setDnsMode(RpnProxyManager.DnsMode.PRIVACY, it) },
                )
                SettingToggleRow(
                    title = "Family",
                    description = "Block adult content plus the privacy filter set.",
                    checked = RpnProxyManager.DnsMode.PARENTAL in modes,
                    enabled = splitDnsOn,
                    onCheckedChange = { setDnsMode(RpnProxyManager.DnsMode.PARENTAL, it) },
                )
                SettingToggleRow(
                    title = "Security",
                    description = "Block malware, phishing, and crypto-jackers.",
                    checked = RpnProxyManager.DnsMode.SECURITY in modes,
                    enabled = splitDnsOn,
                    onCheckedChange = { setDnsMode(RpnProxyManager.DnsMode.SECURITY, it) },
                )
            }

            SettingSectionHeader("Config handling")
            SettingToggleRow(
                title = "Manual configuration",
                description = "Pick identity and port handling yourself instead of AUTO.",
                checked = manual,
                onCheckedChange = { enabled ->
                    persistentState.rpnConfigHandlingManual = enabled
                    reloadKey++
                    applyOpts()
                },
            )
            if (manual) {
                SettingToggleRow(
                    title = "Always change identity",
                    description = "Rotate the exit identity on every connection.",
                    checked = persistentState.rpnAlwaysChangeIdentity,
                    onCheckedChange = { enabled ->
                        persistentState.rpnAlwaysChangeIdentity = enabled
                        applyOpts()
                    },
                )
                SettingToggleRow(
                    title = "Permanent config",
                    description = "Reuse the same tunnel configuration across restarts.",
                    checked = persistentState.rpnUsePermanentConfig,
                    onCheckedChange = { enabled ->
                        persistentState.rpnUsePermanentConfig = enabled
                        applyOpts()
                    },
                )
                SettingSectionHeader("Port")
                val ports = listOf(0 to "Random", 80 to "80", 443 to "443", 53 to "53",
                    123 to "123", 1194 to "1194", 65142 to "65142")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ports.forEach { (port, label) ->
                        FilterChip(
                            label = label,
                            selected = persistentState.rpnPort == port,
                            onClick = {
                                persistentState.rpnPort = port
                                reloadKey++
                                applyOpts()
                            },
                        )
                    }
                }
            }

            SettingSectionHeader("Maintenance")
            FocusableActionCard(
                title = "Excluded countries (AUTO)",
                subtitle = "Countries AUTO will never pick.",
                valueText = "Configure ›",
                onClick = { navController.navigate("rpn/settings/exclude") },
            )
            FocusableActionCard(
                title = "Refresh servers",
                subtitle = "Re-fetch the RPN server list from the tunnel.",
                valueText = "Refresh ›",
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        runCatching { VpnController.refreshRpnProxy(Backend.RpnWin) }
                        withContext(Dispatchers.Main) { reloadKey++ }
                    }
                },
            )
            FocusableActionCard(
                title = "Restore defaults",
                subtitle = "Reset RPN selections, filters, and configuration.",
                valueText = if (resetInFlight) "Working…" else "Reset ›",
                onClick = { confirmReset = true },
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmReset) {
        TvConfirmDialog(
            title = "Restore RPN defaults?",
            message = "Your selected locations, DNS filters, and configuration " +
                "are reset and the server list is re-fetched.",
            confirmLabel = "Reset",
            onConfirm = {
                confirmReset = false
                resetAll()
            },
            onDismiss = { confirmReset = false },
        )
    }
}

/**
 * AUTO exclude-country list — TV counterpart of the sheet's nested
 * `AutoExcludeCountriesBottomSheet`. Writes the CSV of excluded
 * country codes into [PersistentState.rpnAutoExcludedCcs] and
 * re-applies tunnel options.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnExcludeCountriesScreen() {
    val persistentState: PersistentState = koinInject()
    val scope = rememberCoroutineScope()

    var reloadKey by remember { mutableStateOf(0) }

    data class CountryRow(val cc: String, val name: String, val excluded: Boolean)

    val rows by produceState<List<CountryRow>>(emptyList(), reloadKey) {
        value = withContext(Dispatchers.IO) {
            val excluded = persistentState.rpnAutoExcludedCcs
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            runCatching { RpnProxyManager.getWinServers() }.getOrDefault(emptyList())
                .asSequence()
                .filter { it.cc.isNotBlank() && it.cc != RpnProxyManager.AUTO_COUNTRY_CODE }
                .distinctBy { it.cc }
                .sortedBy { it.countryName.lowercase() }
                .map { CountryRow(it.cc, it.countryName, it.cc in excluded) }
                .toList()
        }
    }

    TvScreenScaffold(
        title = "Excluded countries",
        subtitle = "AUTO never picks excluded countries. ${rows.count { it.excluded }} excluded.",
    ) {
        if (rows.isEmpty()) {
            Text(
                text = "Loading country list… (requires an active tunnel)",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@TvScreenScaffold
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(rows, key = { it.cc }) { row ->
                SettingToggleRow(
                    title = "${row.name} (${row.cc})",
                    description = if (row.excluded) "Excluded from AUTO" else "Available to AUTO",
                    checked = row.excluded,
                    onCheckedChange = { enabled ->
                        val current = persistentState.rpnAutoExcludedCcs
                            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
                        if (enabled) current.add(row.cc) else current.remove(row.cc)
                        persistentState.rpnAutoExcludedCcs = current.joinToString(",")
                        scope.launch(Dispatchers.IO) {
                            runCatching { VpnController.onRpnOptsChange() }
                            withContext(Dispatchers.Main) { reloadKey++ }
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        ),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
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
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
            )
        }
    }
}
