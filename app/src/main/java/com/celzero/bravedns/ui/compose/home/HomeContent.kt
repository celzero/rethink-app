/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.home

import androidx.compose.ui.platform.LocalLocale

import com.celzero.bravedns.ui.compose.controls.*
import android.content.res.ColorStateList
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.graphics.Typeface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.*
import androidx.core.graphics.drawable.toBitmap
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max

/** Native content can be tested with presentation-only callbacks and a plain Application. */
@Composable
fun HomeContent(b: HomePresentation) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    // The Activity shell reserves the measured bottom-navigation viewport.
    Column(Modifier.fillMaxSize().background(themeColor(R.attr.background)), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            ProtectionRow(b)
            if (wide) {
                WideHomeCards(b)
            } else {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    DnsCard(b, Modifier.weight(.65f), false)
                    ThroughputCard(b, Modifier.weight(.35f).padding(end = 2.dp).fillMaxHeight(), false)
                }
                Row(Modifier.fillMaxWidth().padding(start = 2.dp).heightIn(min = 120.dp), verticalAlignment = Alignment.CenterVertically) {
                    RulesCard(b, Modifier.weight(1f), false)
                    ProxyCard(b, Modifier.weight(1f).padding(end = 2.dp), false)
                }
                LogsCard(b, false, Modifier.fillMaxWidth().padding(horizontal = 2.dp))
                AppsCard(b, false, Modifier.fillMaxWidth().padding(horizontal = 2.dp))
            }
        }
        Controls(b, wide)
    }
    HomeDialogs(b)
}

/** Match w600dp's two guidelines and bottom barrier without intrinsic measurement of pagers. */
@Composable
private fun WideHomeCards(b: HomePresentation) {
    Layout(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), content = {
        Box(propagateMinConstraints = true) { DnsCard(b, Modifier.fillMaxWidth().padding(start = 2.dp), true) }
        Box(propagateMinConstraints = true) { ThroughputCard(b, Modifier.fillMaxWidth(), true) }
        Box(propagateMinConstraints = true) { RulesCard(b, Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp), true) }
        Box(propagateMinConstraints = true) {
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProxyCard(b, Modifier.fillMaxWidth(), true)
                AppsCard(b, true)
            }
        }
        Box(propagateMinConstraints = true) { LogsCard(b, true, Modifier.fillMaxWidth().padding(start = 2.dp, end = 8.dp)) }
    }) { children, constraints ->
        val width = constraints.maxWidth
        val split = (width * .58f).toInt()
        val dnsSplit = (width * .406f).toInt()
        fun widthConstraints(value: Int) = Constraints(minWidth = value, maxWidth = value)
        val rules = children[2].measure(widthConstraints(width - split))
        val dns = children[0].measure(Constraints.fixed(dnsSplit, rules.height))
        val throughput = children[1].measure(Constraints.fixed(split - dnsSplit, rules.height))
        val right = children[3].measure(widthConstraints(width - split))
        // Normal source geometry fills to the proxy/apps bottom barrier. If translated or
        // enlarged log content needs more space, let the scrollable page grow rather than clip it.
        val logs = children[4].measure(widthConstraints(split).copy(minHeight = right.height))
        val secondRowY = rules.height + 12.dp.roundToPx()
        layout(width, secondRowY + maxOf(right.height, logs.height)) {
            dns.placeRelative(0, 0)
            throughput.placeRelative(dnsSplit, 0)
            rules.placeRelative(split, 0)
            right.placeRelative(split, secondRowY)
            logs.placeRelative(0, secondRowY)
        }
    }
}

@Composable
private fun ProtectionRow(b: HomePresentation) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, start = 26.dp, end = 26.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HomeText(b.fhsProtectionLevelTxt, Modifier.weight(1f).clickable { b.fhsProtectionLevelTxt.performClick() })
        HomeText(b.fhsActiveSinceTxt, Modifier.widthIn(max = 120.dp))
    }
}

