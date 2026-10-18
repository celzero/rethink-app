/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.wireguard

import com.celzero.bravedns.ui.compose.resources.obtainOrderedStyledAttributes

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.content.res.ResourcesCompat
import com.celzero.bravedns.ui.compose.utilities.utilityResolvedWeight
import com.google.android.material.R as MaterialR

internal data class WgTextLook(val style: TextStyle, val colors: ColorStateList, val maxLines: Int,
    val minLines: Int, val singleLine: Boolean, val overflow: TextOverflow, val allCaps: Boolean, val alpha: Float)

/** TextView defaults -> source textAppearance -> source attributes -> live presentation overrides.
 * No MaterialTheme text token, View inflation, or retained layout resource participates. */
internal fun resolveWgText(context: Context, density: Density, source: String?, node: WgControlState? = null): WgTextLook {
    val spec = source?.let { requireNotNull(wgTextSources[it]) { "Unknown WireGuard text source: $it" } }
    val ids = intArrayOf(android.R.attr.textAppearance, android.R.attr.textSize, android.R.attr.textColor,
        android.R.attr.fontFamily, androidx.appcompat.R.attr.fontFamily, android.R.attr.textStyle,
        android.R.attr.textFontWeight, android.R.attr.letterSpacing, android.R.attr.lineSpacingExtra,
        android.R.attr.lineSpacingMultiplier, android.R.attr.lineHeight, MaterialR.attr.lineHeight,
        android.R.attr.includeFontPadding, android.R.attr.maxLines, android.R.attr.minLines,
        android.R.attr.lines, android.R.attr.singleLine, android.R.attr.ellipsize, android.R.attr.gravity,
        android.R.attr.textAlignment, android.R.attr.textAllCaps, androidx.appcompat.R.attr.textAllCaps,
        android.R.attr.alpha)
    val base = context.obtainOrderedStyledAttributes(null, ids, spec?.defaultStyleAttr ?: android.R.attr.textViewStyle, 0)
    val explicit = spec?.let { context.obtainOrderedStyledAttributes(it.style, ids) }
    fun direct(i: Int) = if (explicit?.hasValue(i) == true) explicit else base
    val appearance = context.obtainOrderedStyledAttributes(node?.textAppearanceStyle ?: direct(0).getResourceId(0, 0), ids)
    fun value(i: Int) = when { explicit?.hasValue(i) == true -> explicit; base.hasValue(i) -> base; else -> appearance }
    try {
        val fontArray = when {
            explicit?.hasValue(4) == true || explicit?.hasValue(3) == true -> explicit
            base.hasValue(4) || base.hasValue(3) -> base
            else -> appearance
        }
        val fontIndex = if (fontArray.hasValue(4)) 4 else 3
        val family = node?.fontFamilyName ?: fontArray.getString(fontIndex) ?: "sans-serif"
        val flags = node?.textStyleOverride ?: value(5).getInt(5, Typeface.NORMAL)
        val weight = value(6).getInt(6, -1)
        val fontResource = if (node?.fontFamilyName == null) fontArray.getResourceId(fontIndex, 0) else 0
        // Framework TextAppearances may reference a string (for example sans-serif), not
        // a res/font file. Passing that string ID to getFont crashes About's subtitles.
        var typeface = if (fontResource != 0 && context.resources.getResourceTypeName(fontResource) == "font") ResourcesCompat.getFont(context, fontResource)?.let { Typeface.create(it, flags) }
            ?: Typeface.create(family, flags) else Typeface.create(family, flags)
        if (android.os.Build.VERSION.SDK_INT >= 28 && weight in 1..1000) typeface = Typeface.create(typeface, weight, flags and Typeface.ITALIC != 0)
        val sizeValue = value(1).peekValue(1)
        val textSize = node?.textSizeSp ?: if (sizeValue?.type == TypedValue.TYPE_DIMENSION && sizeValue.complexUnit == TypedValue.COMPLEX_UNIT_SP)
            TypedValue.complexToFloat(sizeValue.data) else with(density) { value(1).getDimension(1, 14.sp.toPx()).toSp().value }
        val paint = Paint().apply { this.typeface = typeface; this.textSize = with(density) { textSize.sp.toPx() } }
        val fm = paint.fontMetricsInt
        val extra = node?.lineSpacingExtraDp?.let { with(density) { it.dp.toPx() } } ?: value(8).getDimension(8, 0f)
        val multiplier = node?.lineSpacingMultiplier ?: value(9).getFloat(9, 1f)
        val lineHeightEnabled = context.obtainOrderedStyledAttributes(intArrayOf(MaterialR.attr.textAppearanceLineHeightEnabled)).let {
            try { it.getBoolean(0, true) } finally { it.recycle() }
        }
        val heightArray = when {
            explicit?.hasValue(10) == true || explicit?.hasValue(11) == true -> explicit
            base.hasValue(10) || base.hasValue(11) -> base
            spec?.material == true && lineHeightEnabled -> appearance
            else -> null
        }
        val lineIndex = if (heightArray?.hasValue(10) == true) 10 else 11
        val fixedHeight = heightArray?.takeIf { it.hasValue(lineIndex) }?.getDimensionPixelSize(lineIndex, -1)
        val lineHeight = when {
            fixedHeight != null && fixedHeight >= 0 -> (fixedHeight / paint.textSize).em
            extra != 0f || multiplier != 1f -> ((fm.descent - fm.ascent + Math.round((fm.descent - fm.ascent) * (multiplier - 1f) + extra)) / paint.textSize).em
            else -> TextUnit.Unspecified
        }
        val gravity = value(18).getInt(18, android.view.Gravity.START)
        val align = when (value(19).getInt(19, 1)) {
            2, 5 -> TextAlign.Start; 3, 6 -> TextAlign.End; 4 -> TextAlign.Center
            else -> when (gravity and android.view.Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK) {
                android.view.Gravity.CENTER_HORIZONTAL -> TextAlign.Center
                android.view.Gravity.END -> TextAlign.End
                android.view.Gravity.RIGHT -> TextAlign.Right
                android.view.Gravity.LEFT -> TextAlign.Left
                else -> TextAlign.Start
            }
        }
        val single = value(16).getBoolean(16, false)
        val lines = value(15).getInt(15, -1)
        val maxLines = node?.maxLinesOverride ?: if (single) 1 else if (lines > 0) lines else value(13).getInt(13, Int.MAX_VALUE)
        val minLines = if (single) 1 else if (lines > 0) lines else value(14).getInt(14, 1).coerceAtLeast(1)
        val overflow = if (node?.ellipsizeOverride != null) { if (node.ellipsize) TextOverflow.Ellipsis else TextOverflow.Clip }
            else when (value(17).getInt(17, 0)) { 1 -> TextOverflow.StartEllipsis; 2 -> TextOverflow.MiddleEllipsis; 3 -> TextOverflow.Ellipsis; else -> TextOverflow.Clip }
        return WgTextLook(TextStyle(fontSize = textSize.sp, fontFamily = FontFamily(typeface),
            fontWeight = FontWeight(utilityResolvedWeight(if (android.os.Build.VERSION.SDK_INT >= 28) typeface.weight else 0, weight, family, flags)),
            fontStyle = if (flags and Typeface.ITALIC != 0) FontStyle.Italic else FontStyle.Normal,
            letterSpacing = (node?.letterSpacingEm ?: value(7).getFloat(7, 0f)).em, lineHeight = lineHeight,
            textAlign = align, textDirection = TextDirection.Content,
            platformStyle = PlatformTextStyle(includeFontPadding = value(12).getBoolean(12, true))),
            node?.textColor?.let(ColorStateList::valueOf) ?: value(2).getColorStateList(2) ?: ColorStateList.valueOf(android.graphics.Color.BLACK),
            maxLines.coerceAtLeast(1), minOf(minLines, maxLines.coerceAtLeast(1)), single, overflow,
            node?.isAllCaps == true || value(21).getBoolean(21, value(20).getBoolean(20, false)), value(22).getFloat(22, 1f))
    } finally { appearance.recycle(); explicit?.recycle(); base.recycle() }
}

