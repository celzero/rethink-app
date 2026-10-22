/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.rpnconfig

import com.celzero.bravedns.ui.compose.controls.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.rpn.RpnSignature
import com.celzero.bravedns.ui.compose.rpn.rpnHeroBackground
import com.celzero.bravedns.ui.compose.wireguard.*

@Composable
fun RpnConfigContent(b: RpnConfigState, headerState: TopAppBarState = rememberTopAppBarState()) {
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state = headerState)
    val contentScroll = rememberScrollState()
    val density = LocalDensity.current
    val expandedHeight = 150.dp * density.fontScale.coerceAtLeast(1f)
    val collapseRange = with(density) { (expandedHeight - 56.dp).toPx() }
    SideEffect { behavior.state.heightOffsetLimit = -collapseRange }
    val offset = behavior.state.heightOffset
    val fraction = (1f - kotlin.math.abs(offset) / collapseRange).coerceIn(0f, 1f)
    val heroAlpha = (fraction / .6f).coerceIn(0f, 1f)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().nestedScroll(behavior.nestedScrollConnection)) {
            Box(Modifier.fillMaxWidth().height(expandedHeight + with(density) { offset.toDp() }).rpnHeroBackground().clipToBounds()
                .testTag("rpn.config.header").rpnHeaderDrag(behavior, contentScroll.value == 0)) {
                RpnConfigFlag(b.flagWatermark, Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).height(expandedHeight).alpha(heroAlpha).graphicsLayer { translationY = offset * .5f })
                // INVISIBLE in the baseline also removes descendant touch/accessibility targets.
                if (heroAlpha > 0f) Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).height(expandedHeight).padding(horizontal = 20.dp, vertical = 20.dp)
                    .graphicsLayer { alpha = heroAlpha; translationY = offset * .5f }, verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { WgText(b.configNameText); WgText(b.tvHeroCity) }
                        Column(horizontalAlignment = Alignment.End) {
                            OriginalChip("activity_rpn_config_detail:refresh_chip", selected = false, selectable = false, onClick = { b.refreshChip.performClick() }, enabled = b.refreshChip.isEnabled && b.refreshChip.isClickable, modifier = Modifier.alpha(b.refreshChip.alpha).testTag("rpn.config.refresh"), label = { Text(b.refreshChip.text.toString(), fontSize = 11.sp) }, leadingIcon = { Icon(painterResource(R.drawable.ic_refresh_white), null, Modifier.size(18.dp).rotate(b.refreshRotation)) })
                            if (b.chipHeroStats.isVisible) Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.background) {
                                WgText(b.chipHeroStats, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        }
                    }
                    WgText(b.valueAddlInfo, Modifier.padding(top = 10.dp))
                    WgText(b.tvHeroPub, Modifier.padding(top = 5.dp).testTag("rpn.config.public"))
                }
                if (fraction < .4f) Text(b.headerTitle?.toString().orEmpty(), Modifier.align(Alignment.CenterStart).padding(horizontal = 24.dp)
                    .alpha((1f - fraction / .4f).coerceIn(0f, 1f)).testTag("rpn.config.collapsed"), style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f).verticalScroll(contentScroll).testTag("rpn.config.body").padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ConfigCard(b.statsCard) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        WgText(b.valueStatus); WgSourceText(" · ", "activity_rpn_config_detail:anonymous_24"); WgText(b.valueSince, Modifier.weight(1f))
                        WgText(b.valueRx); WgText(b.valueTx)
                    }
                    HorizontalDivider(color = themeColor(R.attr.divider).copy(alpha = .08f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { WgText(b.valueLastOk, Modifier.weight(1f)); WgText(b.valueLastOpen) }
                    HorizontalDivider(color = themeColor(R.attr.divider).copy(alpha = .08f))
                    WgText(b.labelIpv4)
                    if (b.clientIpShimmer) ConfigIpShimmer()
                    WgText(b.valueIpv4)
                }
                ConfigCard(b.actionsCard) {
                    WgSourceText(stringResource(R.string.lbl_actions), "activity_rpn_config_detail:anonymous_45")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        ConfigAction(b.applicationsBtn, "apps", R.drawable.ic_app_info_accent, b.appsLabel.text.toString(), b.appsLabel.textColor, Modifier.weight(1f))
                        if (b.hopBtn.isVisible) ConfigAction(b.hopBtn, "hopAction", R.drawable.ic_network_tunnel, b.hopLabel.text.toString(), null, Modifier.weight(1f))
                        ConfigAction(b.logsBtn, "logs", R.drawable.ic_logs, stringResource(R.string.lbl_logs), null, Modifier.weight(1f))
                    }
                }
                ConfigCard(b.otherSettingsCard) {
                    WgSourceText(stringResource(R.string.lbl_advanced), "activity_rpn_config_detail:anonymous_64")
                    ConfigSetting("hop", b.hopRl, b.hopCheck, b.hopTitleTv, stringResource(R.string.relay_rpn_desc))
                    ConfigSetting("lockdown", b.lockdownRl, b.lockdownCheck, b.lockdownTitleTv, stringResource(R.string.lockdown_wg_dialog_desc))
                    ConfigSetting("catchAll", b.catchAllRl, b.catchAllCheck, b.catchAllTitleTv, stringResource(R.string.catch_all_wg_dialog_desc))
                }
                ConfigCard(b.mobileSsidSettingsCard, padding = 20) {
                    WgSourceText(stringResource(R.string.adv_taster_title), "activity_rpn_config_detail:anonymous_82")
                    ConfigSetting("mobile", b.useMobileRl, b.useMobileCheck, b.useMobileTitleTv, stringResource(R.string.wg_setting_use_on_mobile_desc))
                    ConfigSetting("ssid", b.ssidFilterRl, b.ssidCheck, b.ssidTitleTv, b.ssidDescTv.text.toString())
                    if (b.ssidDisplayGroup.isVisible) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        WgText(b.ssidValueTv, Modifier.weight(1f))
                        IconButton({ b.ssidEditBtn.performClick() }, enabled = b.ssidEditBtn.isEnabled, modifier = Modifier.size(32.dp).alpha(b.ssidEditBtn.alpha).testTag("rpn.config.ssidEdit")) {
                            Icon(painterResource(R.drawable.ic_edit_icon_grey), stringResource(R.string.rt_edit_dialog_positive), Modifier.size(20.dp), tint = Color.Unspecified)
                        }
                    }
                    if (b.ssidPermissionErrorLayout.isVisible) Row(verticalAlignment = Alignment.CenterVertically) {
                        WgText(b.ssidPermissionErrorText, Modifier.weight(1f)); WgAction(b.ssidPermissionErrorAction, source = "activity_rpn_config_detail:ssid_permission_error_action")
                    }
                    if (b.ssidLocationErrorLayout.isVisible) Row(verticalAlignment = Alignment.CenterVertically) {
                        WgText(b.ssidLocationErrorText, Modifier.weight(1f)); WgAction(b.ssidLocationErrorAction, source = "activity_rpn_config_detail:ssid_location_error_action")
                    }
                }
                RpnSignature(b.dolphinSignature, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ConfigSetting(tag: String, row: WgControlState, checked: WgControlState, title: WgControlState, description: String) {
    if (!row.isVisible) return
    // Hop and lockdown rows had no click listener; their switch remains the only target.
    Row(Modifier.fillMaxWidth().testTag("rpn.config.$tag.row").then(if (row.hasClickListener) Modifier.clickable(row.isEnabled && row.isClickable) { row.performClick() } else Modifier)
        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) { WgText(title); WgSourceText(description, "activity_rpn_config_detail:" + when (tag) {
            "hop" -> "anonymous_68"; "lockdown" -> "anonymous_73"; "catchAll" -> "anonymous_78"
            "mobile" -> "anonymous_86"; else -> "ssid_desc_tv"
        }, Modifier.padding(top = 4.dp)) }
        WgCheck(checked, title.text.toString(), switch = true, modifier = Modifier.testTag("rpn.config.$tag.switch"), compoundSource = "activity_rpn_config_detail:" + when (tag) { "catchAll" -> "catch_all_check"; "mobile" -> "use_mobile_check"; else -> "${tag}_check" })
    }
}

