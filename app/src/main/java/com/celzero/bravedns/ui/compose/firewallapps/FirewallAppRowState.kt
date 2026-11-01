package com.celzero.bravedns.ui.compose.firewallapps
import androidx.compose.runtime.*
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
class FirewallAppRowState {
    var strikeThrough by mutableStateOf(false)
    val firewallAppDataUsage = WgControlState()
    val firewallAppDetailsLl = WgControlState()
    val firewallAppIconIv = WgControlState()
    val firewallAppLabelTv = WgControlState()
    val firewallAppTextLl = WgControlState()
    val firewallAppToggleMobileData = WgControlState()
    val firewallAppToggleOther = WgControlState()
    val firewallAppToggleWifi = WgControlState()
}
