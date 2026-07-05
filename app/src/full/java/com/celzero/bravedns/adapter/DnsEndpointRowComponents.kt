/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.celzero.bravedns.adapter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class DnsRowAction {
    Info,
    Edit,
    Delete
}

internal enum class DnsRowSelection {
    Radio,
    Checkbox
}

@Composable
internal fun DnsEndpointRow(
    title: String,
    supporting: String?,
    selected: Boolean,
    action: DnsRowAction,
    selection: DnsRowSelection = DnsRowSelection.Radio,
    onActionClick: () -> Unit,
    onSelectionChange: (Boolean) -> Unit
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color =
            if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        onSelectionChange(
                            if (selection == DnsRowSelection.Radio) true else !selected
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (!supporting.isNullOrEmpty()) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            DnsEndpointActionButton(
                action = action,
                onClick = onActionClick
            )

            when (selection) {
                DnsRowSelection.Radio -> {
                    RadioButton(
                        selected = selected,
                        onClick = { onSelectionChange(true) }
                    )
                }
                DnsRowSelection.Checkbox -> {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = onSelectionChange
                    )
                }
            }
        }
    }
}

@Composable
private fun DnsEndpointActionButton(
    action: DnsRowAction,
    onClick: () -> Unit
) {
    val containerColor =
        when (action) {
            DnsRowAction.Delete -> MaterialTheme.colorScheme.errorContainer
            DnsRowAction.Edit -> MaterialTheme.colorScheme.tertiaryContainer
            DnsRowAction.Info -> MaterialTheme.colorScheme.secondaryContainer
        }
    val contentColor =
        when (action) {
            DnsRowAction.Delete -> MaterialTheme.colorScheme.onErrorContainer
            DnsRowAction.Edit -> MaterialTheme.colorScheme.onTertiaryContainer
            DnsRowAction.Info -> MaterialTheme.colorScheme.onSecondaryContainer
        }
    val icon =
        when (action) {
            DnsRowAction.Delete -> Icons.Rounded.DeleteOutline
            DnsRowAction.Edit -> Icons.Rounded.Edit
            DnsRowAction.Info -> Icons.Rounded.MoreHoriz
        }

    Surface(
        shape = CircleShape,
        color = containerColor,
        modifier = Modifier.size(32.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
