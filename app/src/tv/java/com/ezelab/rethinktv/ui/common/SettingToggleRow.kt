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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text

/**
 * A focusable, click-to-toggle setting row used by Firewall and
 * Settings screens.
 *
 * Designed for ten-foot UX:
 *
 *  * Whole row is a single D-pad-focusable Surface — no separate
 *    Switch widget to land on. Pressing Enter / D-pad-center toggles
 *    the value.
 *  * Currently-on state is rendered as a filled check tile on the
 *    trailing side; off state is an empty outlined tile. Easier to
 *    parse at distance than a small material Switch.
 *  * Focused state lifts the row (Surface elevation) and shifts to
 *    the primary container colour so a glanceable selection ring is
 *    visible from a sofa.
 *
 * The composable keeps a local mirror of [checked] so the toggle
 * feels instant even when the backing store ([onCheckedChange])
 * commits asynchronously (e.g. `PersistentState` write that touches
 * SharedPreferences off-thread).
 *
 * @param title short label, e.g. "Block UDP". ≤ ~40 chars.
 * @param description optional sub-line explaining the effect at 10 ft.
 * @param checked current persisted value.
 * @param onCheckedChange invoked with the new value when the user
 *   activates the row. Callee is responsible for persistence and any
 *   side-effects (recount, restart tunnel, …).
 * @param enabled when false the row is dimmed and non-interactive.
 * @param leadingIcon optional icon shown on the start side; helps users
 *   pattern-match the row from across the room.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingToggleRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    // Mirror prop into local state so taps feel instant; resync if
    // [checked] changes from above (e.g. a refresh).
    var localChecked by remember(checked) { mutableStateOf(checked) }

    Surface(
        onClick = {
            val next = !localChecked
            localChecked = next
            onCheckedChange(next)
        },
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                if (!description.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.width(20.dp))
            ToggleStateTile(checked = localChecked)
        }
    }
}

/**
 * Visual indicator that replaces the small material Switch widget.
 * At 10 ft a 44 dp filled circle with a check is far easier to parse
 * than an animated thumb.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ToggleStateTile(checked: Boolean) {
    val container = if (checked) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface
    }
    Surface(
        shape = CircleShape,
        colors = SurfaceDefaults.colors(containerColor = container),
        modifier = Modifier.size(44.dp),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (checked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "On",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Text(
                    text = "Off",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

/**
 * Group header for a stack of [SettingToggleRow]s. Use to separate
 * unrelated toggle sections within the same screen.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingSectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp, start = 4.dp),
    )
}
