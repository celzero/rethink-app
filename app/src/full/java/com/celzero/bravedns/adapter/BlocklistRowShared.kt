/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.adapter

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.service.RethinkBlocklistManager

@Composable
internal fun BlocklistSimpleRow(
    group: String,
    pack: String,
    blocklistCount: Int,
    isSelected: Boolean,
    showHeader: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (showHeader) {
            BlocklistHeader(group = group)
        }

        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(!isSelected) },
            shape = RoundedCornerShape(18.dp),
            color =
                if (isSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = pack.replaceFirstChar(Char::titlecase),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text =
                            context.getString(
                                R.string.rsv_blocklist_count_text,
                                blocklistCount.toString()
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Checkbox(checked = isSelected, onCheckedChange = onToggle)
            }
        }
    }
}

@Composable
internal fun BlocklistAdvancedRow(
    group: String,
    subGroup: String,
    name: String,
    entries: Int,
    level: Int?,
    entryUrl: String?,
    isSelected: Boolean,
    showHeader: Boolean,
    onToggle: (Boolean) -> Unit,
    onEntryClick: (String) -> Unit
) {
    val context = LocalContext.current
    val groupText = if (subGroup.isEmpty()) group else subGroup
    val entryText = context.getString(R.string.dc_entries, entries.toString())
    val (chipText, chipBg) = chipColorsForLevel(level)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (showHeader) {
            BlocklistHeader(group = group)
        }

        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onToggle(!isSelected) },
            shape = RoundedCornerShape(18.dp),
            color =
                if (isSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = groupText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        AssistChip(
                            onClick = { entryUrl?.let(onEntryClick) ?: Unit },
                            enabled = !entryUrl.isNullOrEmpty(),
                            label = { Text(text = entryText) },
                            colors =
                                AssistChipDefaults.assistChipColors(
                                    containerColor = chipBg,
                                    labelColor = chipText
                                )
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Checkbox(checked = isSelected, onCheckedChange = onToggle)
            }
        }
    }
}

@Composable
private fun BlocklistHeader(group: String) {
    val context = LocalContext.current

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 2.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = RethinkBlocklistManager.getGroupName(context, group),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = RethinkBlocklistManager.getTitleDesc(context, group),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun chipColorsForLevel(level: Int?): Pair<Color, Color> {
    if (level == null) {
        val text = MaterialTheme.colorScheme.onSurface
        val bg = MaterialTheme.colorScheme.surface
        return text to bg
    }

    return when (level) {
        0 -> MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.tertiaryContainer
        1 -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceVariant
        2 -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.onSurface to MaterialTheme.colorScheme.surface
    }
}

internal fun RethinkBlocklistManager.getGroupName(context: Context, group: String): String {
    if (group.equals(RethinkBlocklistManager.PARENTAL_CONTROL.name, true)) {
        return context.getString(RethinkBlocklistManager.PARENTAL_CONTROL.label)
    }
    if (group.equals(RethinkBlocklistManager.SECURITY.name, true)) {
        return context.getString(RethinkBlocklistManager.SECURITY.label)
    }
    if (group.equals(RethinkBlocklistManager.PRIVACY.name, true)) {
        return context.getString(RethinkBlocklistManager.PRIVACY.label)
    }
    return group
}

internal fun RethinkBlocklistManager.getTitleDesc(context: Context, group: String): String {
    if (group.equals(RethinkBlocklistManager.PARENTAL_CONTROL.name, true)) {
        return context.getString(RethinkBlocklistManager.PARENTAL_CONTROL.desc)
    }
    if (group.equals(RethinkBlocklistManager.SECURITY.name, true)) {
        return context.getString(RethinkBlocklistManager.SECURITY.desc)
    }
    if (group.equals(RethinkBlocklistManager.PRIVACY.name, true)) {
        return context.getString(RethinkBlocklistManager.PRIVACY.desc)
    }
    return ""
}
