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

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.room.Transaction
import com.celzero.bravedns.util.LoggerConstants


class DnsCryptEndpointRepository(private val dnsCryptEndpointDAO: DnsCryptEndpointDAO) {

    @Transaction
    fun update(dnsCryptEndpoint: DnsCryptEndpoint) {
        dnsCryptEndpointDAO.update(dnsCryptEndpoint)
    }

    suspend fun insertAsync(dnsCryptEndpoint: DnsCryptEndpoint) {
        dnsCryptEndpointDAO.insert(dnsCryptEndpoint)
    }

    suspend fun deleteOlderData(date: Long) {
        dnsCryptEndpointDAO.deleteOlderData(date)
    }

    suspend fun deleteDNSCryptEndpoint(id: Int) {
        dnsCryptEndpointDAO.deleteDNSCryptEndpoint(id)
    }

    suspend fun removeConnectionStatus() {
        dnsCryptEndpointDAO.removeConnectionStatus()
    }

    suspend fun getConnectedDNSCrypt(): List<DnsCryptEndpoint> {
        return dnsCryptEndpointDAO.getConnectedDNSCrypt()
    }

    suspend fun getConnectedCount(): Int {
        return dnsCryptEndpointDAO.getConnectedCount()
    }

    fun getConnectedCountLiveData(): LiveData<Int> {
        return dnsCryptEndpointDAO.getConnectedCountLiveData()
    }

    suspend fun getCount(): Int {
        return dnsCryptEndpointDAO.getCount()
    }

    suspend fun updateConnectionStatus(liveServersID: String?) {
        removeConnectionStatus()
        liveServersID?.split(",")?.forEach {
            dnsCryptEndpointDAO.updateConnectionStatus(it.trim().toInt())
        }
    }

    suspend fun getServersToAdd(): String {
        val servers = getConnectedDNSCrypt().joinToString(separator = ",") {
            "${it.id}#${it.dnsCryptURL}"
        }
        Log.i(LoggerConstants.LOG_TAG_APP_MODE, "Crypt Server: $servers")
        return servers
    }

    suspend fun getServersToRemove(): String {
        return getConnectedDNSCrypt().joinToString(separator = ",") { "${it.id}" }
    }
}
