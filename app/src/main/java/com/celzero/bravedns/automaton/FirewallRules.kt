package com.celzero.bravedns.automaton

import android.content.Context
import android.util.Log
import com.celzero.bravedns.data.ConnectionRules
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.BlockedConnections
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.firewallRules
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

    fun updateFirewallRules(uid: Int, connectionRules: ConnectionRules, context: Context) {

    }

    fun clearFirewallRules(uid : Int, context: Context) {
        GlobalScope.launch(Dispatchers.IO) {
            val mDb = AppDatabase.invoke(context.applicationContext)
            val blockedConnectionsRepository = mDb.blockedConnectionRepository()
            blockedConnectionsRepository.clearFirewallRules(uid)
        }
        firewallRules.removeAll(uid)
        if (DEBUG) printFirewallRules()
    }

    fun removeFirewallRules(uid: Int, ipAddress: String, context: Context) {
        if(DEBUG) Log.d("BraveDNS","Remove Firewall: $uid, $ipAddress")
        GlobalScope.launch(Dispatchers.IO) {
            val mDb = AppDatabase.invoke(context.applicationContext)
            val blockedConnectionsRepository = mDb.blockedConnectionRepository()
            val blockedConnection = constructBlockedConnections(uid, ipAddress)
            blockedConnectionsRepository.deleteAsync(blockedConnection)
        }
        firewallRules.remove(uid, ipAddress)
        if (DEBUG) printFirewallRules()
    }

    fun addFirewallRules(uid: Int, ipAddress: String, context: Context) {
        if(DEBUG) Log.d("BraveDNS","addFirewallRules: $uid, $ipAddress")
        GlobalScope.launch(Dispatchers.IO) {
            val mDb = AppDatabase.invoke(context.applicationContext)
            val blockedConnectionsRepository = mDb.blockedConnectionRepository()
            val blockedConnection = constructBlockedConnections(uid, ipAddress)
            blockedConnectionsRepository.insertAsync(blockedConnection)
        }
        firewallRules.put(uid, ipAddress)
        if (DEBUG) printFirewallRules()
    }

    /**
     * Below function checks for the connection rules including the port and protocol.
     * Introduced new function which checks only on IP.
     */
    /* fun checkRules(uid: Int, connectionRules: ConnectionRules): Boolean {
         var rule = firewallRules[uid]
         rule?.forEach {
             if (it!! == connectionRules) {
                 if (DEBUG) Log.d("BraveDNS", "Check Rules : True ${it.protocol}, ${it.ipAddress}, ${it.port}")
                 return true
             }
         }
         if (DEBUG) Log.d("BraveDNS", "Check Rules : False ${connectionRules.protocol}, ${connectionRules.ipAddress}, ${connectionRules.port}")
         return false
     }*/

    fun checkRules(uid: Int, connectionRules: ConnectionRules): Boolean {
        var rule = firewallRules[uid]
        rule?.forEach {
            if (it!! == connectionRules.ipAddress) {
                //if (DEBUG) Log.d("BraveDNS", "Check Rules : True ${it.protocol}, ${it.ipAddress}, ${it.port}")
                return true
            }
        }
        //if (DEBUG) Log.d("BraveDNS", "Check Rules : False ${connectionRules.protocol}, ${connectionRules.ipAddress}, ${connectionRules.port}")
        return false
    }

    private fun printFirewallRules() {
        firewallRules.asMap().forEach {
            var key = it.key
            it.value.forEach {
                Log.d("BraveDNS", "Rule uid: ${key} ipAddress: ${it}")
            }
        }
    }

    fun loadFirewallRules(context: Context) {
        GlobalScope.launch(Dispatchers.IO) {
            val mDb = AppDatabase.invoke(context.applicationContext)
            val blockedConnectionsRepository = mDb.blockedConnectionRepository()
            val dbVal = blockedConnectionsRepository.getBlockedConnections()
            dbVal.forEach {
                val key = it.uid
                val connRules = ConnectionRules(it.ipAddress!!, it.port, it.protocol!!)
                firewallRules.put(key, it.ipAddress)
            }
        }
    }

    private fun constructBlockedConnections(uid: Int, ipAddress: String): BlockedConnections {
        val blockedConnections = BlockedConnections()
        blockedConnections.ipAddress = ipAddress
        blockedConnections.port = 0
        blockedConnections.protocol = ""
        /*blockedConnections.port = connectionRules.port
        blockedConnections.protocol = connectionRules.protocol*/
        blockedConnections.uid = uid
        return blockedConnections
    }

}