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

class DoHEndpointRepository(private val doHEndpointDAO: DoHEndpointDAO) {

    @Transaction
    suspend fun update(doHEndpoint: DoHEndpoint) {
        doHEndpointDAO.removeConnectionStatus()
        doHEndpointDAO.update(doHEndpoint)
    }

    suspend fun insertAsync(doHEndpoint: DoHEndpoint) {
        doHEndpointDAO.insert(doHEndpoint)
    }

    suspend fun deleteOlderData(date: Long) {
        doHEndpointDAO.deleteOlderData(date)
    }

    suspend fun deleteDoHEndpoint(id: Int) {
        doHEndpointDAO.deleteDoHEndpoint(id)
    }

    suspend fun removeConnectionStatus() {
        doHEndpointDAO.removeConnectionStatus()
    }

    suspend fun getConnectedDoH(): DoHEndpoint? {
        return doHEndpointDAO.getConnectedDoH()
    }

    suspend fun getCount(): Int {
        return doHEndpointDAO.getCount()
    }
}
