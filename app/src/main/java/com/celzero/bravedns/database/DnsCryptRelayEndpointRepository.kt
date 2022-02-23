/*
Copyright 2020 RethinkDNS developers

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.database

import androidx.lifecycle.LiveData
import androidx.paging.PagedList
import androidx.paging.toLiveData
import com.celzero.bravedns.util.Constants.Companion.LIVEDATA_PAGE_SIZE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class DnsCryptRelayEndpointRepository(
        private val dnsCryptRelayEndpointDAO: DnsCryptRelayEndpointDAO) {

    fun update(dnsCryptRelayEndpoint: DnsCryptRelayEndpoint) {
        dnsCryptRelayEndpointDAO.update(dnsCryptRelayEndpoint)
    }

    fun deleteAsync(dnsCryptRelayEndpoint: DnsCryptRelayEndpoint,
                    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        coroutineScope.launch {
            dnsCryptRelayEndpointDAO.delete(dnsCryptRelayEndpoint)
        }
    }

    fun insertAsync(dnsCryptRelayEndpoint: DnsCryptRelayEndpoint,
                    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        coroutineScope.launch {
            dnsCryptRelayEndpointDAO.insert(dnsCryptRelayEndpoint)
        }
    }

    fun getDnsCryptRelayEndpointLiveData(): LiveData<PagedList<DnsCryptRelayEndpoint>> {
        return dnsCryptRelayEndpointDAO.getDnsCryptRelayEndpointLiveData().toLiveData(
            pageSize = LIVEDATA_PAGE_SIZE)
    }

    fun deleteOlderData(date: Long,
                        coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        coroutineScope.launch {
            dnsCryptRelayEndpointDAO.deleteOlderData(date)
        }
    }

    fun getDnsCryptEndpointLiveDataByName(
            query: String): LiveData<PagedList<DnsCryptRelayEndpoint>> {
        return dnsCryptRelayEndpointDAO.getDnsCryptRelayEndpointLiveDataByName(query).toLiveData(
            pageSize = LIVEDATA_PAGE_SIZE)
    }

    fun deleteDnsCryptRelayEndpoint(id: Int) {
        dnsCryptRelayEndpointDAO.deleteDnsCryptRelayEndpoint(id)
    }

    fun removeConnectionStatus() {
        dnsCryptRelayEndpointDAO.removeConnectionStatus()
    }

    fun getConnectedRelays(): List<DnsCryptRelayEndpoint> {
        return dnsCryptRelayEndpointDAO.getConnectedRelays()
    }

    fun getCount(): Int {
        return dnsCryptRelayEndpointDAO.getCount()
    }

    fun getServersToAdd(): String {
        val relays = getConnectedRelays()
        return relays.joinToString(separator = ",") {
            it.dnsCryptRelayURL
        }
    }

    fun getServersToRemove(): String {
        val relays = getConnectedRelays()
        return relays.joinToString(separator = ",") {
            "${it.id}"
        }
    }
}