@Composable
private fun HomeCard(node: HomeNode, modifier: Modifier = Modifier, radius: Dp = 18.dp, wide: Boolean = false, elevationOverride: Dp? = null, content: @Composable ColumnScope.() -> Unit) {
    if (!node.isVisible) return
    val shape = RoundedCornerShape(radius)
    val stroke = when (val color = node.strokeColor) { is Int -> color; is ColorStateList -> color.defaultColor; else -> null }
    var m = modifier.homeTourAnchor(node.id).padding(rememberHomeCardPadding(radius, elevationOverride))
        .testTag(node.context.resources.getResourceEntryName(node.id))
        .shadow(rememberHomeCardElevation(elevationOverride), shape, clip = false)
        .clip(shape).background(themeColor(if (wide) R.attr.colorSurfaceContainer else androidx.cardview.R.attr.cardBackgroundColor))
    if (stroke != null && node.strokeWidth > 0) m = m.border(with(LocalDensity.current) { node.strokeWidth.toDp() }, Color(stroke), shape)
    if (node.click != null) m = m.clickable(enabled = node.isEnabled, onClick = node::performClick)
    Column(m, content = content)
}

@Composable
private fun DnsCard(b: HomePresentation, modifier: Modifier, wide: Boolean) {
    HomeCard(b.fhsCardDnsLl, modifier, 24.dp, wide) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = if (wide) 56.dp else 40.dp), verticalArrangement = Arrangement.Center) {
            HomeText(b.fhsCardDnsConnectedDns)
            HomeText(b.fhsCardDnsLatency)
        }
    }
}

@Composable
private fun ThroughputCard(b: HomePresentation, modifier: Modifier, wide: Boolean) {
    HomeCard(b.fhsThroughputLl, modifier, 24.dp, wide) {
        Column(Modifier.fillMaxWidth().weight(1f, false).padding(8.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            HomeText(b.fhsInternetSpeed, align = TextAlign.Center)
            HomeText(b.fhsInternetSpeedUnit, Modifier.padding(top = 2.dp), align = TextAlign.Center)
        }
    }
}

@Composable
private fun RulesCard(b: HomePresentation, modifier: Modifier, wide: Boolean) {
    val pages = b.fhsFirewallRulesPager.adapter as? HomeRulesPages
    HomeCard(b.fhsCardFirewallRulesCard, modifier.height(if (wide) 80.dp else 84.dp).alpha(b.fhsFirewallBadgesRow.alpha).homeTourAnchor(R.id.fhs_card_firewall_ll), wide = wide, elevationOverride = 0.dp) {
        Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 8.dp)) {
            if (pages != null) NativePager(b.fhsFirewallRulesPager, Modifier.fillMaxWidth().weight(1f, false)) { page ->
                Row(Modifier.fillMaxWidth().clickable { pages.onRulePageTapped?.invoke(page) }.padding(start = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(pages.counts[page], color = themeColor(R.attr.primaryTextColor), style = utilityTextStyle(size = 16f, family = "monospace", weight = Typeface.BOLD), maxLines = 1)
                    BasicText(pages.labels[page].replaceFirstChar { if (it.isLowerCase()) it.titlecase(LocalLocale.current.platformLocale) else it.toString() }, Modifier.weight(1f).padding(start = 6.dp), style = utilityTextStyle(size = 12f, spacing = .02f).copy(color = themeColor(R.attr.primaryTextColor)), maxLines = 1, overflow = TextOverflow.Ellipsis, autoSize = TextAutoSize.StepBased(8.sp, 12.sp, 1.sp))
                }
            }
            else Spacer(Modifier.fillMaxWidth().weight(1f))
            Dots(listOf(b.fhsRulesDot0, b.fhsRulesDot1, b.fhsRulesDot2), wide)
        }
    }
}

