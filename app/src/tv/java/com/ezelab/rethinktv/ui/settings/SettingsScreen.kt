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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.service.PersistentState
import com.ezelab.rethinktv.ui.common.TvScreenScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Settings destination — Phase I will expand this placeholder with
 * the full upstream-parity surface (tunnel / network / misc /
 * advanced / anti-censorship / app lock / backup-restore / console
 * log).
 *
 * To keep Phase A's TV build functionally complete (so users can do
 * something via Settings before Phase I lands), this stub already
 * surfaces the brave-mode picker — the single most-important setting
 * because it gates whether DNS-only, firewall-only, or both engines
 * run. Read/write go through [AppConfig.changeBraveMode] so the
 * tunnel-mode observers upstream registers still fire.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val persistentState: PersistentState = koinInject()
    val appConfig: AppConfig = koinInject()
    val scope = rememberCoroutineScope()

    val currentMode = remember(persistentState.braveMode) {
        AppConfig.BraveMode.getMode(persistentState.braveMode)
    }

    TvScreenScaffold(
        title = "Settings",
        subtitle = "More settings (tunnel, network, app lock, backup) are coming in a later phase.",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Protection mode",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "DNS blocks ad/tracker hostnames. Firewall blocks " +
                    "per-app network access. DNS + Firewall does both.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AppConfig.BraveMode.entries.forEach { mode ->
                    val label = when (mode) {
                        AppConfig.BraveMode.DNS -> "DNS"
                        AppConfig.BraveMode.FIREWALL -> "Firewall"
                        AppConfig.BraveMode.DNS_FIREWALL -> "DNS + Firewall"
                    }
                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                appConfig.changeBraveMode(mode.mode)
                            }
                        },
                        contentPadding = PaddingValues(
                            horizontal = 24.dp,
                            vertical = 12.dp,
                        ),
                    ) {
                        Text(
                            text = if (mode == currentMode) "✓  $label" else label,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}
