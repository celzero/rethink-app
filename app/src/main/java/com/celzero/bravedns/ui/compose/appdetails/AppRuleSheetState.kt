package com.celzero.bravedns.ui.compose.appdetails
import androidx.compose.runtime.*
import android.view.View
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.ui.compose.wireguard.WgListContent
class AppDomainListState { var adapter by mutableStateOf<WgListContent?>(null) }
class AppRuleSheetState {
    val blockIcon = WgControlState()
    val bsacAppIcon = WgControlState()
    val bsacAppName = WgControlState()
    val bsacDomainList = AppDomainListState()
    val bsacDomainLl = WgControlState()
    val bsacDomainRuleTxt = WgControlState()
    val bsacIpAddressTv = WgControlState()
    val bsacIpRuleTxt = WgControlState()
    val chooseProxyRl = WgControlState()
    val trustIcon = WgControlState()
}