@Composable
private fun ProxyCard(b: HomePresentation, modifier: Modifier, wide: Boolean) {
    HomeCard(b.fhsCardProxyLl, modifier, wide = wide) {
        Column(Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(12.dp)) {
            HomeText(b.fhsCardOtherProxyCount, animated = true)
            Spacer(Modifier.height(18.dp))
            if (b.fhsProxyHealthContainer.isVisible) {
                val counts = listOf(b.fhsProxyLiveCount, b.fhsProxyIdleCount, b.fhsProxyFailingCount, b.fhsProxyStoppedCount)
                val bars = listOf(b.fhsProxyBarActive, b.fhsProxyBarIdle, b.fhsProxyBarFailing, b.fhsProxyBarStopped)
                val labels = listOf(R.string.lbl_active, R.string.lbl_idle, R.string.status_failing, R.string.lbl_stopped)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    counts.forEachIndexed { index, count ->
                        if (index != 3 || b.fhsProxyStoppedColumn.isVisible) {
                            val color = bars[index].fillColor?.let(::Color) ?: themeColor(R.attr.primaryLightColorText)
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.fillMaxWidth().height(4.dp).background(color))
                                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(5.dp).background(color, CircleShape))
                                    HomeText(count, Modifier.padding(start = 2.dp))
                                }
                                BasicText(stringResource(labels[index]).uppercase(LocalLocale.current.platformLocale), Modifier.fillMaxWidth(), style = utilityTextStyle(size = 8f, spacing = .05f).copy(color = themeColor(R.attr.primaryLightColorText).copy(alpha = .85f), textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis, autoSize = TextAutoSize.StepBased(6.sp, 8.sp, 1.sp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppsCard(b: HomePresentation, wide: Boolean, modifier: Modifier = Modifier.fillMaxWidth()) {
    HomeCard(b.fhsCardAppsCv, modifier, wide = wide) {
        if (wide) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                AppsCount(b)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppsMetricGrid(b, Modifier.weight(1f).padding(end = 8.dp), 6.dp)
                    AppsArrow()
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AppsCount(b)
                AppsMetricGrid(b, Modifier.weight(1f).padding(start = 12.dp, end = 8.dp), 4.dp)
                AppsArrow()
            }
        }
    }
}

@Composable
private fun AppsCount(b: HomePresentation) {
    Row(verticalAlignment = Alignment.Bottom) {
        HomeText(b.fhsCardAllowedApps)
        HomeText(b.fhsCardAppsAllApps)
        HomeText(b.fhsAppsLabel, Modifier.padding(start = 8.dp))
    }
}

/** Equal-width columns retain upstream's two-row grid even at narrow widths. */
@Composable
private fun AppsMetricGrid(b: HomePresentation, modifier: Modifier, verticalGap: Dp) {
    val nodes = listOf(b.fhsCardAppsBlockedCount, b.fhsCardAppsIsolatedCount, b.fhsCardAppsBypassedCount, b.fhsCardAppsExcludedCount)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(verticalGap)) {
        nodes.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { node -> HomeText(node, Modifier.weight(1f), align = TextAlign.End) }
            }
        }
    }
}

@Composable
private fun AppsArrow() {
    Text("›", Modifier.padding(start = 10.dp).alpha(.8f), color = themeColor(R.attr.primaryLightColorText), style = utilityTextStyle(size = 20f))
}

@Composable
private fun LogsCard(b: HomePresentation, wide: Boolean, modifier: Modifier = Modifier.fillMaxWidth()) {
    val transition = if (b.animateLogs) Modifier.animateContentSize() else Modifier
    HomeCard(b.fhsCardLogsLl, modifier.then(transition), wide = wide) {
        Column(Modifier.fillMaxWidth().padding(if (wide) 20.dp else 16.dp)) {
            if (b.fhsLogsPager.isVisible) {
                Box(Modifier.fillMaxWidth()) {
                    NativePager(b.fhsLogsPager, Modifier.fillMaxWidth().heightIn(min = if (wide) 168.dp else 190.dp)) { page ->
                        if (page == 0) ActivityPage(b.logsPage) else AppsPage(b.topAppsPage)
                    }
                    // Like the baseline FrameLayout, transient dots overlay
                    // the pager and never change the card's measured height.
                    if (b.fhsLogsDots.isVisible) Box(Modifier.align(Alignment.BottomCenter)) {
                        Dots(listOf(b.fhsLogsDot0, b.fhsLogsDot1), wide)
                    }
                }
            }
            if (b.fhsLogsDisabledRow.isVisible) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HomeText(b.fhsLogsDisabledTxt, Modifier.weight(1f))
                    if (b.fhsLogsEnableChip.isVisible) OriginalButton("fragment_home_screen:fhs_logs_enable_chip", onClick = b.fhsLogsEnableChip::performClick) { HomeText(b.fhsLogsEnableChip) }
                }
            }
        }
    }
}

