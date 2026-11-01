/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.rpnconfig

import com.celzero.bravedns.ui.compose.controls.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.wireguard.WgControlState

class RpnWinState {
    // Preserve the original XML defaults until controller counts arrive.
    val frwpAppsCount = WgControlState().apply { text = "6" }
    val frwpDomainsCount = WgControlState().apply { text = "24" }
    val frwpIpsCount = WgControlState().apply { text = "41" }
    val openAppSelector = WgControlState().apply { text = "Select Apps For Proxy" }
}

@Composable
fun RpnWinContent(b: RpnWinState) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(b.frwpAppsCount to "Apps", b.frwpDomainsCount to "Domains", b.frwpIpsCount to "IPs").forEachIndexed { index, (state, label) ->
                    Surface(Modifier.weight(1f).heightIn(min = 72.dp), shape = RoundedCornerShape(16.dp), color = Color.Transparent, border = BorderStroke(1.dp, themeColor(R.attr.accentGood))) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text(state.text.toString(), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = themeColor(if (index == 0) R.attr.secondaryTextColor else R.attr.accentGood))
                            Text(label, fontSize = 13.sp)
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Proxy Name", Modifier.padding(top = 20.dp, start = 4.dp), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                WinInfo(R.drawable.ic_add, "who_or_service_email@domain.com", 18)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    WinInfo(R.drawable.firewall_home_screen, "US", 14); WinInfo(R.drawable.ic_firewall_lockdown_off, "37 ms", 14); WinInfo(R.drawable.ic_timeout, "2 min ago", 14)
                }
                Surface(shape = RoundedCornerShape(16.dp), color = Color.Transparent, border = BorderStroke(1.dp, themeColor(R.attr.accentGood))) {
                    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("CONNECTED", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = themeColor(R.attr.chipTextPositive)); Text("Status", fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(96.dp))
            }
            OriginalButton("activity_rpn_win_proxy_detail:open_app_selector", { b.openAppSelector.performClick() }, modifier = Modifier.fillMaxWidth().padding(28.dp).testTag("rpn.win.apps")) {
                Icon(painterResource(R.drawable.ic_loop_back_app), null, Modifier.size(24.dp)); Text(b.openAppSelector.text.toString(), Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun WinInfo(icon: Int, label: String, size: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(24.dp), tint = themeColor(R.attr.secondaryTextColor)); Text(label, Modifier.padding(start = 10.dp), fontSize = size.sp)
    }
}
