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

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.paging.toLiveData
import com.celzero.bravedns.database.DNSCryptRelayEndpointDAO

class DNSCryptRelayEndpointViewModel(
        private val dnsCryptRelayEndpointDAO: DNSCryptRelayEndpointDAO) : ViewModel() {

    private var filteredList: MutableLiveData<String> = MutableLiveData()

    init {
        filteredList.value = ""
    }

    var dnsCryptRelayEndpointList = Transformations.switchMap(filteredList) { input ->
        if (input.isBlank()) {
            dnsCryptRelayEndpointDAO.getDNSCryptRelayEndpointLiveData().toLiveData(pageSize = 50)
        } else {
            dnsCryptRelayEndpointDAO.getDNSCryptRelayEndpointLiveDataByName("%$input%").toLiveData(
                pageSize = 50)
        }
    }

    fun setFilter(filter: String?) {
        filteredList.value = filter
    }

    fun setFilterBlocked(filter: String) {
        filteredList.value = filter
    }

}