@Composable
private fun NativePager(node: HomePager, modifier: Modifier, content: @Composable (Int) -> Unit) {
    val count = node.adapter?.itemCount ?: return
    if (count == 0) return
    val state = rememberPagerState(initialPage = node.currentItem.coerceIn(0, count - 1), pageCount = { count })
    LaunchedEffect(node.currentItem, count) {
        if (state.currentPage != node.currentItem) state.animateScrollToPage(node.currentItem.coerceIn(0, count - 1))
    }
    LaunchedEffect(state, node) {
        snapshotFlow { state.settledPage }.distinctUntilChanged().collect { node.setCurrentItem(it, true) }
    }
    HorizontalPager(state, modifier.semantics { contentDescription = node.contentDescription?.toString().orEmpty() }) { content(it) }
}

@Composable
private fun Dots(nodes: List<HomeNode>, wide: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        nodes.forEachIndexed { index, node ->
            val color = themeColor(R.attr.primaryLightColorText).copy(alpha = node.alpha)
            if (node.click != null) {
                Box(Modifier.size(32.dp).clickable(onClick = node::performClick).semantics { contentDescription = "${index + 1} / ${nodes.size}" }, contentAlignment = Alignment.Center) { Box(Modifier.size(if (wide) 6.dp else 4.dp).background(color, CircleShape)) }
            } else Box(Modifier.padding(horizontal = 3.dp, vertical = 3.dp).size(if (wide) 6.dp else 4.dp).background(color, CircleShape))
        }
    }
}

@Composable
private fun ActivityPage(page: HomeLogsPage) {
    Column(Modifier.fillMaxWidth().clickable(onClick = page.root::performClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Row(Modifier.weight(1f).padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f).alpha(page.fhsLogsAllowedHeader.alpha)) { HomeText(page.fhsCardAllowedLogsCount); HomeText(page.fhsCardAllowedLogsLabel) }
                Column(Modifier.weight(1f).alpha(page.fhsLogsBlockedHeader.alpha)) { HomeText(page.fhsCardBlockedLogsCount); HomeText(page.fhsCardBlockedLogsLabel) }
            }
            ToggleChips(page.fhsLogsToggleGroup, page.fhsLogsAllowedChip, page.fhsLogsBlockedChip)
        }
        if (page.fhsLogsHourAxis.isVisible) Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            listOf(page.fhsLogsHourStart, page.fhsLogsHourStartPlus6, page.fhsLogsHourStartPlus12, page.fhsLogsHourStartPlus18).forEach { HomeText(it, Modifier.weight(1f)) }
            Text("now", color = themeColor(R.attr.primaryLightColorText).copy(alpha = .55f), fontSize = 10.sp)
        }
        if (page.fhsLogsGrid.isVisible) HomeHeatmapCanvas(page.fhsLogsGrid)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            HomeText(page.fhsCardLogsDuration, Modifier.weight(1f).padding(end = 8.dp))
            if (page.fhsLogsLegend.isVisible) HeatmapLegend(page.fhsLogsGrid.light)
        }
    }
}

