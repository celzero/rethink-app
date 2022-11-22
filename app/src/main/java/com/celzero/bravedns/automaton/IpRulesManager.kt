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
import androidx.lifecycle.LiveData
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.database.CustomIpRepository
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
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

    private var appIpRules: MutableMap<HostName, CustomIp> = hashMapOf()
    private var wildCards: MutableMap<HostName, CustomIp> = hashMapOf()

    // stores the response for the look-up request from the BraveVpnService
    private val ipRulesLookupCache: Cache<HostName, IpCache> = CacheBuilder.newBuilder().maximumSize(
        CACHE_MAX_SIZE).build()

    // stores the data object in ip response look-up cache
    data class IpCache(val uid: Int, val status: IpRuleStatus)

    enum class IPRuleType(val id: Int) {
        IPV4(0), IPV4_WILDCARD(1), IPV6(2), IPV6_WILDCARD(3);
    }

    enum class IpRuleStatus(val id: Int) {
        NONE(0), BLOCK(1), BYPASS_APP_RULES(2), BYPASS_UNIVERSAL(3);

        fun isBlocked(): Boolean {
            return this.id == BLOCK.id
        }

        fun byPassAppRules(): Boolean {
            return this.id == BYPASS_APP_RULES.id
        }

        fun byPassUniversalRules(): Boolean {
            return this.id == BYPASS_UNIVERSAL.id
        }

        fun noRule(): Boolean {
            return this.id == NONE.id
        }

        companion object {

            // labels for spinner / toggle ui
            fun getLabel(): Array<String> {
                return arrayOf("No Rule", "Block", "Bypass App Rules")
            }

            fun getStatus(id: Int): IpRuleStatus {
                return when (id) {
                    NONE.id -> NONE
                    BLOCK.id -> BLOCK
                    BYPASS_UNIVERSAL.id -> BYPASS_UNIVERSAL
                    BYPASS_APP_RULES.id -> BYPASS_APP_RULES
                    else -> NONE
                }
            }
        }
    }

    // The UID used to be generic uid used to block IP addresses which are intended to
    // block for all the applications.
    const val UID_EVERYBODY = -1000

    // returns CustomIp object based on uid and IP address
    private suspend fun getObj(uid: Int, ipAddress: String, port: Int): CustomIp? {
        return customIpRepository.getCustomIpDetail(uid, ipAddress, port)
    }

    fun getCustomIpsLiveData(): LiveData<Int> {
        return customIpRepository.getCustomIpsLiveData()
    }

    fun removeFirewallRules(uid: Int, ipAddress: String, port: Int) {
        Log.i(LOG_TAG_FIREWALL, "IP Rules, remove/delete rule for ip: $ipAddress for uid: $uid")
        if (ipAddress.isEmpty()) {
            return
        }

        io {
            val customIp = getObj(uid, ipAddress, port)
            customIpRepository.deleteIPRulesForUID(uid, ipAddress, port)

            if (customIp == null) return@io

            // remove the ip address from local list
            removeLocalCache(customIp)
            ipRulesLookupCache.invalidateAll()
        }
    }

    fun updateRule(uid: Int, ipAddress: String, port: Int, status: IpRuleStatus) {
        Log.i(LOG_TAG_FIREWALL,
              "IP Rules, update rule for ip: $ipAddress for uid: $uid with status: ${status.name}")
        io {
            val customIpObj = constructCustomIpObject(uid, ipAddress, port, status)
            customIpRepository.insert(customIpObj)
            updateLocalCache(customIpObj)
            ipRulesLookupCache.invalidateAll()
        }
    }

    fun byPassUniversal(customIp: CustomIp) {
        Log.i(LOG_TAG_FIREWALL,
              "IP Rules, by-pass univ rules, ip: ${customIp.ipAddress} for uid: ${customIp.uid} with previous status id: ${customIp.status}")
        io {
            customIp.status = IpRuleStatus.BYPASS_UNIVERSAL.id
            customIpRepository.update(customIp)
            updateLocalCache(customIp)
            ipRulesLookupCache.invalidateAll()
        }
    }

    fun byPassAppRules(customIp: CustomIp) {
        Log.i(LOG_TAG_FIREWALL,
              "IP Rules, by-pass app rules, ip: ${customIp.ipAddress} for uid: ${customIp.uid} with previous status id: ${customIp.status}")
        io {
            customIp.status = IpRuleStatus.BYPASS_APP_RULES.id
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
            if (ip.getCustomIpAddress().address.isMultiple || ip.getCustomIpAddress().address.isAnyLocal) {
                wildCards[ip.getCustomIpAddress()] = ip
                return
            }
            appIpRules[ip.getCustomIpAddress()] = ip
        }
    }

    private fun removeLocalCache(ip: CustomIp) {
        lock.write {
            if (ip.getCustomIpAddress().address.isMultiple || ip.getCustomIpAddress().address.isAnyLocal) {
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

    fun hasRule(uid: Int, ipString: String, port: Int?): IpRuleStatus {
        val ip = if (port == null) {
            HostName(ipString)
        } else {
            HostName(IPAddressString(ipString).address, port)
        }

        // check if the ip address is already available in the cache
        ipRulesLookupCache.getIfPresent(ip)?.let {
            // return only if both ip and app(uid) matches
            if (uid == it.uid) {
                return it.status
            } else {
                // no-op, continue
            }
        }

        if (appIpRules.contains(ip)) {
            val customIp = appIpRules[ip]

            if (customIp?.uid == uid) {
                val status = IpRuleStatus.getStatus(customIp.status)
                ipRulesLookupCache.put(ip, IpCache(customIp.uid, status))
                return status
            }
        }

        var status = subnetMatch(uid, ip)

        // status is not NONE, return the obtained status
        if (status != IpRuleStatus.NONE) {
            ipRulesLookupCache.put(ip, IpCache(uid, status))
            return status
        }

        // no need to carry out below checks if ip is not IPv6
        if (!IpManager.isIpV6(ip.address) && !isIpv6ToV4FilterRequired()) {
            ipRulesLookupCache.put(ip, IpCache(uid, status))
            return status
        }

        // for IPv4 address in IPv6
        var ipv4: IPAddress? = null
        if (IpManager.canMakeIpv4(ip.address)) {
            ipv4 = IpManager.toIpV4(ip.address)
        }

        if (ipv4 != null) {
            status = hasRule(uid, ipv4.toNormalizedString(), ip.port)
            // status is not NONE, return the obtained status
            if (status != IpRuleStatus.NONE) {
                ipRulesLookupCache.put(ip, IpCache(uid, status))
                return status
            }
        } else {
            // no-op
        }

        if (port != null) {
            status = hasRule(uid, ip.address.toNormalizedString(), null)
        }

        ipRulesLookupCache.put(ip, IpCache(uid, status))
        return status
    }

    private fun isIpv6ToV4FilterRequired(): Boolean {
        return persistentState.filterIpv4inIpv6
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
                if (it.getCustomIpAddress().address.isMultiple || it.getCustomIpAddress().address.isAnyLocal) {
                    wildCards[it.getCustomIpAddress()] = it
                    return@forEach
                }
                appIpRules[it.getCustomIpAddress()] = it
            }
        }
    }

    private fun constructCustomIpObject(uid: Int, hostName: HostName, status: IpRuleStatus,
                                        wildcard: Boolean = false): CustomIp {
        val customIp = CustomIp()
        customIp.setCustomIpAddress(hostName)
        customIp.port = hostName.port ?: Constants.UNSPECIFIED_PORT
        customIp.protocol = ""
        customIp.isActive = true
        customIp.status = status.id
        customIp.wildcard = wildcard
        customIp.modifiedDateTime = System.currentTimeMillis()

        // TODO: is this needed in database?
        customIp.ruleType = if (hostName.address?.isIPv6 == true) {
            IPRuleType.IPV6.id
        } else {
            IPRuleType.IPV4.id
        }
        customIp.uid = uid
        return customIp
    }

    private fun constructCustomIpObject(uid: Int, ipAddress: String, port: Int?,
                                        status: IpRuleStatus, wildcard: Boolean = false): CustomIp {
        val customIp = CustomIp()
        customIp.setCustomIpAddress(ipAddress)
        customIp.port = port ?: Constants.UNSPECIFIED_PORT
        customIp.protocol = ""
        customIp.isActive = true
        customIp.status = status.id
        customIp.wildcard = wildcard
        customIp.modifiedDateTime = System.currentTimeMillis()

        // TODO: is this needed in database?
        customIp.ruleType = if (customIp.getCustomIpAddress().address?.isIPv6 == true) {
            IPRuleType.IPV6.id
        } else {
            IPRuleType.IPV4.id
        }
        customIp.uid = uid
        return customIp
    }

    fun addIpRule(uid: Int, hostName: HostName, status: IpRuleStatus) {
        io {
            Log.i(LOG_TAG_FIREWALL,
                  "IP Rules, add rule for ip: $hostName with status: ${status.name}")
            val customIpObj = constructCustomIpObject(uid, hostName, status)
            customIpRepository.insert(customIpObj)
            updateLocalCache(customIpObj)
            ipRulesLookupCache.invalidateAll()
        }
    }

    fun addIpRule(uid: Int, ipAddress: String, port: Int?, status: IpRuleStatus) {
        io {
            Log.i(LOG_TAG_FIREWALL,
                  "IP Rules, add rule for ip: $ipAddress with status: ${status.name}")
            val customIpObj = constructCustomIpObject(uid, ipAddress, port, status)
            customIpRepository.insert(customIpObj)
            updateLocalCache(customIpObj)
            ipRulesLookupCache.invalidateAll()
        }
    }

    private fun subnetMatch(uid: Int, hostName: HostName): IpRuleStatus {
        // TODO: subnet precedence should be taken care of.
        // TODO: IP/16 and IP/24 is added to the rule.
        wildCards.keys.forEach { wc ->
            if (wc.address.contains(hostName.address)) {
                val rule = wildCards[wc]
                if (rule?.uid == uid && (wc.port == null || wc.port == hostName.port)) {
                    return IpRuleStatus.getStatus(rule.status)
                } else {
                    // no-op
                }
            } else if (wc.address.isAnyLocal && wc.address.ipVersion == hostName.address.ipVersion) {
                // case where the default (0.0.0.0 / [::]) is treated as wildcard
                val rule = wildCards[wc]
                if (uid == rule?.uid && wc.port == hostName.port) {
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
