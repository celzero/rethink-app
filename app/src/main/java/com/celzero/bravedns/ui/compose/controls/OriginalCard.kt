/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.controls

import com.celzero.bravedns.ui.compose.resources.obtainOrderedStyledAttributes

import androidx.annotation.AttrRes
import androidx.annotation.StyleRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max

/** Source MaterialCardView defaults + explicit style/instance overrides, drawn only in Compose.
 * Caller margins are outside the shadow/corner clearance; caller child padding is inside it.
 */
@Composable
fun OriginalCardSurface(
    modifier: Modifier = Modifier,
    @StyleRes style: Int = 0,
    @AttrRes styleAttribute: Int = com.google.android.material.R.attr.materialCardViewStyle,
    cornerRadius: Dp? = null,
    elevation: Dp? = null,
    useCompatPadding: Boolean? = null,
    preventCornerOverlap: Boolean? = null,
    containerColor: Color? = null,
    contentColor: Color? = null,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val appearance = remember(context, density, configuration, style, styleAttribute, cornerRadius, elevation, useCompatPadding, preventCornerOverlap) {
        val attributes = intArrayOf(androidx.cardview.R.attr.cardBackgroundColor,
            androidx.cardview.R.attr.cardCornerRadius, androidx.cardview.R.attr.cardElevation,
            androidx.cardview.R.attr.cardMaxElevation, androidx.cardview.R.attr.cardUseCompatPadding,
            androidx.cardview.R.attr.cardPreventCornerOverlap, com.google.android.material.R.attr.strokeColor,
            com.google.android.material.R.attr.strokeWidth, androidx.cardview.R.attr.contentPadding,
            androidx.cardview.R.attr.contentPaddingLeft, androidx.cardview.R.attr.contentPaddingTop,
            androidx.cardview.R.attr.contentPaddingRight, androidx.cardview.R.attr.contentPaddingBottom)
        val defaults = context.obtainOrderedStyledAttributes(null, attributes, styleAttribute, 0)
        val explicit = if (style != 0) context.obtainOrderedStyledAttributes(style, attributes) else null
        fun values(index: Int) = if (explicit?.hasValue(index) == true) explicit else defaults
        try {
            with(density) {
                val radiusPx = cornerRadius?.toPx() ?: values(1).getDimension(1, 0f)
                val elevationPx = elevation?.toPx() ?: values(2).getDimension(2, 0f)
                val maxElevationPx = max(elevationPx, values(3).getDimension(3, elevationPx))
                val corner = if (preventCornerOverlap ?: values(5).getBoolean(5, true))
                    (1.0 - cos(Math.PI / 4.0)) * radiusPx else 0.0
                val compat = useCompatPadding ?: values(4).getBoolean(4, false)
                val contentPaddingPx = values(8).getDimension(8, 0f)
                OriginalCardAppearance(
                    values(0).getColorStateList(0)?.defaultColor?.let(::Color), radiusPx.toDp(), elevationPx.toDp(),
                    PaddingValues(horizontal = if (compat) ceil(maxElevationPx + corner).toFloat().toDp() else 0.dp,
                        vertical = if (compat) ceil(1.5 * maxElevationPx + corner).toFloat().toDp() else 0.dp),
                    values(6).getColorStateList(6)?.defaultColor?.let(::Color), values(7).getDimension(7, 0f).toDp(),
                    // Source card content padding is physical left/right; translate to logical below.
                    values(9).getDimension(9, contentPaddingPx).toDp(), values(10).getDimension(10, contentPaddingPx).toDp(),
                    values(11).getDimension(11, contentPaddingPx).toDp(), values(12).getDimension(12, contentPaddingPx).toDp())
            }
        } finally { explicit?.recycle(); defaults.recycle() }
    }
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Surface(modifier.padding(appearance.outside), shape = RoundedCornerShape(appearance.radius),
        color = containerColor ?: appearance.fill ?: themeColor(R.attr.colorSurfaceContainer),
        contentColor = contentColor ?: themeColor(R.attr.primaryTextColor), tonalElevation = 0.dp,
        shadowElevation = appearance.elevation,
        border = (border ?: appearance.stroke?.takeIf { appearance.strokeWidth > 0.dp }?.let { BorderStroke(appearance.strokeWidth, it) })?.takeIf { it.width > 0.dp }) {
        Column(Modifier.padding(start = if (rtl) appearance.right else appearance.left,
            top = appearance.top, end = if (rtl) appearance.left else appearance.right, bottom = appearance.bottom), content = content)
    }
}

private data class OriginalCardAppearance(val fill: Color?, val radius: Dp, val elevation: Dp,
    val outside: PaddingValues, val stroke: Color?, val strokeWidth: Dp,
    val left: Dp, val top: Dp, val right: Dp, val bottom: Dp)
