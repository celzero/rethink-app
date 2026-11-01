/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.settings

import com.celzero.bravedns.ui.compose.controls.*

import android.os.Bundle
import android.graphics.Typeface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.wireguard.WgControlState

class AntiCensorshipState {
    val acDesyncRl = WgControlState()
    val acNeverSplitRl = WgControlState()
    val acRadioDesync = WgControlState()
    val acRadioNeverRetry = WgControlState()
    val acRadioNeverSplit = WgControlState()
    val acRadioRetryAfterSplit = WgControlState()
    val acRadioRetryWithSplit = WgControlState()
    val acRadioSplitAuto = WgControlState()
    val acRadioSplitTcp = WgControlState()
    val acRadioSplitTls = WgControlState()
    val acRadioTcpProxy = WgControlState()
    val acRetryAfterSplitRl = WgControlState()
    val acRetryNeverRl = WgControlState()
    val acRetryWithSplitRl = WgControlState()
    val acSplitAutoRl = WgControlState()
    val acSplitTcpRl = WgControlState()
    val acSplitTlsRl = WgControlState()
    val acTcpProxyRl = WgControlState()
    val settingsActivityVpnHeadingText = WgControlState()
    private val checks get() = listOf(acRadioNeverSplit, acRadioSplitAuto, acRadioSplitTcp, acRadioSplitTls, acRadioDesync, acRadioTcpProxy, acRadioNeverRetry, acRadioRetryWithSplit, acRadioRetryAfterSplit)
    fun saveChecks(bundle: Bundle) { checks.forEachIndexed { i, c -> bundle.putBoolean("compose.anticensor.$i", c.isChecked) } }
    fun restoreChecks(bundle: Bundle) { checks.forEachIndexed { i, c -> if (bundle.containsKey("compose.anticensor.$i")) c.isChecked = bundle.getBoolean("compose.anticensor.$i") } }
}

@Composable
fun AntiCensorshipContent(b: AntiCensorshipState) {
    Surface(color = themeColor(R.attr.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(bottom = 60.dp)) {
            AntiCensorshipHeading(b.settingsActivityVpnHeadingText.text.toString())
            Text(stringResource(R.string.ac_desc), Modifier.padding(horizontal = 10.dp).padding(10.dp),
                color = themeColor(R.attr.primaryLightColorText), fontSize = 12.sp)
            SettingsCard(contentPadding = 10.dp, horizontalContentPadding = 5.dp) {
                AntiCensorshipRow("ac_radio_never_split", b.acNeverSplitRl, b.acRadioNeverSplit, stringResource(R.string.settings_app_list_default_app), stringResource(R.string.ac_never_split_desc), 5.dp)
                AntiCensorshipRow("ac_radio_split_auto", b.acSplitAutoRl, b.acRadioSplitAuto, stringResource(R.string.settings_ip_text_ipv46), stringResource(R.string.ac_split_auto_desc), 5.dp)
                AntiCensorshipRow("ac_radio_split_tcp", b.acSplitTcpRl, b.acRadioSplitTcp, stringResource(R.string.ac_split_tcp), stringResource(R.string.ac_split_tcp_desc), 5.dp)
                AntiCensorshipRow("ac_radio_split_tls", b.acSplitTlsRl, b.acRadioSplitTls, stringResource(R.string.ac_split_tls), stringResource(R.string.ac_split_tls_desc), 5.dp)
                AntiCensorshipRow("ac_radio_desync", b.acDesyncRl, b.acRadioDesync, stringResource(R.string.ac_desync), stringResource(R.string.ac_desync_desc), 20.dp)
                AntiCensorshipRow("ac_radio_tcp_proxy", b.acTcpProxyRl, b.acRadioTcpProxy, stringResource(R.string.ac_tcp_proxy), stringResource(R.string.ac_tcp_proxy_desc), 20.dp)
            }
            AntiCensorshipHeading(stringResource(R.string.ac_retry_options_title))
            Text(stringResource(R.string.ac_retry_options_desc), Modifier.padding(horizontal = 10.dp).padding(10.dp),
                color = themeColor(R.attr.primaryLightColorText), fontSize = 12.sp)
            SettingsCard(contentPadding = 10.dp, horizontalContentPadding = 5.dp) {
                AntiCensorshipRow("ac_radio_never_retry", b.acRetryNeverRl, b.acRadioNeverRetry, stringResource(R.string.settings_app_list_default_app), stringResource(R.string.ac_retry_options_never_desc), 5.dp)
                AntiCensorshipRow("ac_radio_retry_with_split", b.acRetryWithSplitRl, b.acRadioRetryWithSplit, stringResource(R.string.settings_ip_text_ipv46), stringResource(R.string.ac_retry_options_with_split_desc), 5.dp)
                AntiCensorshipRow("ac_radio_retry_after_split", b.acRetryAfterSplitRl, b.acRadioRetryAfterSplit, stringResource(R.string.lbl_always), stringResource(R.string.ac_retry_options_after_split_desc), 20.dp)
            }
        }
    }
}

@Composable
private fun AntiCensorshipHeading(text: String) {
    val extra = with(LocalDensity.current) { 5.dp.toSp() }
    Text(text, Modifier.padding(start = 55.dp, top = 15.dp).padding(5.dp).semantics { heading() },
        fontFamily = FontFamily(Typeface.create("sans-serif-smallcaps", Typeface.BOLD)), fontWeight = FontWeight.Bold,
        fontSize = 16.sp, lineHeight = (16f * 1.2f + extra.value).sp, color = themeColor(R.attr.accentGood))
}

@Composable
private fun AntiCensorshipRow(sourceId: String, row: WgControlState, radio: WgControlState, title: String, description: String, bottom: androidx.compose.ui.unit.Dp) {
    if (!row.isVisible) return
    Row(Modifier.fillMaxWidth().alpha(row.alpha).padding(bottom = 1.dp).clickable { row.performClick() }.padding(top = 5.dp, bottom = bottom),
        verticalAlignment = Alignment.CenterVertically) {
        OriginalRadioButton("activity_anti_censorship:$sourceId", radio.isChecked, { if (!radio.isChecked) radio.isChecked = true }, enabled = radio.isEnabled)
        Column(Modifier.weight(1f)) {
            Text(title, Modifier.padding(vertical = 5.dp), fontSize = 14.sp, color = themeColor(R.attr.primaryTextColor))
            Text(description, Modifier.padding(bottom = 5.dp), fontSize = 12.sp, color = themeColor(R.attr.secondaryTextColor))
        }
    }
}
