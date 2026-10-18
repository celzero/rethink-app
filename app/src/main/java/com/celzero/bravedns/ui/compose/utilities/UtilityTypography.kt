/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.utilities

import com.celzero.bravedns.ui.compose.resources.obtainOrderedStyledAttributes
import android.graphics.Typeface
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*

/** Resolve the XML TextView default appearance, then explicit TextAppearance and XML overrides. */
@Composable
fun utilityTextStyle(appearance: Int = 0, size: Float? = null, family: String? = null,
                     weight: Int? = null, spacing: Float? = null, lineExtraDp: Float? = null,
                     defaultStyleAttribute: Int = android.R.attr.textViewStyle): TextStyle {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(context, density, appearance, size, family, weight, spacing, lineExtraDp, defaultStyleAttribute) {
        val ids = intArrayOf(android.R.attr.textSize, android.R.attr.fontFamily, android.R.attr.textStyle, android.R.attr.letterSpacing, android.R.attr.textFontWeight)
        val defaultAppearance = context.obtainOrderedStyledAttributes(null, intArrayOf(android.R.attr.textAppearance), defaultStyleAttribute, 0).let {
            try { it.getResourceId(0, 0) } finally { it.recycle() }
        }
        val inherited = context.obtainOrderedStyledAttributes(defaultAppearance, ids)
        val widget = context.obtainOrderedStyledAttributes(null, ids, defaultStyleAttribute, 0)
        val explicit = if (appearance != 0) context.obtainOrderedStyledAttributes(appearance, ids) else null
        fun values(index: Int) = when { explicit?.hasValue(index) == true -> explicit; widget.hasValue(index) -> widget; else -> inherited }
        val sizeValue = values(0).peekValue(0)
        val textSize = size ?: if (sizeValue?.type == android.util.TypedValue.TYPE_DIMENSION &&
            sizeValue.complexUnit == android.util.TypedValue.COMPLEX_UNIT_SP) android.util.TypedValue.complexToFloat(sizeValue.data)
        else with(density) { values(0).getDimension(0, 14.sp.toPx()).toSp().value }
        val name = family ?: values(1).getString(1) ?: "sans-serif"
        val flags = weight ?: values(2).getInt(2, Typeface.NORMAL)
        val letter = spacing ?: values(3).getFloat(3, 0f)
        // textStyle asks the selected family for its bold face; sans-serif-medium + BOLD is 800,
        // not an explicit numeric 700. Only textFontWeight should force a numeric face.
        val fontWeight = values(4).getInt(4, -1)
        var typeface = Typeface.create(name, flags)
        if (android.os.Build.VERSION.SDK_INT >= 28 && fontWeight in 1..1000) typeface = Typeface.create(typeface, fontWeight, flags and Typeface.ITALIC != 0)
        val resolvedWeight = utilityResolvedWeight(
            if (android.os.Build.VERSION.SDK_INT >= 28) typeface.weight else 0, fontWeight, name, flags)
        explicit?.recycle(); widget.recycle(); inherited.recycle()
        val lineHeight = if (lineExtraDp == null) TextUnit.Unspecified else {
            val paint = android.graphics.Paint().apply { this.typeface = typeface; this.textSize = with(density) { textSize.sp.toPx() } }
            // Compose scales SP line heights proportionally at nonlinear font scales. EM preserves
            // the native pixel line step instead of converting it through the SP curve twice.
            val metrics = paint.fontMetricsInt
            val lineStep = metrics.descent - metrics.ascent + Math.round(with(density) { lineExtraDp.dp.toPx() })
            (lineStep / paint.textSize).em
        }
        TextStyle(fontSize = textSize.sp, fontFamily = FontFamily(typeface), fontWeight = FontWeight(resolvedWeight),
            fontStyle = if (flags and Typeface.ITALIC != 0) FontStyle.Italic else FontStyle.Normal,
            letterSpacing = letter.em, lineHeight = lineHeight, textDirection = androidx.compose.ui.text.style.TextDirection.Content, platformStyle = PlatformTextStyle(includeFontPadding = true))
    }
}
fun resolveUtilityStyle(context: android.content.Context, attribute: Int): Int {
    val value = android.util.TypedValue()
    return if (context.theme.resolveAttribute(attribute, value, true)) value.resourceId else 0
}

/** Keep raw sp dimension values so Android 14+ nonlinear font scaling is applied exactly once. */
fun utilityDimensionSp(context: android.content.Context, resource: Int): Float {
    val value = android.util.TypedValue()
    context.resources.getValue(resource, value, true)
    return if (value.type == android.util.TypedValue.TYPE_DIMENSION &&
        value.complexUnit == android.util.TypedValue.COMPLEX_UNIT_SP) android.util.TypedValue.complexToFloat(value.data)
    else context.resources.getDimension(resource) / context.resources.displayMetrics.scaledDensity
}

/** Older/LEGACY graphics implementations can report zero even for a valid Typeface. */
internal fun utilityResolvedWeight(nativeWeight: Int, requestedWeight: Int, family: String, flags: Int): Int =
    nativeWeight.takeIf { it in 1..1000 } ?: requestedWeight.takeIf { it in 1..1000 } ?:
    if (flags and Typeface.BOLD != 0) (if (family.contains("medium")) 800 else 700)
    else if (family.contains("medium")) 500 else 400
