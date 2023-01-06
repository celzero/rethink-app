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

class ProxyEndpointRepository(private val proxyEndpointDAO: ProxyEndpointDAO) {

    suspend fun insert(proxyEndpoint: ProxyEndpoint) {
        proxyEndpointDAO.insert(proxyEndpoint)
    }

    suspend fun deleteOlderData(date: Long) {
        proxyEndpointDAO.deleteOlderData(date)
    }

    suspend fun removeConnectionStatus() {
        proxyEndpointDAO.removeConnectionStatus()
    }

    suspend fun getCount(): Int {
        return proxyEndpointDAO.getCount()
    }

    suspend fun getConnectedProxy(): ProxyEndpoint? {
        return proxyEndpointDAO.getConnectedProxy()
    }

    fun getConnectedProxyLiveData(): LiveData<ProxyEndpoint?> {
        return proxyEndpointDAO.getConnectedProxyLiveData()
    }

    suspend fun getConnectedOrbotProxy(): ProxyEndpoint {
        return proxyEndpointDAO.getConnectedOrbotProxy()
    }

    suspend fun clearAllData() {
        proxyEndpointDAO.clearAllData()
    }

    suspend fun clearOrbotData() {
        proxyEndpointDAO.clearOrbotData()
    }
}
