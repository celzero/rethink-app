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
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.database.CustomIpRepository
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_PORT
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

object IpRulesManager : KoinComponent {

    private val customIpRepository by inject<CustomIpRepository>()
    private val lock = ReentrantReadWriteLock()

    // max size of ip request look-up cache
    private const val CACHE_MAX_SIZE = 10000L

    // maintaining two different maps for the firewall rules
    // wildcard match and the ip match
    private var appIpRules: MutableMap<String, CustomIp> = hashMapOf()
    private var wildcards: MutableMap<String, CustomIp> = hashMapOf()

    // stores the response for the look-up request from the BraveVpnService
    private val ipRulesLookupCache: Cache<String, IpCache> = CacheBuilder.newBuilder().maximumSize(
        CACHE_MAX_SIZE).build()

    // stores the data object in ip response look-up cache
    data class IpCache(val uid: Int, val status: IpRuleStatus)

    enum class IPRuleType(val id: Int) {
        IPV4(0), IPV4_WILDCARD(1), IPV6(2), IPV6_WILDCARD(3);

        companion object {
            fun getInputTypes(): Array<String> {
                return arrayOf("IPV4", "IPV4 Wildcard", "IPV6 (Coming soon)",
                               "IPV6 Wildcard (Coming soon)")
            }

            fun getType(id: Int): IPRuleType {
                return when (id) {
                    IPV4.id -> IPV4
                    IPV4_WILDCARD.id -> IPV4_WILDCARD
                    IPV6.id -> IPV6
                    IPV6_WILDCARD.id -> IPV6_WILDCARD
                    else -> IPV4
                }
            }
        }
    }

    enum class IpRuleStatus(val id: Int) {
        NONE(0), BLOCK(1), WHITELIST(2);

        fun isBlocked(): Boolean {
            return this.id == BLOCK.id
        }

        fun isWhitelist(): Boolean {
            return this.id == WHITELIST.id
        }

        fun noRule(): Boolean {
            return this.id == NONE.id
        }

        companion object {
            fun getStatus(id: Int): IpRuleStatus {
                return when (id) {
                    BLOCK.id -> BLOCK
                    WHITELIST.id -> WHITELIST
                    else -> NONE
                }
            }
        }
    }

    // The UID used to be generic uid used to block IP addresses which are intended to
    // block for all the applications.
    const val UID_EVERYBODY = -1000

    // returns CustomIp object based on uid and IP address
    private suspend fun getObj(uid: Int, ipAddress: String): CustomIp? {
        return customIpRepository.getCustomIpDetail(uid, ipAddress)
    }

    fun clearFirewallRules(uid: Int) {
        io {
            customIpRepository.clearFirewallRules(uid)
        }
        appIpRules.clear()
    }

    fun removeFirewallRules(uid: Int, ipAddress: String) {
        if (DEBUG) Log.d(LOG_TAG_FIREWALL, "Remove Firewall: $uid, $ipAddress")
        if (ipAddress.isEmpty()) {
            return
        }

        io {
            val customIp = getObj(uid, ipAddress)
            customIpRepository.deleteIPRulesForUID(uid, ipAddress)

            if (customIp == null) return@io

            // remove the ip address from local list
            removeLocalCache(customIp)
            ipRulesLookupCache.invalidateAll()
        }
    }

    fun addFirewallRules(uid: Int, ipAddress: String, wildcard: Boolean) {
        if (DEBUG) Log.d(LOG_TAG_FIREWALL, "addFirewallRules: $uid, $ipAddress")
        io {
            val customIpObj = constructCustomIpObject(uid, ipAddress, IpRuleStatus.BLOCK,
                                                      wildcard = wildcard)
            customIpRepository.insert(customIpObj)
            updateLocalCache(customIpObj)
            ipRulesLookupCache.invalidateAll()
        }
    }

    fun updateRule(uid: Int, ipAddress: String, status: IpRuleStatus) {
        io {
            val customIpObj = constructCustomIpObject(uid, ipAddress, status)
            customIpRepository.insert(customIpObj)
            updateLocalCache(customIpObj)
            ipRulesLookupCache.invalidateAll()
        }
    }

    fun whitelistIp(customIp: CustomIp) {
        io {
            customIp.status = IpRuleStatus.WHITELIST.id
            customIpRepository.update(customIp)
            updateLocalCache(customIp)
            ipRulesLookupCache.invalidateAll()
        }
    }

    fun noRuleIp(customIp: CustomIp) {
        io {
            customIp.status = IpRuleStatus.NONE.id
            customIpRepository.update(customIp)
            updateLocalCache(customIp)
            ipRulesLookupCache.invalidateAll()
        }
    }

