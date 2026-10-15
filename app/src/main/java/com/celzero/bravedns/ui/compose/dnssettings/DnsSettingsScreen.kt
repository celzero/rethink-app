/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.dnssettings

import com.celzero.bravedns.ui.compose.controls.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.settings.SettingsCard
import com.celzero.bravedns.ui.compose.settings.NoDimDialogWindow

@Composable
fun DnsSettingsScreen(b: DnsSettingsState, onRestore: () -> Unit, onCopySystemDns: (String) -> Unit) {
    Surface(color = themeColor(R.attr.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(start = 55.dp, top = 10.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.dc_global_heading), Modifier.weight(1f).padding(5.dp).semantics { heading() }, color = themeColor(androidx.appcompat.R.attr.colorPrimary), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = b.dcRestoreDefaults.onClick, modifier = Modifier.testTag("dns-restore")) { Icon(painterResource(R.drawable.ic_backup_restore), stringResource(R.string.restore_defaults_dialog_title), tint = Color.Unspecified) }
                val rotation = if (b.refreshing) {
                    val transition = rememberInfiniteTransition(label = "dns-refresh")
                    transition.animateFloat(0f, 360f, infiniteRepeatable(tween(750, easing = LinearEasing)), label = "rotation").value
                } else 0f
                IconButton(onClick = b.dcRefresh.onClick, enabled = b.dcRefresh.isEnabled, modifier = Modifier.testTag("dns-refresh")) { Icon(painterResource(R.drawable.ic_refresh_white), stringResource(R.string.dns_settings_refresh_action), Modifier.rotate(rotation), tint = Color.Unspecified) }
            }
            SettingsCard(Modifier.padding(4.dp), contentPadding = 10.dp, cornerRadius = 8.dp) {
                DnsChoice(b.networkDnsRb, R.string.network_dns, "network", b.networkDnsInfo.onClick)
                DnsChoice(b.customDnsRb, R.string.dc_custom_dns_radio, "custom")
                DnsChoice(b.rethinkPlusDnsRb, R.string.dc_rethink_dns_radio, "rethink")
                DnsChoice(b.smartDnsRb, R.string.smart_dns, "smart")
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).alpha(.5f)) {
                    Text(b.connectedStatusTitle.text, Modifier.weight(1f).padding(5.dp), fontSize = 14.sp, color = themeColor(R.attr.primaryTextColor))
                    Text(b.connectedStatusTitleUrl.text, Modifier.weight(1f).padding(5.dp), textAlign = TextAlign.End, fontSize = 14.sp, color = themeColor(R.attr.primaryTextColor))
                }
            }
            DnsHeading(R.string.dc_block_heading)
            SettingsCard(Modifier.padding(4.dp), cornerRadius = 8.dp) {
                DnsSettingRow("dcLocalBlocklistRl", b.dcLocalBlocklistRl, R.string.dc_local_block_heading, b.dcLocalBlocklistDesc.text.ifEmpty { stringResource(R.string.dc_local_block_desc_1) }, R.drawable.ic_local_blocklist, null, count = b.dcLocalBlocklistCount)
                if (b.dcLocalBlocklistRl.visible) HorizontalDivider(Modifier.padding(start = 60.dp), color = themeColor(R.attr.primaryLightColorText).copy(alpha = .12f))
                DnsSettingRow("dcDownloaderRl", b.dcDownloaderRl, R.string.settings_custom_downloader_heading, stringResource(R.string.settings_custom_downloader_desc), R.drawable.ic_update, b.dcDownloaderSwitch)
                if (b.dcDownloaderRl.visible) HorizontalDivider(Modifier.padding(start = 60.dp), color = themeColor(R.attr.primaryLightColorText).copy(alpha = .12f))
                DnsSettingRow("dcCheckUpdateRl", b.dcCheckUpdateRl, R.string.dc_check_update_heading, stringResource(R.string.dc_check_update_desc), R.drawable.ic_blocklist_update_check, b.dcCheckUpdateSwitch)
            }
            DnsHeading(R.string.dc_filtering_heading)
            SettingsCard(Modifier.padding(4.dp), cornerRadius = 8.dp) {
                DnsSettingRow("dcAlgRl", b.dcAlgRl, R.string.cd_dns_alg_heading, stringResource(R.string.cd_dns_alg_desc), R.drawable.ic_adv_dns_filter, b.dcAlgSwitch)
                if (b.dcAlgRl.visible) HorizontalDivider(Modifier.padding(start = 60.dp), color = themeColor(R.attr.primaryLightColorText).copy(alpha = .12f))
                DnsSettingRow("dcSplitDnsRl", b.dcSplitDnsRl, R.string.cd_split_dns_heading, stringResource(R.string.cd_split_dns_desc), R.drawable.ic_split_dns, b.dcSplitDnsSwitch)
                if (b.dcSplitDnsRl.visible) HorizontalDivider(Modifier.padding(start = 60.dp), color = themeColor(R.attr.primaryLightColorText).copy(alpha = .12f))
                DnsSettingRow("dcAllowedRecordTypesRl", b.dcAllowedRecordTypesRl, R.string.cd_allowed_dns_record_types_heading, b.dcAllowedRecordTypesDesc.text.ifEmpty { stringResource(R.string.cd_allowed_dns_record_types_desc) }, R.drawable.ic_allow_dns_records, null)
            }
            DnsHeading(R.string.lbl_advanced)
            SettingsCard(Modifier.padding(4.dp), cornerRadius = 8.dp) {
                DnsSettingRow("dcFaviconRl", b.dcFaviconRl, R.string.dc_dns_website_heading, stringResource(R.string.dc_dns_website_desc), R.drawable.ic_fav_icon, b.dcFaviconSwitch)
                if (b.dcFaviconRl.visible) HorizontalDivider(Modifier.padding(start = 60.dp), color = themeColor(R.attr.primaryLightColorText).copy(alpha = .12f))
                DnsSettingRow("dcEnableCacheRl", b.dcEnableCacheRl, R.string.dc_setting_dns_cache_heading, stringResource(R.string.dc_setting_dns_cache_desc), R.drawable.ic_auto_start, b.dcEnableCacheSwitch)
                if (b.dcEnableCacheRl.visible) HorizontalDivider(Modifier.padding(start = 60.dp), color = themeColor(R.attr.primaryLightColorText).copy(alpha = .12f))
                DnsSettingRow("dcProxyDnsRl", b.dcProxyDnsRl, R.string.dc_proxy_dns_heading, stringResource(R.string.dc_proxy_dns_desc), R.drawable.ic_prevent_dns_proxy, b.dcProxyDnsSwitch)
                if (b.dcProxyDnsRl.visible) HorizontalDivider(Modifier.padding(start = 60.dp), color = themeColor(R.attr.primaryLightColorText).copy(alpha = .12f))
                DnsSettingRow("dcPreventDnsLeaksRl", b.dcPreventDnsLeaksRl, R.string.dc_dns_leaks_heading, stringResource(R.string.dc_dns_leaks_desc), R.drawable.ic_prevent_dns_leaks, b.dcPreventDnsLeaksSwitch)
                if (b.dcPreventDnsLeaksRl.visible) HorizontalDivider(Modifier.padding(start = 60.dp), color = themeColor(R.attr.primaryLightColorText).copy(alpha = .12f))
                DnsSettingRow("dcBlockHeadingRl", b.dcBlockHeadingRl, R.string.dc_block_unknown_heading, stringResource(R.string.dc_block_unknown_desc), R.drawable.ic_block_unknown_dns, b.dcBlockUnknownSwitch)
                if (b.dcBlockHeadingRl.visible) HorizontalDivider(Modifier.padding(start = 60.dp), color = themeColor(R.attr.primaryLightColorText).copy(alpha = .12f))
                DnsSettingRow("dcUndelegatedDomainsRl", b.dcUndelegatedDomainsRl, R.string.dc_use_sys_dns_undelegated_heading, stringResource(R.string.dc_use_sys_dns_undelegated_desc), R.drawable.ic_undelegated_domain, b.dcUndelegatedDomainsSwitch)
                if (b.dcUndelegatedDomainsRl.visible) HorizontalDivider(Modifier.padding(start = 60.dp), color = themeColor(R.attr.primaryLightColorText).copy(alpha = .12f))
                DnsSettingRow("dcBlockFreeDnsRl", b.dcBlockFreeDnsRl, R.string.bfdm_heading, b.dcBlockFreeDnsDesc.text.ifEmpty { "" }, R.drawable.ic_use_fallback_bypass, null)
            }
        }
    }
    if (b.restorePrompt) DnsPrompt(stringResource(R.string.restore_defaults_dialog_title), stringResource(R.string.restore_defaults_dialog_message), { b.restorePrompt = false }, stringResource(R.string.lbl_proceed), onRestore, stringResource(R.string.lbl_cancel), { b.restorePrompt = false })
    b.systemDns?.let { dns -> DnsPrompt(stringResource(R.string.network_dns), dns, { b.systemDns = null }, stringResource(R.string.ada_noapp_dialog_positive), { b.systemDns = null }, stringResource(R.string.dns_info_neutral), { onCopySystemDns(dns) }) }
}

