/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.settings

import com.celzero.bravedns.ui.compose.controls.*

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.animation.Animation
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.ui.compose.wireguard.wgAnnotatedText

/** Keeps the original refresh listener contract; Animation supplies timing, never a View. */
class RefreshIconState {
    var isEnabled by mutableStateOf(true)
    var animation: Animation? by mutableStateOf(null)
        private set
    private var onClick: (() -> Unit)? = null
    fun setOnClickListener(value: () -> Unit) { onClick = value }
    fun performClick() { onClick?.invoke() }
    fun startAnimation(value: Animation) { animation = value }
    fun clearAnimation() { animation = null }
}
class HomeModeState(context: Context) {
    val bsHomeScreenConnectedStatus = WgControlState().apply { visibility = View.GONE }
    val bsHomeScreenRadioDns = WgControlState()
    val bsHomeScreenRadioFirewall = WgControlState()
    val bsHomeScreenRadioDnsFirewall = WgControlState()
    val bsHsDnsRl = WgControlState()
    val bsHsFirewallRl = WgControlState()
    val bsHsDnsFirewallRl = WgControlState()
    val bsHomeScreenVpnLockdownDesc = WgControlState().apply { text = context.getText(R.string.hs_btm_sheet_lock_down) }
    val bsHomeScreenRefreshIcon = RefreshIconState()
    val bsHomeScreenAppUptime = WgControlState()
    // Independent nested radios restore in the same XML order, through changed-only setters.
    private val checks get() = listOf(bsHomeScreenRadioDns, bsHomeScreenRadioFirewall, bsHomeScreenRadioDnsFirewall)
    fun saveChecks(bundle: Bundle) { checks.forEachIndexed { i, c -> bundle.putBoolean("compose.homemode.$i", c.isChecked) } }
    fun restoreChecks(bundle: Bundle) { checks.forEachIndexed { i, c -> if (bundle.containsKey("compose.homemode.$i")) c.isChecked = bundle.getBoolean("compose.homemode.$i") } }
}
class BlockFreeModeState(context: Context) {
    val bfdmDesc = WgControlState().apply { text = context.getText(R.string.bfdm_desc) }
    val bfdmOptionFallbackRl = WgControlState()
    val bfdmOptionFallbackRb = WgControlState()
    val bfdmOptionGlobalRl = WgControlState()
    val bfdmOptionGlobalRb = WgControlState()
    val bfdmOptionNoneRl = WgControlState()
    val bfdmOptionNoneRb = WgControlState()
    private val checks get() = listOf(bfdmOptionFallbackRb, bfdmOptionGlobalRb, bfdmOptionNoneRb)
    fun saveChecks(bundle: Bundle) { checks.forEachIndexed { i, c -> bundle.putBoolean("compose.blockfreemode.$i", c.isChecked) } }
    fun restoreChecks(bundle: Bundle) { checks.forEachIndexed { i, c -> if (bundle.containsKey("compose.blockfreemode.$i")) c.isChecked = bundle.getBoolean("compose.blockfreemode.$i") } }
}

