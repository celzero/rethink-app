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

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceGlow
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ClickableSurfaceShape
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.SurfaceColors
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Surface as TvMaterialSurface

/**
 * Drop-in replacement for [androidx.tv.material3.Surface] that also responds to
 * mouse/touch taps. Compose-for-TV's stock Surface intentionally ignores pointer
 * input because real TV apps are remote-driven; on the emulator (and on
 * touchscreen devices), this means clicking with a mouse does nothing. We layer
 * a [pointerInput] tap detector on top of the modifier chain so the same
 * onClick fires for both DPAD CENTER and mouse/touch taps. Focus visuals and
 * DPAD behavior from tv-material are preserved.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    tonalElevation: Dp = 0.dp,
    shape: ClickableSurfaceShape = ClickableSurfaceDefaults.shape(),
    colors: ClickableSurfaceColors = ClickableSurfaceDefaults.colors(),
    scale: ClickableSurfaceScale = ClickableSurfaceDefaults.scale(),
    border: ClickableSurfaceBorder = ClickableSurfaceDefaults.border(),
    glow: ClickableSurfaceGlow = ClickableSurfaceDefaults.glow(),
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    TvMaterialSurface(
        onClick = onClick,
        modifier = modifier.pointerInput(onClick, onLongClick, enabled) {
            detectTapGestures(
                onTap = { if (enabled) onClick() },
                onLongPress = { if (enabled) onLongClick?.invoke() },
            )
        },
        onLongClick = onLongClick,
        enabled = enabled,
        tonalElevation = tonalElevation,
        shape = shape,
        colors = colors,
        scale = scale,
        border = border,
        glow = glow,
        interactionSource = interactionSource,
        content = content,
    )
}

/** Passthrough for the non-clickable [androidx.tv.material3.Surface] overload. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    tonalElevation: Dp = 0.dp,
    shape: Shape = SurfaceDefaults.shape,
    colors: SurfaceColors = SurfaceDefaults.colors(),
    border: Border = Border.None,
    glow: Glow = Glow.None,
    content: @Composable BoxScope.() -> Unit,
) {
    TvMaterialSurface(
        modifier = modifier,
        tonalElevation = tonalElevation,
        shape = shape,
        colors = colors,
        border = border,
        glow = glow,
        content = content,
    )
}