@Composable
private fun ToggleChips(group: HomeToggleGroup, allowed: HomeNode, blocked: HomeNode) {
    if (!group.isVisible) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(allowed, blocked).forEach { node ->
            OriginalChip((if (node.id == R.id.fhs_logs_allowed_chip || node.id == R.id.fhs_logs_blocked_chip) "view_home_logs_activity:" else "view_home_logs_apps:") + node.context.resources.getResourceEntryName(node.id), selected = node.isChecked, onClick = { group.select(node) }, enabled = group.isEnabled && node.isEnabled, label = { Text(node.text?.toString().orEmpty()) }, modifier = Modifier.testTag(node.context.resources.getResourceEntryName(node.id)))
        }
    }
}

@Composable
private fun HomeHeatmapCanvas(grid: HomeHeatmap) {
    val base = themeColor(R.attr.primaryLightColorText)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(Modifier.fillMaxWidth().padding(top = 4.dp).height(60.dp).onSizeChanged { grid.width = it.width; grid.height = it.height }
        .pointerInput(grid) { detectTapGestures { point -> grid.onTouch?.invoke(point.x, point.y); grid.performClick() } }
        .semantics(mergeDescendants = true) { contentDescription = grid.contentDescription?.toString().orEmpty(); role = Role.Button; onClick { grid.onKeyboardClick?.invoke(); true } }
        .testTag("home_activity_heatmap")) {
        val alphas = if (grid.light) listOf(0x40, 0x80, 0x80, 0xB3, 0xE6) else listOf(0x24, 0x52, 0x52, 0x85, 0xCC)
        val fractions = listOf(.30f, .78f, .78f, 1f, 1f)
        val cellBase = (60.dp.toPx() - 6 * 2.dp.toPx() * 2) / 6
        for (row in 0 until 6) for (col in 0 until 24) {
            val interval = grid.intervals.getOrNull(col * 6 + row) ?: continue
            val count = if (grid.blocked) interval.blocked else interval.allowed
            val level = if (count <= 0) 0 else minOf(4, log10(count.toDouble()).toInt() + 1)
            val x = (if (rtl) 23 - col else col) + .5f
            drawCircle(base.copy(alpha = alphas[level] / 255f), radius = cellBase * fractions[level] / 2f, center = Offset(x * size.width / 24, (row + .5f) * size.height / 6))
        }
    }
}

@Composable
private fun HeatmapLegend(light: Boolean) {
    val base = themeColor(R.attr.primaryLightColorText)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("less", color = base.copy(alpha = .8f), fontSize = 12.sp)
        listOf(.3f to if (light) 0x40 else 0x24, .78f to if (light) 0x80 else 0x52, 1f to if (light) 0xB3 else 0x85, 1f to if (light) 0xE6 else 0xCC).forEach { (fraction, alpha) ->
            Box(Modifier.size(width = (6 * fraction).dp, height = (3 * fraction).dp).background(base.copy(alpha = alpha / 255f), CircleShape))
        }
        Text("more", color = base.copy(alpha = .8f), fontSize = 12.sp)
    }
}

