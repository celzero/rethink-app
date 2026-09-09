/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.database

import androidx.room.Transaction
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_DNS

class SmartDnsEndpointRepository(private val smartDnsEndpointDAO: SmartDnsEndpointDAO) {

    companion object {
        private const val TAG = "SmartDnsEndpointRepo"
    }

    suspend fun getSmartDnsEndpoints(): List<SmartDnsEndpoint> {
        return smartDnsEndpointDAO.getSmartDnsEndpoints()
    }

    suspend fun getSelectedEndpoint(): SmartDnsEndpoint? {
        return smartDnsEndpointDAO.getSelectedEndpoint()
    }

    suspend fun getCount(): Int {
        return smartDnsEndpointDAO.getCount()
    }

    suspend fun insert(endpoint: SmartDnsEndpoint) {
        smartDnsEndpointDAO.insert(endpoint)
    }

    @Transaction
    suspend fun select(id: Int) {
        Logger.i(LOG_TAG_DNS, "$TAG select smart dns endpoint: $id")
        smartDnsEndpointDAO.removeConnectionStatus()
        smartDnsEndpointDAO.setConnectionStatus(id)
    }

    suspend fun removeConnectionStatus() {
        smartDnsEndpointDAO.removeConnectionStatus()
    }
}
