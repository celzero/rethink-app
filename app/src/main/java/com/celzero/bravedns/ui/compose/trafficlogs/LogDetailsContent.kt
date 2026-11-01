/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.trafficlogs

import com.celzero.bravedns.ui.compose.controls.*

import android.text.Spanned
import android.text.style.*
import android.view.View
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.NoDimDialogWindow
import com.celzero.bravedns.ui.compose.themeColor

data class LogInfoDialogState(val title: String?, val description: CharSequence, val icon: Int? = null, val fullWidth: Boolean = true)
internal fun logAnnotated(text: CharSequence): AnnotatedString {
    val builder = AnnotatedString.Builder(text.toString())
    if (text is Spanned) text.getSpans(0, text.length, Any::class.java).forEach { span ->
        val style = when (span) {
            is StyleSpan -> SpanStyle(fontWeight = if (span.style and android.graphics.Typeface.BOLD != 0) FontWeight.Bold else null, fontStyle = if (span.style and android.graphics.Typeface.ITALIC != 0) FontStyle.Italic else null)
            is RelativeSizeSpan -> SpanStyle(fontSize = span.sizeChange.em)
            is UnderlineSpan -> SpanStyle(textDecoration = TextDecoration.Underline)
            is ForegroundColorSpan -> SpanStyle(color = Color(span.foregroundColor))
            is StrikethroughSpan -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            else -> null
        }
        style?.let { builder.addStyle(it, text.getSpanStart(span).coerceAtLeast(0), text.getSpanEnd(span).coerceAtMost(text.length)) }
    }
    return builder.toAnnotatedString()
}
@Composable fun LogInfoDialog(state: LogInfoDialogState, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = !state.fullWidth)) {
        NoDimDialogWindow()
        Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = themeColor(R.attr.colorSurfaceContainerHigh)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { IconButton(onDismiss, Modifier.size(30.dp)) { com.celzero.bravedns.ui.compose.utilities.utilityImage(android.R.drawable.ic_menu_close_clear_cancel, Modifier.size(30.dp), stringResource(R.string.lbl_cancel)) } }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    state.icon?.let { Icon(painterResource(it), null, Modifier.padding(5.dp).size(24.dp)) }
                    state.title?.let { Text(it, fontSize = logTextSize(R.dimen.heading_font_text_view), color = themeColor(R.attr.primaryTextColor)) }
                }
                val size = logTextSize(R.dimen.large_font_text_view)
                val extra = with(LocalDensity.current) { 5.dp.toSp() }
                SelectionContainer { Text(logAnnotated(state.description), Modifier.padding(top = 10.dp).padding(5.dp), fontSize = size, lineHeight = (size.value * 1.2f + extra.value).sp, color = themeColor(R.attr.primaryTextColor)) }
            }
        }
    }
}
@Composable private fun LogRuleGlyphContent(state: LogRuleGlyph) {
    val positive = themeColor(R.attr.chipTextPositive); val negative = themeColor(R.attr.accentBad)
    val neutral = themeColor(R.attr.border); val thumb = themeColor(R.attr.invertedPrimaryTextColor)
    val label = stringResource(when (state) { LogRuleGlyph.NEUTRAL -> R.string.ci_no_rule; LogRuleGlyph.OFF -> R.string.ci_block; LogRuleGlyph.ON -> R.string.ci_trust_rule })
    Canvas(Modifier.size(dimensionResource(R.dimen.rule_state_switch_width), dimensionResource(R.dimen.rule_state_switch_height)).semantics { contentDescription = label }) {
        val color = when (state) { LogRuleGlyph.ON -> positive; LogRuleGlyph.OFF -> negative; LogRuleGlyph.NEUTRAL -> neutral }
        val radius = size.height / 2; val r = radius - size.height * .14f
        val cx = if (state == LogRuleGlyph.ON) size.width - radius else radius; val cy = radius
        drawRoundRect(color, cornerRadius = CornerRadius(radius)); drawCircle(thumb, r, Offset(cx, cy))
        if (state == LogRuleGlyph.OFF) {
            val arm = r * .4f
            drawLine(negative, Offset(cx - arm, cy - arm), Offset(cx + arm, cy + arm), r * .32f, StrokeCap.Round)
            drawLine(negative, Offset(cx - arm, cy + arm), Offset(cx + arm, cy - arm), r * .32f, StrokeCap.Round)
        } else if (state == LogRuleGlyph.ON) drawPath(Path().apply { moveTo(cx - r * .48f, cy + r * .08f); lineTo(cx - r * .12f, cy + r * .42f); lineTo(cx + r * .5f, cy - r * .34f) }, positive, style = Stroke(r * .32f, cap = StrokeCap.Round))
    }
}
@Composable private fun DetailCard(modifier: Modifier = Modifier, hero: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(if (hero) 20.dp else 16.dp), border = BorderStroke(1.dp, themeColor(R.attr.border)), colors = CardDefaults.cardColors(containerColor = themeColor(androidx.cardview.R.attr.cardBackgroundColor)), content = content)
}
@Composable private fun DetailLabel(label: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(label).uppercase(), Modifier.alpha(.55f), fontSize = logTextSize(R.dimen.mini_font_text_view), color = themeColor(R.attr.primaryLightColorText)); content()
    }
}
@Composable private fun ColumnScope.Handle() { Spacer(Modifier.height(10.dp)); Box(Modifier.align(Alignment.CenterHorizontally).size(60.dp, 3.dp).background(themeColor(R.attr.border))); Spacer(Modifier.height(34.dp)) }
@Composable private fun RuleRow(row: LogControlState, title: String, state: LogControlState, glyph: LogControlState? = null) {
    if (row.visibility == View.GONE) return
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = row.isEnabled) { row.performClick() }.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, Modifier.weight(1f), fontSize = logTextSize(R.dimen.large_font_text_view))
        LogText(state, lines = 1, colorAttr = R.attr.primaryLightColorText)
        if (glyph != null) LogRuleGlyphContent(glyph.glyph) else Icon(painterResource(R.drawable.ic_right_arrow_small), null, Modifier.size(16.dp))
    }
}
@Composable fun ConnectionDetailsContent(b: ConnectionDetailsState) {
    Column(Modifier.fillMaxWidth().background(themeColor(R.attr.background)).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        Handle()
        DetailCard(hero = true) {
            Row(Modifier.fillMaxWidth().clickable { b.bsConnTrackAppNameHeader.performClick() }.padding(start = 16.dp, end = 16.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                LogDrawable(b.bsConnTrackAppIcon, Modifier.padding(end = 12.dp).size(32.dp), stringResource(R.string.apps_icon_content_desc))
                Column(Modifier.weight(1f)) { LogText(b.bsConnTrackAppName, size = R.dimen.heading_font_text_view, weight = FontWeight.Bold, lines = 1, overflow = TextOverflow.Ellipsis); LogText(b.bsConnTrackPortDetailChip, Modifier.padding(top = 2.dp), size = R.dimen.small_font_text_view, lines = 1, overflow = TextOverflow.Ellipsis, colorAttr = R.attr.primaryLightColorText) }
                Icon(painterResource(R.drawable.ic_right_arrow_small), stringResource(R.string.bsct_app_info), Modifier.padding(start = 8.dp).size(20.dp).alpha(.5f))
            }
            Row(Modifier.fillMaxWidth().clickable { b.bsConnTrackAppInfo.performClick() }.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogDrawable(b.bsConnTrackAppInfoIcon, Modifier.size(16.dp)); LogText(b.bsConnTrackAppInfoTxt, Modifier.weight(1f), lines = 1, overflow = TextOverflow.Ellipsis)
                Icon(painterResource(R.drawable.ic_right_arrow_small), null, Modifier.size(14.dp).alpha(.5f))
            }
            HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).alpha(.5f), color = themeColor(R.attr.border))
            DetailLabel(R.string.bsct_meta_destination) { Row { Text(b.bsConnConnectionFlag.text?.toString().orEmpty(), Modifier.padding(end = 10.dp).widthIn(min = 22.dp), fontSize = 18.sp); SelectionContainer { LogText(b.bsConnConnectionTypeHeading, font = FontFamily.Monospace, lines = 1, overflow = TextOverflow.Ellipsis) } } }
            DetailLabel(R.string.lbl_domain) { SelectionContainer { LogText(b.bsConnDnsCacheText, colorAttr = R.attr.primaryLightColorText) } }
            DetailLabel(R.string.bsct_meta_info) {
                if (b.bsConnSummaryDetailLl.isVisible) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(b.bsConnConnDuration, b.bsConnConnUpload, b.bsConnConnDownload, b.bsConnConnType).forEach { LogText(it, colorAttr = R.attr.primaryLightColorText) }
                }
                LogText(b.bsConnConnTypeSecondary, colorAttr = R.attr.primaryLightColorText)
            }
            Spacer(Modifier.height(16.dp))
        }
        Text(stringResource(R.string.lbl_rules).uppercase(), Modifier.padding(start = 16.dp, top = 16.dp), fontSize = logTextSize(R.dimen.default_font_text_view))
        DetailCard(Modifier.padding(bottom = 10.dp)) {
            RuleRow(b.bsConnRuleRowApp, b.bsConnAppRuleHeading.text?.toString()?.ifEmpty { null } ?: stringResource(R.string.lbl_app_rules), b.bsConnAppRuleState)
            HorizontalDivider(Modifier.padding(start = 16.dp), thickness = .5.dp, color = themeColor(R.attr.border))
            RuleRow(b.bsConnRuleRowIp, b.bsConnIpRuleAddress.text?.toString().orEmpty(), b.bsConnIpRuleState, b.bsConnIpRuleSwitch)
            if (b.bsConnRuleRowDomain.isVisible) { HorizontalDivider(Modifier.padding(start = 16.dp), thickness = .5.dp, color = themeColor(R.attr.border)); RuleRow(b.bsConnRuleRowDomain, b.bsConnDomainRuleAddress.text?.toString().orEmpty(), b.bsConnDomainRuleState, b.bsConnDomainRuleSwitch) }
        }
        if (b.bsConnBlockedRule2HeaderLl.isVisible) DetailCard(Modifier.padding(bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(stringResource(R.string.univ_firewall_rule_3), Modifier.weight(1f), fontSize = logTextSize(R.dimen.large_font_text_view)); OriginalSwitch("bottom_sheet_conn_track:bs_conn_unknown_app_check", b.bsConnUnknownAppCheck.isChecked, { b.bsConnUnknownAppCheck.performClick() }, enabled = b.bsConnUnknownAppCheck.isEnabled) }
        }
        SelectionContainer { Text(b.connectionMessage.text?.toString().orEmpty(), Modifier.fillMaxWidth().padding(top = 10.dp, start = 8.dp, end = 8.dp).alpha(.7f), fontSize = logTextSize(R.dimen.mini_font_text_view), textAlign = TextAlign.Center, color = themeColor(R.attr.primaryLightColorText)) }
    }
    b.infoDialog?.let { LogInfoDialog(it) { b.infoDialog = null } }
}
@Composable fun DnsDetailsContent(b: DnsDetailsState) {
    Column(Modifier.fillMaxWidth().background(themeColor(R.attr.background)).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        Handle()
        DetailCard(hero = true) {
            Row(Modifier.fillMaxWidth().clickable { b.dnsBlockHeaderContainer.performClick() }.padding(start = 16.dp, end = 16.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                LogDrawable(b.dnsBlockFavIcon, Modifier.padding(end = 12.dp).size(24.dp), stringResource(R.string.fav_icon))
                if (b.dnsBlockHeaderFlag.isVisible) Text(b.dnsBlockHeaderFlag.text?.toString().orEmpty(), Modifier.padding(end = 12.dp).widthIn(min = 24.dp), fontSize = 18.sp)
                Column(Modifier.weight(1f)) { LogText(b.dnsBlockUrl, size = R.dimen.heading_font_text_view, weight = FontWeight.Bold, lines = 2, overflow = TextOverflow.Ellipsis); LogText(b.dnsBlockBlockedDesc, Modifier.padding(top = 4.dp), lines = 2, overflow = TextOverflow.Ellipsis, colorAttr = R.attr.primaryLightColorText) }
                Icon(painterResource(R.drawable.ic_right_arrow_small), stringResource(R.string.bsdl_open_domain_connections), Modifier.padding(start = 8.dp).size(20.dp).alpha(.5f))
            }
            if (b.dnsBlockBlocklistInfo.isVisible) Row(Modifier.fillMaxWidth().then(if (b.dnsBlockBlocklistInfo.hasClickListener) Modifier.clickable { b.dnsBlockBlocklistInfo.performClick() } else Modifier).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LogDrawable(b.dnsBlockBlocklistInfoIcon, Modifier.size(16.dp)); LogText(b.dnsBlockBlocklistInfoTxt, Modifier.weight(1f), lines = 1, overflow = TextOverflow.Ellipsis)
                if (b.dnsBlockBlocklistInfoArrow.isVisible) Icon(painterResource(R.drawable.ic_right_arrow_small), null, Modifier.size(14.dp).alpha(.5f))
            }
            HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).alpha(.5f), color = themeColor(R.attr.border))
            DetailLabel(R.string.bsdl_meta_app) { if (b.dnsAppNameHeader.isVisible) Row(verticalAlignment = Alignment.CenterVertically) { LogDrawable(b.dnsAppIcon, Modifier.padding(end = 10.dp).size(20.dp), stringResource(R.string.apps_icon_content_desc)); LogText(b.dnsAppName, lines = 1, overflow = TextOverflow.Ellipsis) } }
            DetailLabel(R.string.bsdl_meta_response) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(b.dnsBlockConnectionFlag.text?.toString().orEmpty(), Modifier.padding(end = 10.dp).widthIn(min = 22.dp), fontSize = 18.sp); SelectionContainer(Modifier.weight(1f)) { LogText(b.dnsBlockIpAddress, font = FontFamily.Monospace, lines = 2, overflow = TextOverflow.Ellipsis) }; LogText(b.dnsBlockIpLatency, Modifier.padding(start = 8.dp).background(themeColor(R.attr.background)).padding(horizontal = 6.dp), size = R.dimen.mini_font_text_view, lines = 1, colorAttr = R.attr.primaryLightColorText) }
            }
            DetailLabel(R.string.bsdl_meta_query_type) { LogText(b.dnsRecordTypeChip, size = R.dimen.small_font_text_view, lines = 1, overflow = TextOverflow.Ellipsis, colorAttr = R.attr.primaryLightColorText) }
            if (b.dnsBlockInfoHeader.isVisible) DetailLabel(R.string.bsdl_meta_resolver) { SelectionContainer { LogText(b.dnsBlockInfoDetails, size = R.dimen.small_font_text_view, lines = 1, overflow = TextOverflow.Ellipsis, colorAttr = R.attr.primaryLightColorText) } }
            if (b.dnsBlockedTargetHeader.isVisible) DetailLabel(R.string.bsdl_meta_alias) { SelectionContainer { LogText(b.dnsBlockedTarget, lines = 1, overflow = TextOverflow.Ellipsis, colorAttr = R.attr.primaryLightColorText) } }
            if (b.dnsRegionHeader.isVisible) DetailLabel(R.string.bsdl_meta_region) { LogText(b.dnsRegion, font = logFont("sans-serif-smallcaps"), lines = 1, overflow = TextOverflow.Ellipsis, colorAttr = R.attr.primaryLightColorText) }
            Spacer(Modifier.height(16.dp))
        }
        DetailCard(Modifier.padding(top = 14.dp)) {
            LogText(b.bsdlRulesDomainLabel, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).alpha(.55f), size = R.dimen.mini_font_text_view, lines = 1, overflow = TextOverflow.Ellipsis)
            RuleRow(b.bsdlRuleRowApp, b.bsdlRuleAppTitle.text?.toString()?.ifEmpty { null } ?: stringResource(R.string.bsdl_rule_scope_app_title), b.bsdlAppRuleState, b.bsdlAppRuleSwitch)
            RuleRow(b.bsdlRuleRowGlobal, stringResource(R.string.bsdl_rule_scope_global_title), b.bsdlGlobalRuleState, b.bsdlGlobalRuleSwitch)
        }
        SelectionContainer { Text(b.dnsMessage.text?.toString().orEmpty(), Modifier.fillMaxWidth().padding(top = 10.dp, start = 8.dp, end = 8.dp).alpha(.7f), fontSize = logTextSize(R.dimen.mini_font_text_view), textAlign = TextAlign.Center, maxLines = 5, color = themeColor(R.attr.primaryLightColorText)) }
    }
    b.infoDialog?.let { LogInfoDialog(it) { b.infoDialog = null } }
}
