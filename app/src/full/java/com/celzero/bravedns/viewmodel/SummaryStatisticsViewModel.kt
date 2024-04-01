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
package com.celzero.bravedns.viewmodel

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.liveData
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.DataUsageSummary
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.DnsLogDAO
import com.celzero.bravedns.util.Constants

class SummaryStatisticsViewModel(
    private val connectionTrackerDAO: ConnectionTrackerDAO,
    private val dnsLogDAO: DnsLogDAO,
    private val appConfig: AppConfig
) : ViewModel() {
    private var networkActivity: MutableLiveData<String> = MutableLiveData()
    private var countryActivities: MutableLiveData<String> = MutableLiveData()
    private var domains: MutableLiveData<String> = MutableLiveData()
    private var ips: MutableLiveData<String> = MutableLiveData()
    private var timeCategory: TimeCategory = TimeCategory.ONE_HOUR
    private var startTime: MutableLiveData<Long> = MutableLiveData()

    companion object {
        private const val ONE_HOUR_MILLIS = 1 * 60 * 60 * 1000L
        private const val ONE_DAY_MILLIS = 24 * ONE_HOUR_MILLIS
        private const val ONE_WEEK_MILLIS = 7 * ONE_DAY_MILLIS
        private const val IS_APP_BYPASSED = "true"
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
        networkActivity.value = ""
        domains.postValue("")
        countryActivities.value = ""
        ips.value = ""
    }

    fun getTimeCategory(): TimeCategory {
        return timeCategory
    }

    fun timeCategoryChanged(tc: TimeCategory, isAppBypassed: Boolean) {
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
        if (isAppBypassed) {
            domains.postValue(IS_APP_BYPASSED)
        } else {
            domains.postValue("")
        }
        countryActivities.value = ""
        ips.value = ""
    }

    val getAllowedAppNetworkActivity =
        networkActivity.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    // use dnsQuery as appName
                    val to = startTime.value ?: 0L
                    connectionTrackerDAO.getAllowedAppNetworkActivity(to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getBlockedAppNetworkActivity =
        networkActivity.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    // use dnsQuery as appName
                    val to = startTime.value ?: 0L
                    connectionTrackerDAO.getBlockedAppNetworkActivity(to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getMostContactedDomains =
        domains.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    if (appConfig.getBraveMode().isDnsMode()) {
                        val to = startTime.value ?: 0L
                        dnsLogDAO.getMostContactedDomains(to)
                    } else {
                        val to = startTime.value ?: 0L
                        connectionTrackerDAO.getMostContactedDomains(to)
                    }
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getMostBlockedDomains =
        domains.switchMap { isAppBypassed ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    if (appConfig.getBraveMode().isDnsMode()) {
                        val to = startTime.value ?: 0L
                        dnsLogDAO.getMostBlockedDomains(to)
                    } else {
                        // if any app bypasses the dns, then the decision made in flow() call
                        val to = startTime.value ?: 0L
                        if (isAppBypassed.isNotEmpty()) {
                            connectionTrackerDAO.getMostBlockedDomains(to)
                        } else {
                            dnsLogDAO.getMostBlockedDomains(to)
                        }
                    }
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getMostContactedIps =
        ips.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    val to = startTime.value ?: 0L
                    connectionTrackerDAO.getMostContactedIps(to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getMostBlockedIps =
        ips.switchMap { _ ->
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
                    connectionTrackerDAO.getMostContactedCountries(to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getMostBlockedCountries =
        countryActivities.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    val to = startTime.value ?: 0L
                    connectionTrackerDAO.getMostBlockedCountries(to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    suspend fun totalUsage(): DataUsageSummary {
        val to = startTime.value ?: 0L
        return connectionTrackerDAO.getTotalUsages(to, ConnectionTracker.ConnType.METERED.value)
    }
}
