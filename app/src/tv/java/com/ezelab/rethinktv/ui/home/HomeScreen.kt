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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.ezelab.rethinktv.ui.common.TvScreenScaffold
import org.koin.compose.koinInject

/**
 * Home destination — the "dashboard" landing screen.
 *
 * Phase A surfaces the minimum viable subset that lets the user
 * actually use the app: protection status + the ON/OFF toggle +
 * current brave mode. Phase B will expand this with quick counters
 * (PersistentState.numberOfRequests), current DNS resolver pill,
 * and a recent-activity strip from ConnectionTrackerRepository.
 *
 * Wiring: observes [VpnController.connectionStatus] LiveData
 * verbatim — this LiveData carries enum values, not mutable
 * references, so the upstream "mutate-in-place" pitfall documented
 * in `common/LiveDataCompose.kt` doesn't apply. `observeAsState`
 * here is safe.
 *
 * Toggling: mirrors the upstream phone fragment's start/stop flow.
 * Calls [VpnService.prepare] before starting — if the user hasn't
 * granted VPN consent yet, surface the system consent dialog through
 * an activity-result launcher.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val persistentState: PersistentState = koinInject()

    val connectionStatus by VpnController.connectionStatus.observeAsState()
    val isOn = remember(connectionStatus) { VpnController.hasTunnel() }
    val mode = remember(persistentState.braveMode) {
        AppConfig.BraveMode.getMode(persistentState.braveMode)
    }

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            VpnController.start(context, true)
        }
    }

    TvScreenScaffold(
        title = "Rethink TV",
        subtitle = if (isOn) {
            "Protection is on — ${mode.label()} mode."
        } else {
            "Protection is off."
        },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isOn) "● ON" else "○ OFF",
                    color = if (isOn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    style = MaterialTheme.typography.headlineLarge,
                )
                Spacer(Modifier.width(24.dp))
                Text(
                    text = mode.label(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(Modifier.height(16.dp))
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
                                    // Devices without a VPN-consent UI
                                    // (rare; stripped-down OEM builds)
                                    // throw here. Phase A swallows it;
                                    // Phase J's first-run wizard will
                                    // surface a user-facing error.
                                }
                            },
                        )
                    },
                    contentPadding = PaddingValues(
                        horizontal = 32.dp,
                        vertical = 16.dp,
                    ),
                ) {
                    Text(
                        text = if (isOn) "Stop protection" else "Start protection",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
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
 *  * if already on → just stop (with `userInitiated=true` so the
 *    notification is dismissed too);
 *  * else → call [VpnService.prepare]. If it returns an Intent (= the
 *    user hasn't granted VPN consent yet, or it expired), launch it
 *    via the consent launcher. If it returns null, the user already
 *    consented at some point — start immediately.
 *
 * Kept as a top-level function (not a method on a ViewModel) so the
 * Composable above stays trivially previewable. Phase B introduces a
 * `HomeViewModel` once we have cross-tab state worth sharing.
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
        // Same defensive catch upstream uses — some Android builds
        // throw NPE here when the device doesn't actually support
        // system-wide VPN. Treat as "can't start" and bail out.
        return
    }
    if (consentIntent == null) {
        VpnController.start(context, true)
    } else {
        onNeedsConsent(consentIntent)
    }
}
