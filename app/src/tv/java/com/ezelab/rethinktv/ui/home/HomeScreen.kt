/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.home

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.ezelab.rethinktv.ui.common.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.ezelab.rethinktv.ui.common.TvScreenScaffold
import com.ezelab.rethinktv.ui.common.WelcomeBanner
import com.ezelab.rethinktv.ui.common.rememberAsImmutableState
import com.ezelab.rethinktv.ui.common.rememberOnboardingState
import org.koin.compose.koinInject

/**
 * Home destination — the dashboard.
 *
 * Surfaces:
 *
 *  * **VPN protection status** — ON/OFF + brave-mode pill, observed
 *    from [VpnController.connectionStatus] and
 *    [PersistentState.vpnEnabledLiveData].
 *  * **Big toggle button** — start / stop protection, with the same
 *    `VpnService.prepare()` consent-launcher flow upstream's phone
 *    fragment uses.
 *  * **Current DNS** — observed from [AppConfig.getConnectedDnsObservable].
 *  * **Counter cards** — DNS query count, network connection count,
 *    recently-blocked-connection count (5-minute window upstream
 *    chose). Read from the same LiveData the phone home fragment
 *    binds to ([AppConfig.dnsLogsCount], [AppConfig.networkLogsCount],
 *    [ConnectionTrackerRepository.getBlockedConnectionsCountLiveData]).
 *
 * Wiring notes:
 *
 *  * `VpnController.connectionStatus` carries an enum, not a mutable
 *    upstream model — `observeAsState` semantics are safe. We use the
 *    project-local [rememberAsImmutableState] for the model-bearing
 *    LiveData (the upstream `MutableLiveData<*>` instances upstream
 *    populates from non-snapshot-aware threads).
 *  * The toggle reuses [VpnController.start] / [VpnController.stop]
 *    verbatim so the engine sees identical lifecycle signals
 *    regardless of whether the phone or TV UI initiated the change.
 *  * `VpnController.hasTunnel()` replaces the deprecated `isOn()`.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val persistentState: PersistentState = koinInject()
    val appConfig: AppConfig = koinInject()
    val connTrackRepo: ConnectionTrackerRepository = koinInject()

    val connectionStatus by rememberAsImmutableState(
        liveData = VpnController.connectionStatus,
        initial = null,
    ) { it }
    val isOn = remember(connectionStatus) { VpnController.hasTunnel() }

    val modeOrdinal by rememberAsImmutableState(
        liveData = appConfig.getBraveModeObservable(),
        initial = persistentState.braveMode,
    ) { it ?: persistentState.braveMode }
    val mode = remember(modeOrdinal) { AppConfig.BraveMode.getMode(modeOrdinal) }

    val connectedDns by rememberAsImmutableState(
        liveData = appConfig.getConnectedDnsObservable(),
        initial = persistentState.connectedDnsName,
    ) { it ?: persistentState.connectedDnsName }

    val dnsCount by rememberAsImmutableState(
        liveData = appConfig.dnsLogsCount,
        initial = 0L,
    ) { it ?: 0L }
    val networkCount by rememberAsImmutableState(
        liveData = appConfig.networkLogsCount,
        initial = 0L,
    ) { it ?: 0L }
    // Blocked-count LiveData is a Room query that captures
    // `System.currentTimeMillis()` at construction time; getting it
    // once per composition means a recomposition after the 5-minute
    // window slides past zero will still read accurate values (Room
    // re-runs the query when the underlying table changes, not when
    // the window pointer moves). Good enough for a dashboard.
    val blockedLiveData = remember { connTrackRepo.getBlockedConnectionsCountLiveData() }
    val blockedCount by rememberAsImmutableState(
        liveData = blockedLiveData,
        initial = 0,
    ) { it ?: 0 }

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            VpnController.start(context, true)
        }
    }

    val onboarding = rememberOnboardingState()
    // Mark the welcome banner seen as soon as the user actually
    // starts protection — they've clearly figured out how the app
    // works. Leaving the banner up after that just clutters Home
    // and pushes the toggle button further off-screen.
    LaunchedEffect(isOn) {
        if (isOn && onboarding.shouldShow) onboarding.markSeen()
    }

    TvScreenScaffold(
        title = "Rethink TV",
        subtitle = if (isOn) {
            "Protection is on — ${mode.label()} mode."
        } else {
            "Protection is off — start it to begin filtering."
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            WelcomeBanner(onboarding)
            StatusRow(
                isOn = isOn,
                mode = mode,
                connectedDns = connectedDns,
            )
            ToggleRow(
                isOn = isOn,
                onClick = {
                    onToggleVpnClicked(
                        isOn = isOn,
                        context = context,
                        onNeedsConsent = { consentIntent ->
                            try {
                                vpnConsentLauncher.launch(consentIntent)
                            } catch (_: ActivityNotFoundException) {
                                // OEM stripped the VPN-consent UI from
                                // their TV image. Tell the user instead
                                // of silently failing.
                                Toast.makeText(
                                    context,
                                    "This device can't show the VPN consent screen. " +
                                        "Sideloading a stock VpnDialogs APK usually fixes it.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                    )
                },
            )
            CountersRow(
                dnsCount = dnsCount,
                networkCount = networkCount,
                blockedCount = blockedCount,
                isOn = isOn,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatusRow(
    isOn: Boolean,
    mode: AppConfig.BraveMode,
    connectedDns: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        StatusBadge(isOn = isOn)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = mode.label(),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
            )
            if (connectedDns.isNotEmpty()) {
                Text(
                    text = "DNS · $connectedDns",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StatusBadge(isOn: Boolean) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        // Use container color rather than the default focusable surface
        // so this purely-decorative badge doesn't compete for D-pad
        // focus with the toggle button beneath it.
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = if (isOn) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Text(
            text = if (isOn) "● ON" else "○ OFF",
            color = if (isOn) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ToggleRow(isOn: Boolean, onClick: () -> Unit) {
    // The big toggle button is the primary action on Home, so park
    // focus on it whenever the screen first composes and whenever
    // the `isOn` state flips (which would otherwise drop focus on
    // some Compose-for-TV versions, leaving the user with no visible
    // focus indicator after they hit Start/Stop once).
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isOn) {
        runCatching { focusRequester.requestFocus() }
    }
    Box {
        Button(
            onClick = onClick,
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
            modifier = Modifier.focusRequester(focusRequester),
        ) {
            Text(
                text = if (isOn) "Stop protection" else "Start protection",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CountersRow(
    dnsCount: Long,
    networkCount: Long,
    blockedCount: Int,
    isOn: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CounterCard(
            label = "DNS queries",
            value = formatCount(dnsCount),
            // When protection is off the counter is still accurate
            // (the engine kept logging past sessions) but the user
            // shouldn't think the numbers are "live" — dim them.
            isLive = isOn,
            modifier = Modifier.weight(1f),
        )
        CounterCard(
            label = "Connections",
            value = formatCount(networkCount),
            isLive = isOn,
            modifier = Modifier.weight(1f),
        )
        CounterCard(
            label = "Blocked (5 min)",
            value = formatCount(blockedCount.toLong()),
            isLive = isOn,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CounterCard(
    label: String,
    value: String,
    isLive: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                alpha = if (isLive) 1.0f else 0.6f,
            ),
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                color = if (isLive) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                },
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

/**
 * Pretty-print a non-negative count for the dashboard tiles. Keeps
 * the rendered string short enough to fit at ten-foot reading
 * distance ('1.2M' rather than '1,234,567').
 */
