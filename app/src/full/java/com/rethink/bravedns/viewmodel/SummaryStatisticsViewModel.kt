/*
 * Copyright 2022 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.rethinkdns.retrixed.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.liveData
import com.rethinkdns.retrixed.data.DataUsageSummary
import com.rethinkdns.retrixed.database.ConnectionTracker
import com.rethinkdns.retrixed.database.ConnectionTrackerDAO
import com.rethinkdns.retrixed.database.StatsSummaryDao
import com.rethinkdns.retrixed.service.VpnController
import com.rethinkdns.retrixed.util.Constants

class SummaryStatisticsViewModel(
    private val connectionTrackerDAO: ConnectionTrackerDAO,
    private val statsDao: StatsSummaryDao
) : ViewModel() {
    private var topActiveConns: MutableLiveData<Long> = MutableLiveData()
    private var networkActivity: MutableLiveData<String> = MutableLiveData()
    private var asn: MutableLiveData<String> = MutableLiveData()
    private var countryActivities: MutableLiveData<String> = MutableLiveData()
    private var domains: MutableLiveData<String> = MutableLiveData()
    private var ips: MutableLiveData<String> = MutableLiveData()
    private var timeCategory: TimeCategory = TimeCategory.ONE_HOUR
    private var startTime: MutableLiveData<Long> = MutableLiveData()
    private var loadMoreClicked: Boolean = false

    companion object {
        private const val ONE_HOUR_MILLIS = 1 * 60 * 60 * 1000L
        private const val ONE_DAY_MILLIS = 24 * ONE_HOUR_MILLIS
        private const val ONE_WEEK_MILLIS = 7 * ONE_DAY_MILLIS
    }

    enum class TimeCategory(val value: Int) {
        ONE_HOUR(0),
        TWENTY_FOUR_HOUR(1),
        SEVEN_DAYS(2);

        companion object {
            fun fromValue(value: Int) = entries.firstOrNull { it.value == value }
        }
    }

    init {
        // set from and to time to current and 1 hr before
        startTime.value = System.currentTimeMillis() - ONE_HOUR_MILLIS
        topActiveConns.value = VpnController.uptimeMs()
        networkActivity.value = ""
        asn.value = ""
    }

    fun getTimeCategory(): TimeCategory {
        return timeCategory
    }

    fun setLoadMoreClicked(b: Boolean) {
        loadMoreClicked = b
        // initialise the live data to trigger the switchMap
        domains.value = ""
        countryActivities.value = ""
        ips.value = ""
    }

    fun timeCategoryChanged(tc: TimeCategory) {
        timeCategory = tc
        when (tc) {
            TimeCategory.ONE_HOUR -> {
                startTime.value = System.currentTimeMillis() - ONE_HOUR_MILLIS
            }
            TimeCategory.TWENTY_FOUR_HOUR -> {
                startTime.value = System.currentTimeMillis() - ONE_DAY_MILLIS
            }
            TimeCategory.SEVEN_DAYS -> {
                startTime.value = System.currentTimeMillis() - ONE_WEEK_MILLIS
            }
        }
        networkActivity.value = ""
        asn.value = ""
        if (loadMoreClicked) {
            countryActivities.value = ""
            ips.value = ""
            domains.value = ""
        }
    }

    val getTopActiveConns =
        topActiveConns.switchMap { it ->
            val to = System.currentTimeMillis() - it
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                statsDao.getTopActiveConns(to)
            }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getAllowedAppNetworkActivity =
        networkActivity.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    // use dnsQuery as appName
                    val to = startTime.value ?: 0L
                    statsDao.getMostAllowedApps(to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getBlockedAppNetworkActivity =
        networkActivity.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    // use dnsQuery as appName
                    val to = startTime.value ?: 0L
                    statsDao.getMostBlockedApps(to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getMostConnectedASN =
        asn.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    val to = startTime.value ?: 0L
                    statsDao.getMostConnectedASN(to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getMostBlockedASN =
        asn.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    val to = startTime.value ?: 0L
                    statsDao.getMostBlockedASN(to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val mbd = domains.switchMap {
        Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
            val to = startTime.value ?: 0L
            statsDao.getMostBlockedDomains(to)
        }
        .liveData
        .cachedIn(viewModelScope)
    }

    val mcd = domains.switchMap {
        Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
            val to = startTime.value ?: 0L
            statsDao.getMostContactedDomains(to)
        }
        .liveData
        .cachedIn(viewModelScope)
    }

    val getMostContactedIps = ips.switchMap { _ ->
        Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
            val to = startTime.value ?: 0L
            connectionTrackerDAO.getMostContactedIps(to)
        }
        .liveData
        .cachedIn(viewModelScope)
    }

    val getMostBlockedIps = ips.switchMap { _ ->
        Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
            val to = startTime.value ?: 0L
            connectionTrackerDAO.getMostBlockedIps(to)
        }
        .liveData
        .cachedIn(viewModelScope)
    }

    val getMostContactedCountries =
        countryActivities.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    val to = startTime.value ?: 0L
                    statsDao.getMostContactedCountries(to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    suspend fun totalUsage(): DataUsageSummary {
        val to = startTime.value ?: 0L
        return connectionTrackerDAO.getTotalUsages(to, ConnectionTracker.ConnType.METERED.value)
    }
}
