/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.networksheets

import com.celzero.bravedns.ui.compose.resources.obtainOrderedStyledAttributes

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import kotlin.math.roundToInt
import androidx.compose.ui.unit.*
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.wireguard.setWgContent
import com.celzero.bravedns.ui.compose.wireguard.wgAnnotatedText
import com.celzero.bravedns.ui.compose.wireguard.wgTextDimensionSp
import com.celzero.bravedns.util.UIUtils

/** Values from dialog_info_rules_layout, not a replacement Material3 title/body typography. */
data class OrbotInfoTypography(val sizeSp: Float, val sizePx: Float, val typeface: Typeface, val letterSpacing: Float, val color: Int,
    val paddingStartDp: Float, val paddingEndDp: Float) {
    fun textStyle() = TextStyle(fontSize = sizeSp.sp, fontFamily = FontFamily(typeface),
        fontWeight = if (typeface.isBold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (typeface.isItalic) FontStyle.Italic else FontStyle.Normal,
        letterSpacing = letterSpacing.em, textDirection = TextDirection.Content, platformStyle = PlatformTextStyle(includeFontPadding = true))
    fun lineHeightPx(extraPx: Float = 0f, multiplier: Float = 1f): Float = TextPaint().let {
        it.typeface = typeface
        it.textSize = sizePx
        it.fontSpacing * multiplier + extraPx
    }
}

fun resolveOrbotInfoTypography(context: Context, description: Boolean): OrbotInfoTypography {
    val appearance = if (description) androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead else androidx.appcompat.R.style.TextAppearance_AppCompat_Headline
    val dimension = if (description) R.dimen.large_font_text_view else R.dimen.heading_font_text_view
    val attrs = intArrayOf(android.R.attr.fontFamily, android.R.attr.textStyle, android.R.attr.letterSpacing, android.R.attr.typeface, android.R.attr.textFontWeight)
    val defaults = context.obtainOrderedStyledAttributes(null, attrs, android.R.attr.textViewStyle, 0)
    val widget = context.obtainOrderedStyledAttributes(androidx.appcompat.R.style.Widget_AppCompat_TextView_SpinnerItem, attrs)
    val values = context.obtainOrderedStyledAttributes(appearance, attrs)
    fun indexValue(index: Int) = when { values.hasValue(index) -> values; widget.hasValue(index) -> widget; else -> defaults }
    val flags = indexValue(1).getInt(1, Typeface.NORMAL)
    val family = indexValue(0).getString(0) ?: when (indexValue(3).getInt(3, 1)) { 2 -> "serif"; 3 -> "monospace"; else -> "sans-serif" }
    var typeface = Typeface.create(family, flags)
    val weight = indexValue(4).getInt(4, -1)
    if (android.os.Build.VERSION.SDK_INT >= 28 && weight >= 0) typeface = Typeface.create(typeface, weight.coerceIn(1, 1000), flags and Typeface.ITALIC != 0)
    val spacing = indexValue(2).getFloat(2, 0f)
    values.recycle(); widget.recycle(); defaults.recycle()
    val colorValue = context.obtainOrderedStyledAttributes(intArrayOf(R.attr.primaryTextColor))
    val color = colorValue.getColor(0, android.graphics.Color.BLACK)
    colorValue.recycle()
    // SpinnerItem defines relative padding. It takes precedence over the description's
    // android:padding=5dp, which only replaces its absolute/top/bottom padding.
    val padding = context.obtainOrderedStyledAttributes(androidx.appcompat.R.style.Widget_AppCompat_TextView_SpinnerItem,
        intArrayOf(android.R.attr.paddingStart, android.R.attr.paddingEnd))
    val density = context.resources.displayMetrics.density
    val fallback = if (description) 5f else 0f
    val start = padding.getDimension(0, fallback * density) / density
    val end = padding.getDimension(1, fallback * density) / density
    padding.recycle()
    return OrbotInfoTypography(wgTextDimensionSp(context, dimension), context.resources.getDimension(dimension), typeface, spacing, color, start, end)
}

/** Preserve the non-interactive HTML spans from the original selectable TextView. */
fun orbotInfoText(value: CharSequence): AnnotatedString = AnnotatedString.Builder(wgAnnotatedText(value)).apply {
    if (value is android.text.Spanned) value.getSpans(0, value.length, Any::class.java).forEach { span ->
        val start = value.getSpanStart(span); val end = value.getSpanEnd(span)
        if (start >= 0 && end > start) when (span) {
            is android.text.style.StyleSpan -> addStyle(SpanStyle(fontStyle = if (span.style and Typeface.ITALIC != 0) FontStyle.Italic else FontStyle.Normal), start, end)
            is android.text.style.UnderlineSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
            is android.text.style.StrikethroughSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
        }
    }
}.toAnnotatedString()

@Composable
fun OrbotInfoContent(title: String, description: CharSequence, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val heading = remember(context, density) { resolveOrbotInfoTypography(context, false) }
    val body = remember(context, density) { resolveOrbotInfoTypography(context, true) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            IconButton(onDismiss, Modifier.align(Alignment.End)) {
                com.celzero.bravedns.ui.compose.utilities.utilityImage(android.R.drawable.ic_menu_close_clear_cancel, Modifier.size(30.dp), stringResource(R.string.lbl_dismiss))
            }
            Text(title, Modifier.testTag("orbot_info_title").padding(start = heading.paddingStartDp.dp, end = heading.paddingEndDp.dp), color = Color(heading.color), style = heading.textStyle())
            SelectionContainer(Modifier.padding(top = 10.dp)) {
                Text(orbotInfoText(description), Modifier.testTag("orbot_info_description").padding(start = body.paddingStartDp.dp, end = body.paddingEndDp.dp, top = 5.dp, bottom = 5.dp), color = Color(body.color),
                    style = sheetLineSpacing(body.textStyle(), extra = 5.dp))
            }
        }
    }
}