@Composable
private fun AppsPage(page: HomeTopAppsPage) {
    Column(Modifier.fillMaxWidth().clickable(onClick = page.root::performClick)) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column { HomeText(page.fhsTopAppsWifiValue); HomeText(page.fhsTopAppsWifiLabel) }
                if (page.fhsTopAppsMobileHeader.isVisible) Column { HomeText(page.fhsTopAppsMobileValue); HomeText(page.fhsTopAppsMobileLabel) }
            }
            ToggleChips(page.fhsTopAppsToggleGroup, page.fhsTopAppsAllowedChip, page.fhsTopAppsBlockedChip)
        }
        Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp).heightIn(min = 72.dp), contentAlignment = Alignment.Center) {
            if (page.fhsTopAppsHistogram.isVisible) HomeHistogramCanvas(page.fhsTopAppsHistogram)
            HomeText(page.fhsTopAppsEmpty)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HomeText(page.fhsTopAppsWindow, Modifier.weight(1f))
            Icon(painterResource(R.drawable.ic_firewall_wifi_on), stringResource(R.string.logs_card_apps_legend_wifi), Modifier.size(12.dp), tint = page.fhsTopAppsSwatchWifi.imageTintList?.defaultColor?.let(::Color) ?: themeColor(R.attr.primaryLightColorText))
            Icon(painterResource(R.drawable.ic_firewall_data_on), stringResource(R.string.logs_card_apps_legend_mobile), Modifier.size(12.dp), tint = page.fhsTopAppsSwatchMobile.imageTintList?.defaultColor?.let(::Color) ?: themeColor(R.attr.primaryLightColorText))
        }
    }
}

@Composable
private fun HomeHistogramCanvas(histogram: HomeHistogram) {
    val entries = histogram.entries
    val base = themeColor(R.attr.primaryLightColorText)
    val density = LocalDensity.current
    val icons = remember(entries, density) { entries.map { it.icon?.toBitmap(width = with(density) { 14.dp.roundToPx() }, height = with(density) { 14.dp.roundToPx() })?.asImageBitmap() } }
    // Each slot is an accessible target as well as a touch target. No database
    // or VPN call is possible from this renderer; it only dispatches selection.
    Canvas(Modifier.fillMaxWidth().height(80.dp).background(Color(0x11000000))
        .pointerInput(entries) { detectTapGestures { point ->
            if (entries.isNotEmpty()) {
                val slot = minOf(size.width.toFloat() / entries.size, 40.dp.toPx()).coerceAtLeast(1f)
                val start = ((size.width - slot * entries.size) / 2f).coerceAtLeast(0f)
                val index = ((point.x - start) / slot).toInt()
                if (index in entries.indices) histogram.select(index)
            }
        } }
        .semantics {
            customActions = entries.mapIndexed { index, entry -> CustomAccessibilityAction(entry.appName) { histogram.select(index); true } }
            contentDescription = entries.joinToString { entry -> if (histogram.blocked) "${entry.appName}: ${entry.blockedCount}" else "${entry.appName}: ${entry.unmeteredBytes + entry.meteredBytes}" }
        }.testTag("home_apps_histogram")) {
        if (entries.isEmpty()) return@Canvas
        val slot = minOf(size.width / entries.size, 40.dp.toPx()).coerceAtLeast(1f)
        val start = ((size.width - slot * entries.size) / 2f).coerceAtLeast(0f)
        val barWidth = minOf(10.dp.toPx(), slot - 3.dp.toPx()).coerceAtLeast(2f)
        val barTop = 19.dp.toPx()
        val barHeight = max(1f, size.height - barTop)
        val maxValue = max(1f, entries.maxOf { if (histogram.blocked) it.blockedCount else it.unmeteredBytes + it.meteredBytes }.toFloat())
        fun segmentHeight(value: Long): Float = if (value <= 0) 0f else max(barHeight * value / maxValue, 2.dp.toPx())
        entries.forEachIndexed { index, entry ->
            val center = start + (index + .5f) * slot
            if (index == histogram.selectedIndex) drawRoundRect(Color.Gray.copy(alpha = 0x1A / 255f), Offset(center - slot / 2 + 3.dp.toPx(), 0f), Size((slot - 6.dp.toPx()).coerceAtLeast(1f), size.height), CornerRadius(barWidth))
            val rect = Rect(center - barWidth / 2f, barTop, center + barWidth / 2f, size.height)
            val path = Path().apply { addRoundRect(RoundRect(rect, CornerRadius(barWidth / 2f))) }
            drawRoundRect(base.copy(alpha = (if (histogram.light) 0x1F else 0x24) / 255f), rect.topLeft, rect.size, CornerRadius(barWidth / 2f))
            clipPath(path) {
                var bottom = size.height
                if (histogram.blocked) {
                    val h = segmentHeight(entry.blockedCount)
                    drawRect(Color(histogram.unmeteredColor()), Offset(rect.left, bottom - h), Size(barWidth, h))
                } else {
                    val wifiH = segmentHeight(entry.unmeteredBytes)
                    drawRect(Color(histogram.unmeteredColor()), Offset(rect.left, bottom - wifiH), Size(barWidth, wifiH))
                    bottom -= wifiH
                    val mobileH = segmentHeight(entry.meteredBytes)
                    drawRect(Color(histogram.meteredColor()), Offset(rect.left, bottom - mobileH), Size(barWidth, mobileH))
                }
            }
            icons[index]?.let { icon ->
                val iconSize = 14.dp.toPx()
                val iconRect = Rect(center - iconSize / 2, 0f, center + iconSize / 2, iconSize)
                clipPath(Path().apply { addOval(iconRect) }) {
                    drawImage(icon, topLeft = iconRect.topLeft, alpha = 230 / 255f)
                }
            }
        }
    }
}

