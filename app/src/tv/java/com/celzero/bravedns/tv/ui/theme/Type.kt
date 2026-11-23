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

import androidx.tv.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Expressive type scale for the TV flavor.
 *
 * Follows the M3 Expressive direction: large, confident display styles
 * for heroes, emphasized (bolder) weights on titles, and tightened
 * letter-spacing at display sizes. Base sizes stay at the ten-foot
 * scale — noticeably larger than the phone defaults — so everything is
 * readable from a couch.
 */
object TvType {
    val DisplayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 60.sp,
        lineHeight = 68.sp,
        letterSpacing = (-0.25).sp,
    )
    val DisplayMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        lineHeight = 56.sp,
        letterSpacing = (-0.2).sp,
    )
    val DisplaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.15).sp,
    )
    val HeadlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.1).sp,
    )
    val HeadlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    )
    val HeadlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
    )
    val TitleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    )
    val TitleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.1.sp,
    )
    val TitleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
    )
    val BodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.2.sp,
    )
    val BodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.2.sp,
    )
    val BodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.3.sp,
    )
    val LabelLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.6.sp,
    )
    val LabelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.5.sp,
    )
    val LabelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
    )
}

/** The theme-facing typography built from the expressive scale. */
internal val ExpressiveTypography = Typography(
    displayLarge = TvType.DisplayLarge,
    displayMedium = TvType.DisplayMedium,
    displaySmall = TvType.DisplaySmall,
    headlineLarge = TvType.HeadlineLarge,
    headlineMedium = TvType.HeadlineMedium,
    headlineSmall = TvType.HeadlineSmall,
    titleLarge = TvType.TitleLarge,
    titleMedium = TvType.TitleMedium,
    titleSmall = TvType.TitleSmall,
    bodyLarge = TvType.BodyLarge,
    bodyMedium = TvType.BodyMedium,
    bodySmall = TvType.BodySmall,
    labelLarge = TvType.LabelLarge,
    labelMedium = TvType.LabelMedium,
    labelSmall = TvType.LabelSmall,
)
