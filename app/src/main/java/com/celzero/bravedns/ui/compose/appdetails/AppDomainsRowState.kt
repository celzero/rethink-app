package com.celzero.bravedns.ui.compose.appdetails
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
class AppDomainsRowState {
    val acdContainer = WgControlState()
    val acdCount = WgControlState()
    val acdDomain = WgControlState()
    val acdFlag = WgControlState()
    val acdIcon = WgControlState()
    val acdIpAddress = WgControlState()
    val progress = WgControlState()
}
