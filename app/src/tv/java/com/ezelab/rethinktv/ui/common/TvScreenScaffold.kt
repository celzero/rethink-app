/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Standard chrome for a top-level TV destination.
 *
 * Provides the ten-foot heading + supporting copy block above the
 * destination's content. Every primary TV destination (Home / DNS /
 * Firewall / Apps / Proxy / Logs / Stats / Settings) uses this so the
 * vertical rhythm and focus-entry point are consistent.
 *
 * @param title the destination's name as the user sees it (defaults
 *   to a "{Destination} settings" pattern; callers usually override).
 * @param subtitle optional one-line description shown beneath the
 *   title. Keep ≤ 100 chars — at 10 ft anything longer is unread.
 * @param content the destination body. Padded with the standard TV
 *   safe-area; content should still apply its own padding for any
 *   focusable widgets.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvScreenScaffold(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.Bold,
            ),
        )
        if (subtitle != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(24.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

/**
 * Phase A placeholder shown by destinations whose feature-parity
 * Composables haven't been implemented yet. Surfaces a clear
 * "coming soon" rather than an empty pane so it's obvious from CI
 * screenshots which destinations are still stubs.
 *
 * Removed once all destinations have a real screen (Phase J).
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PlaceholderScreen(destinationName: String) {
    TvScreenScaffold(
        title = destinationName,
        subtitle = "Coming soon — this destination is under construction.",
    ) {
        Text(
            text = "The $destinationName surface is being built. " +
                "Other tabs are usable; use the left rail to navigate.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
