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

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

/**
 * Tasteful tonal transitions for expressive surfaces.
 *
 * Deliberately low-contrast washes that read as material depth on the
 * deep base — not decoration, and never neon. Layered use (a tonal
 * base, an indigo bloom, a teal counterweight) is what gives the Home
 * hero its expressive feel; individual washes stay quiet on purpose.
 * Reserve these for the largest content on a screen (home hero,
 * dashboards) — not every card.
 */
object TvGradients {
    /** Diagonal indigo-to-card wash for hero panels. */
    fun hero(): Brush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to TvColors.HeroGradientStart,
            1f to TvColors.HeroGradientEnd,
        ),
    )

    /**
     * Expressive primary tonal layer: an indigo bloom fading to
     * transparent, anchored to the hero's top-right corner. Layer it
     * over [hero] inside the hero shape.
     */
    fun heroAccent(): Brush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to TvColors.HeroAccentStart,
            1f to androidx.compose.ui.graphics.Color.Transparent,
        ),
        start = Offset(1f, 0f),
        end = Offset(0f, 1f),
    )

    /**
     * Secondary tonal layer: a teal wash fading to transparent,
     * anchored to the hero's bottom-left corner — offset from
     * [heroAccent] so the two overlapping fades read as depth rather
     * than two flat colors.
     */
    fun tealAccent(): Brush = Brush.linearGradient(
        colorStops = arrayOf(
            0f to TvColors.TealAccentStart,
            1f to androidx.compose.ui.graphics.Color.Transparent,
        ),
        start = Offset(0f, 1f),
        end = Offset(1f, 0f),
    )

    /** Gentle vertical wash for full-screen headers. */
    fun header(): Brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to TvColors.HeroGradientStart.copy(alpha = 0.7f),
            1f to TvColors.HeroGradientEnd.copy(alpha = 0f),
        ),
    )
}
