/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.appdetails

import com.celzero.bravedns.ui.compose.resources.obtainOrderedStyledAttributes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.utilities.utilityDimensionSp
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.ui.compose.wireguard.wgAnnotatedText
import com.google.android.material.progressindicator.LinearProgressIndicatorSpec

@Composable fun AppIpsRowContent(b: AppIpsRowState) = AppConnectionDetailRow(
    b.acdContainer, b.acdFlag, null, b.acdIpAddress, b.acdDomainName, b.acdCount, b.progress, b.acdDownArrowIv)

@Composable fun AppDomainsRowContent(b: AppDomainsRowState) = AppConnectionDetailRow(
    b.acdContainer, b.acdFlag, b.acdIcon, b.acdDomain, b.acdIpAddress, b.acdCount, b.progress, null)

/** list_item_app_country_details is a separate geometry, not an IP row with its arrow removed. */
@Composable fun AppCountriesRowContent(b: AppCountriesRowState) {
    Column(Modifier.fillMaxWidth().heightIn(min = 50.dp).padding(start = 4.dp, end = 16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
            AppConnectionFlag(b.accFlag, Modifier.padding(start = 2.dp, end = 8.dp), .75f, country = true)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                AppConnectionText(b.accCountryName, Modifier.fillMaxWidth(), R.dimen.large_font_text_view,
                    weight = Typeface.BOLD, lines = 1, overflow = TextOverflow.Ellipsis,
                    explicitColor = themeColor(R.attr.primaryTextColor))
                AppConnectionProgress(b.accProgress, country = true)
            }
            AppConnectionCount(b.accCount, Modifier.padding(start = 10.dp), country = true)
        }
        AppConnectionDivider()
    }
}

/** Shared only where the two original domain/IP layouts have identical attributes. */
@Composable private fun AppConnectionDetailRow(container: WgControlState, flag: WgControlState,
    icon: WgControlState?, title: WgControlState, subtitle: WgControlState, count: WgControlState,
    progress: WgControlState, arrow: WgControlState?) {
    Column(Modifier.fillMaxWidth().clickable(enabled = container.isEnabled && container.isClickable) { container.performClick() }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 50.dp), verticalAlignment = Alignment.CenterVertically) {
            // Domain's icon container is alpha .9; IP's flag itself is alpha .75.
            if (icon != null) Row(Modifier.alpha(.9f), verticalAlignment = Alignment.CenterVertically) {
                if (icon.visibility != View.GONE && icon.drawable != null) Box(Modifier.padding(5.dp).size(32.dp).connectionState(icon)) {
                    DrawableImage(icon.drawable, Modifier.fillMaxSize())
                }
                else AppConnectionFlag(flag, Modifier.padding(5.dp), 1f)
            } else AppConnectionFlag(flag, Modifier.padding(5.dp), .75f)
            Column(Modifier.weight(1f)) {
                // The original title has start ellipsis but no maxLines/singleLine override.
                AppConnectionText(title, Modifier.fillMaxWidth().padding(start = 5.dp).padding(2.dp),
                    R.dimen.large_font_text_view, appearance = android.R.style.TextAppearance_Material_Body1,
                    overflow = TextOverflow.StartEllipsis, explicitColor = themeColor(R.attr.primaryTextColor))
                AppConnectionText(subtitle, Modifier.fillMaxWidth().padding(start = 5.dp).padding(2.dp),
                    R.dimen.default_font_text_view, lines = 3, overflow = TextOverflow.Ellipsis)
                AppConnectionProgress(progress, country = false)
            }
            AppConnectionCount(count)
            // INVISIBLE still reserves the source 16dp drawable + 5dp padding + 5dp margins.
            if (arrow == null || arrow.visibility != View.GONE) Icon(painterResource(R.drawable.ic_arrow_down_small), null,
                Modifier.padding(5.dp).size(26.dp).padding(5.dp).alpha(.3f)
                    .then(if (arrow != null) Modifier.connectionState(arrow) else Modifier), tint = Color.Unspecified)
        }
        AppConnectionDivider()
    }
}

private fun Modifier.connectionState(node: WgControlState): Modifier =
    alpha(if (node.visibility == View.INVISIBLE) 0f else node.alpha)
        .then(if (node.visibility == View.INVISIBLE) Modifier.clearAndSetSemantics {} else Modifier)

