/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PublicOff
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.ezelab.rethinktv.ui.common.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.service.PersistentState
import com.ezelab.rethinktv.ui.common.SettingSectionHeader
import com.ezelab.rethinktv.ui.common.SettingToggleRow
import com.ezelab.rethinktv.ui.common.TvScreenScaffold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settings destination — Phase I.
 *
 * Surfaces the union of the upstream Tunnel / Misc / Advanced settings
 * activities, filtered to what makes sense on a TV:
 *
 *  * **Protection mode** — DNS / Firewall / DNS+Firewall picker. The
 *    single most consequential setting on the app; kept at the top of
 *    the screen so it's the first focusable target after the title.
 *  * **Tunnel** — the network-routing toggles users actually flip on
 *    Android TV (LAN traffic, allow-bypass, IPv6 translation, …).
 *  * **WireGuard global** — the two tunnel-wide WG knobs (global
 *    lockdown, smart persistent keep-alive).
 *  * **Reliability** — TCP keep-alive, max MTU, stall-on-no-network.
 *  * **Boot & lifecycle** — auto-start on boot, the only lifecycle
 *    setting that makes sense on a TV (everything else is interactive
 *    or requires an accessibility service).
 *
 * Settings not exposed (deliberately):
 *
 *  * Theme — TV is locked to dark.
 *  * Biometric / fingerprint App Lock — Leanback devices don't have it
 *    reliably; PIN App Lock will be its own destination in a follow-up.
 *  * Notifications — TVs surface them inconsistently.
 *  * Locale picker — uses upstream's full intent flow, easier to land
 *    as a separate dialog later.
 *  * Backup / Restore — file-picker-driven, will be its own card under
 *    Settings in the polish pass.
 *
 * State reads use raw property getters (these are `booleanPref`-backed
 * Kotlin `var`s, not LiveData, so the mutate-in-place identity bug
 * doesn't apply). Writes hop to [Dispatchers.IO] because the
 * `booleanPref` delegate commits to SharedPreferences synchronously.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController? = null) {
    val persistentState = koinInject<PersistentState>()
    val appConfig = koinInject<AppConfig>()
    val composeScope = rememberCoroutineScope()
    val ioScope = remember { CoroutineScope(Dispatchers.IO) }

    TvScreenScaffold(
        title = "Settings",
        subtitle = "Protection, tunnel, network, and reliability options.",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ProtectionModeSection(
                appConfig = appConfig,
                persistentState = persistentState,
                composeScope = composeScope,
            )

            Spacer(Modifier.height(12.dp))
            SettingSectionHeader("Tunnel")
            SettingToggleRow(
                title = "Allow apps to bypass the VPN",
                description = "Lets apps that explicitly opt out (e.g. some VPN clients) skip Rethink.",
                leadingIcon = Icons.AutoMirrored.Filled.AltRoute,
                checked = remember { persistentState.allowBypass },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.allowBypass = next }
                },
            )
            SettingToggleRow(
                title = "Allow LAN traffic",
                description = "Permits direct connections to RFC1918 addresses (printer / NAS / Chromecast on the same network).",
                leadingIcon = Icons.Filled.Lan,
                checked = remember { persistentState.privateIps },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.privateIps = next }
                },
            )
            SettingToggleRow(
                title = "Use all available networks",
                description = "Carries traffic across every active network simultaneously, not just the default. Useful when Wi-Fi and Ethernet are both connected.",
                leadingIcon = Icons.Filled.WifiTethering,
                checked = remember { persistentState.useMultipleNetworks },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.useMultipleNetworks = next }
                },
            )
            SettingToggleRow(
                title = "Protocol translation (NAT64 / 6to4)",
                description = "Translates between IPv4 and IPv6 when the network only carries one of them.",
                leadingIcon = Icons.Filled.Translate,
                checked = remember { persistentState.protocolTranslationType },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.protocolTranslationType = next }
                },
            )

            Spacer(Modifier.height(12.dp))
            SettingSectionHeader("WireGuard")
            SettingToggleRow(
                title = "Global lockdown",
                description = "Drop traffic when no WireGuard tunnel is active. Use with caution — there is no fall-back path.",
                leadingIcon = Icons.Filled.PublicOff,
                checked = remember { persistentState.wgGlobalLockdown },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.wgGlobalLockdown = next }
                },
            )
            SettingToggleRow(
                title = "Smart persistent keep-alive",
                description = "Adjusts WG keep-alive based on tunnel idle time. Lowers battery / CPU when the TV is idle.",
                leadingIcon = Icons.Filled.Repeat,
                checked = remember { persistentState.smartPersistentKeepalive },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.smartPersistentKeepalive = next }
                },
            )

            Spacer(Modifier.height(12.dp))
            SettingSectionHeader("Reliability")
            SettingToggleRow(
                title = "Endpoint-independent mapping",
                description = "Improves NAT traversal for peer-to-peer protocols (game-streaming, VoIP, …).",
                leadingIcon = Icons.Filled.SettingsEthernet,
                checked = remember { persistentState.endpointIndependence },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.endpointIndependence = next }
                },
            )
            SettingToggleRow(
                title = "TCP keep-alive",
                description = "Sends periodic probes on idle TCP connections so flaky NAT routers don't time them out.",
                leadingIcon = Icons.Filled.Tune,
                checked = remember { persistentState.tcpKeepAlive },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.tcpKeepAlive = next }
                },
            )
            SettingToggleRow(
                title = "Use maximum MTU",
                description = "Maximises tunnel MTU. Faster on stable networks, can break on flaky ones.",
                leadingIcon = Icons.Filled.AccessibilityNew,
                checked = remember { persistentState.useMaxMtu },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.useMaxMtu = next }
                },
            )
            SettingToggleRow(
                title = "Stall on no network",
                description = "Pause traffic until a network is back online, instead of failing connections.",
                leadingIcon = Icons.Filled.PowerSettingsNew,
                checked = remember { persistentState.stallOnNoNetwork },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.stallOnNoNetwork = next }
                },
            )

            Spacer(Modifier.height(12.dp))
            SettingSectionHeader("Boot")
            SettingToggleRow(
                title = "Auto-start on boot",
                description = "Re-enable protection automatically when the TV powers on.",
                leadingIcon = Icons.Filled.PlayCircleOutline,
                checked = remember { persistentState.prefAutoStartBootUp },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.prefAutoStartBootUp = next }
                },
            )

            Spacer(Modifier.height(12.dp))
            SettingSectionHeader("Advanced")
            SettingNavRow(
                title = "Pause protection",
                description = "Snooze DNS and firewall rules for a set duration without bringing the tunnel down.",
                leadingIcon = Icons.Filled.Pause,
                onClick = { navController?.navigate("settings/pause") },
            )
            SettingNavRow(
                title = "Anti-censorship",
                description = "Pick the dial-strategy + retry behaviour used to bypass DPI on hostile networks.",
                leadingIcon = Icons.Filled.Shield,
                onClick = { navController?.navigate("settings/anti-censorship") },
            )
            SettingNavRow(
                title = "Console log",
                description = "Rolling app diagnostics. Helpful when something looks broken — useful for bug reports.",
                leadingIcon = Icons.Filled.BugReport,
                onClick = { navController?.navigate("settings/console-log") },
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingNavRow(
    title: String,
    description: String,
    leadingIcon: ImageVector,
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * Three-button picker for the brave-mode (DNS / Firewall / both).
 * Lives in its own composable so the focus-traversal stays linear:
 * users land here first, then the toggle rows below.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProtectionModeSection(
    appConfig: AppConfig,
    persistentState: PersistentState,
    composeScope: kotlinx.coroutines.CoroutineScope,
) {
    // Track current mode locally so the picker feels instant — the
    // AppConfig.changeBraveMode call eventually updates this anyway
    // via the brave-mode observable, but we don't want UI lag.
    var currentMode by remember {
        mutableStateOf(AppConfig.BraveMode.getMode(persistentState.braveMode))
    }

    SettingSectionHeader("Protection mode")
    Text(
        text = "DNS blocks ad / tracker hostnames. Firewall blocks per-app network access. " +
            "DNS + Firewall does both.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 12.dp, start = 4.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AppConfig.BraveMode.entries.forEach { mode ->
            val label = when (mode) {
                AppConfig.BraveMode.DNS -> "DNS"
                AppConfig.BraveMode.FIREWALL -> "Firewall"
                AppConfig.BraveMode.DNS_FIREWALL -> "DNS + Firewall"
            }
            val selected = mode == currentMode
            Surface(
                onClick = {
                    currentMode = mode
                    composeScope.launch(Dispatchers.IO) {
                        appConfig.changeBraveMode(mode.mode)
                    }
                },
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                ) {
                    Text(
                        text = if (selected) "✓  $label" else label,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
    }
}
