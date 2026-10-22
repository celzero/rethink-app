/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.settings

import com.celzero.bravedns.ui.compose.controls.*

import android.os.Bundle
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.wireguard.WgControlState

class UniversalFirewallState {
    val deviceLockedProgress = WgControlState(checkable = false)
    val dnsBypassProgress = WgControlState(checkable = false)
    val firewallAllAppsCheck = WgControlState(checkable = true)
    val firewallAllAppsTxt = WgControlState(checkable = false)
    val firewallBackgroundModeCheck = WgControlState(checkable = true)
    val firewallBackgroundModeTxt = WgControlState(checkable = false)
    val firewallBlockHttpCheck = WgControlState(checkable = true)
    val firewallBlockHttpTxt = WgControlState(checkable = false)
    val firewallBlockMeteredCheck = WgControlState(checkable = true)
    val firewallBlockMeteredTxt = WgControlState(checkable = false)
    val firewallBlockNewAppCheck = WgControlState(checkable = true)
    val firewallBlockNewAppTxt = WgControlState(checkable = false)
    val firewallDeviceLockedRl = WgControlState(checkable = false)
    val firewallDeviceLockedShimmerLayout = WgControlState(checkable = false).apply { isIndeterminate = true }
    val firewallDeviceLockedStats = WgControlState(checkable = false)
    val firewallDisallowDnsBypassModeCheck = WgControlState(checkable = true)
    val firewallDisallowDnsBypassModeTxt = WgControlState(checkable = false)
    val firewallDnsBypassRl = WgControlState(checkable = false)
    val firewallDnsBypassShimmerLayout = WgControlState(checkable = false).apply { isIndeterminate = true }
    val firewallDnsBypassStats = WgControlState(checkable = false)
    val firewallHttpRl = WgControlState(checkable = false)
    val firewallHttpShimmerLayout = WgControlState(checkable = false).apply { isIndeterminate = true }
    val firewallHttpStats = WgControlState(checkable = false)
    val firewallLockdownRl = WgControlState(checkable = false)
    val firewallLockdownShimmerLayout = WgControlState(checkable = false).apply { isIndeterminate = true }
    val firewallLockdownStats = WgControlState(checkable = false)
    val firewallMeteredRl = WgControlState(checkable = false)
    val firewallMeteredShimmerLayout = WgControlState(checkable = false).apply { isIndeterminate = true }
    val firewallMeteredStats = WgControlState(checkable = false)
    val firewallNewAppRl = WgControlState(checkable = false)
    val firewallNewAppShimmerLayout = WgControlState(checkable = false).apply { isIndeterminate = true }
    val firewallNewAppStats = WgControlState(checkable = false)
    val firewallNotInUseRl = WgControlState(checkable = false)
    val firewallNotInUseShimmerLayout = WgControlState(checkable = false).apply { isIndeterminate = true }
    val firewallNotInUseStats = WgControlState(checkable = false)
    val firewallUdpConnectionModeCheck = WgControlState(checkable = true)
    val firewallUdpConnectionModeTxt = WgControlState(checkable = false)
    val firewallUdpRl = WgControlState(checkable = false)
    val firewallUdpShimmerLayout = WgControlState(checkable = false).apply { isIndeterminate = true }
    val firewallUdpStats = WgControlState(checkable = false)
    val firewallUnivLockdownCheck = WgControlState(checkable = true)
    val firewallUnivLockdownTxt = WgControlState(checkable = false)
    val firewallUnknownConnectionModeCheck = WgControlState(checkable = true)
    val firewallUnknownConnectionModeTxt = WgControlState(checkable = false)
    val firewallUnknownRl = WgControlState(checkable = false)
    val firewallUnknownShimmerLayout = WgControlState(checkable = false).apply { isIndeterminate = true }
    val firewallUnknownStats = WgControlState(checkable = false)
    val httpProgress = WgControlState(checkable = false)
    val lockdownProgress = WgControlState(checkable = false)
    val meteredProgress = WgControlState(checkable = false)
    val newAppProgress = WgControlState(checkable = false)
    val notInUseProgress = WgControlState(checkable = false)
    val udpProgress = WgControlState(checkable = false)
    val unknownProgress = WgControlState(checkable = false)
    private val checks get() = listOf(firewallAllAppsCheck, firewallBackgroundModeCheck, firewallBlockHttpCheck, firewallBlockMeteredCheck, firewallBlockNewAppCheck, firewallDisallowDnsBypassModeCheck, firewallUdpConnectionModeCheck, firewallUnivLockdownCheck, firewallUnknownConnectionModeCheck)
    fun saveChecks(bundle: Bundle) { checks.forEachIndexed { index, control -> bundle.putBoolean("compose.universal.$index", control.isChecked) } }
    fun restoreChecks(bundle: Bundle) { checks.forEachIndexed { index, control -> if (bundle.containsKey("compose.universal.$index")) control.isChecked = bundle.getBoolean("compose.universal.$index") } }
}

