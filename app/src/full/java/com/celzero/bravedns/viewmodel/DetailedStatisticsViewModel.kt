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
import com.celzero.bravedns.ui.fragment.SummaryStatisticsFragment
import com.celzero.bravedns.util.Constants

class DetailedStatisticsViewModel(
    private val connectionTrackerDAO: ConnectionTrackerDAO,
    private val dnsLogDAO: DnsLogDAO,
    appConfig: AppConfig
) : ViewModel() {
    private var allowedNetworkActivity: MutableLiveData<String> = MutableLiveData()
    private var blockedNetworkActivity: MutableLiveData<String> = MutableLiveData()
    private var allowedDomains: MutableLiveData<String> = MutableLiveData()
    private var blockedDomains: MutableLiveData<String> = MutableLiveData()
    private var allowedIps: MutableLiveData<String> = MutableLiveData()
    private var blockedIps: MutableLiveData<String> = MutableLiveData()
    private var allowedCountries: MutableLiveData<String> = MutableLiveData()
    private var blockedCountries: MutableLiveData<String> = MutableLiveData()
    private var startTime: MutableLiveData<Long> = MutableLiveData()

    companion object {
        private const val ONE_HOUR_MILLIS = 1 * 60 * 60 * 1000L
        private const val ONE_DAY_MILLIS = 24 * ONE_HOUR_MILLIS
        private const val ONE_WEEK_MILLIS = 7 * ONE_DAY_MILLIS
        private const val IS_APP_BYPASSED = "true"
    }

    fun setData(type: SummaryStatisticsFragment.SummaryStatisticsType, isAppBypassed: Boolean) {
        when (type) {
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONNECTED_APPS -> {
                allowedNetworkActivity.value = ""
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_APPS -> {
                blockedNetworkActivity.value = ""
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONTACTED_DOMAINS -> {
                allowedDomains.value = ""
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_DOMAINS -> {
                if (isAppBypassed) {
                    blockedDomains.postValue(IS_APP_BYPASSED)
                } else {
                    blockedDomains.postValue("")
                }
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONTACTED_IPS -> {
                allowedIps.value = ""
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_IPS -> {
                blockedIps.value = ""
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONTACTED_COUNTRIES -> {
                allowedCountries.value = ""
            }
            SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_COUNTRIES -> {
                blockedCountries.value = ""
            }
        }
    }

    fun timeCategoryChanged(timeCategory: SummaryStatisticsViewModel.TimeCategory) {
        when (timeCategory) {
            SummaryStatisticsViewModel.TimeCategory.ONE_HOUR -> {
                startTime.value = System.currentTimeMillis() - ONE_HOUR_MILLIS
            }
            SummaryStatisticsViewModel.TimeCategory.TWENTY_FOUR_HOUR -> {
                startTime.value = System.currentTimeMillis() - ONE_DAY_MILLIS
            }
            SummaryStatisticsViewModel.TimeCategory.SEVEN_DAYS -> {
                startTime.value = System.currentTimeMillis() - ONE_WEEK_MILLIS
            }
        }
    }

    val getAllAllowedAppNetworkActivity =
        allowedNetworkActivity.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    val to = startTime.value ?: 0L
                    connectionTrackerDAO.getAllAllowedAppNetworkActivity(to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getAllBlockedAppNetworkActivity =
        blockedNetworkActivity.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    val to = startTime.value ?: 0L
                    connectionTrackerDAO.getAllBlockedAppNetworkActivity(to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getAllContactedDomains =
        allowedDomains.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    val to = startTime.value ?: 0L
                    if (appConfig.getBraveMode().isDnsMode()) {
                        dnsLogDAO.getAllContactedDomains(to)
                    } else {
                        connectionTrackerDAO.getAllContactedDomains(to)
                    }
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getAllBlockedDomains =
        blockedDomains.switchMap { isAppBypassed ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    val to = startTime.value ?: 0L
                    if (appConfig.getBraveMode().isDnsMode()) {
                        dnsLogDAO.getAllBlockedDomains(to)
                    } else {
                        // if any app bypasses the dns, then the decision made in flow() call
                        if (isAppBypassed.isNotEmpty()) {
                            connectionTrackerDAO.getAllBlockedDomains(to)
                        } else {
                            dnsLogDAO.getAllBlockedDomains(to)
                        }
                    }
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getAllContactedIps =
        allowedIps.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    val to = startTime.value ?: 0L
                    connectionTrackerDAO.getAllContactedIps(to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getAllBlockedIps =
        blockedIps.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    val to = startTime.value ?: 0L
                    connectionTrackerDAO.getAllBlockedIps(to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getAllContactedCountries =
        allowedCountries.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    val to = startTime.value ?: 0L
                    connectionTrackerDAO.getAllContactedCountries(to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    val getAllBlockedCountries =
        blockedCountries.switchMap { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    val to = startTime.value ?: 0L
                    connectionTrackerDAO.getAllBlockedCountries(to)
                }
                .liveData
                .cachedIn(viewModelScope)
        }
}
