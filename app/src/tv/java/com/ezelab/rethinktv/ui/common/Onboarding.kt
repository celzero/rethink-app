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

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

private const val PREFS = "rethink_tv_ux"
private const val KEY_ONBOARDING_SEEN = "onboarding_seen"

/**
 * Returns `true` once on the very first launch of the TV UI and
 * `false` on every launch thereafter. Backed by a TV-only
 * [android.content.SharedPreferences] file so it doesn't pollute
 * upstream's [com.celzero.bravedns.service.PersistentState] (which
 * would create a merge conflict on every upstream PersistentState
 * change).
 *
 * The current value is held in Compose state for the duration of
 * the session, so dismissing the welcome banner during this run
 * also hides it for the rest of the session — even though the
 * SharedPreferences write is asynchronous.
 */
@Composable
fun rememberOnboardingState(): OnboardingState {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var seen by remember { mutableStateOf(prefs.getBoolean(KEY_ONBOARDING_SEEN, false)) }
    return remember(seen) {
        OnboardingState(
            shouldShow = !seen,
            markSeen = {
                seen = true
                prefs.edit().putBoolean(KEY_ONBOARDING_SEEN, true).apply()
            },
        )
    }
}

data class OnboardingState(
    val shouldShow: Boolean,
    val markSeen: () -> Unit,
)

/**
 * First-run welcome banner. Renders nothing once dismissed; the
 * dismissal is persisted across launches via [rememberOnboardingState].
 *
 * Designed for the Home dashboard but the API is generic — any
 * screen can drop this in. Two-line copy + a single "Got it" button
 * so a TV remote's centre key dismisses without scrolling.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WelcomeBanner(state: OnboardingState) {
    if (!state.shouldShow) return
    Surface(
        shape = RoundedCornerShape(12.dp),
        colors = SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Welcome to Rethink TV",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Start protection from the toggle below, then explore DNS, " +
                        "Firewall, Apps, and Proxy from the left rail. Logs and Stats " +
                        "populate once traffic flows through the tunnel.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.padding(start = 16.dp))
            Button(onClick = state.markSeen) {
                Text("Got it")
            }
        }
    }
}
