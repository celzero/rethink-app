/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.tv.ui.common.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.ui.bottomsheet.BlockFreeDnsModeBottomSheet.BlockFreeDnsMode
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles
import com.celzero.bravedns.util.Utilities.isAtleastR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * DNS-for-bypass (block-free DNS) mode picker — TV port of the phone's
 * `BlockFreeDnsModeBottomSheet`. Selects which DNS serves trusted and
 * bypassed ips/domains.
 *
 * Options are only meaningful when split DNS is enabled; without it the
 * engine picks the default automatically (AUTO on Android R+, else
 * Bootstrap), so the picker is dimmed and a note mirrors the sheet.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DnsBypassScreen() {
    val persistentState = koinInject<PersistentState>()
    val eventLogger = koinInject<EventLogger>()
    val ioScope = remember { CoroutineScope(Dispatchers.IO) }

    var currentMode by remember {
        mutableStateOf(BlockFreeDnsMode.fromMode(persistentState.blockFreeDnsMode))
    }
    val splitDnsEnabled = persistentState.splitDns

    TvScreenScaffold(
        title = "DNS for bypass",
        subtitle = "Select the DNS to use for trusted and bypassed ips and domains",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
        ) {
            if (!splitDnsEnabled) {
                val defaultMode = if (isAtleastR()) BlockFreeDnsMode.AUTO else BlockFreeDnsMode.FALLBACK
                Text(
                    text = "Split DNS is disabled. ${modeLabel(defaultMode)}.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
                )
            }
            BlockFreeDnsMode.entries.forEach { mode ->
                ModeRow(
                    title = modeLabel(mode),
                    description = modeDescription(mode),
                    selected = mode == currentMode,
                    enabled = splitDnsEnabled,
                    onClick = {
                        currentMode = mode
                        ioScope.launch {
                            persistentState.blockFreeDnsMode = mode.mode
                            eventLogger.log(
                                EventType.UI_SETTING_CHANGED,
                                Severity.LOW,
                                "block free dns mode? $mode",
                                EventSource.UI,
                                false,
                                "User changed block free dns mode to $mode"
                            )
                        }
                    },
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun modeLabel(mode: BlockFreeDnsMode): String = when (mode) {
    BlockFreeDnsMode.FALLBACK -> "Use Bootstrap DNS"
    BlockFreeDnsMode.GLOBAL -> "Use Global DNS"
    BlockFreeDnsMode.AUTO -> "Automatic"
}

private fun modeDescription(mode: BlockFreeDnsMode): String = when (mode) {
    BlockFreeDnsMode.FALLBACK -> "Use the bootstrap DNS for trusted and bypassed connections"
    BlockFreeDnsMode.GLOBAL -> "Use the globally configured DNS for trusted and bypassed connections"
    BlockFreeDnsMode.AUTO -> "Automatically select DNS based on other settings"
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ModeRow(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = TvSurfaceStyles.interactiveShape(),
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
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Box(modifier = Modifier.padding(end = TvSpacing.IconTextGap, top = 2.dp)) {
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
