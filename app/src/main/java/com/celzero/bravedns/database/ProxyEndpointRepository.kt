/*
Copyright 2020 RethinkDNS and its authors

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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch


class ProxyEndpointRepository(private val proxyEndpointDAO: ProxyEndpointDAO) {

    fun updateAsync(proxyEndpoint: ProxyEndpoint, coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            proxyEndpointDAO.update(proxyEndpoint)
        }
    }

    fun deleteAsync(proxyEndpoint: ProxyEndpoint, coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            proxyEndpointDAO.delete(proxyEndpoint)
        }
    }

    fun insertAsync(proxyEndpoint: ProxyEndpoint, coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            proxyEndpointDAO.insert(proxyEndpoint)
        }
    }

    fun getDNSProxyEndpointLiveData(): LiveData<PagedList<ProxyEndpoint>> {
        return proxyEndpointDAO.getDNSProxyEndpointLiveData().toLiveData(
            pageSize = LIVEDATA_PAGE_SIZE)
    }

    fun deleteOlderData(date: Long, coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            proxyEndpointDAO.deleteOlderData(date)
        }
    }

    fun getDNSProxyEndpointLiveDataByType(query: String): LiveData<PagedList<ProxyEndpoint>> {
        return proxyEndpointDAO.getDNSProxyEndpointLiveDataByType(query).toLiveData(
            pageSize = LIVEDATA_PAGE_SIZE)
    }

    fun deleteDNSProxyEndpoint(proxyIP: String, port: Int) {
        proxyEndpointDAO.deleteDNSProxyEndpoint(proxyIP, port)
    }

    fun removeConnectionStatus() {
        proxyEndpointDAO.removeConnectionStatus()
    }

    fun getCount(): Int {
        return proxyEndpointDAO.getCount()
    }

    fun getConnectedProxy(): ProxyEndpoint? {
        return proxyEndpointDAO.getConnectedProxy()
    }

    fun getConnectedOrbotProxy(): ProxyEndpoint {
        return proxyEndpointDAO.getConnectedOrbotProxy()
    }

    fun clearAllData() {
        proxyEndpointDAO.clearAllData()
    }

    fun clearOrbotData() {
        proxyEndpointDAO.clearOrbotData()
    }

}