@Composable private fun DnsHeading(title: Int) {
    Text(stringResource(title), Modifier.padding(start = 60.dp, top = 15.dp, bottom = 10.dp).semantics { heading() }, color = themeColor(androidx.appcompat.R.attr.colorPrimary), style = MaterialTheme.typography.titleSmall)
}

@Composable private fun DnsChoice(control: DnsControl, label: Int, key: String, onInfo: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 48.dp).testTag("dns-choice-$key").clickable(role = Role.RadioButton) { control.isChecked = true; control.onClick() }, verticalAlignment = Alignment.CenterVertically) {
            OriginalRadioButton("fragment_dns_configure:" + when (key) { "rethink" -> "rethink_plus_dns_rb"; else -> "${key}_dns_rb" }, control.isChecked, onClick = null, enabled = control.isEnabled, indicatorOnly = true)
            Text(stringResource(label), Modifier.weight(1f), color = themeColor(R.attr.primaryTextColor))
            if (onInfo == null) Icon(painterResource(R.drawable.ic_right_arrow_white), null, tint = Color.Unspecified)
        }
        if (onInfo != null) IconButton(onClick = onInfo, modifier = Modifier.testTag("dns-system-info")) { Icon(painterResource(R.drawable.ic_info_white_16), stringResource(R.string.dns_settings_system_info_action), tint = Color.Unspecified) }
    }
}

