/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.blocklists

import com.celzero.bravedns.ui.compose.resources.obtainOrderedStyledAttributes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.google.android.material.elevation.ElevationOverlayProvider
import com.google.android.material.theme.overlay.MaterialThemeOverlay
import com.google.android.material.R as MaterialR

/**
 * The original simple/advanced adapters set the card fill to background/selectedCardBg.
 * MaterialCardView still applies its native elevation overlay to a surface-colored fill.
 * Painting the raw background in Compose erased that teal surface in the supplied reference.
 * Resolve the same theme/elevation rather than baking screenshot RGB values into a theme.
 */
@Composable
internal fun blocklistCardColor(checked: Boolean): Color {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val fill = themeColor(if (checked) R.attr.selectedCardBg else R.attr.background)
    return remember(context, configuration, fill) {
        val cardContext = MaterialThemeOverlay.wrap(context, null,
            MaterialR.attr.materialCardViewElevatedStyle, MaterialR.style.Widget_MaterialComponents_CardView)
        val attributes = cardContext.obtainOrderedStyledAttributes(null,
            intArrayOf(androidx.cardview.R.attr.cardElevation),
            MaterialR.attr.materialCardViewElevatedStyle, MaterialR.style.Widget_MaterialComponents_CardView)
        val elevationPx = try { attributes.getDimension(0, 0f) } finally { attributes.recycle() }
        Color(ElevationOverlayProvider(cardContext).compositeOverlayIfNeeded(fill.toArgb(), elevationPx))
    }
}
