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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.ezelab.rethinktv.ui.common.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.service.PersistentState
import com.celzero.firestack.settings.Settings
import com.ezelab.rethinktv.ui.common.SettingSectionHeader
import com.ezelab.rethinktv.ui.common.TvScreenScaffold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * Anti-censorship sub-screen — TV port of the phone's
 * `AntiCensorshipActivity`.
 *
 * Two independent pickers backed by [PersistentState]:
 *
 *  * **Dial strategy** — how the engine establishes outbound
 *    connections. Defaults to `SPLIT_AUTO` (the upstream default).
 *    `DESYNC` requires firestack ≥ 4.12 but is exposed unconditionally
 *    here — modern Android TVs ship recent firestack and the upstream
 *    Activity's OS-version guard is over-conservative for TV.
 *  * **Retry strategy** — how the engine reacts when a dial fails.
 *    Defaults to `RETRY_AFTER_SPLIT`.
 *
 * Writes hop to [Dispatchers.IO] because the `intPref` delegate
 * commits to SharedPreferences synchronously.
 *
 * `TCP_PROXY` (upstream alias of SPLIT_AUTO + auto-proxy enabled) is
 * deliberately omitted — the TCP proxy toggle is a separate setting
 * that belongs alongside the SOCKS5 / HTTP proxy editor, not the
 * dial-strategy picker.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AntiCensorshipScreen() {
    val persistentState = koinInject<PersistentState>()
    val ioScope = remember { CoroutineScope(Dispatchers.IO) }

    var dialStrategy by remember {
        mutableIntStateOf(persistentState.dialStrategy)
    }
    var retryStrategy by remember {
        mutableIntStateOf(persistentState.retryStrategy)
    }

    TvScreenScaffold(
        title = "Anti-censorship",
        subtitle = "Choose how Rethink dials connections and what it does when a dial fails.",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingSectionHeader("Dial strategy")
            Text(
                text = "How outbound TCP/TLS streams are split when first opening a connection. " +
                    "Split-auto picks the most permissive option that works on the current network.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
            )
            DialStrategyOptions.forEach { opt ->
                StrategyRow(
                    title = opt.label,
                    description = opt.description,
                    selected = dialStrategy == opt.mode,
                    onClick = {
                        dialStrategy = opt.mode
                        ioScope.launch { persistentState.dialStrategy = opt.mode }
                    },
                )
            }

            Spacer(Modifier.height(20.dp))
            SettingSectionHeader("Retry strategy")
            Text(
                text = "What to do if the initial dial fails — useful on networks where DPI " +
                    "selectively drops the first packets of a stream.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
            )
            RetryStrategyOptions.forEach { opt ->
                StrategyRow(
                    title = opt.label,
                    description = opt.description,
                    selected = retryStrategy == opt.mode,
                    onClick = {
                        retryStrategy = opt.mode
                        ioScope.launch { persistentState.retryStrategy = opt.mode }
                    },
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

private data class StrategyOption(
    val mode: Int,
    val label: String,
    val description: String,
)

/**
 * Mirrors `AntiCensorshipActivity.DialStrategies` for the four modes
 * we surface on TV. Kept as raw `Settings.Split*` ints so that an
 * upstream rename of the enum doesn't break this screen — the int
 * contract is what the engine consumes.
 */
private val DialStrategyOptions = listOf(
    StrategyOption(
        mode = Settings.SplitAuto,
        label = "Split auto (recommended)",
        description = "Splits TCP / TLS based on what the current network allows.",
    ),
    StrategyOption(
        mode = Settings.SplitTCP,
        label = "Split TCP",
        description = "Splits every TCP stream; bypasses simple DPI rules.",
    ),
    StrategyOption(
        mode = Settings.SplitTCPOrTLS,
        label = "Split TCP or TLS",
        description = "Splits at the TLS boundary when TLS is in use, else TCP.",
    ),
    StrategyOption(
        mode = Settings.SplitDesync,
        label = "Desync",
        description = "Aggressively desynchronises the handshake. Hardest to fingerprint, " +
            "but slower and may break some networks.",
    ),
    StrategyOption(
        mode = Settings.SplitNever,
        label = "Never split",
        description = "Connect normally without any splitting. Fastest, easiest to fingerprint.",
    ),
)

private val RetryStrategyOptions = listOf(
    StrategyOption(
        mode = Settings.RetryAfterSplit,
        label = "Retry after split (recommended)",
        description = "If the first dial fails, retry with split-TCP enabled.",
    ),
    StrategyOption(
        mode = Settings.RetryWithSplit,
        label = "Retry with split",
        description = "Always retry failed dials with split-TCP, even on networks where it isn't needed.",
    ),
    StrategyOption(
        mode = Settings.RetryNever,
        label = "Never retry",
        description = "Fail fast — surface dial errors immediately to the app.",
    ),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StrategyRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
            pressedContainerColor = MaterialTheme.colorScheme.primary,
            pressedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Box(modifier = Modifier.padding(end = 16.dp, top = 2.dp)) {
                Text(
                    text = if (selected) "●" else "○",
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
