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
package com.celzero.bravedns.ui.compose.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun RethinkSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: RoundedCornerShape = RoundedCornerShape(Dimensions.cornerRadiusMdLg),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    leadingIconTint: Color = MaterialTheme.colorScheme.primary,
    iconSize: Dp = Dimensions.iconSizeSm,
    trailingIconSize: Dp = iconSize,
    trailingIconButtonSize: Dp? = null,
    clearQueryContentDescription: String? = null,
    closeWhenEmptyContentDescription: String? = null,
    onClearQuery: (() -> Unit)? = null,
    onCloseWhenEmpty: (() -> Unit)? = null
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        singleLine = true,
        enabled = enabled,
        textStyle = textStyle,
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = leadingIconTint,
                modifier = Modifier.size(iconSize)
            )
        },
        trailingIcon = {
            val action =
                when {
                    query.isNotEmpty() -> onClearQuery ?: { onQueryChange("") }
                    onCloseWhenEmpty != null -> onCloseWhenEmpty
                    else -> null
                }
            if (action != null) {
                val description =
                    if (query.isNotEmpty()) {
                        clearQueryContentDescription
                    } else {
                        closeWhenEmptyContentDescription
                    }
                val buttonModifier =
                    if (trailingIconButtonSize != null) {
                        Modifier.size(trailingIconButtonSize)
                    } else {
                        Modifier
                    }
                IconButton(
                    onClick = action,
                    modifier = buttonModifier
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = description,
                        modifier = Modifier.size(trailingIconSize)
                    )
                }
            }
        },
        shape = shape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = containerColor,
            unfocusedContainerColor = containerColor,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent
        )
    )
}

@Composable
fun RethinkFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(Dimensions.cornerRadiusMdLg),
    selectedContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textStyle: TextStyle = MaterialTheme.typography.labelMedium,
    leadingIcon: (@Composable () -> Unit)? = null,
    selectedLeadingIconColor: Color = selectedLabelColor,
    leadingIconColor: Color = labelColor,
    border: BorderStroke? = null,
    minHeight: Dp = 0.dp,
    selectedLabelWeight: FontWeight = FontWeight.SemiBold,
    defaultLabelWeight: FontWeight = FontWeight.Normal
) {
    FilterChip(
        modifier = modifier.heightIn(min = minHeight),
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontWeight = if (selected) selectedLabelWeight else defaultLabelWeight,
                style = textStyle
            )
        },
        leadingIcon = leadingIcon,
        shape = shape,
        border = border,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = selectedContainerColor,
            selectedLabelColor = selectedLabelColor,
            containerColor = containerColor,
            labelColor = labelColor,
            selectedLeadingIconColor = selectedLeadingIconColor,
            iconColor = leadingIconColor
        )
    )
}