@Composable
private fun Controls(b: HomePresentation, wide: Boolean) {
    Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp).heightIn(min = 64.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ControlIcon(b.homeFragmentPauseIcon, R.drawable.ic_pause, stringResource(R.string.notification_action_pause_vpn), wide)
        val node = b.fhsDnsOnOffBtn
        val shape = RoundedCornerShape(28.dp)
        val density = LocalDensity.current
        val stroke = (node.strokeColor as? ColorStateList)?.defaultColor
        Box(Modifier.weight(1f).heightIn(min = 56.dp).homeTourAnchor(R.id.fhs_dns_on_off_btn).testTag("fhs_dns_on_off_btn").clip(shape)
            .background(node.backgroundTintList?.defaultColor?.let(::Color) ?: themeColor(R.attr.accentGood))
            .then(if (node.strokeWidth > 0 && stroke != null) Modifier.border(with(density) { node.strokeWidth.toDp() }, Color(stroke), shape) else Modifier)
            .clickable(enabled = node.isEnabled, role = Role.Button, onClick = node::performClick), contentAlignment = Alignment.Center) {
            HomeText(node, Modifier.padding(horizontal = 12.dp, vertical = 12.dp), align = TextAlign.Center)
            if (b.borderAnimating && b.fhsAnimatedBorderView.isVisible) StartBorder(b, Modifier.matchParentSize())
        }
        ControlIcon(b.homeFragmentBottomSheetIcon, R.drawable.ic_arrow_down, stringResource(R.string.title_settings), wide)
    }
}

@Composable
private fun ControlIcon(node: HomeNode, drawable: Int, description: String, wide: Boolean) {
    Box(Modifier.size(width = 50.dp, height = 56.dp).clip(RoundedCornerShape(16.dp)).background(themeColor(if (wide) R.attr.colorSurfaceContainer else androidx.cardview.R.attr.cardBackgroundColor)).clickable(enabled = node.isEnabled, role = Role.Button, onClick = node::performClick).testTag(node.context.resources.getResourceEntryName(node.id)), contentAlignment = Alignment.Center) {
        Icon(painterResource(drawable), description, Modifier.size(20.dp), tint = themeColor(R.attr.primaryTextColor))
    }
}

