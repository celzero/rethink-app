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

import android.content.Context
import android.util.Log
import com.celzero.bravedns.data.ConnectionRules
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.BlockedConnections
import com.celzero.bravedns.database.BlockedConnectionsRepository
import com.celzero.bravedns.ui.ConnTrackerBottomSheetFragment
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.firewallRules
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent.get

class FirewallRules {

    companion object {
        var firewallRulesObj: FirewallRules? = null
        fun getInstance(): FirewallRules {
            if (firewallRulesObj == null)
                firewallRulesObj = FirewallRules()
            return firewallRulesObj as FirewallRules
        }
    }

    fun updateFirewallRules(uid: Int, connectionRules: ConnectionRules, context: Context) {

    }

    fun clearFirewallRules(uid : Int, blockedConnectionsRepository:BlockedConnectionsRepository) {
        GlobalScope.launch(Dispatchers.IO) {
            blockedConnectionsRepository.clearFirewallRules(uid)
        }
        firewallRules.removeAll(uid)
    }

    fun removeFirewallRules(uid: Int, ipAddress: String, ruleType: String, blockedConnectionsRepository:BlockedConnectionsRepository) {
        if(DEBUG) Log.d(LOG_TAG,"Remove Firewall: $uid, $ipAddress")
        GlobalScope.launch(Dispatchers.IO) {
            //val blockedConnection = constructBlockedConnections(uid, ipAddress,ruleType)
            if(uid == ConnTrackerBottomSheetFragment.UNIVERSAL_RULES_UID)
                blockedConnectionsRepository.deleteIPRulesUniversal(ipAddress)
            else
                blockedConnectionsRepository.deleteIPRulesForUID(uid, ipAddress)
            //mDb.close()
        }
        firewallRules.remove(uid, ipAddress)
    }

    fun addFirewallRules(uid: Int, ipAddress: String, ruleType: String, blockedConnectionsRepository:BlockedConnectionsRepository) {
        if(DEBUG) Log.d(LOG_TAG,"addFirewallRules: $uid, $ipAddress")
        GlobalScope.launch(Dispatchers.IO) {
            val blockedConnection = constructBlockedConnections(uid, ipAddress,ruleType)
            blockedConnectionsRepository.insertAsync(blockedConnection)
            //mDb.close()
        }
        firewallRules.put(uid, ipAddress)
    }

    /**
     * Below function checks for the connection rules including the port and protocol.
     * Introduced new function which checks only on IP.
     */
    /* fun checkRules(uid: Int, connectionRules: ConnectionRules): Boolean {
         var rule = firewallRules[uid]
         rule?.forEach {
             if (it!! == connectionRules) {
                 if (DEBUG) Log.d(LOG_TAG, "Check Rules : True ${it.protocol}, ${it.ipAddress}, ${it.port}")
                 return true
             }
         }
         if (DEBUG) Log.d(LOG_TAG, "Check Rules : False ${connectionRules.protocol}, ${connectionRules.ipAddress}, ${connectionRules.port}")
         return false
     }*/

    fun checkRules(uid: Int, connectionRules: ConnectionRules): Boolean {
        val rule = firewallRules[uid]
        rule?.forEach {
            if (it!! == connectionRules.ipAddress) {
                //if (DEBUG) Log.d(LOG_TAG, "Check Rules : True ${it.protocol}, ${it.ipAddress}, ${it.port}")
                return true
            }
        }
        //if (DEBUG) Log.d(LOG_TAG, "Check Rules : False ${connectionRules.protocol}, ${connectionRules.ipAddress}, ${connectionRules.port}")
        return false
    }


    fun loadFirewallRules(blockedConnectionsRepository:BlockedConnectionsRepository) {
        GlobalScope.launch(Dispatchers.IO) {
            val dbVal = blockedConnectionsRepository.getBlockedConnections()
            dbVal.forEach {
                val key = it.uid
                val connRules = ConnectionRules(it.ipAddress!!, it.port, it.protocol!!)
                firewallRules.put(key, it.ipAddress)
            }
            //mDb.close()
        }
    }

    private fun constructBlockedConnections(uid: Int, ipAddress: String, ruleType : String): BlockedConnections {
        val blockedConnections = BlockedConnections()
        blockedConnections.ipAddress = ipAddress
        /*blockedConnections.port = connectionRules.port
         blockedConnections.protocol = connectionRules.protocol*/
        blockedConnections.port = 0
        blockedConnections.protocol = ""
        blockedConnections.isActive = true
        blockedConnections.modifiedDateTime = System.currentTimeMillis()
        blockedConnections.ruleType = ruleType
        blockedConnections.uid = uid
        return blockedConnections
    }

}