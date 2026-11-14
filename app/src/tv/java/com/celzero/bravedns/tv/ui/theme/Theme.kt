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
import androidx.tv.material3.MaterialTheme

/**
 * Root theme for the `tv` flavor — Material 3 Expressive on tv-material.
 *
 * Android TV apps are almost universally dark-themed — the "ten-foot UI"
 * pattern assumes the device is in a living room and bright surfaces are
 * uncomfortable to look at from the couch. We lock the TV flavor to the
 * dark "Indigo Deep" expressive scheme ([TvColors]) — no system-following
 * light/dark toggle.
 *
 * Expressive adoption lives in the token objects next to this file:
 *  * [TvColors] — bright indigo emphasis on deep indigo-black surfaces.
 *    tv-material 1.1.0's color scheme has no surface-container slots,
 *    so the full tonal container ladder lives in [TvColors] and
 *    screens/components pick steps from there directly.
 *  * [ExpressiveTypography] — bold display heroes, emphasized titles.
 *  * [TvShapes] — steep roundness progression plus the asymmetric
 *    Hero/Panel opt-in tokens.
 *  * [TvMotion] — shared spring signature for focus/entrance motion.
 *
 * Components read from these via MaterialTheme + the token objects; no
 * screen should hardcode colors, radii, or springs.
 */
@Composable
fun RethinkTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = expressiveDarkColorScheme(),
        typography = ExpressiveTypography,
        shapes = TvShapes.ThemeShapes,
        content = content,
    )
}

private fun expressiveDarkColorScheme() = androidx.tv.material3.darkColorScheme(
    primary = TvColors.Primary,
    onPrimary = TvColors.OnPrimary,
    primaryContainer = TvColors.PrimaryContainer,
    onPrimaryContainer = TvColors.OnPrimaryContainer,
    inversePrimary = TvColors.InversePrimary,
    secondary = TvColors.Secondary,
    onSecondary = TvColors.OnSecondary,
    secondaryContainer = TvColors.SecondaryContainer,
    onSecondaryContainer = TvColors.OnSecondaryContainer,
    tertiary = TvColors.Tertiary,
    onTertiary = TvColors.OnTertiary,
    tertiaryContainer = TvColors.TertiaryContainer,
    onTertiaryContainer = TvColors.OnTertiaryContainer,
    background = TvColors.Background,
    onBackground = TvColors.OnBackground,
    surface = TvColors.Surface,
    onSurface = TvColors.OnSurface,
    surfaceVariant = TvColors.SurfaceVariant,
    onSurfaceVariant = TvColors.OnSurfaceVariant,
    // tv-material 1.1.0's darkColorScheme has no surface-container
    // parameters; the container ladder lives in [TvColors] only.
    surfaceTint = TvColors.Primary,
    inverseSurface = TvColors.InverseSurface,
    inverseOnSurface = TvColors.InverseOnSurface,
    error = TvColors.Error,
    onError = TvColors.OnError,
    errorContainer = TvColors.ErrorContainer,
    onErrorContainer = TvColors.OnErrorContainer,
    border = TvColors.Border,
    borderVariant = TvColors.BorderVariant,
    scrim = TvColors.Scrim,
)
