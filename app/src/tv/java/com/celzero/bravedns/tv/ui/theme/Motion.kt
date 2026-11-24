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

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

/**
 * Motion + spacing tokens for the TV flavor's expressive language.
 *
 * tv-material has no MotionScheme, so springs live here and are shared
 * by every animated interaction (focus lifts, entrance staggers, state
 * color changes) to keep the whole app on one motion signature.
 */
object TvMotion {
    /**
     * Focus/press transitions: slightly under-damped so surfaces land
     * with a soft, premium overshoot instead of a hard snap.
     */
    fun <T> expressive() = spring<T>(
        dampingRatio = 0.8f,
        stiffness = Spring.StiffnessMediumLow,
    )

    /** Color/shape state changes: quicker, no visible overshoot. */
    fun <T> quick() = spring<T>(
        dampingRatio = 1f,
        stiffness = Spring.StiffnessMedium,
    )

    /** Container/layout transitions (panels, expandable rows). */
    fun <T> spatial() = spring<T>(
        dampingRatio = 0.85f,
        stiffness = Spring.StiffnessLow,
    )

    /** Per-item delay for staggered entrance of card grids. */
    const val STAGGER_MS = 40L
}

object TvSpacing {
    // Screen safe-area (ten-foot TV convention).
    val ScreenHorizontal = 32.dp
    val ScreenVertical = 24.dp

    // Section rhythm.
    val SectionGap = 24.dp
    val HeaderGap = 8.dp
    val GroupGap = 12.dp

    // Card / row internals.
    val CardGap = 12.dp
    val RowGap = 8.dp
    val ContentHorizontal = 20.dp
    val ContentVertical = 16.dp
    val IconTextGap = 16.dp
}

object TvSizes {
    // Focus-scale behavior for clickable surfaces. TV-material's
    // default grows 1.0 → 1.1; the expressive direction is subtler.
    val FocusScaleDown = 1f
    val FocusScaleUp = 1.06f
    val PressedScale = 0.97f

    // Repeated component dimensions.
    val RailWidth = 232.dp
    val RailItem = 52.dp
    val RailIcon = 26.dp
    val ToggleTile = 44.dp
    val LeadingIcon = 28.dp

    // Network activity visualization — flowing-wave composition
    // (segmented LinearWavyProgressIndicator rows). The Home screen's
    // visual centerpiece: Total gets the tallest band, Blocked the
    // shortest, so the layering mirrors the semantics.
    val ActivityWaveTotalHeight = 64.dp
    val ActivityWaveAllowedHeight = 44.dp
    val ActivityWaveBlockedHeight = 28.dp
    val ActivityWaveStroke = 4.dp
    val ActivityWaveWavelength = 28.dp
    val ActivityWaveCompositionHeight = 160.dp
}

/**
 * Tonal elevation ladder. tv-material surfaces are flat-color; the
 * layering effect comes from stepping container tones upward
 * (see [TvColors] surface roles) plus a soft focus glow.
 */
object TvElevation {
    /** Base screen level — flat background. */
    val Level0 = 0.dp

    /** Grouped content panels / cards. */
    val Level1 = 3.dp

    /** Cards inside panels, dialogs, dropdowns. */
    val Level2 = 6.dp

    /** Focus glow halo radius for interactive surfaces. */
    val FocusGlow = 16.dp
}
