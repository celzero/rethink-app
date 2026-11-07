/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.controls

import com.celzero.bravedns.ui.compose.resources.obtainOrderedStyledAttributes

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.view.ContextThemeWrapper
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.utilities.utilityDimensionSp
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import com.google.android.material.R as MaterialR
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** Text-only hosts of @style/TabbedLayoutDefault. Reads the original MDC style hierarchy and
 * paints its indicator/background resources on Compose Canvas; no TabLayout, View, or XML inflation.
 * Selection/clicks remain owned by the host pager. There is no Material3 default divider or pill.
 */
@Composable
fun OriginalTabStrip(selectedIndex: Int, labels: List<String>, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, pageOffset: Float = 0f) {
    if (labels.isEmpty()) return
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val look = remember(context, density, configuration) { resolveOriginalTabs(context) }
    check(look.mode == 0) { "TabbedLayoutDefault must retain its source scrollable mode" }
    val scroll = rememberScrollState()
    val measurer = rememberTextMeasurer()
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val locale = LocalLocale.current.platformLocale
    CompositionLocalProvider(LocalContext provides look.context) {
        val regular = utilityTextStyle(look.textAppearance)
        val selected = utilityTextStyle(look.selectedTextAppearance)
        BoxWithConstraints(modifier.fillMaxWidth().drawTabDrawable(look.background)) {
            val viewport = with(density) { maxWidth.roundToPx() }
            val maxTab = (if (look.maxWidth > 0) look.maxWidth else viewport - with(density) { 56.dp.roundToPx() }).coerceAtLeast(1)
            val padding = look.paddingStart + look.paddingEnd
            val labelWidth = (maxTab - padding).coerceAtLeast(1)
            val displayLabels = labels.map { if (look.allCaps) it.uppercase(locale) else it }
            val textStyles = displayLabels.mapIndexed { index, text ->
                val style = if (index == selectedIndex) selected else regular
                val measured = measurer.measure(text, style, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = labelWidth))
                if (measured.lineCount > 1) style.copy(fontSize = look.multiLineSize.sp) else style
            }
            val labelWidths = displayLabels.mapIndexed { index, text ->
                measurer.measure(text, textStyles[index], maxLines = 2,
                    overflow = TextOverflow.Ellipsis, constraints = Constraints(maxWidth = labelWidth)).size.width
            }
            val naturalWidths = labelWidths.map { (it + padding).coerceIn(look.minWidth.coerceAtMost(maxTab), maxTab) }
            val widths = originalTabWidths(naturalWidths, viewport, with(density) { 16.dp.roundToPx() }, look.gravity)
            val edge = (look.contentStart - look.paddingStart).coerceAtLeast(0)
            val total = maxOf(viewport, edge + widths.sum())
            val contentStart = edge + if (look.gravity == 1) (total - edge - widths.sum()) / 2 else 0
            val starts = widths.indices.map { contentStart + widths.take(it).sum() }
            val position = (selectedIndex + pageOffset).coerceIn(0f, (labels.size - 1).toFloat())
            val first = floor(position).toInt()
            val second = (first + 1).coerceAtMost(labels.lastIndex)
            val fraction = position - first
            val selectedCenter = starts[first] + widths[first] / 2f + fraction *
                (starts[second] + widths[second] / 2f - starts[first] - widths[first] / 2f)
            LaunchedEffect(selectedCenter, viewport, total) {
                // Logical offsets keep selection in view in both LTR and RTL scroll containers.
                scroll.scrollTo((selectedCenter - viewport / 2f).roundToInt().coerceIn(0, (total - viewport).coerceAtLeast(0)))
            }
            Row(Modifier.horizontalScroll(scroll).width(with(density) { total.toDp() }).height(48.dp)
                .drawBehind {
                    val indicator = look.indicator ?: return@drawBehind
                    fun bounds(index: Int): Pair<Float, Float> {
                        val left = if (rtl) total - starts[index] - widths[index] else starts[index]
                        val width = if (look.indicatorFullWidth) widths[index] else maxOf(24.dp.roundToPx(), labelWidths[index])
                        val indicatorLeft = left + (widths[index] - width) / 2f
                        return indicatorLeft to indicatorLeft + width
                    }
                    val (fromLeft, fromRight) = bounds(first)
                    val (toLeft, toRight) = bounds(second)
                    val edges = originalTabIndicatorEdges(fromLeft, fromRight, toLeft, toRight, fraction, look.animationMode)
                    val h = (if (look.indicatorHeight >= 0) look.indicatorHeight else indicator.intrinsicHeight).coerceAtLeast(0)
                    val top = when (look.indicatorGravity) { 1 -> (size.height - h) / 2f; 2, 3 -> 0f; else -> size.height - h }
                    val bottom = if (look.indicatorGravity == 3) size.height else top + h
                    indicator.setBounds(edges.first.roundToInt(), top.roundToInt(), edges.second.roundToInt(), bottom.roundToInt())
                    drawIntoCanvas { indicator.draw(it.nativeCanvas) }
                }.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(with(density) { contentStart.toDp() }))
                displayLabels.forEachIndexed { index, text ->
                    val states = intArrayOf(android.R.attr.state_enabled,
                        if (index == selectedIndex) android.R.attr.state_selected else -android.R.attr.state_selected)
                    val interaction = remember { MutableInteractionSource() }
                    val color = if (index == selectedIndex) look.selectedTextColor else
                        look.textColors?.getColorForState(states, look.textColors.defaultColor) ?: android.graphics.Color.BLACK
                    Box(Modifier.width(with(density) { widths[index].toDp() }).fillMaxHeight()
                        .drawTabDrawable(look.tabBackground, states)
                        .selectable(index == selectedIndex, role = Role.Tab, interactionSource = interaction,
                            indication = look.rippleColors?.let { ripple(bounded = !look.unboundedRipple,
                                color = Color(it.getColorForState(states, it.defaultColor))) }, onClick = { onSelect(index) })
                        .padding(start = with(density) { look.paddingStart.toDp() }, end = with(density) { look.paddingEnd.toDp() },
                            top = with(density) { look.paddingTop.toDp() }, bottom = with(density) { look.paddingBottom.toDp() }),
                        contentAlignment = Alignment.Center) {
                        Text(text, style = textStyles[index], color = Color(color), maxLines = 2,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

/** Center-gravity TabLayout equalizes to the largest natural tab only when it fits its gutters. */
internal fun originalTabWidths(natural: List<Int>, viewport: Int, gutter: Int, gravity: Int): List<Int> {
    val largest = natural.maxOrNull() ?: return emptyList()
    return if (gravity == 1 && largest * natural.size <= viewport - 2 * gutter) natural.map { largest } else natural
}

/** Source elastic indicator moves its leading/trailing edges separately, including RTL. */
internal fun originalTabIndicatorEdges(left: Float, right: Float, targetLeft: Float, targetRight: Float,
    fraction: Float, mode: Int): Pair<Float, Float> {
    val amount = fraction.coerceIn(0f, 1f)
    val slow = if (mode == 1) (1.0 - cos(amount * Math.PI / 2.0)).toFloat() else amount
    val fast = if (mode == 1) sin(amount * Math.PI / 2.0).toFloat() else amount
    val movingRight = targetLeft >= left
    return (left + (targetLeft - left) * if (movingRight) slow else fast) to
        (right + (targetRight - right) * if (movingRight) fast else slow)
}

private fun Modifier.drawTabDrawable(drawable: Drawable?, state: IntArray = intArrayOf(android.R.attr.state_enabled)): Modifier =
    if (drawable == null) this else drawBehind {
        drawable.state = state
        drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }

internal data class OriginalTabLook(val context: Context, val textAppearance: Int, val selectedTextAppearance: Int,
    val allCaps: Boolean, val textColors: ColorStateList?, val selectedTextColor: Int,
    val rippleColors: ColorStateList?, val iconTint: ColorStateList?, val background: Drawable?, val tabBackground: Drawable?,
    val indicator: Drawable?, val indicatorHeight: Int, val indicatorGravity: Int, val indicatorFullWidth: Boolean,
    val animationMode: Int, val mode: Int, val gravity: Int, val contentStart: Int,
    val paddingStart: Int, val paddingEnd: Int, val paddingTop: Int, val paddingBottom: Int,
    val minWidth: Int, val maxWidth: Int, val multiLineSize: Float, val unboundedRipple: Boolean)

internal fun resolveOriginalTabs(base: Context): OriginalTabLook {
    val overlay = base.obtainOrderedStyledAttributes(R.style.TabbedLayoutDefault, intArrayOf(MaterialR.attr.materialThemeOverlay))
        .let { try { it.getResourceId(0, 0) } finally { it.recycle() } }
    val context = if (overlay == 0) base else ContextThemeWrapper(base, overlay)
    val attrs = intArrayOf(MaterialR.attr.tabTextAppearance, MaterialR.attr.tabSelectedTextAppearance,
        MaterialR.attr.tabTextColor, MaterialR.attr.tabSelectedTextColor, MaterialR.attr.tabRippleColor,
        MaterialR.attr.tabIconTint, android.R.attr.background, MaterialR.attr.tabBackground,
        MaterialR.attr.tabIndicator, MaterialR.attr.tabIndicatorColor, MaterialR.attr.tabIndicatorHeight,
        MaterialR.attr.tabIndicatorGravity, MaterialR.attr.tabIndicatorFullWidth, MaterialR.attr.tabIndicatorAnimationMode,
        MaterialR.attr.tabMode, MaterialR.attr.tabGravity, MaterialR.attr.tabContentStart, MaterialR.attr.tabPadding,
        MaterialR.attr.tabPaddingStart, MaterialR.attr.tabPaddingEnd, MaterialR.attr.tabPaddingTop, MaterialR.attr.tabPaddingBottom,
        MaterialR.attr.tabMinWidth, MaterialR.attr.tabMaxWidth, MaterialR.attr.tabUnboundedRipple)
    val a = context.obtainOrderedStyledAttributes(R.style.TabbedLayoutDefault, attrs)
    try {
        val textAppearance = a.getResourceId(0, R.style.TabTextDefault)
        val selectedAppearance = a.getResourceId(1, textAppearance)
        val text = context.obtainOrderedStyledAttributes(textAppearance, intArrayOf(android.R.attr.textColor, androidx.appcompat.R.attr.textAllCaps))
        val colors: ColorStateList?
        val allCaps: Boolean
        try { colors = a.getColorStateList(2) ?: text.getColorStateList(0); allCaps = text.getBoolean(1, false) }
        finally { text.recycle() }
        fun drawable(index: Int): Drawable? {
            val resource = a.getResourceId(index, 0)
            return (if (resource != 0) AppCompatResources.getDrawable(context, resource) else a.getDrawable(index))?.mutate()
        }
        val indicator = drawable(8)?.also { it.setTint(a.getColor(9, android.graphics.Color.TRANSPARENT)) }
        val padding = a.getDimensionPixelSize(17, 0)
        return OriginalTabLook(context, textAppearance, selectedAppearance, allCaps, colors,
            a.getColor(3, colors?.getColorForState(intArrayOf(android.R.attr.state_selected), colors.defaultColor) ?: android.graphics.Color.BLACK),
            a.getColorStateList(4), a.getColorStateList(5), drawable(6), drawable(7), indicator,
            a.getDimensionPixelSize(10, -1), a.getInt(11, 0), a.getBoolean(12, true), a.getInt(13, 0),
            a.getInt(14, 0), a.getInt(15, 1), a.getDimensionPixelSize(16, 0),
            a.getDimensionPixelSize(18, padding), a.getDimensionPixelSize(19, padding),
            a.getDimensionPixelSize(20, padding), a.getDimensionPixelSize(21, padding),
            a.getDimensionPixelSize(22, context.resources.getDimensionPixelSize(MaterialR.dimen.design_tab_scrollable_min_width)),
            a.getDimensionPixelSize(23, -1), utilityDimensionSp(context, MaterialR.dimen.design_tab_text_size_2line), a.getBoolean(24, false))
    } finally { a.recycle() }
}
