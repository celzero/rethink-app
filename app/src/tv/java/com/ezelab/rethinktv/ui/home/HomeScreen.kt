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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Tab
import androidx.tv.material3.TabRow
import androidx.tv.material3.Text
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.ezelab.rethinktv.ui.theme.RethinkTvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Root composable for the TV launcher.
 *
 * Hosts a three-tab top-bar navigation (Home / Settings / About).
 * Tab switching is plain state-driven `when`-routing rather than the
 * androidx.navigation library — Phase 5 has too few destinations to
 * justify the extra dependency and the boilerplate around
 * `NavController`. We'll graduate to androidx.navigation when there
 * are nested destinations or back-stack semantics worth modelling.
 *
 * Pulls injected singletons via Koin's Compose extensions
 * (`koinInject`), so the entire UI surface can be tested / previewed
 * without a host Activity.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvHomeApp() {
    RethinkTvTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            NavScaffold()
        }
    }
}

private enum class TvDestination(val label: String) {
    Home("Home"),
    Settings("Settings"),
    About("About"),
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavScaffold() {
    var selectedIndex by remember { mutableIntStateOf(TvDestination.Home.ordinal) }
    val destinations = remember { TvDestination.entries.toList() }

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedIndex,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 56.dp, vertical = 24.dp),
        ) {
            destinations.forEachIndexed { index, destination ->
                Tab(
                    selected = index == selectedIndex,
                    onFocus = { selectedIndex = index },
                ) {
                    Text(
                        text = destination.label,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            when (destinations[selectedIndex]) {
                TvDestination.Home -> HomeContent()
                TvDestination.Settings -> SettingsContent()
                TvDestination.About -> AboutContent()
            }
        }
    }
}

/**
 * The Phase 5 dashboard — protection status + one big toggle.
 *
 * Observes [VpnController.connectionStatus] LiveData and re-reads
 * `isOn()` whenever it ticks (the LiveData carries the granular State
 * enum but `isOn()` already collapses it into the boolean we want).
 *
 * Delegates start/stop to the same `VpnService.prepare()` →
 * `VpnController.start/stop` flow upstream's phone fragment uses
 * (see `HomeScreenFragment.prepareVpnService` / `startVpnService`).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeContent() {
    val context = LocalContext.current
    val persistentState: PersistentState = koinInject()

    val connectionStatus by VpnController.connectionStatus.observeAsState()
    val isOn = remember(connectionStatus) { VpnController.isOn() }
    val mode = remember(persistentState.braveMode) {
        AppConfig.BraveMode.getMode(persistentState.braveMode)
    }

    // Registers a launcher for the system VPN-consent dialog. Used only
    // when `VpnService.prepare()` returns a non-null Intent (= the user
    // hasn't granted VPN consent yet, or it expired).
    val vpnConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            VpnController.start(context, true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 56.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "Rethink TV",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(24.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isOn) "Protection: ON" else "Protection: OFF",
                color = if (isOn) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.width(24.dp))
            Text(
                text = "Mode: ${mode.label()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Spacer(Modifier.height(48.dp))

        Box {
            Button(
                onClick = {
                    onToggleVpnClicked(
                        isOn = isOn,
                        context = context,
                        onNeedsConsent = { consentIntent ->
                            try {
                                vpnConsentLauncher.launch(consentIntent)
                            } catch (_: ActivityNotFoundException) {
                                // Devices that don't ship a VPN-consent
                                // UI (rare; mostly stripped-down OEM
                                // builds) will throw here. Phase 5
                                // intentionally swallows this — Phase 7
                                // (first-run wizard) will surface a
                                // user-facing error.
                            }
                        },
                    )
                },
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp),
            ) {
                Text(
                    text = if (isOn) "Stop protection" else "Start protection",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/**
 * Settings tab — Phase 5 ships just the Brave-mode selector
 * (DNS / Firewall / DNS + Firewall). Writes via `AppConfig.changeBraveMode`
 * (rather than poking `persistentState.braveMode` directly) so the
 * tunnel-mode observers upstream wires up still fire.
 *
 * Phase 6 will expand this tab with DNS upstream picker, app exclusions,
 * etc. — for now it's a single three-way selector to validate the
 * write path through the inherited engine.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsContent() {
    val persistentState: PersistentState = koinInject()
    val appConfig: AppConfig = koinInject()
    val scope = rememberCoroutineScope()

    val currentMode = remember(persistentState.braveMode) {
        AppConfig.BraveMode.getMode(persistentState.braveMode)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 56.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "Protection mode",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "DNS blocks ad/tracker hostnames. Firewall blocks " +
                "per-app network access. DNS + Firewall does both.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(32.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AppConfig.BraveMode.entries.forEach { mode ->
                ModeButton(
                    label = mode.label(),
                    selected = mode == currentMode,
                    onClick = {
                        // `changeBraveMode` notifies the AppConfig
                        // observers in addition to writing the pref.
                        // Run off the main thread because upstream's
                        // observer callbacks may touch the database.
                        scope.launch(Dispatchers.IO) {
                            appConfig.changeBraveMode(mode.mode)
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(
            text = if (selected) "✓  $label" else label,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AboutContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 56.dp, vertical = 24.dp),
    ) {
        Text(
            text = "Rethink TV",
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Android TV launcher for Rethink DNS + Firewall.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Built on celzero/rethink-app. " +
                "github.com/ezelab/rethink-tv",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private fun AppConfig.BraveMode.label(): String = when (this) {
    AppConfig.BraveMode.DNS -> "DNS"
    AppConfig.BraveMode.FIREWALL -> "Firewall"
    AppConfig.BraveMode.DNS_FIREWALL -> "DNS + Firewall"
}

/**
 * Mirrors the upstream phone fragment's start/stop flow:
 *
 *   - if already on → just stop;
 *   - else → call [VpnService.prepare]; if it returns an Intent (= user
 *     has not granted VPN consent yet), launch it via the consent
 *     launcher; if it returns null, the user already consented at some
 *     point and we can start immediately.
 *
 * Kept as a top-level function (not a method on a ViewModel) for Phase 5
 * to keep the dependency graph minimal. A ViewModel layer will appear
 * later once we have more than one screen with shared cross-tab state.
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
    val consentIntent: Intent? = try {
        VpnService.prepare(context)
    } catch (_: NullPointerException) {
        // Same defensive catch upstream uses — some Android builds throw
        // NPE here when the device doesn't actually support system-wide
        // VPN. Treat as "can't start" and bail out.
        return
    }
    if (consentIntent == null) {
        VpnController.start(context, true)
    } else {
        onNeedsConsent(consentIntent)
    }
}
