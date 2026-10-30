/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.rules

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SurfaceDefaults
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.theme.TvColors
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles
import androidx.tv.material3.Text
import androidx.compose.material3.Text as M3Text

@Composable
internal fun RuleFormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        M3Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { M3Text(hint, style = MaterialTheme.typography.bodyMedium) },
            singleLine = true,
            keyboardOptions = keyboardOptions,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun RuleSegmentedChoice(
    label: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        M3Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap)) {
            options.forEachIndexed { index, option ->
                Surface(
                    onClick = { onSelect(index) },
                    shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Pill),
                    colors = TvSurfaceStyles.railItemColors(selected = index == selectedIndex),
                ) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun RulePrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = TvSurfaceStyles.interactiveShape(),
        colors = if (destructive) {
            ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                focusedContainerColor = MaterialTheme.colorScheme.errorContainer,
                focusedContentColor = MaterialTheme.colorScheme.onErrorContainer,
                pressedContainerColor = MaterialTheme.colorScheme.errorContainer,
                pressedContentColor = MaterialTheme.colorScheme.onErrorContainer,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        } else {
            ClickableSurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        },
        modifier = modifier,
    ) {
        Box(modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun RuleSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = TvSurfaceStyles.interactiveShape(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
            pressedContainerColor = MaterialTheme.colorScheme.primary,
            pressedContentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.padding(horizontal = 28.dp, vertical = 14.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * Modal delete confirmation for the rules screens: scrim + centered
 * panel, focus parked on the confirm action. Follows the same overlay
 * conventions as the logs app-filter picker.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun RulesConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val confirmFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { confirmFocus.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvColors.Scrim.copy(alpha = 0.72f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = TvShapes.Medium,
            colors = SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.fillMaxWidth(0.5f),
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap),
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(TvSpacing.GroupGap)) {
                    RulePrimaryButton(
                        label = confirmLabel,
                        destructive = true,
                        onClick = onConfirm,
                        modifier = Modifier.focusRequester(confirmFocus),
                    )
                    RuleSecondaryButton(label = "Cancel", onClick = onDismiss)
                }
            }
        }
    }
}
