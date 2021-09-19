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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class BlockedConnectionsRepository(private val blockedConnectionsDAO: BlockedConnectionsDAO) {

    fun update(blockedConnections: BlockedConnections) {
        blockedConnectionsDAO.update(blockedConnections)
    }

    fun clearFirewallRules(uid: Int) {
        blockedConnectionsDAO.clearFirewallRules(uid)
    }

    fun insert(blockedConnections: BlockedConnections) {
        blockedConnectionsDAO.insert(blockedConnections)
    }

    fun getBlockedConnections(): List<BlockedConnections> {
        return blockedConnectionsDAO.getBlockedConnections()
    }

    fun deleteIPRulesUniversal(ipAddress: String) {
        blockedConnectionsDAO.deleteIPRulesUniversal(ipAddress)
    }

    fun deleteIPRulesForUID(uid: Int, ipAddress: String) {
        blockedConnectionsDAO.deleteIPRulesForUID(uid, ipAddress)
    }

    fun deleteAllIPRulesUniversal(coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        coroutineScope.launch {
            blockedConnectionsDAO.deleteAllIPRulesUniversal()
        }
    }

    fun getBlockedConnectionsCount(): Int {
        return blockedConnectionsDAO.getBlockedConnectionsCount()
    }

    fun getBlockedConnectionCountLiveData(): LiveData<Int> {
        return blockedConnectionsDAO.getBlockedConnectionCountLiveData()
    }
}