private fun formatCount(n: Long): String = when {
    n < 0L -> "—"
    n < 1_000L -> n.toString()
    n < 1_000_000L -> String.format("%.1fK", n / 1_000.0)
    n < 1_000_000_000L -> String.format("%.1fM", n / 1_000_000.0)
    else -> String.format("%.1fB", n / 1_000_000_000.0)
}

private fun AppConfig.BraveMode.label(): String = when (this) {
    AppConfig.BraveMode.DNS -> "DNS"
    AppConfig.BraveMode.FIREWALL -> "Firewall"
    AppConfig.BraveMode.DNS_FIREWALL -> "DNS + Firewall"
}

/**
 * Mirrors the upstream phone fragment's start/stop flow:
 *
 *  * if already on → just stop (with `userInitiated=true` so the
 *    notification is dismissed too);
 *  * else → call [VpnService.prepare]. If it returns an Intent (= the
 *    user hasn't granted VPN consent yet, or it expired), launch it
 *    via the consent launcher. If it returns null, the user already
 *    consented at some point — start immediately.
 *
 * Kept as a top-level function (not a method on a ViewModel) so the
 * Composable above stays trivially previewable. Phase J introduces a
 * `HomeViewModel` if cross-tab state turns out to need sharing.
 */
private fun onToggleVpnClicked(
    isOn: Boolean,
    context: Context,
    onNeedsConsent: (Intent) -> Unit,
) {
    if (isOn) {
        VpnController.stop(reason = "tv-home", context = context, userInitiated = true)
        return
    }
    // Defensive try/catch: some Android builds throw NPE here when the
    // device doesn't actually support system-wide VPN. Upstream catches
    // this too — see HomeScreenFragment.prepareVpnService.
    val consentIntent: Intent? = try {
        VpnService.prepare(context)
    } catch (_: NullPointerException) {
        return
    }
    if (consentIntent == null) {
        VpnController.start(context, true)
    } else {
        onNeedsConsent(consentIntent)
    }
}

