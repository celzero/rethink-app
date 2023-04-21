/*
 * Copyright 2021 RethinkDNS and its authors
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
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.database.CustomIpDao
import com.celzero.bravedns.util.Constants.Companion.LIVEDATA_PAGE_SIZE
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY

class CustomIpViewModel(private val customIpDao: CustomIpDao) : ViewModel() {

    private var filteredList: MutableLiveData<String> = MutableLiveData()
    private var uid: Int = UID_EVERYBODY

    init {
        filteredList.value = ""
    }

    val customIpDetails =
        filteredList.switchMap { input ->
            if (uid != UID_EVERYBODY) {
                getAppWise(uid, input)
            } else {
                getUniversal(input)
            }
        }

    fun ipRulesCount(uid: Int): LiveData<Int> {
        return customIpDao.getAppWiseIpRulesCount(uid)
    }

    private fun getAppWise(uid: Int, input: String?): LiveData<PagingData<CustomIp>> {
        return if (input.isNullOrBlank()) {
            Pager(PagingConfig(LIVEDATA_PAGE_SIZE)) { customIpDao.getAppWiseCustomIp(uid) }
                .liveData
                .cachedIn(viewModelScope)
        } else {
            Pager(PagingConfig(LIVEDATA_PAGE_SIZE)) {
                    customIpDao.getAppWiseCustomIp("%$input%", uid)
                }
                .liveData
                .cachedIn(viewModelScope)
        }
    }

    private fun getUniversal(input: String?): LiveData<PagingData<CustomIp>> {
        return if (input.isNullOrBlank()) {
            Pager(PagingConfig(LIVEDATA_PAGE_SIZE)) {
                    customIpDao.getUnivBlockedConnectionsLiveData()
                }
                .liveData
                .cachedIn(viewModelScope)
        } else {
            Pager(PagingConfig(LIVEDATA_PAGE_SIZE)) {
                    customIpDao.getUnivBlockedConnectionsByIP("%$input%")
                }
                .liveData
                .cachedIn(viewModelScope)
        }
    }

    fun setFilter(filter: String) {
        filteredList.value = filter
    }

    fun setUid(i: Int) {
        this.uid = i
    }
}
