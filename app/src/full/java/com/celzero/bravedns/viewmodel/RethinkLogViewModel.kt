/*
 * Copyright 2023 RethinkDNS and its authors
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
import com.celzero.bravedns.database.RethinkLog
import com.celzero.bravedns.database.RethinkLogDao
import com.celzero.bravedns.util.Constants.Companion.LIVEDATA_PAGE_SIZE

class RethinkLogViewModel(private val rlogDao: RethinkLogDao) : ViewModel() {

    private var filterString: MutableLiveData<String> = MutableLiveData()
    private val pagingConfig: PagingConfig

    init {
        filterString.value = ""

        pagingConfig =
            PagingConfig(
                enablePlaceholders = true,
                prefetchDistance = 3,
                initialLoadSize = LIVEDATA_PAGE_SIZE * 2,
                maxSize = LIVEDATA_PAGE_SIZE * 3,
                pageSize = LIVEDATA_PAGE_SIZE * 2,
                jumpThreshold = 5
            )
    }

    val rlogList = filterString.switchMap { input -> fetchNetworkLogs(input) }

    fun setFilter(searchString: String) {
        if (searchString.isNotBlank()) filterString.value = searchString
        else filterString.value = ""
    }

    private fun fetchNetworkLogs(input: String): LiveData<PagingData<RethinkLog>> {
        return getAllNetworkLogs(input)
    }

    private fun getAllNetworkLogs(input: String): LiveData<PagingData<RethinkLog>> {
        return Pager(pagingConfig) {
                if (input.isBlank()) rlogDao.getRethinkLogByName()
                else rlogDao.getRethinkLogByName("%$input%")
            }
            .liveData
            .cachedIn(viewModelScope)
    }
}
