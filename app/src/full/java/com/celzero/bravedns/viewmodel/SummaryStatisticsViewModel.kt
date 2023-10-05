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
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.DnsLogDAO
import com.celzero.bravedns.service.FirewallManager
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

    private var fromTime: MutableLiveData<Long> = MutableLiveData()
    private var toTime: MutableLiveData<Long> = MutableLiveData()

    companion object {
        const val TIME_1_HOUR = 1 * 60 * 60 * 1000L
        const val TIME_24_HOUR = 24 * 60 * 60 * 1000L
        const val TIME_7_DAYS = 7 * 24 * 60 * 60 * 1000L
    }

    enum class TimeCategory(val value: Int) {
        ONE_HOUR(0),
        TWENTY_FOUR_HOUR(1),
        SEVEN_DAYS(2);

        companion object {
            fun fromValue(value: Int) = values().first { it.value == value }
        }
    }

    init {
        networkActivity.value = ""
        countryActivities.value = ""
        domains.value = ""
        ips.value = ""
        // set from and to time to current and 3 hrs before
        fromTime.value = System.currentTimeMillis() - TIME_1_HOUR
        toTime.value = System.currentTimeMillis()
    }

    fun timeCategoryChanged(timeCategory: TimeCategory) {
        when (timeCategory) {
            TimeCategory.ONE_HOUR -> {
                fromTime.value = System.currentTimeMillis() - TIME_1_HOUR
                toTime.value = System.currentTimeMillis()
            }
            TimeCategory.TWENTY_FOUR_HOUR -> {
                fromTime.value = System.currentTimeMillis() - TIME_24_HOUR
                toTime.value = System.currentTimeMillis()
            }
            TimeCategory.SEVEN_DAYS -> {
                fromTime.value = System.currentTimeMillis() - TIME_7_DAYS
                toTime.value = System.currentTimeMillis()
            }
        }
        networkActivity.value = ""
        countryActivities.value = ""
        domains.value = ""
        ips.value = ""
    }

    val getAllowedAppNetworkActivity =
        networkActivity.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    // use dnsQuery as appName
                    val from = fromTime.value ?: 0L
                    val to = toTime.value ?: 0L
                    connectionTrackerDAO.getAllowedAppNetworkActivity(from, to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getBlockedAppNetworkActivity =
        networkActivity.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    // use dnsQuery as appName
                    val from = fromTime.value ?: 0L
                    val to = toTime.value ?: 0L
                    connectionTrackerDAO.getBlockedAppNetworkActivity(from, to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getMostContactedDomains =
        domains.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    if (appConfig.getBraveMode().isDnsMode()) {
                        val from = fromTime.value ?: 0L
                        val to = toTime.value ?: 0L
                        dnsLogDAO.getMostContactedDomains(from, to)
                    } else {
                        val from = fromTime.value ?: 0L
                        val to = toTime.value ?: 0L
                        connectionTrackerDAO.getMostContactedDomains(from, to)
                    }
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getMostBlockedDomains =
        domains.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    if (appConfig.getBraveMode().isDnsMode()) {
                        val from = fromTime.value ?: 0L
                        val to = toTime.value ?: 0L
                        dnsLogDAO.getMostBlockedDomains(from, to)
                    } else {
                        // if any app bypasses the dns, then the decision made in flow() call
                        val from = fromTime.value ?: 0L
                        val to = toTime.value ?: 0L
                        if (FirewallManager.isAnyAppBypassesDns()) {
                            connectionTrackerDAO.getMostBlockedDomains(from, to)
                        } else {
                            dnsLogDAO.getMostBlockedDomains(from, to)
                        }
                    }
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getMostContactedIps =
        ips.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    val from = fromTime.value ?: 0L
                    val to = toTime.value ?: 0L
                    connectionTrackerDAO.getMostContactedIps(from, to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getMostBlockedIps =
        ips.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    val from = fromTime.value ?: 0L
                    val to = toTime.value ?: 0L
                    connectionTrackerDAO.getMostBlockedIps(from, to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getMostContactedCountries =
        countryActivities.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    val from = fromTime.value ?: 0L
                    val to = toTime.value ?: 0L
                    connectionTrackerDAO.getMostContactedCountries(from, to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getMostBlockedCountries =
        countryActivities.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    val from = fromTime.value ?: 0L
                    val to = toTime.value ?: 0L
                    connectionTrackerDAO.getMostBlockedCountries(from, to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }
}
