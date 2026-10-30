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

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Shapes

/**
 * Expressive roundness progression for the TV flavor.
 *
 * Uses a steeper curve than the tv-material defaults — large panels
 * get distinctly generous radii (the M3 Expressive "extra-round"
 * direction) while chips/labels stay modest. The full-pill shape is
 * reserved for the nav rail and status pills.
 */
object TvShapes {
    val ExtraSmall = RoundedCornerShape(8.dp)
    val Small = RoundedCornerShape(12.dp)
    val Medium = RoundedCornerShape(16.dp)
    val Large = RoundedCornerShape(24.dp)
    val ExtraLarge = RoundedCornerShape(32.dp)

    /** Full-round, for rail items and pill chips. */
    val Pill = RoundedCornerShape(50)

    /**
     * Asymmetric hero container — the M3 Expressive "organic squircle"
     * feel. Large opposite corners (44.dp) counter-weighted by small
     * ones (20.dp) make the biggest surface on a screen read as a
     * shaped object rather than a rectangle. Used by the Home
     * protection hero.
     */
    val Hero = RoundedCornerShape(
        topStart = 44.dp,
        topEnd = 20.dp,
        bottomEnd = 44.dp,
        bottomStart = 20.dp,
    )

    /**
     * Asymmetric section panel — a softer sibling of [Hero] for
     * activity/section panels (network activity chart, event lists).
     */
    val Panel = RoundedCornerShape(
        topStart = 28.dp,
        topEnd = 12.dp,
        bottomEnd = 28.dp,
        bottomStart = 12.dp,
    )

    /** Wired into [androidx.tv.material3.MaterialTheme]. */
    val ThemeShapes = Shapes(
        extraSmall = ExtraSmall,
        small = Small,
        medium = Medium,
        large = Large,
        extraLarge = ExtraLarge,
    )
}
