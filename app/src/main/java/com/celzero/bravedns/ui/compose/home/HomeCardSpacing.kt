/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.home

import com.celzero.bravedns.ui.compose.resources.obtainOrderedStyledAttributes

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max

internal data class HomeCardInsets(val horizontalPx: Int, val verticalPx: Int)

/** The baseline rounded CardView's outside shadow/corner clearance, rounded in pixels. */
internal fun homeCardInsets(radiusPx: Float, maxElevationPx: Float, preventCornerOverlap: Boolean): HomeCardInsets {
    val cornerPadding = if (preventCornerOverlap) (1.0 - cos(Math.PI / 4.0)) * radiusPx else 0.0
    return HomeCardInsets(
        horizontalPx = ceil(maxElevationPx + cornerPadding).toInt(),
        verticalPx = ceil(1.5 * maxElevationPx + cornerPadding).toInt()
    )
}

/**
 * Home's XML sections mostly have no margins: their themed cardUseCompatPadding
 * supplies the visible gutters. Compose surfaces do not inherit those insets.
 * Read only the theme values and reproduce that outside space with native padding;
 * no CardView, XML inflation, service or controller is involved.
 */
@Composable
internal fun rememberHomeCardPadding(radius: Dp, elevationOverride: Dp? = null): PaddingValues {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    return remember(context, density, configuration, radius, elevationOverride) {
        val values = context.obtainOrderedStyledAttributes(
            null,
            intArrayOf(
                androidx.cardview.R.attr.cardUseCompatPadding,
                androidx.cardview.R.attr.cardPreventCornerOverlap,
                androidx.cardview.R.attr.cardElevation,
                androidx.cardview.R.attr.cardMaxElevation
            ),
            com.google.android.material.R.attr.materialCardViewStyle,
            0
        )
        try {
            if (!values.getBoolean(0, false)) {
                PaddingValues(0.dp)
            } else {
                with(density) {
                    val elevation = elevationOverride?.toPx() ?: values.getDimension(2, 0f)
                    val maxElevation = max(elevation, values.getDimension(3, elevation))
                    val insets = homeCardInsets(radius.toPx(), maxElevation, values.getBoolean(1, true))
                    PaddingValues(horizontal = insets.horizontalPx.toDp(), vertical = insets.verticalPx.toDp())
                }
            }
        } finally {
            values.recycle()
        }
    }
}

/** Match the baseline's visual shadow separately from its outside shadow allocation. */
@Composable
internal fun rememberHomeCardElevation(override: Dp?): Dp {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    return remember(context, density, configuration, override) {
        override ?: context.obtainOrderedStyledAttributes(null, intArrayOf(androidx.cardview.R.attr.cardElevation),
            com.google.android.material.R.attr.materialCardViewStyle, 0).let { values ->
            try { with(density) { values.getDimension(0, 0f).toDp() } } finally { values.recycle() }
        }
    }
}