/** TextView lineSpacingMultiplier applies to font metrics, not directly to the font's sp size. */
@Composable
internal fun sheetLineSpacing(style: TextStyle, multiplier: Float = 1f, extra: Dp = 0.dp): TextStyle {
    val density = LocalDensity.current
    val typeface = LocalFontFamilyResolver.current.resolve(style.fontFamily, style.fontWeight ?: FontWeight.Normal,
        style.fontStyle ?: FontStyle.Normal).value as Typeface
    val fontPx = with(density) { style.fontSize.toPx() }
    val paint = TextPaint().apply { this.typeface = typeface; textSize = fontPx }
    val metrics = paint.fontMetricsInt
    val naturalLine = metrics.descent - metrics.ascent
    // StaticLayout rounds the extra spacing around integer font metrics. Compose's sp
    // lineHeight scales relative to fontSize on Android 14+, so px -> nonlinear sp
    // applies the scale twice. Em keeps this exact physical baseline step at any scale.
    val step = naturalLine + (naturalLine * (multiplier - 1f) + with(density) { extra.toPx() }).roundToInt()
    return style.copy(lineHeight = (step / fontPx).em,
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
            androidx.compose.ui.text.style.LineHeightStyle.Alignment.Top,
            androidx.compose.ui.text.style.LineHeightStyle.Trim.LastLineBottom))
}

fun showOrbotInfoDialog(activity: Activity, context: Context, title: String, description: CharSequence) {
    val dialog = Dialog(context, R.style.App_Dialog_NoDim)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setCancelable(true)
    dialog.setCanceledOnTouchOutside(true)
    dialog.setWgContent(activity) { OrbotInfoContent(title, description) { dialog.dismiss() } }
    dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    dialog.window?.setDimAmount(0f)
    dialog.show()
    dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    UIUtils.capDialogWidth(dialog)
}
