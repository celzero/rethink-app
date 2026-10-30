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
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.MutableLiveData
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.tv.ui.common.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles
import com.celzero.bravedns.service.PauseTimer
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.common.rememberAsImmutableState
import java.util.concurrent.TimeUnit

/**
 * Pause-VPN sub-screen — TV port of `PauseActivity`.
 *
 * Pause is a "snooze" rather than a full disable: the tunnel keeps
 * its state but stops applying any DNS or firewall rules until the
 * countdown finishes or the user resumes manually.
 *
 * UX on TV:
 *
 *  * If the tunnel is currently *not* paused, show the
 *    [PauseTimer.DEFAULT_PAUSE_TIME_MS] (15 min) start button plus
 *    quick presets (5 / 15 / 30 / 60 min). The user picks one and
 *    we start the countdown via [VpnController.pauseApp] and
 *    [PauseTimer.start].
 *  * If the tunnel *is* paused, show the live countdown observed
 *    from [VpnController.getPauseCountDownObserver] plus +1m / −1m
 *    buttons and a Resume button.
 *
 * The Resume button calls [VpnController.resumeApp] and stops the
 * timer; UI flips back to the picker on the next observer tick.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PauseVpnScreen() {
    val pauseObserver = remember { VpnController.getPauseCountDownObserver() }
    val remaining by rememberAsImmutableState(
        liveData = pauseObserver ?: MutableLiveData(0L),
        initial = 0L,
    ) { it ?: 0L }
    val paused = VpnController.isAppPaused() && remaining > 0L

    TvScreenScaffold(
        title = "Pause protection",
        subtitle = "Temporarily stop applying DNS and firewall rules — the tunnel itself stays up.",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (paused) {
                PausedCountdown(remainingMs = remaining)
            } else {
                PausePicker()
            }

            Spacer(Modifier.height(TvSpacing.RowGap))
            Text(
                text = "While paused, traffic flows directly without any blocking. " +
                    "Use this for short-term debugging of an app that needs an unfiltered network.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PausePicker() {
    val presets = remember {
        listOf(5, 15, 30, 60).map {
            PausePreset(label = "$it min", durationMs = TimeUnit.MINUTES.toMillis(it.toLong()))
        }
    }
    Text(
        text = "How long?",
        style = MaterialTheme.typography.titleMedium,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap)) {
        presets.forEach { p ->
            Surface(
                onClick = {
                    VpnController.pauseApp()
                    PauseTimer.start(p.durationMs)
                },
                shape = TvSurfaceStyles.interactiveShape(),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.primary,
                    focusedContentColor = MaterialTheme.colorScheme.onPrimary,
                    pressedContainerColor = MaterialTheme.colorScheme.primary,
                    pressedContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Box(modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp)) {
                    Text(
                        text = p.label,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PausedCountdown(remainingMs: Long) {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs) % 60

    Surface(
        shape = TvShapes.Medium,
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Protection paused",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(TvSpacing.RowGap))
            Text(
                text = String.format("%02d:%02d", minutes, seconds),
                style = MaterialTheme.typography.displayLarge,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AdjustButton(label = "−1 min") {
                    PauseTimer.subtractDuration(PauseTimer.PAUSE_VPN_EXTRA_MILLIS)
                }
                AdjustButton(label = "+1 min") {
                    PauseTimer.addDuration(PauseTimer.PAUSE_VPN_EXTRA_MILLIS)
                }
                Spacer(Modifier.width(TvSpacing.RowGap))
                Surface(
                    onClick = {
                        PauseTimer.stop()
                        VpnController.resumeApp()
                    },
                    shape = TvSurfaceStyles.interactiveShape(),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                        focusedContainerColor = MaterialTheme.colorScheme.error,
                        focusedContentColor = MaterialTheme.colorScheme.onError,
                        pressedContainerColor = MaterialTheme.colorScheme.error,
                        pressedContentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Box(modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)) {
                        Text(
                            text = "Resume now",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AdjustButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Small),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
            pressedContainerColor = MaterialTheme.colorScheme.primary,
            pressedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

private data class PausePreset(val label: String, val durationMs: Long)
