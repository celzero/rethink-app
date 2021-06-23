/*
 * Copyright 2020 RethinkDNS and its authors
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

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.paging.toLiveData
import com.celzero.bravedns.database.DNSLogDAO
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.DNS_LIVEDATA_PAGE_SIZE
import com.celzero.bravedns.util.Constants.Companion.FILTER_IS_FILTER

class DNSLogViewModel(private val dnsLogDAO: DNSLogDAO) : ViewModel() {

    private var filteredList: MutableLiveData<String> = MutableLiveData()

    init {
        filteredList.value = ""
    }

    var dnsLogsList = Transformations.switchMap(filteredList) { input ->
        if (input.isBlank()) {
            dnsLogDAO.getDNSLogsLiveData().toLiveData(pageSize = DNS_LIVEDATA_PAGE_SIZE)
        } else if (input.contains(FILTER_IS_FILTER)) {
            val searchString = input.split(":")[0]
            if (searchString.isEmpty()) {
                dnsLogDAO.getBlockedDNSLogsLiveData().toLiveData(pageSize = DNS_LIVEDATA_PAGE_SIZE)
            } else {
                dnsLogDAO.getBlockedDNSLogsLiveDataByName("%$searchString%").toLiveData(pageSize = DNS_LIVEDATA_PAGE_SIZE)
            }
        } else {
            dnsLogDAO.getDNSLogsByQueryLiveData("%$input%").toLiveData(DNS_LIVEDATA_PAGE_SIZE)
        }
    }

    fun setFilter(searchString: String?, filter: String) {
        filteredList.value = "$searchString$filter"
    }

    fun setFilterBlocked(filter: String?) {
        filteredList.value = filter
    }

}
