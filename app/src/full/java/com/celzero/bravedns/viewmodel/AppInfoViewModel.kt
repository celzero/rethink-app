package com.celzero.bravedns.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.liveData
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoDAO
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.activity.AppListActivity
import com.celzero.bravedns.util.Constants
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AppInfoViewModel(private val appInfoDAO: AppInfoDAO) : ViewModel() {

    private var filter: MutableLiveData<String> = MutableLiveData()
    private var category: MutableSet<String> = mutableSetOf()
    private var topLevelFilter = AppListActivity.TopLevelFilter.ALL
    private var firewallFilter = AppListActivity.FirewallFilter.ALL
    private var search: String = ""

    init {
        filter.value = ""
    }

    val appInfo = filter.switchMap { input: String -> getAppInfo(input) }

    private fun setFilterWithDebounce(searchString: String) {
        viewModelScope.launch {
            debounceFilter(searchString)
        }
    }

    private var debounceJob: Job? = null
    private fun debounceFilter(searchString: String) {
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(300) // 300ms debounce delay
            filter.value = searchString
        }
    }

    fun setFilter(filters: AppListActivity.Filters) {
        this.category.clear()
        this.category.addAll(filters.categoryFilters)

        this.firewallFilter = filters.firewallFilter
        this.topLevelFilter = filters.topLevelFilter

        this.search = filters.searchString
        setFilterWithDebounce(filters.searchString)
    }

    private fun getAppInfo(searchString: String): LiveData<PagingData<AppInfo>> {
        return when (topLevelFilter) {
            // get the app info based on the filter
            AppListActivity.TopLevelFilter.ALL -> {
                allApps(searchString)
            }
            AppListActivity.TopLevelFilter.INSTALLED -> {
                installedApps(searchString)
            }
            AppListActivity.TopLevelFilter.SYSTEM -> {
                systemApps(searchString)
            }
        }
    }

    private fun getBypassProxyFilter(): Set<Int> {
        val filter = firewallFilter.getFilter()
        val bypassFilter = setOf(2, 7)
        if (filter == bypassFilter) {
            return setOf(1)
        }
        return setOf() // empty set (as query uses or condition)
    }

    private fun allApps(searchString: String): LiveData<PagingData<AppInfo>> {
        val includeProxyBypass = getBypassProxyFilter()
        return if (category.isEmpty()) {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    appInfoDAO.getAppInfos(
                        "%$searchString%",
                        firewallFilter.getFilter(),
                        firewallFilter.getConnectionStatusFilter(),
                        includeProxyBypass
                    )
                }
                .liveData
                .cachedIn(viewModelScope)
        } else {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    appInfoDAO.getAppInfos(
                        "%$searchString%",
                        category,
                        firewallFilter.getFilter(),
                        firewallFilter.getConnectionStatusFilter(),
                        includeProxyBypass
                    )
                }
                .liveData
                .cachedIn(viewModelScope)
        }
    }

    private fun installedApps(search: String): LiveData<PagingData<AppInfo>> {
        val includeProxyBypass = getBypassProxyFilter()
        return if (category.isEmpty()) {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    appInfoDAO.getInstalledApps(
                        "%$search%",
                        firewallFilter.getFilter(),
                        firewallFilter.getConnectionStatusFilter(),
                        includeProxyBypass
                    )
                }
                .liveData
                .cachedIn(viewModelScope)
        } else {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    appInfoDAO.getInstalledApps(
                        "%$search%",
                        category,
                        firewallFilter.getFilter(),
                        firewallFilter.getConnectionStatusFilter(),
                        includeProxyBypass
                    )
                }
                .liveData
                .cachedIn(viewModelScope)
        }
    }

    private fun systemApps(search: String): LiveData<PagingData<AppInfo>> {
        val includeProxyBypass = getBypassProxyFilter()
        return if (category.isEmpty()) {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    appInfoDAO.getSystemApps(
                        "%$search%",
                        firewallFilter.getFilter(),
                        firewallFilter.getConnectionStatusFilter(),
                        includeProxyBypass
                    )
                }
                .liveData
                .cachedIn(viewModelScope)
        } else {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    appInfoDAO.getSystemApps(
                        "%$search%",
                        category,
                        firewallFilter.getFilter(),
                        firewallFilter.getConnectionStatusFilter(),
                        includeProxyBypass
                    )
                }
                .liveData
                .cachedIn(viewModelScope)
        }
    }

    // apply the firewall rules to the filtered apps
    suspend fun updateUnmeteredStatus(blocked: Boolean) {
        val appList = getFilteredApps()
        appList
            .distinctBy { it.uid }
            .forEach {
                val connStatus = FirewallManager.connectionStatus(it.uid)
                val appStatus = getAppStateForWifi(blocked, connStatus)
                FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid)
            }
    }

    suspend fun updateMeteredStatus(blocked: Boolean) {
        val appList = getFilteredApps()
        appList
            .distinctBy { it.uid }
            .forEach {
                val connStatus = FirewallManager.connectionStatus(it.uid)
                val appStatus = getAppStateForMobileData(blocked, connStatus)
                FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid)
            }
    }

    suspend fun updateBypassStatus(bypass: Boolean) {
        val appList = getFilteredApps()
        // update the bypass status for the filtered apps
        // if the app is already in the bypass list, remove it
        // else add it to the bypass list
        val appStatus =
            if (bypass) {
                AppState(
                    FirewallManager.FirewallStatus.BYPASS_UNIVERSAL,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            } else {
                AppState(
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            }
        appList
            .distinctBy { it.uid }
            .forEach { FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid) }
    }

    suspend fun updateBypassDnsFirewall(bypass: Boolean) {
        val appList = getFilteredApps()
        // update the bypass status for the filtered apps
        // if the app is already in the bypass list, remove it
        // else add it to the bypass list
        val appStatus =
            if (bypass) {
                AppState(
                    FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            } else {
                AppState(
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            }
        appList
            .distinctBy { it.uid }
            .forEach { FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid) }
    }

    suspend fun updateExcludeStatus(exclude: Boolean) {
        val appList = getFilteredApps()
        // update the exclude status for the filtered apps
        // if the app is already in the exclude list, remove it
        // else add it to the exclude list
        val appStatus =
            if (exclude) {
                AppState(
                    FirewallManager.FirewallStatus.EXCLUDE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            } else {
                AppState(
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            }
        appList
            .distinctBy { it.uid }
            .forEach { FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid) }
    }

    suspend fun updateLockdownStatus(lockdown: Boolean) {
        val appList = getFilteredApps()
        // update the lockdown status for the filtered apps
        // if the app is already in the lockdown list, remove it
        // else add it to the lockdown list
        val appStatus =
            if (lockdown) {
                AppState(
                    FirewallManager.FirewallStatus.ISOLATE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            } else {
                AppState(
                    FirewallManager.FirewallStatus.NONE,
                    FirewallManager.ConnectionStatus.ALLOW
                )
            }

        appList
            .distinctBy { it.uid }
            .forEach { FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid) }
    }

    private fun getAppStateForWifi(
        blocked: Boolean,
        connStatus: FirewallManager.ConnectionStatus
    ): AppState {
        if (blocked) {
            return when (connStatus) {
                FirewallManager.ConnectionStatus.ALLOW -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.UNMETERED
                    )
                }
                FirewallManager.ConnectionStatus.UNMETERED -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.UNMETERED
                    )
                }
                FirewallManager.ConnectionStatus.METERED -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.BOTH
                    )
                }
                FirewallManager.ConnectionStatus.BOTH -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.BOTH
                    )
                }
            }
        } else {
            return when (connStatus) {
                FirewallManager.ConnectionStatus.ALLOW -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                }
                FirewallManager.ConnectionStatus.UNMETERED -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                }
                FirewallManager.ConnectionStatus.METERED -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.METERED
                    )
                }
                FirewallManager.ConnectionStatus.BOTH -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.METERED
                    )
                }
            }
        }
    }

    data class AppState(
        val fid: FirewallManager.FirewallStatus,
        val cid: FirewallManager.ConnectionStatus
    )

    private fun getAppStateForMobileData(
        blocked: Boolean,
        connStatus: FirewallManager.ConnectionStatus
    ): AppState {
        if (blocked) {
            return when (connStatus) {
                FirewallManager.ConnectionStatus.ALLOW -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.METERED
                    )
                }
                FirewallManager.ConnectionStatus.UNMETERED -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.BOTH
                    )
                }
                FirewallManager.ConnectionStatus.METERED -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.METERED
                    )
                }
                FirewallManager.ConnectionStatus.BOTH -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.BOTH
                    )
                }
            }
        } else {
            return when (connStatus) {
                FirewallManager.ConnectionStatus.ALLOW -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                }
                FirewallManager.ConnectionStatus.UNMETERED -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.UNMETERED
                    )
                }
                FirewallManager.ConnectionStatus.METERED -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.ALLOW
                    )
                }
                FirewallManager.ConnectionStatus.BOTH -> {
                    AppState(
                        FirewallManager.FirewallStatus.NONE,
                        FirewallManager.ConnectionStatus.UNMETERED
                    )
                }
            }
        }
    }

    private fun getFilteredApps(): List<AppInfo> {
        val appType =
            when (topLevelFilter) {
                AppListActivity.TopLevelFilter.ALL -> {
                    setOf(0, 1)
                }
                AppListActivity.TopLevelFilter.INSTALLED -> {
                    setOf(0)
                }
                AppListActivity.TopLevelFilter.SYSTEM -> {
                    setOf(1)
                }
            }
        return if (category.isEmpty()) {
            appInfoDAO.getFilteredApps(
                "%$search%",
                firewallFilter.getFilter(),
                appType,
                firewallFilter.getConnectionStatusFilter()
            )
        } else {
            appInfoDAO.getFilteredApps(
                "%$search%",
                category,
                firewallFilter.getFilter(),
                appType,
                firewallFilter.getConnectionStatusFilter()
            )
        }
    }
}
