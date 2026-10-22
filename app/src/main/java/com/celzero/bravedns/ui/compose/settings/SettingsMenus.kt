/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.settings

import androidx.compose.ui.platform.LocalLocale
import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.chrome.homeNavigationClearance
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.wireguard.WgControlState

class ConfigureTitleState(context: Context) {
    var text: CharSequence by mutableStateOf(context.getString(R.string.app_name_small_case))
    var isAllCaps by mutableStateOf(true)
}
class ConfigureMenuState(context: Context) {
    val fhsTitleRethink = ConfigureTitleState(context)
    val fsAppsCard = WgControlState()
    val fsAppsTv = WgControlState().apply { text = context.getString(R.string.apps_info_title) }
    val fsDnsCard = WgControlState()
    val fsDnsTv = WgControlState().apply { text = context.getString(R.string.dns_mode_info_title) }
    val fsFirewallCard = WgControlState()
    val fsFirewallTv = WgControlState().apply { text = context.getString(R.string.firewall_mode_info_title) }
    val fsProxyCard = WgControlState()
    val fsProxyTv = WgControlState().apply { text = context.getString(R.string.cd_custom_dns_proxy_name_default) }
    val fsNetworkCard = WgControlState()
    val fsNetworkTv = WgControlState().apply { text = context.getString(R.string.lbl_network) }
    val fsOthersCard = WgControlState()
    val fsOthersTv = WgControlState().apply { text = context.getString(R.string.title_settings) }
    val fsLogsCard = WgControlState()
    val fsLogsTv = WgControlState().apply { text = context.getString(R.string.lbl_logs) }
    val fsAntiCensorshipCard = WgControlState()
    val fsAntiCensorshipTv = WgControlState().apply { text = context.getString(R.string.anti_censorship_title) }
    val fsAdvancedCard = WgControlState()
    val fsAdvancedTv = WgControlState().apply { text = context.getString(R.string.lbl_advanced) }
}

@Composable
fun ConfigureMenuContent(b: ConfigureMenuState) {
    Surface(color = themeColor(R.attr.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(bottom = homeNavigationClearance(90.dp, contentGap = 10.dp))) {
            Column(Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 8.dp)) {
                val title = b.fhsTitleRethink.text.toString()
                val locale = LocalLocale.current.platformLocale
                Text(if (b.fhsTitleRethink.isAllCaps) title.uppercase(locale) else title,
                    Modifier.fillMaxWidth().alpha(.55f).semantics { heading() }, fontSize = 24.sp, letterSpacing = 6.sp,
                    fontFamily = FontFamily(Typeface.create("sans-serif-smallcaps", Typeface.NORMAL)), fontWeight = FontWeight.Medium,
                    color = themeColor(R.attr.colorOnSurface))
                Text(stringResource(R.string.settings_title_desc), Modifier.fillMaxWidth().padding(top = 2.dp).alpha(.45f),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 11.sp), color = themeColor(R.attr.colorOnSurface))
            }
            if (b.fsAppsCard.isVisible) ConfigureMenuCard(b.fsAppsTv.text.toString(), stringResource(R.string.fs_configure_apps_desc), R.drawable.ic_app_info_accent) { b.fsAppsCard.performClick() }
            if (b.fsDnsCard.isVisible) ConfigureMenuCard(b.fsDnsTv.text.toString(), stringResource(R.string.dns_mode_info_explanation_new), R.drawable.dns_home_screen) { b.fsDnsCard.performClick() }
            if (b.fsFirewallCard.isVisible) ConfigureMenuCard(b.fsFirewallTv.text.toString(), stringResource(R.string.firewall_mode_info_explanation_new), R.drawable.firewall_home_screen) { b.fsFirewallCard.performClick() }
            if (b.fsProxyCard.isVisible) ConfigureMenuCard(b.fsProxyTv.text.toString(), stringResource(R.string.fs_configure_proxy_desc), R.drawable.ic_proxy) { b.fsProxyCard.performClick() }
            if (b.fsNetworkCard.isVisible) ConfigureMenuCard(b.fsNetworkTv.text.toString(), stringResource(R.string.fs_configure_network_desc), R.drawable.ic_network_tunnel) { b.fsNetworkCard.performClick() }
            if (b.fsOthersCard.isVisible) ConfigureMenuCard(b.fsOthersTv.text.toString(), stringResource(R.string.fs_configure_others_desc), R.drawable.ic_other_settings) { b.fsOthersCard.performClick() }
            if (b.fsLogsCard.isVisible) ConfigureMenuCard(b.fsLogsTv.text.toString(), stringResource(R.string.fs_configure_logs_desc), R.drawable.ic_logs_accent) { b.fsLogsCard.performClick() }
            if (b.fsAntiCensorshipCard.isVisible) ConfigureMenuCard(b.fsAntiCensorshipTv.text.toString(), stringResource(R.string.anti_censorship_desc), R.drawable.ic_anti_dpi) { b.fsAntiCensorshipCard.performClick() }
            if (b.fsAdvancedCard.isVisible) ConfigureMenuCard(b.fsAdvancedTv.text.toString(), stringResource(R.string.fs_configure_advanced_desc), R.drawable.ic_advanced_settings) { b.fsAdvancedCard.performClick() }
        }
    }
}

@Composable
private fun ConfigureMenuCard(title: String, description: String, icon: Int, onClick: () -> Unit) {
    SettingsCard(Modifier.padding(4.dp).clickable(onClick = onClick), contentPadding = 0.dp, cornerRadius = 8.dp) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = themeColor(R.attr.colorOnSurface))
                Text(description, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = themeColor(R.attr.primaryLightColorText))
            }
            Icon(painterResource(R.drawable.ic_right_arrow_white), null, Modifier.size(16.dp).alpha(.4f), tint = themeColor(R.attr.primaryLightColorText))
        }
    }
}

class FirewallSettingsMenuState(context: Context) {
    val customIpDomainUniversalRl = WgControlState()
    val universalFirewallRl = WgControlState()
    val appWiseIpDomainRl = WgControlState()
}

@Composable
fun FirewallSettingsMenuContent(b: FirewallSettingsMenuState) {
    SettingsPage(stringResource(R.string.firewall_act_universal_tab)) {
        SettingsCard {
            FirewallMenuRow(stringResource(R.string.univ_firewall_heading), stringResource(R.string.universal_firewall_explanation), R.drawable.universal_firewall) { b.universalFirewallRl.performClick() }
            FirewallMenuRow(stringResource(R.string.univ_view_blocked_ip), stringResource(R.string.univ_view_blocked_ip_desc), R.drawable.universal_ip_rule) { b.customIpDomainUniversalRl.performClick() }
        }
        Text(stringResource(R.string.lbl_app_wise), Modifier.padding(start = 60.dp, top = 15.dp, bottom = 10.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        SettingsCard {
            FirewallMenuRow(stringResource(R.string.app_ip_domain_rules), stringResource(R.string.app_ip_domain_rules_desc), R.drawable.ic_ip_address) { b.appWiseIpDomainRl.performClick() }
        }
    }
}

@Composable
private fun FirewallMenuRow(title: String, description: String, icon: Int, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(icon), null, Modifier.padding(15.dp).size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(description, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodyMedium, color = themeColor(R.attr.colorOnSurfaceVariant))
        }
        Image(painterResource(R.drawable.ic_right_arrow_white), null, Modifier.padding(horizontal = 20.dp).size(24.dp))
    }
}
