/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.apps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.common.TvSelectableChip
import com.celzero.bravedns.tv.ui.common.rememberAsImmutableState
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Detail screen for a single app — the equivalent of upstream's
 * `AppInfoActivity`, trimmed to the controls that matter on day-1 of
 * the TV port:
 *
 *  * **Firewall status** — Allow / Block / Bypass universal / Isolate /
 *    Bypass DNS+Firewall. Five-button segmented selector; pressing
 *    one fires `FirewallManager.updateFirewallStatus(...)` on IO.
 *  * **Connection class** — Allow / Block both / Block metered /
 *    Block Wi-Fi. Disabled when Firewall status is anything other
 *    than NONE (the connection-class concept only applies to the
 *    NONE / NONE-with-class baseline upstream uses).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppDetailScreen(uid: Int, navController: NavController? = null) {
    val scope = rememberCoroutineScope()

    // Backed by the app-list LiveData so rule writes (which post a fresh
    // snapshot via informObservers) re-project this row and refresh the UI.
    val rowState = rememberAsImmutableState(
        liveData = FirewallManager.getApplistObserver(),
        initial = null as AppRow?,
    ) { apps -> apps.orEmpty().firstOrNull { it.uid == uid }?.toRow() }
    val row = rowState.value

    if (row == null) {
        TvScreenScaffold(title = "App", subtitle = "Loading…") {
            Text(
                "Couldn't find app with uid $uid. It may have been uninstalled while you were navigating.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    TvScreenScaffold(
        title = row.appName.ifBlank { row.packageName },
        subtitle = buildString {
            append(row.packageName)
            if (row.isSystemApp) append(" · system")
            if (row.appCategory.isNotBlank()) append(" · ").append(row.appCategory)
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
        ) {
            // Identity header: large icon + current status pill.
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(packageName = row.packageName, size = 96)
                Spacer(Modifier.width(TvSpacing.ContentHorizontal))
                Column {
                    StatusPill(row.firewallStatus, row.connectionStatus)
                    Spacer(Modifier.height(TvSpacing.RowGap))
                    Text(
                        text = "uid · ${row.uid}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            SectionLabel("Firewall rule")
            FirewallStatusRow(
                current = row.firewallStatus,
                excludeEnabled = !row.isUnknownPackage,
                onSelect = { selected ->
                    scope.launch(Dispatchers.IO) {
                        FirewallManager.updateFirewallStatus(
                            uid = uid,
                            firewallStatus = selected,
                            connectionStatus = row.connectionStatus,
                        )
                    }
                },
            )

            Spacer(Modifier.height(16.dp))
            SectionLabel("Connection class")
            val classDisabled = row.firewallStatus != FirewallManager.FirewallStatus.NONE
            ConnectionStatusRow(
                current = row.connectionStatus,
                enabled = !classDisabled,
                onSelect = { selected ->
                    scope.launch(Dispatchers.IO) {
                        FirewallManager.updateFirewallStatus(
                            uid = uid,
                            firewallStatus = FirewallManager.FirewallStatus.NONE,
                            connectionStatus = selected,
                        )
                    }
                },
            )
            if (classDisabled) {
                Spacer(Modifier.height(TvSpacing.RowGap))
                Text(
                    "Connection class is only meaningful when the firewall rule is the default (\"Allow / Block\").",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp, start = 2.dp),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FirewallStatusRow(
    current: FirewallManager.FirewallStatus,
    excludeEnabled: Boolean,
    onSelect: (FirewallManager.FirewallStatus) -> Unit,
) {
    val options = remember {
        listOf(
            FirewallManager.FirewallStatus.NONE to "Allow / Block",
            FirewallManager.FirewallStatus.BYPASS_UNIVERSAL to "Bypass universal",
            FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL to "Bypass DNS+FW",
            FirewallManager.FirewallStatus.ISOLATE to "Isolate",
            FirewallManager.FirewallStatus.EXCLUDE to "Exclude from VPN",
        )
    }
    SegmentedButtons(
        options = options,
        current = current,
        enabled = true,
        // updateFirewallStatus silently ignores EXCLUDE for unknown/synthetic
        // packages, so grey the option out instead of showing a dead button.
        enabledFor = { it != FirewallManager.FirewallStatus.EXCLUDE || excludeEnabled },
        onSelect = onSelect,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConnectionStatusRow(
    current: FirewallManager.ConnectionStatus,
    enabled: Boolean,
    onSelect: (FirewallManager.ConnectionStatus) -> Unit,
) {
    val options = remember {
        listOf(
            FirewallManager.ConnectionStatus.ALLOW to "Allow",
            FirewallManager.ConnectionStatus.BOTH to "Block all",
            FirewallManager.ConnectionStatus.METERED to "Block metered",
            FirewallManager.ConnectionStatus.UNMETERED to "Block Wi-Fi",
        )
    }
    SegmentedButtons(
        options = options,
        current = current,
        enabled = enabled,
        onSelect = onSelect,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun <T> SegmentedButtons(
    options: List<Pair<T, String>>,
    current: T,
    enabled: Boolean,
    enabledFor: (T) -> Boolean = { true },
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
    ) {
        options.forEach { (value, label) ->
            TvSelectableChip(
                label = label,
                selected = value == current,
                onClick = { onSelect(value) },
                enabled = enabled && enabledFor(value),
            )
        }
    }
}