internal fun wgTextStateSet(node: WgControlState?, pressed: Boolean = false) = intArrayOf(
    if (node?.isEnabled != false) android.R.attr.state_enabled else -android.R.attr.state_enabled,
    if (node?.isSelected == true) android.R.attr.state_selected else -android.R.attr.state_selected,
    if (node?.isChecked == true) android.R.attr.state_checked else -android.R.attr.state_checked,
    if (pressed) android.R.attr.state_pressed else -android.R.attr.state_pressed)

/** Source typography for static labels which have no legacy presentation node. */
@Composable fun WgSourceText(text: CharSequence, source: String, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified) {
    val context = LocalContext.current; val density = LocalDensity.current; val configuration = LocalConfiguration.current
    val look = remember(context, density, configuration, source) { resolveWgText(context, density, source) }
    Text(wgAnnotatedText(if (look.allCaps) text.toString().uppercase() else text), modifier.alpha(look.alpha),
        style = look.style, color = if (color != androidx.compose.ui.graphics.Color.Unspecified) color else androidx.compose.ui.graphics.Color(look.colors.getColorForState(wgTextStateSet(null), look.colors.defaultColor)),
        maxLines = look.maxLines, minLines = look.minLines, softWrap = !look.singleLine, overflow = look.overflow)
}
