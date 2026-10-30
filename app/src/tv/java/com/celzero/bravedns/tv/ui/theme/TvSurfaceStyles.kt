/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceGlow
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SurfaceColors
import androidx.tv.material3.SurfaceDefaults

/**
 * Shared expressive surface styles.
 *
 * Every focusable card/row/rail item in the app should pull its
 * colors from here instead of hand-writing
 * [androidx.tv.material3.ClickableSurfaceDefaults.colors] so the
 * focus language (indigo-toned fill + glow halo + sharpened border)
 * stays consistent everywhere.
 */
object TvSurfaceStyles {

    /** Wrap a raw shape into the clickable-surface shape bundle. */
    @Composable
    fun interactiveShape(
        shape: androidx.compose.ui.graphics.Shape = TvShapes.Medium,
    ): androidx.tv.material3.ClickableSurfaceShape =
        ClickableSurfaceDefaults.shape(shape = shape)

    /**
     * Standard interactive container: elevated plum card at rest,
     * champagne-bronze fill on focus/press.
     */
    @Composable
    fun interactiveColors(
        container: androidx.compose.ui.graphics.Color = TvColors.SurfaceCard,
        content: androidx.compose.ui.graphics.Color = TvColors.OnSurface,
    ): ClickableSurfaceColors = ClickableSurfaceDefaults.colors(
        containerColor = container,
        contentColor = content,
        focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        disabledContainerColor = TvColors.DisabledContainer,
        disabledContentColor = TvColors.DisabledContent,
    )

    /**
     * Muted variant for rows that sit inside a larger panel (settings
     * toggles, action lists): surfaceVariant at rest.
     */
    @Composable
    fun rowColors(
        container: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surfaceVariant,
        content: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
    ): ClickableSurfaceColors = ClickableSurfaceDefaults.colors(
        containerColor = container,
        contentColor = content,
        focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        disabledContainerColor = TvColors.DisabledContainer,
        disabledContentColor = TvColors.DisabledContent,
    )

    /**
     * Rail / pill item colors. Rest states step with the tonal ladder —
     * unselected items sit quietly on [TvColors.SurfaceContainer],
     * selected items fill with the primary container so the active
     * destination is obvious even before focus arrives. Focus (and
     * press) resolve to the full primary fill: the strongest indigo,
     * covering both focused+selected and focused+unselected cases.
     * No outlines — tonal fills plus the shared glow only.
     */
    @Composable
    fun railItemColors(selected: Boolean): ClickableSurfaceColors =
        ClickableSurfaceDefaults.colors(
            containerColor = if (selected) TvColors.PrimaryContainer
            else TvColors.SurfaceContainer,
            contentColor = if (selected) TvColors.OnPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primary,
            focusedContentColor = MaterialTheme.colorScheme.onPrimary,
            pressedContainerColor = MaterialTheme.colorScheme.primary,
            pressedContentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = TvColors.DisabledContainer,
            disabledContentColor = TvColors.DisabledContent,
        )

    /**
     * Expressive focus scale: a subtle grow with a light press-in.
     * tv-material animates these transitions internally; the shared
     * springs in [TvMotion] cover our own animated state changes.
     */
    fun expressiveScale(): ClickableSurfaceScale = ClickableSurfaceDefaults.scale(
        scale = TvSizes.FocusScaleDown,
        focusedScale = TvSizes.FocusScaleUp,
        pressedScale = TvSizes.PressedScale,
    )

    /**
     * Clear D-pad focus halo: a soft primary-toned glow appears only
     * while focused, reinforcing the fill change with ambient light.
     * Controlled intensity — visible from the couch, never neon.
     * Focus borders are intentionally NOT defaulted in: a fixed-shape
     * border would fight pill/extra-large surfaces; individual
     * call-sites may still pass a matching [androidx.tv.material3.Border].
     */
    @Composable
    fun expressiveGlow(): ClickableSurfaceGlow {
        val focusGlow = Glow(
            elevationColor = TvColors.FocusGlowScrim,
            elevation = TvElevation.FocusGlow,
        )
        return ClickableSurfaceDefaults.glow(
            glow = Glow.None,
            focusedGlow = focusGlow,
            pressedGlow = focusGlow,
        )
    }

    /** Static (non-clickable) surface defaults on the card tone. */
    @Composable
    fun cardColors(): SurfaceColors = SurfaceDefaults.colors(
        containerColor = TvColors.SurfaceCard,
        contentColor = TvColors.OnSurface,
    )
}
