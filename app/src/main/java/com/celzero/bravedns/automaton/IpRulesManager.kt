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
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_PORT
import com.celzero.bravedns.util.IpManager
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import inet.ipaddr.HostName
import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

object IpRulesManager : KoinComponent {

    private val customIpRepository by inject<CustomIpRepository>()
    private val persistentState by inject<PersistentState>()
    private val lock = ReentrantReadWriteLock()

    // max size of ip request look-up cache
    private const val CACHE_MAX_SIZE = 10000L

    private var appIpRules: MutableMap<IPAddress, CustomIp> = hashMapOf()
    private var wildCards: MutableMap<IPAddress, CustomIp> = hashMapOf()

    // stores the response for the look-up request from the BraveVpnService
    private val ipRulesLookupCache: Cache<IPAddress, IpCache> = CacheBuilder.newBuilder().maximumSize(
        CACHE_MAX_SIZE).build()

    // stores the data object in ip response look-up cache
    data class IpCache(val uid: Int, val status: IpRuleStatus)

    enum class IPRuleType(val id: Int) {
        IPV4(0), IPV4_WILDCARD(1), IPV6(2), IPV6_WILDCARD(3);
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

            // labels for spinner / toggle ui
            fun getLabel(): Array<String> {
                return arrayOf("Allow", "Block", "Whitelist")
            }

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

    fun removeFirewallRules(uid: Int, ipAddress: String) {
        Log.i(LOG_TAG_FIREWALL, "IP Rules, remove/delete rule for ip: $ipAddress for uid: $uid")
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

    fun updateRule(uid: Int, ipAddress: String, status: IpRuleStatus) {
        Log.i(LOG_TAG_FIREWALL,
              "IP Rules, update rule for ip: $ipAddress for uid: $uid with status: ${status.name}")
        io {
            val customIpObj = constructCustomIpObject(uid, ipAddress, status)
            customIpRepository.insert(customIpObj)
            updateLocalCache(customIpObj)
            ipRulesLookupCache.invalidateAll()
        }
    }

    fun whitelistIp(customIp: CustomIp) {
        Log.i(LOG_TAG_FIREWALL,
              "IP Rules, whitelist ip: ${customIp.ipAddress} for uid: ${customIp.uid} with previous status id: ${customIp.status}")
        io {
            customIp.status = IpRuleStatus.WHITELIST.id
            customIpRepository.update(customIp)
            updateLocalCache(customIp)
            ipRulesLookupCache.invalidateAll()
        }
    }

    fun noRuleIp(customIp: CustomIp) {
        Log.i(LOG_TAG_FIREWALL,
              "IP Rules, remove(soft delete) rule for ip: ${customIp.ipAddress} for uid: ${customIp.uid} with previous status id: ${customIp.status}")
        io {
            customIp.status = IpRuleStatus.NONE.id
            customIpRepository.update(customIp)
            updateLocalCache(customIp)
            ipRulesLookupCache.invalidateAll()
        }
    }

    private fun updateLocalCache(ip: CustomIp) {
        lock.write {
            if (ip.getCustomIpAddress().isMultiple) {
                wildCards[ip.getCustomIpAddress()] = ip
                return
            }
            appIpRules[ip.getCustomIpAddress()] = ip
        }
    }

    private fun removeLocalCache(ip: CustomIp) {
        lock.write {
            if (ip.getCustomIpAddress().isMultiple) {
                wildCards.remove(ip.getCustomIpAddress())
                return
            }
            appIpRules.remove(ip.getCustomIpAddress())
        }
    }

    fun blockIp(customIp: CustomIp) {
        Log.i(LOG_TAG_FIREWALL,
              "IP Rules, block rule for ip: ${customIp.ipAddress} for uid: ${customIp.uid} with previous status id: ${customIp.status}")
        io {
            customIp.status = IpRuleStatus.BLOCK.id
            customIpRepository.update(customIp)
            updateLocalCache(customIp)
            ipRulesLookupCache.invalidateAll()
        }
    }

    fun hasRule(uid: Int, ipString: String): IpRuleStatus {
        val ip = IPAddressString(ipString).address
        // check if the ip address is already available in the cache
        ipRulesLookupCache.getIfPresent(ip)?.let {
            // return only if both ip and app(uid) matches
            if (uid == UID_EVERYBODY || uid == it.uid) {
                return it.status
            } else {
                // no-op, continue
            }
        }

        if (appIpRules.contains(ip)) {
            val customIp = appIpRules[ip] ?: return IpRuleStatus.NONE

            // only if the uid is either global or same uid
            if (customIp.uid == UID_EVERYBODY || customIp.uid == uid) {
                val status = IpRuleStatus.getStatus(customIp.status)
                ipRulesLookupCache.put(ip, IpCache(customIp.uid, status))
                return status
            }
        }

        var status = subnetMatch(uid, ip)

        // no need to carry out below checks if ip is not IPv6
        if (!IpManager.isIpV6(ip) && !isIpv6ToV4FilterRequired()) {
            ipRulesLookupCache.put(ip, IpCache(uid, status))
            return status
        }

        // for IPv4 address in IPv6
        if (status == IpRuleStatus.NONE) { // no match with previous rules
            var ipv4: IPAddress? = null
            if (IpManager.canMakeIpv4(ip)) {
                ipv4 = IpManager.toIpV4(ip)
            }

            if (ipv4 != null) {
                status = hasRule(uid, ipv4.toNormalizedString())
            } else {
                // no-op
            }
        }

        ipRulesLookupCache.put(ip, IpCache(uid, status))
        return status
    }

    private fun isIpv6ToV4FilterRequired(): Boolean {
        return persistentState.filterIpv4inIpv6
    }

    fun getStatus(uid: Int, ip: String): IpRuleStatus {
        val ipAddress = IPAddressString(ip).address
        if (!appIpRules.contains(ipAddress)) return IpRuleStatus.NONE

        // get the status of the ipAddress from local list if available.
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
            appIpRules.clear()
            wildCards.clear()
        }
    }

