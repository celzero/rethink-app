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
import com.celzero.bravedns.ui.ConnTrackerBottomSheetFragment
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.firewallRules
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class FirewallRules {

    companion object {
        var firewallRulesObj: FirewallRules? = null
        fun getInstance(): FirewallRules {
            if (firewallRulesObj == null)
                firewallRulesObj = FirewallRules()
            return firewallRulesObj as FirewallRules
        }
    }

    fun clearFirewallRules(uid : Int, blockedConnectionsRepository:BlockedConnectionsRepository) {
        GlobalScope.launch(Dispatchers.IO) {
            blockedConnectionsRepository.clearFirewallRules(uid)
        }
        firewallRules.removeAll(uid)
    }

    fun removeFirewallRules(uid: Int, ipAddress: String, blockedConnectionsRepository:BlockedConnectionsRepository) {
        if(DEBUG) Log.d(LOG_TAG_FIREWALL,"Remove Firewall: $uid, $ipAddress")
        GlobalScope.launch(Dispatchers.IO) {
            if(uid == ConnTrackerBottomSheetFragment.UNIVERSAL_RULES_UID)
                blockedConnectionsRepository.deleteIPRulesUniversal(ipAddress)
            else
                blockedConnectionsRepository.deleteIPRulesForUID(uid, ipAddress)
        }
        firewallRules.remove(uid, ipAddress)
    }

    fun addFirewallRules(uid: Int, ipAddress: String, ruleType: String, blockedConnectionsRepository:BlockedConnectionsRepository) {
        if(DEBUG) Log.d(LOG_TAG_FIREWALL,"addFirewallRules: $uid, $ipAddress")
        GlobalScope.launch(Dispatchers.IO) {
            val blockedConnection = constructBlockedConnections(uid, ipAddress,ruleType)
            blockedConnectionsRepository.insertAsync(blockedConnection)
        }
        firewallRules.put(uid, ipAddress)
    }

    fun checkRules(uid: Int, connectionRules: ConnectionRules): Boolean {
        val rule = firewallRules[uid]
        rule?.forEach {
            if (it!! == connectionRules.ipAddress) {
                return true
            }
        }
        return false
    }


    fun loadFirewallRules(blockedConnectionsRepository:BlockedConnectionsRepository) {
        GlobalScope.launch(Dispatchers.IO) {
            val dbVal = blockedConnectionsRepository.getBlockedConnections()
            dbVal.forEach {
                val key = it.uid
                firewallRules.put(key, it.ipAddress)
            }
        }
    }

    private fun constructBlockedConnections(uid: Int, ipAddress: String, ruleType : String): BlockedConnections {
        val blockedConnections = BlockedConnections()
        blockedConnections.ipAddress = ipAddress
        blockedConnections.port = 0
        blockedConnections.protocol = ""
        blockedConnections.isActive = true
        blockedConnections.modifiedDateTime = System.currentTimeMillis()
        blockedConnections.ruleType = ruleType
        blockedConnections.uid = uid
        return blockedConnections
    }

}