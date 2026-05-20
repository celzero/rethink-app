/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.apps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.ezelab.rethinktv.ui.common.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.service.FirewallManager
import com.ezelab.rethinktv.ui.common.TvScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 *
 * Per-app domain & IP rules and per-app proxy mapping will be added
 * as sub-destinations during the polish phase. The decision to ship
 * the segmented status selectors first is deliberate: the headline
 * "block this app from the internet" use-case is the marquee feature
 * for a TV firewall, and it works without the rest.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AppDetailScreen(uid: Int, navController: NavController? = null) {
    val scope = rememberCoroutineScope()

    // Re-fetch on `uid` so navigating to a sibling redraws the screen.
    val initialRow by produceState<AppRow?>(initialValue = null, uid) {
        value = withContext(Dispatchers.IO) {
            FirewallManager.getAppInfoByUid(uid)?.toRow()
        }
    }
    val row = initialRow

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Identity header: large icon + current status pill.
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(packageName = row.packageName, size = 96)
                Spacer(Modifier.width(20.dp))
                Column {
                    StatusPill(row.firewallStatus, row.connectionStatus)
                    Spacer(Modifier.height(8.dp))
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
                Spacer(Modifier.height(8.dp))
                Text(
                    "Connection class is only meaningful when the firewall rule is the default (\"Allow / Block\").",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Per-app domain and IP rules, plus per-app proxy mapping, will live here in a follow-up.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp, start = 2.dp),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FirewallStatusRow(
    current: FirewallManager.FirewallStatus,
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
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            val selected = value == current
            Surface(
                onClick = { onSelect(value) },
                enabled = enabled,
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
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
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                ),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (selected) "✓  $label" else label,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
    }
}
