/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSizes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles

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
    leadingIconRes: Int? = null,
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
        shape = TvSurfaceStyles.interactiveShape(),
        colors = TvSurfaceStyles.rowColors(),
        scale = TvSurfaceStyles.expressiveScale(),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = TvSpacing.RowGap),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = TvSpacing.ContentHorizontal,
                    vertical = TvSpacing.ContentVertical,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    modifier = Modifier.size(TvSizes.LeadingIcon),
                )
                Spacer(Modifier.width(TvSpacing.IconTextGap))
            } else if (leadingIconRes != null) {
                Icon(
                    painter = painterResource(leadingIconRes),
                    contentDescription = null,
                    modifier = Modifier.size(TvSizes.LeadingIcon),
                )
                Spacer(Modifier.width(TvSpacing.IconTextGap))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
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
            Spacer(Modifier.width(TvSpacing.ContentHorizontal))
            // Display-only Material switch: the whole row is the
            // D-pad target, so the switch itself is non-interactive
            // and mirrors the row state.
            Switch(
                checked = localChecked,
                onCheckedChange = null,
                enabled = enabled,
            )
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
        style = MaterialTheme.typography.labelLarge,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = TvSpacing.GroupGap, bottom = TvSpacing.HeaderGap, start = 4.dp),
    )
}
