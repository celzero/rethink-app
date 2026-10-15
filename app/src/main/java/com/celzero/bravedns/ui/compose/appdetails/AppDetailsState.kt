package com.celzero.bravedns.ui.compose.appdetails
import androidx.compose.runtime.*
import android.view.View
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.ui.stats.StatsViewMode
import com.celzero.bravedns.data.AppConnection
class AppDetailListState { var adapter by mutableStateOf<AppConnectionsPresenter?>(null) }
class AppDetailsState {
    var mode by mutableStateOf(StatsViewMode.LIST)
    var onMode: (StatsViewMode) -> Unit = {}
    val insightsSnapshots = mutableStateMapOf<AppInsightsSection, List<AppConnection>>()
    val aadActiveConnsChip = WgControlState()
    val aadActiveConnsRl = WgControlState()
    val aadActiveConnsRv = AppDetailListState()
    val aadAppDetailIcon = WgControlState()
    val aadAppDetailName = WgControlState()
    val aadAppInfoIcon = WgControlState()
    val aadAppParentRl = WgControlState()
    val aadAppSettingsBlockMd = WgControlState()
    val aadAppSettingsBlockWifi = WgControlState()
    val aadAppSettingsBypassDnsFirewall = WgControlState()
    val aadAppSettingsBypassUniv = WgControlState()
    val aadAppSettingsExclude = WgControlState()
    val aadAppSettingsIsolate = WgControlState()
    val aadAsnChip = WgControlState()
    val aadAsnRl = WgControlState()
    val aadAsnRv = AppDetailListState()
    val aadBlockedAsnChip = WgControlState()
    val aadBlockedAsnRl = WgControlState()
    val aadBlockedAsnRv = AppDetailListState()
    val aadBlockedDomainsChip = WgControlState()
    val aadBlockedDomainsRl = WgControlState()
    val aadBlockedDomainsRv = AppDetailListState()
    val aadBlockedIpsChip = WgControlState()
    val aadBlockedIpsRl = WgControlState()
    val aadBlockedIpsRv = AppDetailListState()
    val aadBlocklistChip = WgControlState()
    val aadBlocklistRl = WgControlState()
    val aadBlocklistRv = AppDetailListState()
    val aadCloseConnsChip = WgControlState()
    val aadCountriesChip = WgControlState()
    val aadDataUsageStatus = WgControlState()
    val aadDomainBlockCard = WgControlState()
    val aadDomainBlockHeader = WgControlState()
    val aadDomainsChip = WgControlState()
    val aadFirewallStatus = WgControlState()
    val aadIaActiveConnsChip = WgControlState()
    val aadIaAsnChip = WgControlState()
    val aadIaBlockedAsnChip = WgControlState()
    val aadIaBlockedDomainsChip = WgControlState()
    val aadIaBlockedIpsChip = WgControlState()
    val aadIaBlocklistChip = WgControlState()
    val aadIaCountriesChip = WgControlState()
    val aadIaDomainsChip = WgControlState()
    val aadIaIpsChip = WgControlState()
    val aadInsightsContainer = WgControlState()
    val aadIpBlockCard = WgControlState()
    val aadIpBlockHeader = WgControlState()
    val aadIpsChip = WgControlState()
    val aadListContainer = WgControlState()
    val aadMostContactedCountriesRl = WgControlState()
    val aadMostContactedCountriesRv = AppDetailListState()
    val aadMostContactedDomainRl = WgControlState()
    val aadMostContactedDomainRv = AppDetailListState()
    val aadMostContactedIpsRl = WgControlState()
    val aadMostContactedIpsRv = AppDetailListState()
    val aadNotesChip = WgControlState()
    val aadPkgName = WgControlState()
    val aadProxyDetails = WgControlState()
    val aadRethinkWarningContainer = WgControlState()
    val aadRethinkWarningProceed = WgControlState()
    val aadViewModeToggleGroup = WgControlState()
    val excludeProxyRl = WgControlState()
    val excludeProxySwitch = WgControlState(checkable = true)
    val tempAllowDesc = WgControlState()
    val tempAllowRl = WgControlState()
    val tempAllowSwitch = WgControlState(checkable = true)
    init {
        aadRethinkWarningContainer.visibility = View.GONE
        aadProxyDetails.visibility = View.GONE
        aadActiveConnsRl.visibility = View.GONE
        aadMostContactedCountriesRl.visibility = View.GONE
        aadAsnRl.visibility = View.GONE
        aadBlockedAsnRl.visibility = View.GONE
        aadBlocklistRl.visibility = View.GONE
        aadMostContactedDomainRl.visibility = View.GONE
        aadBlockedDomainsRl.visibility = View.GONE
        aadMostContactedIpsRl.visibility = View.GONE
        aadBlockedIpsRl.visibility = View.GONE
        aadInsightsContainer.visibility = View.GONE
    }
}