    fun loadIpRules() {
        io {
            val rules = customIpRepository.getIpRules()
            rules.forEach {
                if (it.getCustomIpAddress().isMultiple) {
                    wildCards[it.getCustomIpAddress()] = it
                    return@forEach
                }
                appIpRules[it.getCustomIpAddress()] = it
            }
        }
    }

    private fun constructCustomIpObject(uid: Int, ipAddress: String, status: IpRuleStatus,
                                        wildcard: Boolean = false): CustomIp {
        val customIp = CustomIp()
        customIp.setCustomIpAddress(ipAddress)
        customIp.port = UNSPECIFIED_PORT
        customIp.protocol = ""
        customIp.isActive = true
        customIp.status = status.id
        customIp.wildcard = wildcard
        customIp.modifiedDateTime = System.currentTimeMillis()

        // TODO: is this needed in database?
        customIp.ruleType = if (HostName(ipAddress).address?.isIPv6 == true) {
            IPRuleType.IPV6.id
        } else {
            IPRuleType.IPV4.id
        }
        customIp.uid = uid
        return customIp
    }

    fun addIpRule(uid: Int, ipAddress: String, status: IpRuleStatus) {
        io {
            Log.i(LOG_TAG_FIREWALL,
                  "IP Rules, add rule for ip: $ipAddress with status: ${status.name}")
            val customIpObj = constructCustomIpObject(uid, ipAddress, status)
            customIpRepository.insert(customIpObj)
            updateLocalCache(customIpObj)
            ipRulesLookupCache.invalidateAll()
        }
    }

    private fun subnetMatch(uid: Int, ipAddress: IPAddress): IpRuleStatus {
        // TODO: subnet precedence should be taken care of.
        // TODO: IP/16 and IP/24 is added to the rule.
        wildCards.keys.forEach { wc ->
            if (wc.contains(ipAddress)) {
                val rule = wildCards[wc]
                if (rule?.uid == UID_EVERYBODY || rule?.uid == uid) {
                    return IpRuleStatus.getStatus(rule.status)
                } else {
                    // no-op
                }
            } else {
                // no-op
            }
        }

        return IpRuleStatus.NONE
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            f()
        }
    }
}
