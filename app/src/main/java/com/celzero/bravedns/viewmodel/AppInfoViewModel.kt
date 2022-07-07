package com.celzero.bravedns.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.paging.PagedList
import androidx.paging.toLiveData
import androidx.sqlite.db.SimpleSQLiteQuery
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoDAO
import com.celzero.bravedns.ui.FirewallAppFragment
import com.celzero.bravedns.util.Constants

class AppInfoViewModel(private val appInfoDAO: AppInfoDAO) : ViewModel() {

    private var filter: MutableLiveData<String> = MutableLiveData()
    private var category: MutableSet<String> = mutableSetOf()
    private var topLevelFilter = FirewallAppFragment.TopLevelFilter.ALL
    private var firewallFilter = FirewallAppFragment.FirewallFilter.NONE
    private var orderBy: String = " lower(appName)"
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
        this.orderBy = filters.sortType.getSortByQuery()

        this.search = filters.searchString
        this.filter.value = filters.searchString

    }

    private fun getAppInfo(searchString: String): LiveData<PagedList<AppInfo>> {
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

    private fun allApps(searchString: String): LiveData<PagedList<AppInfo>> {
        return if (category.isEmpty()) {
            val query = SimpleSQLiteQuery(
                "select * from AppInfo where (appName like '%$searchString%' or uid like '%$searchString%') and firewallStatus in (${getFirewallStatusFilter()}) order by $orderBy")
            appInfoDAO.getQuery(query).toLiveData(pageSize = Constants.LIVEDATA_PAGE_SIZE)
            // using SimpleSQLiteQuery instead of @Dao query
            // issue: values passed for the order by parameter is not working as expected
            // https://developer.android.com/reference/androidx/room/RawQuery
            /*appInfoDAO.getAppInfo("%$searchString%", orderBy).toLiveData(
                pageSize = Constants.LIVEDATA_PAGE_SIZE)*/
        } else {
            val cat = category.joinToString { c -> "'$c'" }
            val query = SimpleSQLiteQuery(
                "select * from AppInfo where (appName like '%$searchString%' or uid like '%$searchString%')  and firewallStatus in (${getFirewallStatusFilter()}) and appCategory in ($cat) order by $orderBy")
            appInfoDAO.getQuery(query).toLiveData(pageSize = Constants.LIVEDATA_PAGE_SIZE)
            /*appInfoDAO.getAppInfo("%$searchString%", category, orderBy).toLiveData(
                pageSize = Constants.LIVEDATA_PAGE_SIZE)*/
        }
    }

    private fun installedApps(search: String): LiveData<PagedList<AppInfo>> {
        return if (category.isEmpty()) {
            val query = SimpleSQLiteQuery(
                "select * from AppInfo where isSystemApp = 0 and (appName like '%$search%' or uid like '%$search%')  and firewallStatus in (${getFirewallStatusFilter()}) order by $orderBy")
            appInfoDAO.getQuery(query).toLiveData(pageSize = Constants.LIVEDATA_PAGE_SIZE)
            /*appInfoDAO.getInstalledApps("%$searchString%", orderBy).toLiveData(
                pageSize = Constants.LIVEDATA_PAGE_SIZE)*/
        } else {
            val cat = category.joinToString { c -> "'$c'" }
            val query = SimpleSQLiteQuery(
                "select * from AppInfo where isSystemApp = 0 and (appName like '%$search%' or uid like '%$search%') and appCategory in ($cat)  and firewallStatus in (${getFirewallStatusFilter()}) order by $orderBy")
            appInfoDAO.getQuery(query).toLiveData(pageSize = Constants.LIVEDATA_PAGE_SIZE)
            /*appInfoDAO.getInstalledApps("%$searchString%", category, orderBy).toLiveData(
                pageSize = Constants.LIVEDATA_PAGE_SIZE)*/
        }
    }

    private fun systemApps(search: String): LiveData<PagedList<AppInfo>> {
        return if (category.isEmpty()) {
            val query = SimpleSQLiteQuery(
                "select * from AppInfo where isSystemApp = 1 and (appName like '%$search%' or uid like '%$search%')  and firewallStatus in (${getFirewallStatusFilter()}) order by $orderBy")
            appInfoDAO.getQuery(query).toLiveData(pageSize = Constants.LIVEDATA_PAGE_SIZE)
            /*appInfoDAO.getSystemApps("%$searchString%", orderBy).toLiveData(
                pageSize = Constants.LIVEDATA_PAGE_SIZE)*/
        } else {
            val cat = category.joinToString { c -> "'$c'" }
            val query = SimpleSQLiteQuery(
                "select * from AppInfo where isSystemApp = 1 and (appName like '%$search%' or uid like '%$search%') and appCategory in ($cat) and firewallStatus in (${getFirewallStatusFilter()}) order by $orderBy")
            appInfoDAO.getQuery(query).toLiveData(pageSize = Constants.LIVEDATA_PAGE_SIZE)
            /*appInfoDAO.getSystemApps("%$searchString%", category, orderBy).toLiveData(
                pageSize = Constants.LIVEDATA_PAGE_SIZE)*/
        }
    }

    private fun getFirewallStatusFilter(): String {
        return firewallFilter.getFilterString()
    }

    fun updateWifiStatus(blocked: Boolean) {
        val appList = getFilteredApps()

        appList.distinctBy { it.uid }.forEach {
            val appStatus = getAppStatusForWifi(blocked, it.firewallStatus, it.metered)
            val connStatus = getConnStatusForWifi(blocked, it.firewallStatus, it.metered)

            FirewallManager.updateFirewallStatus(it.uid, appStatus, connStatus)
        }
    }

    private fun getAppStatusForWifi(blocked: Boolean, firewall: Int,
                                    metered: Int): FirewallManager.FirewallStatus {
        var appStatus = FirewallManager.FirewallStatus.ALLOW
        if (blocked) {
            appStatus = FirewallManager.FirewallStatus.BLOCK
        }

        if (isBlockWifi(firewall, metered)) {
            if (!blocked) {
                return FirewallManager.FirewallStatus.BLOCK
            }
        }

        return appStatus
    }

    private fun getConnStatusForWifi(blocked: Boolean, firewall: Int,
                                     metered: Int): FirewallManager.ConnectionStatus {
        var connStatus = FirewallManager.ConnectionStatus.BOTH

        if (blocked) {
            connStatus = FirewallManager.ConnectionStatus.WIFI
        }

        if (isBlockWifi(firewall, metered)) {
            return if (blocked) {
                FirewallManager.ConnectionStatus.BOTH
            } else {
                FirewallManager.ConnectionStatus.MOBILE_DATA
            }
        }

        return connStatus
    }

    fun updateMobileDataStatus(blocked: Boolean) {
        val appList = getFilteredApps()

        appList.distinctBy { it.uid }.forEach {
            val appStatus = getAppStatusForMobileData(blocked, it.firewallStatus, it.metered)
            val connStatus = getConnStatusForMobileData(blocked, it.firewallStatus, it.metered)
            FirewallManager.updateFirewallStatus(it.uid, appStatus, connStatus)
        }
    }

    private fun getAppStatusForMobileData(blocked: Boolean, firewall: Int,
                                          metered: Int): FirewallManager.FirewallStatus {
        var appStatus = FirewallManager.FirewallStatus.ALLOW

        if (blocked) {
            appStatus = FirewallManager.FirewallStatus.BLOCK
        }

        if (isBlockMobileData(firewall, metered)) {
            if (!blocked) {
                return FirewallManager.FirewallStatus.BLOCK
            }
        }

        return appStatus
    }

    private fun getConnStatusForMobileData(blocked: Boolean, firewall: Int,
                                           metered: Int): FirewallManager.ConnectionStatus {
        var connStatus = FirewallManager.ConnectionStatus.BOTH

        if (blocked) {
            connStatus = FirewallManager.ConnectionStatus.MOBILE_DATA
        }

        if (isBlockMobileData(firewall, metered)) {
            return if (blocked) {
                FirewallManager.ConnectionStatus.BOTH
            } else {
                FirewallManager.ConnectionStatus.WIFI
            }
        }

        return connStatus
    }

    private fun isBlockWifi(firewall: Int, metered: Int): Boolean {
        return (firewall == FirewallManager.FirewallStatus.BLOCK.id && (metered == FirewallManager.ConnectionStatus.BOTH.id || metered == FirewallManager.ConnectionStatus.MOBILE_DATA.id))
    }

    private fun isBlockMobileData(firewall: Int, metered: Int): Boolean {
        return firewall == FirewallManager.FirewallStatus.BLOCK.id && (metered == FirewallManager.ConnectionStatus.BOTH.id || metered == FirewallManager.ConnectionStatus.WIFI.id)
    }

    private fun getFilteredApps(): List<AppInfo> {
        return if (category.isEmpty()) {
            appInfoDAO.getFilteredApps("%$search%")
        } else {
            appInfoDAO.getFilteredApps("%$search%", category)
        }
    }
}
