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
package com.celzero.bravedns.automaton

import android.util.Log
import com.celzero.bravedns.data.ConnectionRules
import com.celzero.bravedns.database.BlockedConnections
import com.celzero.bravedns.database.BlockedConnectionsRepository
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_PORT
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.google.common.collect.HashMultimap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object FirewallRules {

    var appIpRules: HashMultimap<Int, String> = HashMultimap.create()

    // The UID used to be generic uid used to block IP addresses which are intented to
    // block for all the applications.
    const val UID_EVERYBODY = -1000

    fun clearFirewallRules(uid: Int, blockedConnectionsRepository: BlockedConnectionsRepository) {
        CoroutineScope(Dispatchers.IO).launch {
            blockedConnectionsRepository.clearFirewallRules(uid)
        }
        appIpRules.removeAll(uid)
    }

    fun removeFirewallRules(uid: Int, ipAddress: String?,
                            blockedConnectionsRepository: BlockedConnectionsRepository) {
        if (DEBUG) Log.d(LOG_TAG_FIREWALL, "Remove Firewall: $uid, $ipAddress")
        if (ipAddress.isNullOrEmpty()) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            if (uid == UID_EVERYBODY) blockedConnectionsRepository.deleteIPRulesUniversal(ipAddress)
            else blockedConnectionsRepository.deleteIPRulesForUID(uid, ipAddress)
        }
        appIpRules.remove(uid, ipAddress)
    }

    fun addFirewallRules(uid: Int, ipAddress: String, ruleType: String,
                         blockedConnectionsRepository: BlockedConnectionsRepository) {
        if (DEBUG) Log.d(LOG_TAG_FIREWALL, "addFirewallRules: $uid, $ipAddress")
        CoroutineScope(Dispatchers.IO).launch {
            val blockedConnection = constructBlockedConnections(uid, ipAddress, ruleType)
            blockedConnectionsRepository.insertAsync(blockedConnection)
        }
        appIpRules.put(uid, ipAddress)
    }

    fun checkRules(uid: Int, rules: ConnectionRules): Boolean {
        val rule = this.appIpRules[uid]
        rule?.forEach {
            if (rules.ipAddress == it) {
                return true
            }
        }
        return false
    }

    fun clearAllIpRules(blockedConnectionsRepository: BlockedConnectionsRepository) {
        CoroutineScope(Dispatchers.IO).launch {
            blockedConnectionsRepository.deleteAllIPRulesUniversal()
        }
        appIpRules.clear()
    }

    fun loadFirewallRules(blockedConnectionsRepository: BlockedConnectionsRepository) {
        CoroutineScope(Dispatchers.IO).launch {
            val rules = blockedConnectionsRepository.getBlockedConnections()
            rules.forEach {
                val key = it.uid
                this@FirewallRules.appIpRules.put(key, it.ipAddress)
            }
        }
    }

    private fun constructBlockedConnections(uid: Int, ipAddress: String,
                                            ruleType: String): BlockedConnections {
        val blockedConnections = BlockedConnections()
        blockedConnections.ipAddress = ipAddress
        blockedConnections.port = UNSPECIFIED_PORT
        blockedConnections.protocol = ""
        blockedConnections.isActive = true
        blockedConnections.modifiedDateTime = System.currentTimeMillis()
        blockedConnections.ruleType = ruleType
        blockedConnections.uid = uid
        return blockedConnections
    }

}
