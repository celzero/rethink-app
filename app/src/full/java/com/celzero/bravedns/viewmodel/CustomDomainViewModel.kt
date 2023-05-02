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
import androidx.paging.cachedIn
import androidx.paging.liveData
import com.celzero.bravedns.database.CustomDomainDAO
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LIVEDATA_PAGE_SIZE

class CustomDomainViewModel(private val customDomainDAO: CustomDomainDAO) : ViewModel() {

    private var filteredList: MutableLiveData<String> = MutableLiveData()
    private var uid: Int = Constants.UID_EVERYBODY

    init {
        filteredList.value = ""
    }

    val customDomains =
        filteredList.switchMap { input ->
            Pager(PagingConfig(LIVEDATA_PAGE_SIZE)) {
                    customDomainDAO.getDomainsLiveData(uid, "%$input%")
                }
                .liveData
                .cachedIn(viewModelScope)
        }

    fun setFilter(filter: String) {
        filteredList.value = filter
    }

    fun domainRulesCount(uid: Int): LiveData<Int> {
        return customDomainDAO.getAppWiseDomainRulesCount(uid)
    }

    fun setUid(i: Int) {
        this.uid = i
    }
}
