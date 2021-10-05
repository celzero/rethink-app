package com.celzero.bravedns.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.paging.PagedList
import androidx.paging.toLiveData
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.AppInfoDAO
import com.celzero.bravedns.ui.FirewallAppsFragment
import com.celzero.bravedns.util.Constants

class AppInfoViewModel(private val appInfoDAO: AppInfoDAO) : ViewModel() {

    private var filter: MutableLiveData<String> = MutableLiveData()
    private var category: MutableSet<String> = mutableSetOf()
    private var topLevelFilter = FirewallAppsFragment.TopLevelFilter.INSTALLED

    init {
        filter.value = ""
    }

    val appInfos = Transformations.switchMap(filter) { input: String ->
        fetchAppInfos(input)
    }

    fun setFilter(searchString: String?, category: Set<String>,
                  topLevelFilter: FirewallAppsFragment.TopLevelFilter) {
        this.category.clear()
        this.category.addAll(category)

        this.topLevelFilter = topLevelFilter

        if (!searchString.isNullOrBlank()) this.filter.value = searchString
        else filter.value = ""
    }

    private fun fetchAppInfos(searchString: String): LiveData<PagedList<AppInfo>> {
        return when (topLevelFilter) {
            FirewallAppsFragment.TopLevelFilter.ALL -> {
                allApps(searchString)
            }
            FirewallAppsFragment.TopLevelFilter.INSTALLED -> {
                installedApps(searchString)
            }
            FirewallAppsFragment.TopLevelFilter.SYSTEM -> {
                systemApps(searchString)
            }
        }
    }

    private fun allApps(searchString: String): LiveData<PagedList<AppInfo>> {
        return if (category.isEmpty()) {
            appInfoDAO.getAppInfosLiveData("%$searchString%").toLiveData(
                pageSize = Constants.LIVEDATA_PAGE_SIZE)
        } else {
            appInfoDAO.getAppInfosLiveData("%$searchString%", category).toLiveData(
                pageSize = Constants.LIVEDATA_PAGE_SIZE)
        }
    }

    private fun installedApps(searchString: String): LiveData<PagedList<AppInfo>> {
        return if (category.isEmpty()) {
            appInfoDAO.getInstalledAppInfoLiveData("%$searchString%").toLiveData(
                pageSize = Constants.LIVEDATA_PAGE_SIZE)
        } else {
            appInfoDAO.getInstalledAppInfoLiveData("%$searchString%", category).toLiveData(
                pageSize = Constants.LIVEDATA_PAGE_SIZE)
        }
    }

    private fun systemApps(searchString: String): LiveData<PagedList<AppInfo>> {
        return if (category.isEmpty()) {
            appInfoDAO.getSystemAppInfoLiveData("%$searchString%").toLiveData(
                pageSize = Constants.LIVEDATA_PAGE_SIZE)
        } else {
            appInfoDAO.getSystemAppInfoLiveData("%$searchString%", category).toLiveData(
                pageSize = Constants.LIVEDATA_PAGE_SIZE)
        }
    }
}