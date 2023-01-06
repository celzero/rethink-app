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

import androidx.lifecycle.*
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.liveData
import com.celzero.bravedns.database.CustomIpDao
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY

class AppCustomIpViewModel(private val customIpDao: CustomIpDao) : ViewModel() {

    private var filteredList: MutableLiveData<String> = MutableLiveData()
    private var uid: Int = UID_EVERYBODY

    init {
        filteredList.value = ""
    }

    val customIpDetails =
        Transformations.switchMap(filteredList) { input ->
            if (input.isNullOrBlank()) {
                Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                        customIpDao.getAppWiseCustomIp(uid)
                    }
                    .liveData
                    .cachedIn(viewModelScope)
            } else {
                Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                        customIpDao.getAppWiseCustomIp("%$input%", uid)
                    }
                    .liveData
                    .cachedIn(viewModelScope)
            }
        }

    fun appWiseIpRulesCount(uid: Int): LiveData<Int> {
        return customIpDao.getAppWiseIpRulesCount(uid)
    }

    fun setFilter(filter: String) {
        filteredList.value = filter
    }

    fun setUid(i: Int) {
        this.uid = i
    }
}