@Composable
fun HomeModeContent(b: HomeModeState) {
    Surface(color = themeColor(R.attr.background)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
            Spacer(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 10.dp).size(60.dp, 3.dp).background(themeColor(R.attr.border)))
            Box(Modifier.fillMaxWidth().padding(5.dp).heightIn(min = 38.dp), contentAlignment = Alignment.Center) {
                SelectionContainer { Text(stringResource(R.string.app_mode_choose), Modifier.padding(horizontal = 58.dp).semantics { heading() }, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = themeColor(R.attr.primaryLightColorText), textAlign = TextAlign.Center) }
                val animation = b.bsHomeScreenRefreshIcon.animation
                val degrees = if (animation != null) {
                    val transition = rememberInfiniteTransition(label = "refresh")
                    val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(animation.duration.toInt(), easing = LinearEasing)), label = "refresh rotation")
                    rotation
                } else 0f
                IconButton({ b.bsHomeScreenRefreshIcon.performClick() }, Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(38.dp), enabled = b.bsHomeScreenRefreshIcon.isEnabled) {
                    Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.server_settings_refresh_servers), Modifier.padding(8.dp).rotate(degrees), tint = themeColor(R.attr.primaryTextColor))
                }
            }
            if (b.bsHomeScreenVpnLockdownDesc.isVisible) Row(Modifier.fillMaxWidth().clickable { b.bsHomeScreenVpnLockdownDesc.performClick() }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.dis_allowed), null, Modifier.size(24.dp))
                Text(wgAnnotatedText(b.bsHomeScreenVpnLockdownDesc.text), Modifier.padding(start = 10.dp), fontSize = 12.sp, color = themeColor(R.attr.primaryTextColor))
            }
            HomeModeRow("bs_home_screen_radio_dns", b.bsHsDnsRl, b.bsHomeScreenRadioDns, R.drawable.dns_home_screen, R.string.app_mode_dns_low_battery, R.string.dns_mode_info_explanation_new)
            HomeModeRow("bs_home_screen_radio_firewall", b.bsHsFirewallRl, b.bsHomeScreenRadioFirewall, R.drawable.firewall_home_screen, R.string.app_mode_firewall, R.string.firewall_mode_info_explanation_new)
            HomeModeRow("bs_home_screen_radio_dns_firewall", b.bsHsDnsFirewallRl, b.bsHomeScreenRadioDnsFirewall, R.drawable.ic_dns_firewall, R.string.app_mode_dns_firewall, R.string.dns_firewall_mode_info_explanation_new)
            if (b.bsHomeScreenConnectedStatus.isVisible) SelectionContainer { Text(b.bsHomeScreenConnectedStatus.text.toString(), Modifier.fillMaxWidth().padding(5.dp), fontSize = 12.sp, textAlign = TextAlign.Center) }
            if (b.bsHomeScreenAppUptime.isVisible) Text(b.bsHomeScreenAppUptime.text.toString(), Modifier.fillMaxWidth().padding(10.dp).alpha(.75f), fontSize = 10.sp, color = themeColor(R.attr.primaryTextColor), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun HomeModeRow(sourceId: String, row: WgControlState, radio: WgControlState, icon: Int, title: Int, desc: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp).padding(bottom = 1.dp).alpha(row.alpha)
        .clickable(enabled = row.isEnabled) { row.performClick() }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(icon), null, Modifier.padding(5.dp).size(32.dp).padding(5.dp))
        Column(Modifier.weight(1f).padding(horizontal = 5.dp)) {
            Text(stringResource(title), fontSize = 14.sp, color = themeColor(R.attr.primaryTextColor))
            Text(stringResource(desc), fontSize = 12.sp, color = themeColor(R.attr.primaryLightColorText))
        }
        OriginalRadioButton("bottom_sheet_home_screen:$sourceId", radio.isChecked, { if (!radio.isChecked) radio.isChecked = true }, enabled = radio.isEnabled)
    }
}

@Composable
fun BlockFreeModeContent(b: BlockFreeModeState) {
    Surface(color = themeColor(R.attr.background)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 100.dp)) {
            Spacer(Modifier.align(Alignment.CenterHorizontally).padding(top = 20.dp, bottom = 40.dp).size(60.dp, 3.dp).background(themeColor(R.attr.border)))
            Text(stringResource(R.string.bfdm_heading), Modifier.padding(horizontal = 24.dp).padding(bottom = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = themeColor(R.attr.colorOnSurface))
            Text(b.bfdmDesc.text.toString(), Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp), style = MaterialTheme.typography.bodyMedium, color = themeColor(R.attr.colorOnSurfaceVariant))
            BlockFreeModeRow("bfdm_option_fallback_rb", b.bfdmOptionFallbackRl, b.bfdmOptionFallbackRb, R.string.bfdm_option_bootstrap_label, R.string.bfdm_option_bootstrap_desc)
            BlockFreeModeRow("bfdm_option_global_rb", b.bfdmOptionGlobalRl, b.bfdmOptionGlobalRb, R.string.bfdm_option_global_label, R.string.bfdm_option_global_desc)
            BlockFreeModeRow("bfdm_option_none_rb", b.bfdmOptionNoneRl, b.bfdmOptionNoneRb, R.string.bfdm_option_auto_label, R.string.bfdm_option_auto_desc)
        }
    }
}

@Composable
private fun BlockFreeModeRow(sourceId: String, row: WgControlState, radio: WgControlState, title: Int, desc: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).alpha(row.alpha)
        .selectable(selected = radio.isChecked, enabled = row.isEnabled, role = Role.RadioButton) { row.performClick() }
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        OriginalRadioButton("bottom_sheet_block_free_dns_mode:$sourceId", radio.isChecked, null, enabled = radio.isEnabled)
        Column(Modifier.weight(1f).padding(start = 4.dp, end = 8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = themeColor(R.attr.colorOnSurface))
            Text(stringResource(desc), Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = themeColor(R.attr.colorOnSurfaceVariant))
        }
    }
}
