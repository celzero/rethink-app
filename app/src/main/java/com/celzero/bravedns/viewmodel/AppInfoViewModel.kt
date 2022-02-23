package com.celzero.bravedns.viewmodel

import android.util.Log
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
    private var orderBy: String = " lower(appName)"
    private var searchTerm: String = ""

    init {
        filter.value = ""
    }

    val appInfos = Transformations.switchMap(filter) { input: String ->
        fetchAppInfos(input)
    }

    fun setFilter(filters: FirewallAppFragment.Filters) {
        this.category.clear()
        this.category.addAll(filters.categoryFilters)

        this.topLevelFilter = filters.topLevelFilter
        this.orderBy = filters.getSortByQuery()

        this.searchTerm = filters.searchString
        this.filter.value = filters.searchString
    }

    private fun fetchAppInfos(searchString: String): LiveData<PagedList<AppInfo>> {
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
                "select * from AppInfo where appName like '%$searchString%' order by $orderBy")
            appInfoDAO.getQuery(query).toLiveData(pageSize = Constants.LIVEDATA_PAGE_SIZE)
            // using SimpleSQLiteQuery instead of @Dao query
            // issue: values passed for the order by parameter is not working as expected
            // https://developer.android.com/reference/androidx/room/RawQuery
            /*appInfoDAO.getAppInfos("%$searchString%", orderBy).toLiveData(
                pageSize = Constants.LIVEDATA_PAGE_SIZE)*/
        } else {
            val cat = category.joinToString { c -> "'$c'" }
            val query = SimpleSQLiteQuery(
                "select * from AppInfo where appName like '%$searchString%' and appCategory in ($cat) order by $orderBy")
            appInfoDAO.getQuery(query).toLiveData(pageSize = Constants.LIVEDATA_PAGE_SIZE)
            /*appInfoDAO.getAppInfos("%$searchString%", category, orderBy).toLiveData(
                pageSize = Constants.LIVEDATA_PAGE_SIZE)*/
        }
    }

    private fun installedApps(searchString: String): LiveData<PagedList<AppInfo>> {
        return if (category.isEmpty()) {
            val query = SimpleSQLiteQuery(
                "select * from AppInfo where isSystemApp = 0 and appName like '%$searchString%' order by $orderBy")
            appInfoDAO.getQuery(query).toLiveData(pageSize = Constants.LIVEDATA_PAGE_SIZE)
            /*appInfoDAO.getInstalledApps("%$searchString%", orderBy).toLiveData(
                pageSize = Constants.LIVEDATA_PAGE_SIZE)*/
        } else {
            val cat = category.joinToString { c -> "'$c'" }
            val query = SimpleSQLiteQuery(
                "select * from AppInfo where isSystemApp = 0 and appName like '%$searchString%' and appCategory in ($cat) order by $orderBy")
            appInfoDAO.getQuery(query).toLiveData(pageSize = Constants.LIVEDATA_PAGE_SIZE)
            /*appInfoDAO.getInstalledApps("%$searchString%", category, orderBy).toLiveData(
                pageSize = Constants.LIVEDATA_PAGE_SIZE)*/
        }
    }

    private fun systemApps(searchString: String): LiveData<PagedList<AppInfo>> {
        return if (category.isEmpty()) {
            val query = SimpleSQLiteQuery(
                "select * from AppInfo where isSystemApp = 1 and appName like '%$searchString%' order by $orderBy")
            appInfoDAO.getQuery(query).toLiveData(pageSize = Constants.LIVEDATA_PAGE_SIZE)
            /*appInfoDAO.getSystemApps("%$searchString%", orderBy).toLiveData(
                pageSize = Constants.LIVEDATA_PAGE_SIZE)*/
        } else {
            val cat = category.joinToString { c -> "'$c'" }
            val query = SimpleSQLiteQuery(
                "select * from AppInfo where isSystemApp = 1 and appName like '%$searchString%'  and appCategory in ($cat) order by $orderBy")
            appInfoDAO.getQuery(query).toLiveData(pageSize = Constants.LIVEDATA_PAGE_SIZE)
            /*appInfoDAO.getSystemApps("%$searchString%", category, orderBy).toLiveData(
                pageSize = Constants.LIVEDATA_PAGE_SIZE)*/
        }
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
                                    metered: Int): FirewallManager.AppStatus {
        var appStatus = FirewallManager.AppStatus.ALLOW
        if (blocked) {
            appStatus = FirewallManager.AppStatus.BLOCK
        }

        if (firewall == 1 && (metered == 0 || metered == 2)) {
            if (!blocked) {
                return FirewallManager.AppStatus.BLOCK
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

        if (firewall == 1 && (metered == 0 || metered == 2)) {
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
                                          metered: Int): FirewallManager.AppStatus {
        var appStatus = FirewallManager.AppStatus.ALLOW

        if (blocked) {
            appStatus = FirewallManager.AppStatus.BLOCK
        }

        if (firewall == 1 && (metered == 0 || metered == 1)) {
            if (!blocked) {
                return FirewallManager.AppStatus.BLOCK
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

        if (firewall == 1 && (metered == 0 || metered == 1)) {
            return if (blocked) {
                FirewallManager.ConnectionStatus.BOTH
            } else {
                FirewallManager.ConnectionStatus.WIFI
            }
        }

        return connStatus
    }

    private fun getFilteredApps(): List<AppInfo> {
        return if (category.isEmpty()) {
            appInfoDAO.getFilteredApps("%$searchTerm%")
        } else {
            appInfoDAO.getFilteredApps("%$searchTerm%", category)
        }
    }
}
