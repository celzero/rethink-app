/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.trafficlogs

import android.graphics.Typeface
import android.view.View
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.graphics.drawable.toBitmap
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor

@Composable internal fun logTextSize(dimension: Int): TextUnit = with(LocalDensity.current) { dimensionResource(dimension).toSp() }
@Composable internal fun logFont(name: String): FontFamily = remember(name) { FontFamily(Typeface.create(name, Typeface.NORMAL)) }
@Composable fun LogText(node: LogControlState, modifier: Modifier = Modifier, size: Int = R.dimen.default_font_text_view, lines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip, font: FontFamily? = null, weight: FontWeight? = null, colorAttr: Int = R.attr.primaryTextColor) {
    if (node.visibility == View.GONE) return
    Text(logAnnotated(node.text ?: ""), modifier.alpha(if (node.visibility == View.INVISIBLE) 0f else node.alpha),
        color = node.textColor?.let(::Color) ?: themeColor(colorAttr), fontSize = logTextSize(size), maxLines = lines, overflow = overflow, fontFamily = font, fontWeight = weight)
}
@Composable fun LogDrawable(node: LogControlState, modifier: Modifier, description: String? = null) {
    if (node.visibility == View.GONE) return
    val bitmap = remember(node.drawable, node.colorFilter) { node.drawable?.mutate()?.apply { colorFilter = node.colorFilter }?.toBitmap(64, 64)?.asImageBitmap() }
    if (bitmap != null) Image(bitmap, description, modifier.alpha(if (node.visibility == View.INVISIBLE) 0f else node.alpha)) else Spacer(modifier)
}
@Composable fun ConnectionLogRow(b: ConnectionRowState) {
    val light = logFont("sans-serif-light")
    val condensed = logFont("sans-serif-condensed")
    Column(Modifier.fillMaxWidth().background(themeColor(R.attr.background)).clickable { b.connectionParentLayout.performClick() }) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(1.5.dp).fillMaxHeight().background(if (b.connectionStatusIndicator.isVisible) b.connectionStatusIndicator.backgroundColor?.let(::Color) ?: Color.Transparent else Color.Transparent))
            LogDrawable(b.connectionAppIcon, Modifier.padding(10.dp).size(32.dp).alpha(.9f), stringResource(R.string.apps_icon_content_desc))
            Column(Modifier.weight(1f).padding(top = 5.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    LogText(b.connectionAppName, Modifier.weight(.65f).padding(start = 8.dp, end = 10.dp), lines = 2, overflow = TextOverflow.StartEllipsis, font = light, colorAttr = R.attr.primaryLightColorText)
                    Box(Modifier.weight(.2f), contentAlignment = Alignment.Center) { LogText(b.connLatencyTxt, Modifier.background(themeColor(R.attr.background)).padding(5.dp), size = R.dimen.mini_font_text_view, lines = 1, font = light, weight = FontWeight.Bold) }
                    Box(Modifier.weight(.15f), contentAlignment = Alignment.Center) { Text(b.connectionFlag.text?.toString().orEmpty(), Modifier.alpha(.9f), fontSize = 26.sp, maxLines = 1) }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(.65f).padding(start = 8.dp, end = 10.dp)) {
                        LogText(b.connectionIpAddress, size = R.dimen.large_font_text_view, font = FontFamily.Monospace, lines = 1, overflow = TextOverflow.Ellipsis)
                        LogText(b.connectionDomain, Modifier.alpha(.7f), font = light, lines = 1, overflow = TextOverflow.Ellipsis, colorAttr = R.attr.primaryLightColorText)
                    }
                    Box(Modifier.weight(.2f), contentAlignment = Alignment.Center) { LogText(b.connectionResponseTime, font = condensed, lines = 1) }
                    Box(Modifier.weight(.15f), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_keyboard_arrow_down), null, tint = themeColor(R.attr.primaryTextColor)) }
                }
                if (b.connectionSummaryLl.isVisible) Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LogText(b.connectionDataUsage, Modifier.weight(.65f).padding(start = 8.dp, end = 10.dp), font = light, lines = 1, overflow = TextOverflow.StartEllipsis, colorAttr = R.attr.primaryLightColorText)
                    Box(Modifier.weight(.2f), contentAlignment = Alignment.Center) { LogText(b.connectionDuration, font = light, lines = 1, colorAttr = R.attr.primaryLightColorText) }
                    Box(Modifier.weight(.15f), contentAlignment = Alignment.Center) { LogText(b.connectionDelay, lines = 1) }
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = themeColor(R.attr.divider))
    }
}
@Composable fun DnsLogRow(b: DnsRowState) {
    val light = logFont("sans-serif-light")
    val condensed = logFont("sans-serif-condensed")
    Column(Modifier.fillMaxWidth().background(themeColor(R.attr.background)).clickable { b.dnsParentLayout.performClick() }) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(1.5.dp).fillMaxHeight().background(if (b.dnsStatusIndicator.isVisible) b.dnsStatusIndicator.backgroundColor?.let(::Color) ?: Color.Transparent else Color.Transparent))
            if (b.dnsFavIcon.isVisible) LogDrawable(b.dnsFavIcon, Modifier.padding(10.dp).size(32.dp).alpha(.9f), stringResource(R.string.fav_icon))
            if (b.dnsFlag.isVisible) Box(Modifier.padding(10.dp).size(32.dp), contentAlignment = Alignment.Center) { Text(b.dnsFlag.text?.toString().orEmpty(), Modifier.alpha(.9f), fontSize = 25.sp, maxLines = 1) }
            Column(Modifier.weight(1f).padding(top = 5.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    LogText(b.dnsQueryType, Modifier.weight(.65f).padding(start = 8.dp, end = 10.dp), lines = 2, overflow = TextOverflow.StartEllipsis, font = light, colorAttr = R.attr.primaryLightColorText)
                    Box(Modifier.weight(.2f), contentAlignment = Alignment.Center) { LogText(b.dnsTypeName, Modifier.background(themeColor(R.attr.background)).padding(5.dp), size = R.dimen.mini_font_text_view, lines = 1, font = light, weight = FontWeight.Bold) }
                    Box(Modifier.weight(.15f), contentAlignment = Alignment.Center) { LogDrawable(b.dnsAppIcon, Modifier.padding(3.dp).size(24.dp).alpha(.9f), stringResource(R.string.apps_icon_content_desc)) }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(.65f).padding(start = 8.dp, end = 10.dp)) {
                        LogText(b.dnsQuery, size = R.dimen.large_font_text_view, font = FontFamily.Monospace, lines = 1, overflow = TextOverflow.Ellipsis)
                        LogText(b.dnsAppName, Modifier.alpha(.7f), font = light, lines = 1, overflow = TextOverflow.Ellipsis, colorAttr = R.attr.primaryLightColorText)
                    }
                    Box(Modifier.weight(.2f), contentAlignment = Alignment.Center) { LogText(b.dnsWallTime, font = condensed, lines = 1) }
                    Box(Modifier.weight(.15f), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_keyboard_arrow_down), null, tint = themeColor(R.attr.primaryTextColor)) }
                }
                if (b.dnsSummaryLl.isVisible) Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LogText(b.dnsIps, Modifier.weight(.65f).padding(start = 8.dp, end = 10.dp), font = light, lines = 1, overflow = TextOverflow.StartEllipsis, colorAttr = R.attr.primaryLightColorText)
                    Box(Modifier.weight(.2f), contentAlignment = Alignment.Center) { LogText(b.dnsLatency, font = light, lines = 1, colorAttr = R.attr.primaryLightColorText) }
                    Box(Modifier.weight(.15f), contentAlignment = Alignment.Center) { LogText(b.dnsUnicodeHint, lines = 1) }
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = themeColor(R.attr.divider))
    }
}
