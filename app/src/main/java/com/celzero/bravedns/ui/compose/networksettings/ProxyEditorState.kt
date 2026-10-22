/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.networksettings

import android.content.Context
import android.os.Bundle
import android.view.View
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.util.UIUtils

/** Native presentation state. XML-derived order and defaults; no View inflation or backend access. */
class ProxyEditorState(context: Context) {
    val node0 = WgControlState(context, checkable = false)
    val llProxyHeader = WgControlState(context, checkable = false) .apply { visibility = View.VISIBLE }
    val dialogProxyHeader = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_dns_proxy_dialog_header) }
    val dialogProxyHeaderDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_dns_proxy_dialog_app_desc) }
    val dialogProxyHeaderLockdownDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_lock_down_mode_desc) }
    val dialogProxySpinnerHeader = WgControlState(context, checkable = false)
    val node6 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_dns_proxy_dialog_app) }
    val dialogProxySpinnerAppname = NetworkSpinnerState()
    val dialogProxyIpHeader = WgControlState(context, checkable = false)
    val node9 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_dns_proxy_dialog_hostname) }
    val dialogProxyEditIp = WgControlState(context, checkable = false) .apply { hint = context.getText(R.string.settings_socks5_dialog_hint_ip); maxLines = 1 }
    val dialogProxyPortHeader = WgControlState(context, checkable = false)
    val node12 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_dns_proxy_dialog_port_number) }
    val dialogProxyEditPort = WgControlState(context, checkable = false) .apply { hint = context.getText(R.string.settings_socks5_dialog_hint_port); maxLines = 1 }
    val dialogProxyUsernameHeader = WgControlState(context, checkable = false)
    val node15 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_dns_proxy_dialog_username) }
    val dialogProxyEditUsername = WgControlState(context, checkable = false) .apply { hint = context.getText(R.string.settings_socks5_dialog_hint_username); maxLines = 1 }
    val dialogProxyPasswordHeader = WgControlState(context, checkable = false)
    val node18 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_dns_proxy_dialog_password) }
    val dialogProxyEditPassword = WgControlState(context, checkable = false) .apply { hint = context.getText(R.string.settings_socks5_dialog_hint_password); isPassword = true; maxLines = 1 }
    val dialogProxyUdpHeader = WgControlState(context, checkable = false)
    val node21 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.univ_firewall_rule_4) }
    val dialogProxyUdpCheck = WgControlState(context, checkable = true)
    val dialogProxyExcludeAppsHeader = WgControlState(context, checkable = false)
    val node24 = WgControlState(context, checkable = false)
    val node25 = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_exclude_proxy_apps_heading) }
    val dialogProxyExcludeAppsDesc = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_exclude_proxy_apps_desc) }
    val dialogProxyExcludeAppsCheck = WgControlState(context, checkable = true)
    val dialogProxyErrorText = WgControlState(context, checkable = false) .apply { textColor = UIUtils.fetchColor(context, androidx.appcompat.R.attr.colorError) }
    val node29 = WgControlState(context, checkable = false)
    val dialogProxyCancelBtn = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.lbl_cancel); textColor = UIUtils.fetchColor(context, R.attr.accentGood) }
    val dialogProxyApplyBtn = WgControlState(context, checkable = false) .apply { text = context.getText(R.string.settings_dns_proxy_dialog_positive_btn); textColor = UIUtils.fetchColor(context, R.attr.accentGood) }
    val layout = NetworkNode(NetworkKind.SCROLL, control = node0, fillWidth = true, children = listOf(
        NetworkNode(NetworkKind.COLUMN, control = llProxyHeader, key = "network.ll_proxy_header", margin = NetworkInsets(5f, 5f, 5f, 5f), fillWidth = true, children = listOf(
            NetworkNode(NetworkKind.TEXT, control = dialogProxyHeader, key = "network.dialog_proxy_header", appearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead, textSizeRes = R.dimen.large_font_text_view, padding = NetworkInsets(10f, 10f, 10f, 10f), fillWidth = true),
            NetworkNode(NetworkKind.TEXT, control = dialogProxyHeaderDesc, key = "network.dialog_proxy_header_desc", textSizeRes = R.dimen.default_font_text_view, alignEnd = true, padding = NetworkInsets(10f, 0f, 10f, 0f), fillWidth = true),
            NetworkNode(NetworkKind.TEXT, control = dialogProxyHeaderLockdownDesc, key = "network.dialog_proxy_header_lockdown_desc", textSizeRes = R.dimen.default_font_text_view, alignEnd = true, padding = NetworkInsets(10f, 10f, 10f, 0f), fillWidth = true),
            NetworkNode(NetworkKind.ROW, control = dialogProxySpinnerHeader, key = "network.dialog_proxy_spinner_header", margin = NetworkInsets(0f, 5f, 0f, 0f), padding = NetworkInsets(10f, 10f, 10f, 0f), fillWidth = true, children = listOf(
                NetworkNode(NetworkKind.TEXT, control = node6, textSizeRes = R.dimen.large_font_text_view, width = 0f, weight = 0.3f),
                NetworkNode(NetworkKind.SPINNER, spinner = dialogProxySpinnerAppname, key = "network.dialog_proxy_spinner_appname", textSizeRes = R.dimen.large_font_text_view, width = 0f, weight = 0.7f)
            )),
            NetworkNode(NetworkKind.ROW, control = dialogProxyIpHeader, key = "network.dialog_proxy_ip_header", padding = NetworkInsets(10f, 10f, 10f, 10f), fillWidth = true, children = listOf(
                NetworkNode(NetworkKind.TEXT, control = node9, textSizeRes = R.dimen.large_font_text_view, width = 0f, weight = 0.3f),
                NetworkNode(NetworkKind.FIELD, control = dialogProxyEditIp, key = "network.dialog_proxy_edit_ip", textSizeRes = R.dimen.large_font_text_view, fontFamily = "monospace", width = 0f, weight = 0.7f)
            )),
            NetworkNode(NetworkKind.ROW, control = dialogProxyPortHeader, key = "network.dialog_proxy_port_header", padding = NetworkInsets(10f, 10f, 10f, 10f), fillWidth = true, children = listOf(
                NetworkNode(NetworkKind.TEXT, control = node12, textSizeRes = R.dimen.large_font_text_view, width = 0f, weight = 0.3f),
                NetworkNode(NetworkKind.FIELD, control = dialogProxyEditPort, key = "network.dialog_proxy_edit_port", textSizeRes = R.dimen.large_font_text_view, fontFamily = "monospace", width = 0f, numeric = true, weight = 0.7f)
            )),
            NetworkNode(NetworkKind.ROW, control = dialogProxyUsernameHeader, key = "network.dialog_proxy_username_header", padding = NetworkInsets(10f, 10f, 10f, 10f), fillWidth = true, children = listOf(
                NetworkNode(NetworkKind.TEXT, control = node15, textSizeRes = R.dimen.large_font_text_view, width = 0f, weight = 0.3f),
                NetworkNode(NetworkKind.FIELD, control = dialogProxyEditUsername, key = "network.dialog_proxy_edit_username", textSizeRes = R.dimen.large_font_text_view, fontFamily = "monospace", width = 0f, weight = 0.7f)
            )),
            NetworkNode(NetworkKind.ROW, control = dialogProxyPasswordHeader, key = "network.dialog_proxy_password_header", padding = NetworkInsets(10f, 10f, 10f, 10f), fillWidth = true, children = listOf(
                NetworkNode(NetworkKind.TEXT, control = node18, textSizeRes = R.dimen.large_font_text_view, width = 0f, weight = 0.3f),
                NetworkNode(NetworkKind.FIELD, control = dialogProxyEditPassword, key = "network.dialog_proxy_edit_password", textSizeRes = R.dimen.large_font_text_view, fontFamily = "monospace", width = 0f, weight = 0.7f)
            )),
            NetworkNode(NetworkKind.ROW, control = dialogProxyUdpHeader, key = "network.dialog_proxy_udp_header", padding = NetworkInsets(10f, 10f, 10f, 10f), fillWidth = true, children = listOf(
                NetworkNode(NetworkKind.TEXT, control = node21, textSizeRes = R.dimen.large_font_text_view, width = 0f, weight = 0.9f),
                NetworkNode(NetworkKind.CHECK, control = dialogProxyUdpCheck, key = "network.dialog_proxy_udp_check", width = 0f, weight = 0.1f)
            )),
            NetworkNode(NetworkKind.ROW, control = dialogProxyExcludeAppsHeader, key = "network.dialog_proxy_exclude_apps_header", padding = NetworkInsets(10f, 10f, 10f, 0f), fillWidth = true, children = listOf(
                NetworkNode(NetworkKind.COLUMN, control = node24, margin = NetworkInsets(0f, 0f, 10f, 0f), width = 0f, weight = 0.9f, children = listOf(
                    NetworkNode(NetworkKind.TEXT, control = node25, textSizeRes = R.dimen.large_font_text_view),
                    NetworkNode(NetworkKind.TEXT, control = dialogProxyExcludeAppsDesc, key = "network.dialog_proxy_exclude_apps_desc", textSizeRes = R.dimen.default_font_text_view, padding = NetworkInsets(0f, 5f, 0f, 0f), fillWidth = true)
                )),
                NetworkNode(NetworkKind.CHECK, control = dialogProxyExcludeAppsCheck, key = "network.dialog_proxy_exclude_apps_check", width = 0f, weight = 0.1f)
            )),
            NetworkNode(NetworkKind.TEXT, control = dialogProxyErrorText, key = "network.dialog_proxy_error_text", textSizeRes = R.dimen.default_font_text_view, padding = NetworkInsets(10f, 0f, 10f, 0f), fillWidth = true),
            NetworkNode(NetworkKind.ROW, control = node29, alignEnd = true, fillWidth = true, children = listOf(
                NetworkNode(NetworkKind.BUTTON, control = dialogProxyCancelBtn, key = "network.dialog_proxy_cancel_btn", textSizeRes = R.dimen.large_font_text_view, centerText = true, margin = NetworkInsets(5f, 5f, 5f, 5f)),
                NetworkNode(NetworkKind.BUTTON, control = dialogProxyApplyBtn, key = "network.dialog_proxy_apply_btn", textSizeRes = R.dimen.large_font_text_view, centerText = true, margin = NetworkInsets(5f, 5f, 5f, 5f))
            ))
        ))
    ))
}
