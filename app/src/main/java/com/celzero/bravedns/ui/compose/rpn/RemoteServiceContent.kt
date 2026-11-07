/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rpn

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor

/** View-lifecycle-owned presentation; controller retains all service and clipboard operations. */
internal class RemoteServiceState {
    var busy by mutableStateOf(false)
    val rmtCopy = RpnControlState().apply { isClickable = true }
    val rmtStartStop = RpnControlState().apply { isClickable = true; visibility = View.GONE }
    val rmtAddrCard = RpnControlState().apply { visibility = View.GONE }
    val rmtStatusRow = RpnControlState().apply { visibility = View.GONE }
    val rmtNoTunnel = RpnControlState().apply { visibility = View.GONE }
    val rmtAddr = RpnControlState()
    val rmtStatusDot = RpnControlState().apply { backgroundRes = R.drawable.dot_yellow }
    val rmtStatusText = RpnControlState()
}

@Composable
internal fun RemoteServiceContent(state: RemoteServiceState) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 18.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp, bottom = 10.dp)
            .size(40.dp, 4.dp).alpha(.5f).background(themeColor(R.attr.border)))
        Text(stringResource(R.string.rpn_remote_service_title), Modifier.padding(horizontal = 24.dp),
            color = themeColor(R.attr.primaryTextColor), fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(stringResource(R.string.rpn_remote_service_desc), Modifier.padding(horizontal = 24.dp).padding(top = 6.dp).alpha(.8f),
            color = themeColor(R.attr.primaryLightColorText), fontSize = 13.sp)
        if (state.rmtAddrCard.isVisible) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp), shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.rpn_remote_service_addr_hint), Modifier.alpha(.7f),
                            color = themeColor(R.attr.primaryLightColorText), fontSize = 10.sp, letterSpacing = .8.sp)
                        SelectionContainer {
                            Text(state.rmtAddr.text.toString(), Modifier.padding(top = 4.dp).testTag("remote_service_address"),
                                color = themeColor(R.attr.primaryTextColor), fontSize = 16.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(onClick = { state.rmtCopy.performClick() }, modifier = Modifier.testTag("remote_service_copy")) {
                        Icon(painterResource(R.drawable.ic_copy), stringResource(android.R.string.copy), tint = themeColor(R.attr.primaryLightColorText))
                    }
                }
            }
        }
        if (state.rmtNoTunnel.isVisible) {
            Text(stringResource(R.string.rpn_remote_service_no_tunnel), Modifier.padding(horizontal = 24.dp).padding(top = 14.dp).alpha(.8f),
                color = themeColor(R.attr.primaryTextColor), fontSize = 14.sp)
        }
        if (state.rmtStatusRow.isVisible) {
            val statusColor = when (state.rmtStatusDot.backgroundRes) {
                R.drawable.dot_green -> R.attr.chipTextPositive
                R.drawable.dot_red -> R.attr.chipTextNegative
                else -> R.attr.chipTextNeutral
            }
            Row(Modifier.align(Alignment.CenterHorizontally).padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(themeColor(statusColor), CircleShape))
                Text(state.rmtStatusText.text.toString(), Modifier.padding(start = 8.dp).testTag("remote_service_status"),
                    color = themeColor(R.attr.primaryLightColorText), fontSize = 13.sp)
            }
        }
        if (state.rmtStartStop.isVisible) {
            Button(onClick = { state.rmtStartStop.performClick() }, enabled = !state.busy && state.rmtStartStop.isEnabled,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp).testTag("remote_service_start_stop"),
                shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = themeColor(R.attr.accentGood))) {
                Text(state.rmtStartStop.text.toString())
            }
        }
    }
}
