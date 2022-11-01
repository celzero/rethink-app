package com.celzero.bravedns.viewmodel

import androidx.lifecycle.*
import androidx.paging.*
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoDAO
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.FirewallAppFragment
import com.celzero.bravedns.util.Constants

class AppInfoViewModel(private val appInfoDAO: AppInfoDAO) : ViewModel() {

    private var filter: MutableLiveData<String> = MutableLiveData()
    private var category: MutableSet<String> = mutableSetOf()
    private var topLevelFilter = FirewallAppFragment.TopLevelFilter.ALL
    private var firewallFilter = FirewallAppFragment.FirewallFilter.ALL
    private var search: String = ""

    init {
        filter.value = ""
    }

    val appInfo = Transformations.switchMap(filter) { input: String ->
        getAppInfo(input)
    }

    fun setFilter(filters: FirewallAppFragment.Filters) {
        this.category.clear()
        this.category.addAll(filters.categoryFilters)

        this.firewallFilter = filters.firewallFilter
        this.topLevelFilter = filters.topLevelFilter

        this.search = filters.searchString
        this.filter.value = filters.searchString

    }

    private fun getAppInfo(searchString: String): LiveData<PagingData<AppInfo>> {
        return when (topLevelFilter) {
            FirewallAppFragment.TopLevelFilter.ALL -> {
                allApps(searchString)
            }
            FirewallAppFragment.TopLevelFilter.INSTALLED -> {
                installedApps(searchString)
            }
            FirewallAppFragment.TopLevelFilter.SYSTEM -> {
                systemApps(searchString)
            }
        }
    }

    private fun allApps(searchString: String): LiveData<PagingData<AppInfo>> {
        return if (category.isEmpty()) {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                appInfoDAO.getAppInfos("%$searchString%", firewallFilter.getFilter())
            }.liveData.cachedIn(viewModelScope)
        } else {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                appInfoDAO.getAppInfos("%$searchString%", category, firewallFilter.getFilter())
            }.liveData.cachedIn(viewModelScope)
        }
    }

    private fun installedApps(search: String): LiveData<PagingData<AppInfo>> {
        return if (category.isEmpty()) {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                appInfoDAO.getInstalledApps("%$search%", firewallFilter.getFilter())
            }.liveData.cachedIn(viewModelScope)
        } else {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                appInfoDAO.getInstalledApps("%$search%", category, firewallFilter.getFilter())
            }.liveData.cachedIn(viewModelScope)
        }
    }

    private fun systemApps(search: String): LiveData<PagingData<AppInfo>> {
        return if (category.isEmpty()) {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                appInfoDAO.getSystemApps("%$search%", firewallFilter.getFilter())
            }.liveData.cachedIn(viewModelScope)
        } else {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                appInfoDAO.getSystemApps("%$search%", category, firewallFilter.getFilter())
            }.liveData.cachedIn(viewModelScope)
        }
    }

    fun updateUnmeteredStatus(blocked: Boolean) {
        val appList = getFilteredApps()

        appList.distinctBy { it.uid }.forEach {
            val appStatus = getAppStateForWifi(blocked, it.firewallStatus, it.metered)
            FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid)
        }
    }

    private fun getAppStateForWifi(blocked: Boolean, firewall: Int, metered: Int): AppState {
        val state: AppState

        if (firewall == FirewallManager.FirewallStatus.BLOCK.id) {
            state = if (blocked) {
                if (metered == FirewallManager.ConnectionStatus.WIFI.id) {
                    AppState(
                        FirewallManager.FirewallStatus.BLOCK,
                        FirewallManager.ConnectionStatus.WIFI
                    )
                } else {
                    AppState(
                        FirewallManager.FirewallStatus.BLOCK,
                        FirewallManager.ConnectionStatus.BOTH
                    )
                }
            } else {
                if (metered == FirewallManager.ConnectionStatus.WIFI.id) {
                    AppState(
                        FirewallManager.FirewallStatus.ALLOW,
                        FirewallManager.ConnectionStatus.BOTH
                    )
                } else {
                    AppState(
                        FirewallManager.FirewallStatus.BLOCK,
                        FirewallManager.ConnectionStatus.MOBILE_DATA
                    )
                }
            }
        } else {
            state = if (blocked) {
                AppState(
                    FirewallManager.FirewallStatus.BLOCK,
                    FirewallManager.ConnectionStatus.WIFI
                )
            } else {
                AppState(
                    FirewallManager.FirewallStatus.ALLOW,
                    FirewallManager.ConnectionStatus.BOTH
                )
            }
        }

        return state
    }

    data class AppState(
        val fid: FirewallManager.FirewallStatus,
        val cid: FirewallManager.ConnectionStatus
    )


    fun updateMeteredStatus(blocked: Boolean) {
        val appList = getFilteredApps()

        appList.distinctBy { it.uid }.forEach {
            val appStatus = getAppStateForMobileData(blocked, it.firewallStatus, it.metered)
            FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid)
        }
    }

    private fun getAppStateForMobileData(blocked: Boolean, firewall: Int, metered: Int): AppState {
        if (blocked) {
            val fid = FirewallManager.FirewallStatus.BLOCK
            var cid = FirewallManager.ConnectionStatus.BOTH
            if (metered == FirewallManager.ConnectionStatus.MOBILE_DATA.id) {
                cid = FirewallManager.ConnectionStatus.MOBILE_DATA
            } else if (firewall != FirewallManager.FirewallStatus.BLOCK.id) {
                cid = FirewallManager.ConnectionStatus.MOBILE_DATA
            }
            return AppState(fid, cid)
        } else {
            var fid = FirewallManager.FirewallStatus.ALLOW
            var cid = FirewallManager.ConnectionStatus.BOTH
            if (firewall != FirewallManager.FirewallStatus.BLOCK.id) {
                cid = FirewallManager.ConnectionStatus.BOTH
            } else if (metered != FirewallManager.ConnectionStatus.MOBILE_DATA.id) {
                fid = FirewallManager.FirewallStatus.BLOCK
                cid = FirewallManager.ConnectionStatus.WIFI
            }
            return AppState(fid, cid)
        }
    }

    private fun getFilteredApps(): List<AppInfo> {
        val appType = when (topLevelFilter) {
            FirewallAppFragment.TopLevelFilter.ALL -> {
                setOf(0, 1)
            }
            FirewallAppFragment.TopLevelFilter.INSTALLED -> {
                setOf(0)
            }
            FirewallAppFragment.TopLevelFilter.SYSTEM -> {
                setOf(1)
            }
        }
        return if (category.isEmpty()) {
            appInfoDAO.getFilteredApps("%$search%", firewallFilter.getFilter(), appType)
        } else {
            appInfoDAO.getFilteredApps("%$search%", category, firewallFilter.getFilter(), appType)
        }
    }
}
