/*
 * Copyright 2024 RethinkDNS and its authors
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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Design system dimensions following Material 3 Expressive guidelines.
 */
object Dimensions {
    // Spacing scale (4dp base)
    val spacingNone: Dp = 0.dp
    val spacingXs: Dp = 4.dp
    val spacingGridTile: Dp = 2.dp
    val spacingSm: Dp = 8.dp
    val spacingMd: Dp = 12.dp
    val spacingSmMd: Dp = 10.dp
    val spacingLg: Dp = 16.dp
    val spacingXl: Dp = 24.dp
    val spacing2xl: Dp = 32.dp
    val spacing3xl: Dp = 48.dp

    // M3 Expressive shape scale — larger, more expressive corner radii
    val cornerRadius2xs: Dp = 3.dp
    val cornerRadiusXs: Dp = 4.dp
    val cornerRadiusSm: Dp = 6.dp
    val cornerRadiusSmMd: Dp = 10.dp
    val cornerRadiusMd: Dp = 12.dp
    val cornerRadiusMdLg: Dp = 14.dp
    val cornerRadiusLg: Dp = 16.dp
    // Normalized medium-large card radii to one standard (20dp)
    val cornerRadiusXl: Dp = 20.dp
    val cornerRadius2xl: Dp = 20.dp
    val cornerRadius3xl: Dp = 20.dp
    val cornerRadius4xl: Dp = 24.dp
    val cornerRadius5xl: Dp = 28.dp
    val cornerRadiusPill: Dp = 50.dp
    val cornerRadiusFull: Dp = 999.dp

    val cardCornerRadius: Dp = 20.dp           // Cards, list groups
    val cardCornerRadiusLarge: Dp = 28.dp      // Section cards
    val heroCornerRadius: Dp = 32.dp           // Hero/protection cards
    val chipCornerRadius: Dp = 50.dp           // Full pill chips
    val iconContainerRadius: Dp = 12.dp        // Icon surface containers
    val buttonCornerRadius: Dp = 50.dp         // Full pill buttons (M3 Expressive default)
    val buttonCornerRadiusLarge: Dp = 50.dp

    // Card padding
    val cardPadding: Dp = 16.dp
    val cardPaddingSm: Dp = 12.dp

    // Screen padding
    val screenPaddingHorizontal: Dp = 16.dp
    val screenPaddingVertical: Dp = 12.dp

    // Icon sizes
    val iconSizeXs: Dp = 16.dp
    val iconSizeSm: Dp = 20.dp
    val iconSizeMd: Dp = 24.dp
    val iconSizeLg: Dp = 32.dp
    val iconSizeXl: Dp = 48.dp

    // Icon container sizes (tinted squircle containers)
    val iconContainerSm: Dp = 32.dp
    val iconContainerMd: Dp = 40.dp
    val iconContainerLg: Dp = 48.dp

    // Touch targets (minimum 48dp for accessibility)
    val touchTargetMin: Dp = 48.dp
    val touchTargetSm: Dp = 44.dp

    // Button dimensions — M3 Expressive prefers taller buttons
    val buttonHeight: Dp = 52.dp
    val buttonHeightSm: Dp = 44.dp
    val buttonHeightLg: Dp = 60.dp

    // List item dimensions
    val listItemHeight: Dp = 64.dp
    val listItemHeightSm: Dp = 56.dp
    val listItemHeightLg: Dp = 72.dp

    // Progress indicators
    val progressBarHeight: Dp = 10.dp

    // Active tab indicator
    val tabIndicatorHeight: Dp = 4.dp

    // Divider
    val dividerThickness: Dp = 0.5.dp
    val dividerThicknessBold: Dp = 1.dp

    // Opacity values
    object Opacity {
        const val FULL: Float = 1f
        const val HIGH: Float = 0.87f
        const val MEDIUM: Float = 0.7f
        const val DISABLED: Float = 0.38f
        const val LOW: Float = 0.5f
        const val HINT: Float = 0.6f
    }

    // Elevation values
    object Elevation {
        val none: Dp = 0.dp
        val low: Dp = 1.dp
        val medium: Dp = 4.dp
        val high: Dp = 8.dp
    }
}

/**
 * Standard padding values for common use cases.
 */
object Paddings {
    val none = PaddingValues(0.dp)
    val xs = PaddingValues(Dimensions.spacingXs)
    val sm = PaddingValues(Dimensions.spacingSm)
    val md = PaddingValues(Dimensions.spacingMd)
    val lg = PaddingValues(Dimensions.spacingLg)
    val xl = PaddingValues(Dimensions.spacingXl)

    val screen = PaddingValues(
        horizontal = Dimensions.screenPaddingHorizontal,
        vertical = Dimensions.screenPaddingVertical
    )

    val card = PaddingValues(Dimensions.cardPadding)
    val cardSm = PaddingValues(Dimensions.cardPaddingSm)
}
