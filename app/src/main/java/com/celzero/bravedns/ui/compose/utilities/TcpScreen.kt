/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.utilities

import com.celzero.bravedns.ui.compose.controls.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor

@Composable fun TcpScreen(ui: TcpUiState) {
    Surface(Modifier.fillMaxSize(), color = themeColor(R.attr.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UtilityText(ui.settingsProxy, Modifier.padding(start = 39.dp).semantics { heading() })
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    TcpToggle("Rethink's Proxy", ui.tcpProxySwitch)
                    UtilityText(ui.tcpProxyDesc, Modifier.padding(vertical = 5.dp))
                    UtilityText(ui.tcpProxyStatus)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UtilityText(ui.enableUdpRelay, Modifier.weight(1f))
                        OriginalCheckbox("activity_tcp_proxy:enable_udp_relay", ui.enableUdpRelay.isChecked, { ui.enableUdpRelay.isChecked = it }, Modifier.testTag(ui.enableUdpRelay.tag).semantics { contentDescription = "Enable UDP Relay" })
                    }
                    UtilityButton(ui.tcpProxyAddApps, source = "activity_tcp_proxy:tcp_proxy_add_apps")
                    if (ui.tcpErrorRl.isVisible) UtilityText(ui.tcpErrorTv)
                }
            }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp)) {
                    TcpToggle("Cloudflare WARP", ui.warpSwitch)
                    UtilityText(ui.warpDesc, Modifier.padding(vertical = 5.dp))
                    UtilityText(ui.warpStatus)
                    Text("more...", Modifier.align(Alignment.End), style = utilityTextStyle(), color = themeColor(R.attr.primaryTextColor))
                }
            }
        }
    }
}
@Composable private fun TcpToggle(label: String, state: UtilityControl) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        utilityImage(R.drawable.ic_wireguard_icon, Modifier.size(32.dp))
        Text(label, Modifier.weight(1f).padding(horizontal = 12.dp), style = utilityTextStyle(size = 16f), color = themeColor(R.attr.secondaryTextColor))
        OriginalSwitch(state.tag.replace('.', ':'), state.isChecked, { state.isChecked = it }, Modifier.testTag(state.tag).semantics { contentDescription = label })
    }
}
