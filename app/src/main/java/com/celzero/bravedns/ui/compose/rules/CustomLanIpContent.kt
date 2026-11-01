/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.rules
import com.celzero.bravedns.ui.compose.controls.*
import com.celzero.bravedns.ui.compose.fields.OriginalTextField
import com.celzero.bravedns.ui.compose.fields.OriginalFieldStyle
import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.ui.compose.wireguard.WgText

internal class LanFields(context: Context) {
    val gatewayIpv4 = WgControlState(context); val gatewayIpv4Prefix = WgControlState(context)
    val gatewayIpv6 = WgControlState(context); val gatewayIpv6Prefix = WgControlState(context)
    val routerIpv4 = WgControlState(context); val routerIpv4Prefix = WgControlState(context)
    val routerIpv6 = WgControlState(context); val routerIpv6Prefix = WgControlState(context)
    val dnsIpv4 = WgControlState(context); val dnsIpv4Prefix = WgControlState(context)
    val dnsIpv6 = WgControlState(context); val dnsIpv6Prefix = WgControlState(context)
    val autoToggleBtn = WgControlState(context).apply { text = context.getString(R.string.settings_ip_text_ipv46) }
    val manualToggleBtn = WgControlState(context).apply { text = context.getString(R.string.lbl_manual) }
    val modeDesc = WgControlState(context)
    val resetButton = WgControlState(context).apply { text = context.getString(R.string.lbl_reset) }
    val saveButton = WgControlState(context).apply { text = context.getString(R.string.lbl_save) }
    val errorText = WgControlState(context).apply { visibility = View.GONE }
}
@Composable internal fun CustomLanIpContent(fields: LanFields) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((-1).dp, Alignment.CenterHorizontally)) {
                listOf(fields.autoToggleBtn, fields.manualToggleBtn).forEachIndexed { index, node ->
                    OriginalButton("dialog_custom_lan_ip:" + if (index == 0) "auto_toggle_btn" else "manual_toggle_btn", { node.performClick() }, Modifier.testTag(if (index == 0) "lan_auto" else "lan_manual"), selected = node.isChecked, segmentIndex = index, segmentCount = 2, contentColor = node.textColor?.let(::Color), backgroundTint = node.backgroundTintList) { Text(node.text.toString()) }
                }
            }
            WgText(fields.modeDesc)
            LanGroup(stringResource(R.string.custom_lan_ip_gateway), "gateway", fields.gatewayIpv4, fields.gatewayIpv4Prefix, fields.gatewayIpv6, fields.gatewayIpv6Prefix)
            LanGroup(stringResource(R.string.custom_lan_ip_router), "router", fields.routerIpv4, fields.routerIpv4Prefix, fields.routerIpv6, fields.routerIpv6Prefix)
            LanGroup(stringResource(R.string.dns_mode_info_title), "dns", fields.dnsIpv4, fields.dnsIpv4Prefix, fields.dnsIpv6, fields.dnsIpv6Prefix)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OriginalButton("dialog_custom_lan_ip:reset_button", { fields.resetButton.performClick() }, Modifier.testTag("lan_reset"), enabled = fields.resetButton.isEnabled) { Text(fields.resetButton.text.toString()) }
                OriginalButton("dialog_custom_lan_ip:save_button", { fields.saveButton.performClick() }, Modifier.testTag("lan_save"), enabled = fields.saveButton.isEnabled) { Text(fields.saveButton.text.toString()) }
            }
            if (fields.errorText.visibility == View.VISIBLE) Text(fields.errorText.text.toString(), Modifier.testTag("lan_error").semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
        }
    }
}
@Composable private fun LanGroup(title: String, tag: String, v4: WgControlState, p4: WgControlState, v6: WgControlState, p6: WgControlState) {
    Text(title, Modifier.semantics { heading() }, fontSize = 14.sp)
    LanPair(v4, p4, stringResource(R.string.settings_ip_text_ipv4), "${tag}_v4")
    LanPair(v6, p6, stringResource(R.string.settings_ip_text_ipv6), "${tag}_v6")
}
@Composable private fun LanPair(ip: WgControlState, prefix: WgControlState, label: String, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OriginalTextField(ip.text.toString(), { ip.setText(it) }, Modifier.weight(2f).testTag("lan_$tag"), style = OriginalFieldStyle.Lan, enabled = ip.isEnabled, label = { Text(label) }, singleLine = true)
        OriginalTextField(prefix.text.toString(), { prefix.setText(it.filter(Char::isDigit)) }, Modifier.weight(1f).testTag("lan_${tag}_prefix"), style = OriginalFieldStyle.Lan, enabled = prefix.isEnabled,
            label = { Text(stringResource(R.string.lbl_prefix)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
    }
}