@Composable
private fun StartBorder(b: HomePresentation, modifier: Modifier) {
    val accent = themeColor(R.attr.accentGood)
    var elapsed by remember(b.borderStartedAt) { mutableLongStateOf(0L) }
    LaunchedEffect(b.borderStartedAt) {
        while (true) withFrameNanos { elapsed = android.os.SystemClock.elapsedRealtime() - b.borderStartedAt }
    }
    Canvas(modifier) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
        val fraction = (elapsed % 3000L).toFloat() / 3000f
        // Full rainbow cycle in 3 seconds; constant counterclockwise 4-second turn.
        val highlight = Color.hsv((hsv[0] + 180f + fraction * 360f) % 360f, .85f, 1f)
        val rotation = -(elapsed % 4000L) / 4000f * 360f
        // Match RotatingBorderDrawable's 72-sample transparent-to-highlight
        // triangle wave, rebuilding the shader rather than rotating geometry.
        val wrapped = ((rotation % 360f) + 360f) % 360f
        val shift = Math.round(wrapped / 360f * 72).toInt() % 72
        val colors = IntArray(72) { index ->
            val fraction = ((index + shift) % 72) * 2f / 72
            highlight.copy(alpha = if (fraction <= 1f) fraction else 2f - fraction).toArgb()
        }
        val positions = FloatArray(72) { it / 71f }
        val androidShader = android.graphics.SweepGradient(size.width / 2, size.height / 2, colors, positions)
        drawRoundRect(ShaderBrush(androidShader), topLeft = Offset(1.25.dp.toPx(), 1.25.dp.toPx()), size = Size(size.width - 2.5.dp.toPx(), size.height - 2.5.dp.toPx()), cornerRadius = CornerRadius(size.height / 2), style = Stroke(2.5.dp.toPx()))
    }
}

@Composable
private fun HomeText(node: HomeNode, modifier: Modifier = Modifier, align: TextAlign? = null, animated: Boolean = false) {
    if (!node.isVisible) return
    val size = homeTextSizeSp(node.context, node.textSize).sp
    val text = if (node.allCaps) node.text?.toString()?.uppercase(LocalLocale.current.platformLocale).orEmpty() else node.text ?: ""
    val color = node.textColor?.let(::Color) ?: themeColor(R.attr.primaryTextColor)
    val contract = homeTextContract(node.id)
    val style = utilityTextStyle(size = size.value, family = if (node.monospace) "monospace" else contract.family, spacing = contract.tracking)
    val textModifier = modifier.alpha(node.alpha).then(if (contract.marquee) Modifier.basicMarquee() else Modifier)
    if (node.id == R.id.fhs_card_allowed_logs_count || node.id == R.id.fhs_card_blocked_logs_count) {
        BasicText(homeAnnotatedText(text), textModifier, style = style.copy(color = color, textAlign = align ?: TextAlign.Unspecified), maxLines = 1, overflow = TextOverflow.Ellipsis, autoSize = TextAutoSize.StepBased(18.sp, maxOf(size.value, 18f).sp, 1.sp))
    } else if (animated) AnimatedContent(text, textModifier, transitionSpec = { fadeIn(tween(150, 150)) togetherWith fadeOut(tween(150)) }, label = "home status") { value ->
        Text(homeAnnotatedText(value), color = color, style = style, maxLines = contract.lines, overflow = contract.overflow, textAlign = align)
    } else Text(homeAnnotatedText(text), textModifier, color = color, style = style, maxLines = contract.lines, overflow = contract.overflow, textAlign = align)

}

fun homeAnnotatedText(text: CharSequence): AnnotatedString = buildAnnotatedString {
    append(text.toString())
    if (text is Spanned) text.getSpans(0, text.length, Any::class.java).forEach { span ->
        val style = when (span) {
            is ForegroundColorSpan -> SpanStyle(color = Color(span.foregroundColor))
            is RelativeSizeSpan -> SpanStyle(fontSize = span.sizeChange.em)
            is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
            is StyleSpan -> when (span.style) {
                Typeface.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                Typeface.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                Typeface.BOLD_ITALIC -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                else -> SpanStyle()
            }
            else -> SpanStyle()
        }
        addStyle(style, text.getSpanStart(span).coerceAtLeast(0), text.getSpanEnd(span).coerceAtMost(text.length))
    }
}
