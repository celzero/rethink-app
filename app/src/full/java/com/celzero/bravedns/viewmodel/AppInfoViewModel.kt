package com.celzero.bravedns.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoDAO
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.compose.firewall.Filters
import com.celzero.bravedns.ui.compose.firewall.FirewallFilter
import com.celzero.bravedns.ui.compose.firewall.TopLevelFilter
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(FlowPreview::class)
class AppInfoViewModel(private val appInfoDAO: AppInfoDAO) : ViewModel() {

    private val defaultFilters = Filters(topLevelFilter = TopLevelFilter.INSTALLED)
    private val baseFilters = MutableStateFlow(defaultFilters.copy(searchString = ""))
    private val searchInput = MutableStateFlow(defaultFilters.searchString)
    private val bulkUpdateMutex = Mutex()

    private val effectiveFilters: StateFlow<Filters> =
        combine(
            baseFilters,
            searchInput
                .debounce(300)
                .distinctUntilChanged()
        ) { base, debouncedSearch ->
            base.copy(searchString = debouncedSearch.trim())
        }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                defaultFilters
            )

    val appInfo: StateFlow<List<AppInfo>> =
        combine(
            appInfoDAO.getAllAppDetailsFlow(),
            effectiveFilters
        ) { apps, filters ->
            filterAndSortApps(apps, filters)
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                emptyList()
            )

    fun setFilter(filters: Filters) {
        baseFilters.value = filters.copy(searchString = "")
        searchInput.value = filters.searchString
    }

    private fun filterAndSortApps(
        apps: List<AppInfo>,
        filters: Filters
    ): List<AppInfo> {
        return apps
            .asSequence()
            .filter { app -> matchesTopLevelFilter(app, filters.topLevelFilter) }
            .filter { app -> matchesCategoryFilter(app, filters.categoryFilters) }
            .filter { app -> matchesFirewallFilter(app, filters.firewallFilter) }
            .filter { app -> matchesSearch(app, filters.searchString) }
            .sortedWith(
                compareBy<AppInfo>(
                    { app -> app.appName.ifBlank { app.packageName }.lowercase(Locale.getDefault()) },
                    { app -> app.packageName.lowercase(Locale.getDefault()) },
                    { app -> app.uid }
                )
            )
            .toList()
    }

    private fun matchesTopLevelFilter(
        app: AppInfo,
        filter: TopLevelFilter
    ): Boolean {
        return when (filter) {
            TopLevelFilter.ALL -> true
            TopLevelFilter.INSTALLED -> !app.isSystemApp
            TopLevelFilter.SYSTEM -> app.isSystemApp
        }
    }

    private fun matchesCategoryFilter(
        app: AppInfo,
        categories: Set<String>
    ): Boolean {
        if (categories.isEmpty()) return true
        return categories.contains(app.appCategory)
    }

    private fun matchesFirewallFilter(
        app: AppInfo,
        filter: FirewallFilter
    ): Boolean {
        val firewallMatches =
            filter.getFilter().contains(app.firewallStatus) &&
                filter.getConnectionStatusFilter().contains(app.connectionStatus)
        val bypassProxyMatches = filter == FirewallFilter.BYPASS && app.isProxyExcluded
        return firewallMatches || bypassProxyMatches
    }

    private fun matchesSearch(
        app: AppInfo,
        search: String
    ): Boolean {
        if (search.isBlank()) return true
        val query = search.trim()
        return app.appName.contains(query, ignoreCase = true)
    }

    // apply the firewall rules to the filtered apps
    suspend fun updateUnmeteredStatus(blocked: Boolean) {
        bulkUpdateMutex.withLock {
            val appList = appInfo.value
            appList
                .distinctBy { it.uid }
                .forEach {
                    val connStatus = FirewallManager.connectionStatus(it.uid)
                    val appStatus = getAppStateForWifi(blocked, connStatus)
                    FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid)
                }
        }
    }

    suspend fun updateMeteredStatus(blocked: Boolean) {
        bulkUpdateMutex.withLock {
            val appList = appInfo.value
            appList
                .distinctBy { it.uid }
                .forEach {
                    val connStatus = FirewallManager.connectionStatus(it.uid)
                    val appStatus = getAppStateForMobileData(blocked, connStatus)
                    FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid)
                }
        }
    }

    suspend fun updateBypassStatus(bypass: Boolean) {
        bulkUpdateMutex.withLock {
            val appList = appInfo.value
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
                .forEach {
                    FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid)
                }
        }
    }

    suspend fun updateBypassDnsFirewall(bypass: Boolean) {
        bulkUpdateMutex.withLock {
            val appList = appInfo.value
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
                .forEach {
                    FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid)
                }
        }
    }

    suspend fun updateExcludeStatus(exclude: Boolean) {
        bulkUpdateMutex.withLock {
            val appList = appInfo.value
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
                .forEach {
                    FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid)
                }
        }
    }

    suspend fun updateLockdownStatus(lockdown: Boolean) {
        bulkUpdateMutex.withLock {
            val appList = appInfo.value
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
                .forEach {
                    FirewallManager.updateFirewallStatus(it.uid, appStatus.fid, appStatus.cid)
                }
        }
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

    suspend fun getAppCount(): Int {
        return appInfoDAO.getAppCount()
    }
}
