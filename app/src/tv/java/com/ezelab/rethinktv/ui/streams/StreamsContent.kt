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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Observer
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.service.FirewallManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Immutable snapshot of one streamer row's state, suitable for direct
 * consumption by Compose.
 *
 * This indirection exists because of a subtle interaction between
 * upstream `FirewallManager` and Compose's snapshot system:
 *
 *   * `FirewallManager.invalidateFirewallStatus()` mutates the
 *     `AppInfo.firewallStatus` field IN PLACE on the cached objects.
 *   * It then calls `appInfosLiveData.postValue(snapshotAppInfos())`.
 *     The new List contains the SAME AppInfo references, just with
 *     their fields mutated.
 *   * `LiveData.observeAsState` writes to a `MutableState` that uses
 *     `structuralEqualityPolicy()`. Comparing old list vs new list
 *     returns "equal" because the elements are the same references
 *     (and their custom `equals()` resolves to identity for any pair).
 *   * Result: Compose decides "no change" and skips recomposition —
 *     the row never updates until the activity is rebuilt.
 *
 * We work around this by subscribing to the LiveData ourselves and
 * mapping each emission to a fresh list of [StreamerView] data classes
 * with snapshotted primitive fields. When `firewallStatus` flips for
 * one app, the corresponding `excluded: Boolean` flips, the data class
 * is no longer equal, the list is no longer equal, and Compose
 * correctly recomposes the row.
 */
private data class StreamerView(
    val uid: Int,
    val displayName: String,
    val excluded: Boolean,
)

/**
 * Subscribe to the upstream applist LiveData and project each emission
 * into an immutable [StreamerView] list filtered to recognised
 * streaming apps. See [StreamerView] for why the indirection is
 * necessary.
 */
@Composable
private fun rememberStreamerViews(): State<List<StreamerView>> {
    val liveData = remember { FirewallManager.getApplistObserver() }
    return produceState(initialValue = emptyList<StreamerView>(), liveData) {
        val observer = Observer<Collection<AppInfo>> { apps ->
            value = apps.orEmpty()
                .asSequence()
                .filter {
                    it.tombstoneTs == 0L &&
                        KnownStreamers.isStreamer(it.packageName)
                }
                .map { app ->
                    StreamerView(
                        uid = app.uid,
                        displayName = KnownStreamers.friendlyName(
                            app.packageName,
                        ) ?: app.appName,
                        excluded = app.firewallStatus ==
                            FirewallManager.FirewallStatus.EXCLUDE.id,
                    )
                }
                .sortedBy { it.displayName }
                .toList()
        }
        // observeForever is correct here because produceState's lambda
        // is scoped to the composition; awaitDispose tears the
        // observer down deterministically when the composable leaves.
        liveData.observeForever(observer)
        awaitDispose { liveData.removeObserver(observer) }
    }
}

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
    val streamers by rememberStreamerViews()
    val scope = rememberCoroutineScope()

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
            streamers.isEmpty() -> {
                // No emission yet OR genuinely no recognised streamers
                // installed. Phase 6 doesn't distinguish these — both
                // are extremely brief / rare on a real TV device — but
                // we err toward the "looking" copy because a fresh
                // launch on an unprovisioned device hits this state for
                // a few hundred ms while FirewallManager enumerates.
                Text(
                    text = "Looking for installed apps…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items = streamers, key = { it.uid }) { view ->
                        val uid = view.uid
                        val excluded = view.excluded
                        StreamerRow(
                            displayName = view.displayName,
                            excluded = excluded,
                            onToggle = {
                                val newStatus = if (excluded) {
                                    FirewallManager.FirewallStatus.NONE
                                } else {
                                    FirewallManager.FirewallStatus.EXCLUDE
                                }
                                scope.launch(Dispatchers.IO) {
                                    FirewallManager.updateFirewallStatus(
                                        uid,
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
    displayName: String,
    excluded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
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
            onClick = onToggle,
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