@Composable
fun UniversalFirewallContent(b: UniversalFirewallState) {
    Surface(color = themeColor(R.attr.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(bottom = 5.dp)) {
            Text(stringResource(R.string.firewall_act_universal_tab), Modifier.padding(start = 45.dp, top = 15.dp, bottom = 10.dp).semantics { heading() },
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.universal_firewall_explanation), Modifier.padding(horizontal = 2.dp).padding(10.dp).alpha(.7f),
                color = themeColor(R.attr.primaryLightColorText), fontSize = 12.sp)
            UniversalRuleRow("firewall_all_apps_check", stringResource(R.string.univ_firewall_rule_1), R.drawable.ic_device_lock, b.firewallAllAppsTxt, b.firewallAllAppsCheck, b.firewallDeviceLockedRl, b.firewallDeviceLockedStats, b.deviceLockedProgress, b.firewallDeviceLockedShimmerLayout)
            UniversalRuleRow("firewall_background_mode_check", stringResource(R.string.univ_firewall_rule_2), R.drawable.ic_foreground, b.firewallBackgroundModeTxt, b.firewallBackgroundModeCheck, b.firewallNotInUseRl, b.firewallNotInUseStats, b.notInUseProgress, b.firewallNotInUseShimmerLayout)
            UniversalRuleRow("firewall_unknown_connection_mode_check", stringResource(R.string.univ_firewall_rule_3), R.drawable.ic_unknown_app, b.firewallUnknownConnectionModeTxt, b.firewallUnknownConnectionModeCheck, b.firewallUnknownRl, b.firewallUnknownStats, b.unknownProgress, b.firewallUnknownShimmerLayout)
            UniversalRuleRow("firewall_udp_connection_mode_check", stringResource(R.string.univ_firewall_rule_4), R.drawable.ic_udp, b.firewallUdpConnectionModeTxt, b.firewallUdpConnectionModeCheck, b.firewallUdpRl, b.firewallUdpStats, b.udpProgress, b.firewallUdpShimmerLayout)
            UniversalRuleRow("firewall_disallow_dns_bypass_mode_check", stringResource(R.string.univ_firewall_rule_5), R.drawable.ic_prevent_dns_leaks, b.firewallDisallowDnsBypassModeTxt, b.firewallDisallowDnsBypassModeCheck, b.firewallDnsBypassRl, b.firewallDnsBypassStats, b.dnsBypassProgress, b.firewallDnsBypassShimmerLayout)
            UniversalRuleRow("firewall_block_new_app_check", stringResource(R.string.univ_firewall_rule_6), R.drawable.ic_app_info, b.firewallBlockNewAppTxt, b.firewallBlockNewAppCheck, b.firewallNewAppRl, b.firewallNewAppStats, b.newAppProgress, b.firewallNewAppShimmerLayout)
            UniversalRuleRow("firewall_block_metered_check", stringResource(R.string.univ_firewall_rule_9), R.drawable.ic_univ_metered, b.firewallBlockMeteredTxt, b.firewallBlockMeteredCheck, b.firewallMeteredRl, b.firewallMeteredStats, b.meteredProgress, b.firewallMeteredShimmerLayout)
            UniversalRuleRow("firewall_block_http_check", stringResource(R.string.univ_firewall_rule_8), R.drawable.ic_http, b.firewallBlockHttpTxt, b.firewallBlockHttpCheck, b.firewallHttpRl, b.firewallHttpStats, b.httpProgress, b.firewallHttpShimmerLayout)
            UniversalRuleRow("firewall_univ_lockdown_check", stringResource(R.string.univ_firewall_rule_10), R.drawable.ic_global_lockdown, b.firewallUnivLockdownTxt, b.firewallUnivLockdownCheck, b.firewallLockdownRl, b.firewallLockdownStats, b.lockdownProgress, b.firewallLockdownShimmerLayout)
        }
    }
}

@Composable
private fun UniversalRuleRow(sourceId: String, title: String, icon: Int, titleControl: WgControlState, checked: WgControlState,
    statsRow: WgControlState, stats: WgControlState, progress: WgControlState, shimmer: WgControlState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable { titleControl.performClick() }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(icon), null, Modifier.size(24.dp))
            Text(title, Modifier.padding(start = 10.dp), color = themeColor(R.attr.primaryTextColor), fontSize = 14.sp)
        }
        OriginalSwitch("activity_universal_firewall_settings:$sourceId", checked.isChecked, { checked.performClick() })
    }
    Box(Modifier.fillMaxWidth().clickable { statsRow.performClick() }) {
        Row(Modifier.fillMaxWidth().padding(start = 45.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(progress = { progress.progress / 100f }, modifier = Modifier.weight(1f).padding(8.dp).alpha(.5f),
                color = themeColor(R.attr.accentGood), trackColor = themeColor(R.attr.background))
            Row(Modifier.padding(5.dp).alpha(.5f), verticalAlignment = Alignment.CenterVertically) {
                Text(stats.text.toString(), color = themeColor(R.attr.primaryLightColorText))
                Image(painterResource(R.drawable.ic_right_arrow_white), null, Modifier.size(24.dp))
            }
        }
        if (shimmer.isVisible) {
            val transition = rememberInfiniteTransition(label = "rule loading")
            val shimmerAlpha by transition.animateFloat(.35f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "shimmer")
            Spacer(Modifier.fillMaxWidth().height(28.dp).alpha(if (shimmer.isIndeterminate) shimmerAlpha else 1f).background(themeColor(R.attr.homeScreenBtnBackground)))
        }
    }
}
