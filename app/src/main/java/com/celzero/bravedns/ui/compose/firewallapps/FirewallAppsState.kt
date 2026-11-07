package com.celzero.bravedns.ui.compose.firewallapps
import android.view.animation.Animation
import androidx.compose.runtime.*
import com.celzero.bravedns.ui.compose.rpn.RpnControlState
import com.celzero.bravedns.ui.compose.appdetails.AppSearchState
import com.celzero.bravedns.adapter.FirewallAppListAdapter
class AppListControlState : RpnControlState() {
    var tag:Any="0"
    var animation:Animation?=null
    var spinning by mutableStateOf(false)
    var tooltipGeneration by mutableIntStateOf(0)
    fun startAnimation(value:Animation){spinning=true}
    fun clearAnimation(){spinning=false}
    fun performLongClick():Boolean{tooltipGeneration++;return true}
}
class FirewallListState {
    var adapter by mutableStateOf<FirewallAppListAdapter?>(null)
    fun post(action:()->Unit){android.os.Handler(android.os.Looper.getMainLooper()).post(action)}
}
class FirewallAppsState {
    var selectedFilter by mutableIntStateOf(0)
    var onFirewall:(Int)->Unit={}
    var scrollToTopGeneration by mutableIntStateOf(0)
    fun selectFirewall(id:Int){if(selectedFilter==id)return;selectedFilter=id;onFirewall(id)}
    val ffaAppInfoIcon = AppListControlState()
    val ffaAppList = FirewallListState()
    val ffaFilterIcon = AppListControlState()
    val ffaRefreshList = AppListControlState()
    val ffaSearch = AppSearchState()
    val ffaToggleAllBypass = AppListControlState()
    val ffaToggleAllBypassDnsFirewall = AppListControlState()
    val ffaToggleAllExclude = AppListControlState()
    val ffaToggleAllLockdown = AppListControlState()
    val ffaToggleAllMobileData = AppListControlState()
    val ffaToggleAllWifi = AppListControlState()
    val firewallAppLabelTv = AppListControlState()
}
