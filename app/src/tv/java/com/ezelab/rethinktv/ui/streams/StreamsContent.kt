/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.streams

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.service.FirewallManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The "Streams" tab. Surfaces the subset of installed apps that
 * [KnownStreamers] recognises as a streaming service, and lets the user
 * toggle each one between two states:
 *
 *   * **Through Rethink** ([FirewallManager.FirewallStatus.NONE]) —
 *     the default. Traffic goes through Rethink's tunnel and benefits
 *     from DNS-level ad-blocking. Works for most apps but can break
 *     DRM-protected playback on some streamers (Netflix in particular
 *     is sensitive to the VPN flag, regardless of whether the VPN
 *     actually changes IPs).
 *
 *   * **Bypass Rethink** ([FirewallManager.FirewallStatus.EXCLUDE]) —
 *     the per-app escape hatch. Upstream's TUN driver skips this app's
 *     UID entirely; its packets go out the normal interface and never
 *     see Rethink's DNS. Use this for streamers that misbehave under
 *     VPN.
 *
 * This is the primary TV-specific user affordance: the whole reason
 * Rethink-on-TV exists is that DNS ad-blocking is great until your
 * streaming app refuses to play, and then you need a one-tap way to
 * unstick it.
 *
 * Wiring: reads from [FirewallManager.getApplistObserver] (the same
 * LiveData the phone firewall screen reads) and writes via
 * [FirewallManager.updateFirewallStatus], so the engine view of
 * per-app state stays consistent across the phone and TV UIs.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StreamsContent() {
    val allApps by FirewallManager.getApplistObserver()
        .observeAsState(emptyList())
    val scope = rememberCoroutineScope()

    val streamers = remember(allApps) {
        allApps
            .filter { it.tombstoneTs == 0L && KnownStreamers.isStreamer(it.packageName) }
            .sortedBy { KnownStreamers.friendlyName(it.packageName) ?: it.appName }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 56.dp, vertical = 24.dp),
    ) {
        Text(
            text = "Streaming apps",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Bypass Rethink for any app where playback breaks " +
                "under the VPN (Netflix and Prime are common culprits). " +
                "Apps that aren't bypassed still get DNS-level " +
                "ad-blocking on the rest of their traffic.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))

        when {
            allApps.isEmpty() -> {
                Text(
                    text = "Looking for installed apps…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            streamers.isEmpty() -> {
                Text(
                    text = "No recognised streaming apps installed.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items = streamers, key = { it.uid }) { app ->
                        StreamerRow(
                            app = app,
                            onToggle = { newStatus ->
                                scope.launch(Dispatchers.IO) {
                                    FirewallManager.updateFirewallStatus(
                                        app.uid,
                                        newStatus,
                                        FirewallManager.ConnectionStatus.ALLOW,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StreamerRow(
    app: AppInfo,
    onToggle: (FirewallManager.FirewallStatus) -> Unit,
) {
    val excluded = app.firewallStatus == FirewallManager.FirewallStatus.EXCLUDE.id
    val display = KnownStreamers.friendlyName(app.packageName) ?: app.appName

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = display,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = if (excluded) "Bypassing Rethink (DRM-safe)"
                else "Through Rethink (with ad-blocking)",
                color = if (excluded) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(
            onClick = {
                onToggle(
                    if (excluded) FirewallManager.FirewallStatus.NONE
                    else FirewallManager.FirewallStatus.EXCLUDE,
                )
            },
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text(
                text = if (excluded) "Send through Rethink"
                else "Bypass Rethink",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