    private fun updateLocalCache(ip: CustomIp) {

        if (ip.wildcard) {
            lock.write {
                wildcards[ip.ipAddress] = ip
            }
            return
        }

        lock.write {
            appIpRules[ip.ipAddress] = ip
        }
    }

    private fun removeLocalCache(ip: CustomIp) {
        if (ip.wildcard) {
            lock.write {
                wildcards.remove(ip.ipAddress)
            }
            return
        }

        lock.write {
            appIpRules.remove(ip.ipAddress)
        }
    }

    fun blockIp(customIp: CustomIp) {
        io {
            customIp.status = IpRuleStatus.BLOCK.id
            customIpRepository.update(customIp)
            updateLocalCache(customIp)
            ipRulesLookupCache.invalidateAll()
        }
    }

    fun hasRule(uid: Int, ipAddress: String): IpRuleStatus {
        // check if the ip address is already available in the cache
        ipRulesLookupCache.getIfPresent(ipAddress)?.let {
            // return only if both ip and app(uid) matches
            if (uid == UID_EVERYBODY || uid == it.uid) {
                return it.status
            }
        }

        if (appIpRules.contains(ipAddress)) {
            val ip = appIpRules[ipAddress] ?: return IpRuleStatus.NONE

            val status = IpRuleStatus.getStatus(ip.status)
            ipRulesLookupCache.put(ipAddress, IpCache(ip.uid, status))
            return status
        }

        val match = wildcardMatch(ipAddress)

        ipRulesLookupCache.put(ipAddress, IpCache(uid, match))
        return match
    }

    fun getStatus(uid: Int, ipAddress: String): IpRuleStatus {
        if (!appIpRules.contains(ipAddress)) return IpRuleStatus.NONE

        // get the status of the ipAddress from local list if available
        // status will be retrieved for uid matching either UID_EVERYBODY or specific uid
        return appIpRules[ipAddress]?.status?.let {
            val ruleUid = appIpRules[ipAddress]?.uid
            if (ruleUid == UID_EVERYBODY || uid == ruleUid) {
                IpRuleStatus.getStatus(it)
            } else {
                IpRuleStatus.NONE
            }
        } ?: IpRuleStatus.NONE
    }

    fun clearAllIpRules() {
        io {
            customIpRepository.deleteAllIPRulesUniversal()
            loadFirewallRules()
        }
    }

    fun loadFirewallRules() {
        io {
            val rules = customIpRepository.getIpRules()
            rules.forEach {
                // wildcards are added in separate list
                if (it.wildcard) {
                    wildcards[it.ipAddress] = it
                    return@forEach
                }

                appIpRules[it.ipAddress] = it
            }
        }
    }

    private fun constructCustomIpObject(uid: Int, ipAddress: String, status: IpRuleStatus,
                                        ruleType: IPRuleType = IPRuleType.IPV4,
                                        wildcard: Boolean = false): CustomIp {
        val customIp = CustomIp()
        customIp.ipAddress = ipAddress
        customIp.port = UNSPECIFIED_PORT
        customIp.protocol = ""
        customIp.isActive = true
        customIp.status = status.id
        customIp.wildcard = wildcard
        customIp.modifiedDateTime = System.currentTimeMillis()
        customIp.ruleType = ruleType.id
        customIp.uid = uid
        return customIp
    }

    fun addIpRule(uid: Int, ipAddress: String, status: IpRuleStatus, type: IPRuleType) {
        io {
            // fixme: as of now, ip rules are not validated against uid's so assigning
            // UID_EVERYBODY to uid instead of actual uid
            val _uid = UID_EVERYBODY
            val customIpObj = constructCustomIpObject(_uid, ipAddress, status, type)
            customIpRepository.insert(customIpObj)
            updateLocalCache(customIpObj)
        }
    }

    private fun wildcardMatch(ipAddress: String): IpRuleStatus {
        wildcards.keys.forEach { wildcard ->
            if (isMatch(wildcard, ipAddress)) {
                return wildcards[wildcard]?.status?.let {
                    IpRuleStatus.getStatus(it)
                } ?: IpRuleStatus.NONE
            }
        }

        return IpRuleStatus.NONE
    }

    private fun isMatch(w: String, i: String): Boolean {
        val wildcard = Array(4) { "*" }
        val ipAddress = Array(4) { "*" }

        w.split(".").toTypedArray().copyInto(wildcard, 0)
        i.split(".").toTypedArray().copyInto(ipAddress, 0)

        wildcard.forEachIndexed { index, s ->
            if (s != "*" && ipAddress[index] != s) {
                return false
            }
        }

        return true
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            f()
        }
    }
}
