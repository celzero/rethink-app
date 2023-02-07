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

import androidx.room.Transaction

class DnsProxyEndpointRepository(private val dnsProxyEndpointDAO: DnsProxyEndpointDAO) {

    @Transaction
    fun update(dnsProxyEndpoint: DnsProxyEndpoint) {
        dnsProxyEndpointDAO.removeConnectionStatus()
        dnsProxyEndpointDAO.update(dnsProxyEndpoint)
    }

    suspend fun insertAsync(dnsCryptEndpoint: DnsProxyEndpoint) {
        dnsProxyEndpointDAO.insert(dnsCryptEndpoint)
    }

    suspend fun deleteOlderData(date: Long) {
        dnsProxyEndpointDAO.deleteOlderData(date)
    }

    suspend fun deleteDnsProxyEndpoint(id: Int) {
        dnsProxyEndpointDAO.deleteDnsProxyEndpoint(id)
    }

    suspend fun removeConnectionStatus() {
        dnsProxyEndpointDAO.removeConnectionStatus()
    }

    suspend fun getCount(): Int {
        return dnsProxyEndpointDAO.getCount()
    }

    suspend fun getSelectedProxy(): DnsProxyEndpoint? {
        return dnsProxyEndpointDAO.getSelectedProxy()
    }

    suspend fun getOrbotDnsEndpoint(): DnsProxyEndpoint? {
        return dnsProxyEndpointDAO.getOrbotDnsEndpoint()
    }
}