@Composable private fun AppConnectionFlag(node: WgControlState, modifier: Modifier, alpha: Float, country: Boolean = false) {
    if (node.visibility == View.GONE) return
    Box(modifier.size(32.dp).alpha(alpha).connectionState(node), contentAlignment = Alignment.Center) {
        Text(wgAnnotatedText(node.text), maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
            color = node.textColor?.let(::Color) ?: connectionTextColor(),
            style = utilityTextStyle(appearance = if (country) 0 else androidx.appcompat.R.style.TextAppearance_AppCompat_Headline,
                size = node.textSizeSp ?: 26f, weight = node.textStyle))
    }
}

@Composable private fun AppConnectionCount(node: WgControlState, modifier: Modifier = Modifier, country: Boolean = false) {
    AppConnectionText(node, modifier.widthIn(min = 40.dp, max = 60.dp)
        .alpha(if (country) .85f else 1f).background(themeColor(R.attr.background)).padding(5.dp),
        R.dimen.extra_large_font_text_view, appearance = android.R.style.TextAppearance_Material_Subhead,
        family = "sans-serif-light", weight = Typeface.BOLD, lines = 1,
        align = if (country) TextAlign.Start else TextAlign.Center,
        explicitColor = if (country) themeColor(R.attr.primaryTextColor) else null)
}

@Composable private fun AppConnectionText(node: WgControlState, modifier: Modifier, dimension: Int,
    appearance: Int = 0, family: String? = null, weight: Int? = null, lines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip, align: TextAlign = TextAlign.Start, explicitColor: Color? = null) {
    if (node.visibility == View.GONE) return
    Text(wgAnnotatedText(node.text), modifier.connectionState(node),
        style = utilityTextStyle(appearance, node.textSizeSp ?: utilityDimensionSp(LocalContext.current, dimension),
            family, node.textStyle.takeIf { it != Typeface.NORMAL } ?: weight),
        color = node.textColor?.let(::Color) ?: explicitColor ?: connectionTextColor(appearance),
        maxLines = minOf(lines, node.maxLines).coerceAtLeast(1), softWrap = lines != 1,
        overflow = if (node.ellipsize) TextOverflow.Ellipsis else overflow, textAlign = align)
}

@Composable private fun connectionTextColor(appearance: Int = 0): Color {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration, appearance) {
        val defaults = context.obtainOrderedStyledAttributes(null, intArrayOf(android.R.attr.textAppearance, android.R.attr.textColor), android.R.attr.textViewStyle, 0)
        val inherited = context.obtainOrderedStyledAttributes(if (appearance != 0) appearance else defaults.getResourceId(0, 0), intArrayOf(android.R.attr.textColor))
        try { Color((if (appearance != 0) inherited.getColorStateList(0) else defaults.getColorStateList(1) ?: inherited.getColorStateList(0))?.defaultColor ?: android.graphics.Color.BLACK) }
        finally { inherited.recycle(); defaults.recycle() }
    }
}

/** Material themes can express trackCornerRadius as either a dimension or a fraction. */
@SuppressLint("RestrictedApi")
internal fun appConnectionProgressMetrics(context: Context, density: Density, country: Boolean): Pair<Dp, Dp> {
    if (country) return 3.dp to 2.dp
    val spec = LinearProgressIndicatorSpec(context, null)
    return with(density) { spec.trackThickness.toDp() to spec.getTrackCornerRadiusInPx().toDp() }
}

@Composable private fun AppConnectionProgress(node: WgControlState, country: Boolean) {
    if (node.visibility == View.GONE) return
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val metrics = remember(context, density, configuration, country) {
        appConnectionProgressMetrics(context, density, country)
    }
    val fraction = (node.progress.toFloat() / node.max.coerceAtLeast(1)).coerceIn(0f, 1f)
    val track = themeColor(R.attr.background)
    val indicator = node.indicatorColor?.let(::Color) ?: themeColor(androidx.appcompat.R.attr.colorPrimary)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val margins = if (country) PaddingValues(top = 5.dp, bottom = 2.dp)
        else PaddingValues(start = 7.dp, top = 5.dp, end = 5.dp, bottom = 5.dp)
    Canvas(Modifier.fillMaxWidth().padding(margins).connectionState(node).alpha(if (country) .9f else .75f)
        .then(if (country) Modifier else Modifier.padding(3.dp)).height(metrics.first)
        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) }) {
        val radius = CornerRadius(minOf(metrics.second.toPx(), size.height / 2f))
        drawRoundRect(track, cornerRadius = radius)
        val width = size.width * fraction
        drawRoundRect(indicator, topLeft = Offset(if (rtl) size.width - width else 0f, 0f),
            size = Size(width, size.height), cornerRadius = radius)
    }
}

@Composable private fun AppConnectionDivider() {
    HorizontalDivider(Modifier.padding(vertical = 3.dp), thickness = .75.dp, color = themeColor(R.attr.divider))
}
