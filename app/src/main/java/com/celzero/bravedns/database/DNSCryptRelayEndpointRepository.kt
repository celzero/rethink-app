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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch


class DNSCryptRelayEndpointRepository(
        private val dnsCryptRelayEndpointDAO: DNSCryptRelayEndpointDAO) {

    fun updateAsync(dnsCryptRelayEndpoint: DNSCryptRelayEndpoint,
                    coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            dnsCryptRelayEndpointDAO.update(dnsCryptRelayEndpoint)
        }
    }

    fun deleteAsync(dnsCryptRelayEndpoint: DNSCryptRelayEndpoint,
                    coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            dnsCryptRelayEndpointDAO.delete(dnsCryptRelayEndpoint)
        }
    }


    fun insertAsync(dnsCryptRelayEndpoint: DNSCryptRelayEndpoint,
                    coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            dnsCryptRelayEndpointDAO.insert(dnsCryptRelayEndpoint)
        }
    }

    fun getDNSCryptRelayEndpointLiveData(): LiveData<PagedList<DNSCryptRelayEndpoint>> {
        return dnsCryptRelayEndpointDAO.getDNSCryptRelayEndpointLiveData().toLiveData(pageSize = 50)
    }

    fun deleteOlderData(date: Long, coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            dnsCryptRelayEndpointDAO.deleteOlderData(date)
        }
    }

    fun getDNSCryptEndpointLiveDataByName(
            query: String): LiveData<PagedList<DNSCryptRelayEndpoint>> {
        return dnsCryptRelayEndpointDAO.getDNSCryptRelayEndpointLiveDataByName(query).toLiveData(
            pageSize = 50)
    }

    fun deleteDNSCryptRelayEndpoint(url: String) {
        dnsCryptRelayEndpointDAO.deleteDNSCryptRelayEndpoint(url)
    }

    fun removeConnectionStatus() {
        dnsCryptRelayEndpointDAO.removeConnectionStatus()
    }

    fun getConnectedRelays(): List<DNSCryptRelayEndpoint> {
        return dnsCryptRelayEndpointDAO.getConnectedRelays()
    }

    fun getCount(): Int {
        return dnsCryptRelayEndpointDAO.getCount()
    }
}