@Composable private fun DnsSettingRow(key: String, row: DnsControl, title: Int, description: String, icon: Int, toggle: DnsControl?, count: DnsControl? = null) {
    if (!row.visible) return
    Row(Modifier.fillMaxWidth().alpha(row.alpha).testTag("dns-row-$key").clickable(enabled = row.isEnabled && row.isClickable, onClick = row.onClick).padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.padding(horizontal = 15.dp).size(24.dp), tint = Color.Unspecified)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(title), Modifier.weight(1f), color = themeColor(R.attr.primaryTextColor), fontSize = 14.sp)
                count?.let { Text(it.text, color = it.color?.let(::Color) ?: themeColor(R.attr.secondaryTextColor), fontSize = 12.sp) }
            }
            Text(description, Modifier.padding(top = 5.dp), fontSize = 12.sp, color = themeColor(R.attr.primaryTextColor))
        }
        if (toggle != null) OriginalSwitch("fragment_dns_configure:" + requireNotNull(dnsSwitchSources[key]), toggle.isChecked, { toggle.isChecked = it }, Modifier.testTag("dns-switch-$key"), enabled = toggle.isEnabled && toggle.isClickable)
        else Icon(painterResource(R.drawable.ic_arrow_down_small), null, Modifier.padding(10.dp), tint = Color.Unspecified)
    }
}

@Composable private fun DnsPrompt(title: String, message: String, dismiss: () -> Unit, positive: String, confirm: () -> Unit, secondary: String, secondaryAction: () -> Unit) {
    NoDimDialogWindow(onDismissRequest = dismiss, properties = DialogProperties()) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = themeColor(R.attr.background)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = themeColor(R.attr.primaryTextColor))
                Text(message, Modifier.padding(top = 16.dp, bottom = 16.dp), color = themeColor(R.attr.primaryTextColor))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OriginalButton("@dialog_negative", onClick = secondaryAction, modifier = Modifier.testTag("dns-dialog-secondary")) { Text(secondary, color = themeColor(R.attr.secondaryTextColor)) }
                    OriginalButton("@dialog_positive", onClick = confirm, modifier = Modifier.testTag("dns-dialog-positive")) { Text(positive, color = themeColor(androidx.appcompat.R.attr.colorPrimary)) }
                }
            }
        }
    }
}

private val dnsSwitchSources = mapOf(
    "dcDownloaderRl" to "dc_downloader_switch",
    "dcCheckUpdateRl" to "dc_check_update_switch",
    "dcAlgRl" to "dc_alg_switch",
    "dcSplitDnsRl" to "dc_split_dns_switch",
    "dcFaviconRl" to "dc_favicon_switch",
    "dcEnableCacheRl" to "dc_enable_cache_switch",
    "dcProxyDnsRl" to "dc_proxy_dns_switch",
    "dcPreventDnsLeaksRl" to "dc_prevent_dns_leaks_switch",
    "dcBlockHeadingRl" to "dc_block_unknown_switch",
    "dcUndelegatedDomainsRl" to "dc_undelegated_domains_switch",
)
