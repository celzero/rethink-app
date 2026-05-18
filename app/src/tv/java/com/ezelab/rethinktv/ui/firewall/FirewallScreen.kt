/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.firewall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.NoEncryption
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.service.PersistentState
import com.ezelab.rethinktv.ui.common.SettingSectionHeader
import com.ezelab.rethinktv.ui.common.SettingToggleRow
import com.ezelab.rethinktv.ui.common.TvScreenScaffold
import com.ezelab.rethinktv.ui.common.rememberAsImmutableState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Firewall destination — universal-firewall toggles.
 *
 * Mirrors the phone's
 * [com.celzero.bravedns.ui.activity.UniversalFirewallSettingsActivity]
 * screen, dropping the toggles that are either unsupported on TV
 * (background-mode requires the accessibility-service shim, which
 * Leanback devices generally don't expose) or have always been
 * commented out upstream (filterIpv4inIpv6).
 *
 * Each row writes directly through [PersistentState] setters — those
 * are the same code paths the phone screen uses, and they each call
 * `setUniversalRulesCount()` internally, which keeps the rule-count
 * badge in the title in sync via [PersistentState.universalRulesCount]
 * LiveData.
 *
 * State reads route through [rememberAsImmutableState] to dodge the
 * mutate-in-place LiveData / Compose identity bug documented for
 * upstream's manager singletons.
 *
 * Per-app firewall rules and custom domain/IP rules live under the
 * Apps destination — adding their entry points to this screen is a
 * Phase E/J task.
 */
@Composable
fun FirewallScreen() {
    val persistentState = koinInject<PersistentState>()
    // background scope — PersistentState setters write to shared prefs,
    // which is cheap but technically off-thread by convention upstream.
    val ioScope = remember { CoroutineScope(Dispatchers.IO) }

    val rulesCount by rememberAsImmutableState(
        persistentState.universalRulesCount,
        initial = 0,
    ) { it ?: 0 }

    TvScreenScaffold(
        title = "Firewall",
        subtitle = if (rulesCount > 0) {
            "Universal rules apply to every app. $rulesCount active."
        } else {
            "Universal rules apply to every app. None active yet."
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SettingSectionHeader("Connection types")
            SettingToggleRow(
                title = "Block UDP",
                description = "Drops all UDP except DNS (port 53). Breaks QUIC, gaming, voice — useful for forcing TCP.",
                leadingIcon = Icons.Filled.Speed,
                checked = remember { persistentState.getUdpBlocked() },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.setUdpBlocked(next) }
                },
            )
            SettingToggleRow(
                title = "Block HTTP",
                description = "Drops plain-text HTTP. Apps fall back to HTTPS or fail.",
                leadingIcon = Icons.Filled.Http,
                checked = remember { persistentState.getBlockHttpConnections() },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.setBlockHttpConnections(next) }
                },
            )
            SettingToggleRow(
                title = "Block metered connections",
                description = "Stops traffic on cellular / hot-spotted networks. On Android TV this also matches Ethernet flagged metered.",
                leadingIcon = Icons.Filled.SignalCellular4Bar,
                checked = remember { persistentState.getBlockMeteredConnections() },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.setBlockMeteredConnections(next) }
                },
            )
            SettingToggleRow(
                title = "Block unknown / unsolicited",
                description = "Drops connections Rethink can't tie to any installed app (rare on TV but possible with sideloaded services).",
                leadingIcon = Icons.AutoMirrored.Filled.HelpOutline,
                checked = remember { persistentState.getBlockUnknownConnections() },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.setBlockUnknownConnections(next) }
                },
            )

            Spacer(Modifier.height(12.dp))
            SettingSectionHeader("DNS protection")
            SettingToggleRow(
                title = "Disallow DNS bypass",
                description = "Forces all DNS through Rethink. Apps using hard-coded resolvers (e.g. 8.8.8.8) will fail.",
                leadingIcon = Icons.Filled.NoEncryption,
                checked = remember { persistentState.getDisallowDnsBypass() },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.setDisallowDnsBypass(next) }
                },
            )

            Spacer(Modifier.height(12.dp))
            SettingSectionHeader("App lifecycle")
            SettingToggleRow(
                title = "Block when screen is off",
                description = "Stops all network traffic while the TV display is off / standby.",
                leadingIcon = Icons.Filled.PhonelinkLock,
                checked = remember { persistentState.getBlockWhenDeviceLocked() },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.setBlockWhenDeviceLocked(next) }
                },
            )
            SettingToggleRow(
                title = "Block newly installed apps",
                description = "Any app sideloaded after this toggle is enabled starts in the firewalled state.",
                leadingIcon = Icons.Filled.NewReleases,
                checked = remember { persistentState.getBlockNewlyInstalledApp() },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.setBlockNewlyInstalledApp(next) }
                },
            )

            Spacer(Modifier.height(12.dp))
            SettingSectionHeader("Lockdown")
            SettingToggleRow(
                title = "Universal lockdown",
                description = "Drops every connection except DNS to Rethink. Use as an emergency kill-switch.",
                leadingIcon = Icons.Filled.Lock,
                checked = remember { persistentState.getUniversalLockdown() },
                onCheckedChange = { next ->
                    ioScope.launch { persistentState.setUniversalLockdown(next) }
                },
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}
