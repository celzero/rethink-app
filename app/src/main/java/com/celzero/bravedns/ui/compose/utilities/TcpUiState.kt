/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.utilities
import android.content.Context
import androidx.compose.runtime.*
import com.celzero.bravedns.R

class TcpUiState(context: Context) {
    val settingsProxy = UtilityControl(context, "activity_tcp_proxy.settings_proxy").apply { text = "proxy"; textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.accentGood); fontSize = utilityDimensionSp(context, R.dimen.extra_large_font_text_view); fontFamily = "sans-serif-smallcaps"; fontStyle = 1; textAppearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead }
    val tcpProxyContainer = UtilityControl(context, "activity_tcp_proxy.tcp_proxy_container").apply { visibility = android.view.View.VISIBLE }
    val tcpProxyIcon = UtilityControl(context, "activity_tcp_proxy.tcp_proxy_icon").apply { imageRes = R.drawable.ic_wireguard_icon }
    val tcpProxyHeadingRl = UtilityControl(context, "activity_tcp_proxy.tcp_proxy_heading_rl").apply {  }
    val tcpProxySwitch = UtilityControl(context, "activity_tcp_proxy.tcp_proxy_switch").apply {  }
    val tcpProxyDesc = UtilityControl(context, "activity_tcp_proxy.tcp_proxy_desc").apply { text = "Controlled access over the internet. Improves the security and enhances the privacy of the user. Ensured Privacy. Blocked Sites Becomes Accessible."; fontSize = utilityDimensionSp(context, R.dimen.default_font_text_view) }
    val tcpProxyStatus = UtilityControl(context, "activity_tcp_proxy.tcp_proxy_status").apply { textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.secondaryTextColor) }
    val enableUdpRelay = UtilityControl(context, "activity_tcp_proxy.enable_udp_relay").apply { text = "Enable UDP Relay" }
    val tcpProxyAddApps = UtilityControl(context, "activity_tcp_proxy.tcp_proxy_add_apps").apply { text = context.getText(R.string.add_remove_apps); textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.secondaryTextColor); fontSize = utilityDimensionSp(context, R.dimen.large_font_text_view) }
    val tcpErrorRl = UtilityControl(context, "activity_tcp_proxy.tcp_error_rl").apply {  }
    val tcpErrorTv = UtilityControl(context, "activity_tcp_proxy.tcp_error_tv").apply { text = "Error"; visibility = android.view.View.GONE; textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.accentBad); fontSize = utilityDimensionSp(context, R.dimen.default_font_text_view) }
    val warpCardView = UtilityControl(context, "activity_tcp_proxy.warp_card_view").apply {  }
    val warpTopRl = UtilityControl(context, "activity_tcp_proxy.warp_top_rl").apply { visibility = android.view.View.VISIBLE }
    val warpIcon = UtilityControl(context, "activity_tcp_proxy.warp_icon").apply { imageRes = R.drawable.ic_wireguard_icon }
    val warpHeadingRl = UtilityControl(context, "activity_tcp_proxy.warp_heading_rl").apply {  }
    val warpSwitch = UtilityControl(context, "activity_tcp_proxy.warp_switch").apply {  }
    val warpDesc = UtilityControl(context, "activity_tcp_proxy.warp_desc").apply { text = "Modern, fast, and secure way to connect to a VPN server. Secure all your internet traffic with an ultra-secure, lightning-quick VPN service that lets you connect with the WireGuard protocol."; fontSize = utilityDimensionSp(context, R.dimen.default_font_text_view) }
    val warpStatus = UtilityControl(context, "activity_tcp_proxy.warp_status").apply { textColor = com.celzero.bravedns.util.UIUtils.fetchColor(context, R.attr.secondaryTextColor); fontSize = utilityDimensionSp(context, R.dimen.default_font_text_view) }
    val savedControls get() = listOf<UtilityControl>(tcpProxySwitch, enableUdpRelay, warpSwitch)
}