@Composable
private fun ConfigAction(state: WgControlState, tag: String, icon: Int, label: String, color: Int?, modifier: Modifier) {
    if (!state.isVisible) return
    Surface(onClick = { state.performClick() }, enabled = state.isEnabled && state.isClickable,
        modifier = modifier.alpha(state.alpha).testTag("rpn.config.$tag"), shape = RoundedCornerShape(20.dp), color = themeColor(R.attr.chipBgColorPositive)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(icon), null, Modifier.size(24.dp), tint = themeColor(R.attr.accentGood))
            WgSourceText(label, "activity_rpn_config_detail:" + when (tag) {
                "apps" -> "apps_label"; "hopAction" -> "hop_label"; else -> "anonymous_60"
            }, Modifier.weight(1f), color = color?.let(::Color) ?: Color.Unspecified)
            Icon(painterResource(R.drawable.ic_right_arrow_secondary), null, Modifier.size(18.dp), tint = themeColor(R.attr.accentGood))
        }
    }
}

@Composable
private fun ConfigCard(state: WgControlState, padding: Int = 18, content: @Composable ColumnScope.() -> Unit) {
    if (!state.isVisible) return
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(state.backgroundColor ?: 0)), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Column(Modifier.padding(padding.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun ConfigIpShimmer() {
    val transition = rememberInfiniteTransition(label = "client IP shimmer")
    val phase by transition.animateFloat(-1f, 2f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "shimmer band")
    val color = themeColor(R.attr.primaryTextColor)
    Canvas(Modifier.fillMaxWidth().height(49.dp).testTag("rpn.config.ipLoading")) {
        val x = size.width * phase
        val brush = Brush.linearGradient(listOf(color.copy(alpha = .3f), color, color.copy(alpha = .3f)),
            start = androidx.compose.ui.geometry.Offset(x - size.width / 2f, 0f), end = androidx.compose.ui.geometry.Offset(x + size.width / 2f, size.height))
        listOf(Triple(110f, 13f, 0f), Triple(220f, 11f, 21f), Triple(160f, 11f, 38f)).forEach { (width, height, top) ->
            drawRoundRect(brush, topLeft = androidx.compose.ui.geometry.Offset(0f, top.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(width.dp.toPx().coerceAtMost(size.width), height.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()))
        }
    }
}
