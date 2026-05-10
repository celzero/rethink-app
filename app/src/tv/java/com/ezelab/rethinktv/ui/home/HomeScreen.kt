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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.ezelab.rethinktv.ui.theme.RethinkTvTheme
import org.koin.compose.koinInject

/**
 * Root composable for the TV launcher.
 *
 * Pulls injected singletons via Koin's Compose extensions (instead of
 * `inject<>()` on the Activity), so the entire UI surface can be
 * tested / previewed without a host Activity.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvHomeApp() {
    RethinkTvTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            HomeScreen()
        }
    }
}

/**
 * The single screen shipped in Phase 5 — status + one big action.
 *
 * UX intent:
 *   - Headline: "Rethink TV" so users know which app they're in.
 *   - Status line: "Protection ON / OFF" + the current Brave mode (DNS,
 *     Firewall, or DNS + Firewall) so the user knows what they're
 *     getting before they hit the button.
 *   - Primary action: one large focusable button that toggles the VPN.
 *     D-pad will focus it by default thanks to TV-Material 3's
 *     `Button` having the focus-on-first-render semantics.
 *
 * The button delegates to the same `VpnService.prepare()` →
 * `VpnController.start/stop` flow upstream's phone fragment uses (see
 * `HomeScreenFragment.prepareVpnService` / `startVpnService`), so the
 * engine side is unchanged.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val persistentState: PersistentState = koinInject()

    // Observe the VpnController's connection-status LiveData. We don't
    // care about the actual State enum value here — we use it as a
    // change-notifier and re-read `isOn()` whenever it ticks, because
    // `isOn()` already collapses the (requested, started, bound)
    // tri-state into a single boolean.
    val connectionStatus by VpnController.connectionStatus.observeAsState()
    val isOn = remember(connectionStatus) { VpnController.isOn() }
    val mode = remember(persistentState.braveMode) {
        AppConfig.BraveMode.getMode(persistentState.braveMode)
    }

    // Registers a launcher for the system VPN-consent dialog. We invoke
    // it only when `VpnService.prepare()` returns a non-null Intent
    // (i.e. the user hasn't granted VPN consent yet, or it expired).
    // On RESULT_OK we proceed to start the VPN.
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
            .padding(horizontal = 56.dp, vertical = 48.dp),
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
 * to keep the dependency graph minimal. A ViewModel layer will appear in
 * Phase 6 once we have more than one screen and shared state to manage.
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
