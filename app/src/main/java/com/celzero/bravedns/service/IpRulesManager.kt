/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns.service

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import backend.Backend
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.database.CustomIpRepository
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger.Companion.LOG_TAG_FIREWALL
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import inet.ipaddr.HostName
import inet.ipaddr.IPAddressString
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object IpRulesManager : KoinComponent {

    private val db by inject<CustomIpRepository>()

    // max size of ip request look-up cache
    private const val CACHE_MAX_SIZE = 10000L

    private var iptree = Backend.newIpTree()

    // key-value object for ip look-up
    data class CacheKey(val hostName: HostName, val uid: Int)

    // stores the response for the look-up request from the BraveVpnService
    // especially useful for storing results of subnetMatch() function as it is expensive
    private val resultsCache: Cache<CacheKey, IpRuleStatus> =
        CacheBuilder.newBuilder().maximumSize(CACHE_MAX_SIZE).build()

    enum class IPRuleType(val id: Int) {
        IPV4(0),
        IPV6(2)
    }

    enum class IpRuleStatus(val id: Int) {
        NONE(0),
        BLOCK(1),
        TRUST(2),
        BYPASS_UNIVERSAL(3);

        fun isBlocked(): Boolean {
            return this.id == BLOCK.id
        }

        companion object {

            // labels for spinner / toggle ui
            fun getLabel(context: Context): Array<String> {
                return arrayOf(
                    context.getString(R.string.ci_no_rule),
                    context.getString(R.string.ci_block),
                    context.getString(R.string.ci_trust_rule)
                )
            }

            fun getStatus(id: Int?): IpRuleStatus {
                if (id == null) {
                    return NONE
                }

                return when (id) {
                    NONE.id -> NONE
                    BLOCK.id -> BLOCK
                    TRUST.id -> TRUST
                    BYPASS_UNIVERSAL.id -> BYPASS_UNIVERSAL
                    else -> NONE
                }
            }
        }
    }

    private fun logd(msg: String) {
        if (DEBUG) Log.d(LOG_TAG_FIREWALL, msg)
    }

    suspend fun load(): Long {
        iptree.clear()
        db.getIpRules().forEach {
            val ipstr = it.getCustomIpAddress().toNormalizedString()
            val k = treeKey(ipstr)
            val v = treeVal(it.uid, it.port, it.status)
            if (!k.isNullOrEmpty()) {
                try {
                    logd("iptree.add($k, $v)")
                    iptree.add(k, v)
                } catch (e: Exception) {
                    Log.e(LOG_TAG_FIREWALL, "err iptree.add($k, $v)", e)
                }
            }
        }
        return iptree.len()
    }

    fun getCustomIpsLiveData(): LiveData<Int> {
        return db.getCustomIpsLiveData()
    }

    private fun treeKey(ipstr: String): String? {
        // "192/8" -> 0.0.0.192/32
        // "*.*" -> 0.0.0.0/0
        // "*.*.*.*" -> 0.0.0.0/0
        // "0/24" -> 0.0.0.0/24
        // "::" -> ::/128
        // "::/1" -> ::/1
        // assignPrefixForSingleBlock: equivalent CIDR address with a prefix length for which the
        // address subnet block matches the range of values in this address.
        // If no such prefix length exists, returns null.
        // Examples:
        // 1.2.3.4 returns 1.2.3.4/32
        // 1.2.*.* returns 1.2.0.0/16
        // 1.2.*.0/24 returns 1.2.0.0/16
        // 1.2.*.4 returns null
        // 1.2.252-255.* returns 1.2.252.0/22
        // 1.2.3.4/x returns the same address
        return ipaddr(ipstr)?.asAddress()?.assignPrefixForSingleBlock()?.toCanonicalString()
    }

    private fun treeValLike(uid: Int, port: Int): String {
        return "$uid:$port"
    }

    private fun treeValStatus(v: String?): IpRuleStatus {
        if (v.isNullOrEmpty()) {
            return IpRuleStatus.NONE
        }
        try {
            val items = v.split(":")
            if (items.size != 3) {
                return IpRuleStatus.NONE
            }
            return IpRuleStatus.getStatus(items[2].toIntOrNull())
        } catch (e: Exception) {
            Log.e(LOG_TAG_FIREWALL, "err treeValStatus: ${e.message}")
            return IpRuleStatus.NONE
        }
    }

    private fun treeValsFromCsv(csv: String): List<String> {
        return csv.split(Backend.Vsep)
    }

    private fun treeVal(uid: Int, port: Int, rule: Int): String {
        return "$uid:$port:$rule"
    }

    suspend fun removeIpRule(uid: Int, ipstr: String, port: Int) {
        Log.i(LOG_TAG_FIREWALL, "ip rule, rmv: $ipstr for uid: $uid")
        if (ipstr.isEmpty()) {
            return
        }

        db.deleteRule(uid, ipstr, port)

        val k = treeKey(ipstr)
        if (!k.isNullOrEmpty()) iptree.escLike(k, treeValLike(uid, port))

        resultsCache.invalidateAll()
    }

    private suspend fun updateRule(uid: Int, ipaddr: String, port: Int, status: IpRuleStatus) {
        Log.i(LOG_TAG_FIREWALL, "ip rule, update: $ipaddr for uid: $uid; status: ${status.name}")
        val c = makeCustomIp(uid, ipaddr, port, status)
        db.update(c)
        val k = treeKey(ipaddr)
        if (!k.isNullOrEmpty()) {
            iptree.escLike(k, treeValLike(uid, port))
            iptree.add(k, treeVal(uid, port, status.id))
        }
        resultsCache.invalidateAll()
    }

    suspend fun updateBypass(c: CustomIp) {
        return updateRule(c.uid, c.ipAddress, c.port, IpRuleStatus.BYPASS_UNIVERSAL)
    }

    suspend fun updateTrust(c: CustomIp) {
        return updateRule(c.uid, c.ipAddress, c.port, IpRuleStatus.TRUST)
    }

    suspend fun updateNoRule(c: CustomIp) {
        return updateRule(c.uid, c.ipAddress, c.port, IpRuleStatus.NONE)
    }

    suspend fun updateBlock(customIp: CustomIp) {
        return updateRule(customIp.uid, customIp.ipAddress, customIp.port, IpRuleStatus.BLOCK)
    }

    fun hasRule(uid: Int, ipstr: String, port: Int): IpRuleStatus {
        val ip = ipaddr(ipstr, port) ?: return IpRuleStatus.NONE
        val ck = CacheKey(ip, uid)

        resultsCache.getIfPresent(ck)?.let {
            // return only if both ip and app(uid) matches
            logd("match in cache $uid $ipstr: $it")
            return it
        }

        getMostSpecificRuleMatch(uid, ipstr, port).let {
            logd("ip rule for $uid $ipstr $port => $it ??")
            if (it != IpRuleStatus.NONE) {
                resultsCache.put(ck, it)
                return it
            }
        }
        getMostSpecificRuleMatch(uid, ipstr).let {
            logd("ip rule for $uid $ipstr => $it ??")
            if (it != IpRuleStatus.NONE) {
                resultsCache.put(ck, it)
                return it
            }
        }
        getMostSpecificRouteMatch(uid, ipstr, port).let {
            logd("route rule for $uid $ipstr $port => $it ??")
            if (it != IpRuleStatus.NONE) {
                resultsCache.put(ck, it)
                return it
            }
        }
        getMostSpecificRouteMatch(uid, ipstr).let {
            logd("route rule for $uid $ipstr => $it ??")
            if (it != IpRuleStatus.NONE) {
                resultsCache.put(ck, it)
                return it
            }
        }
        logd("hasRule? NO: $uid, $ipstr, $port")
        return IpRuleStatus.NONE
    }

    fun ipaddr(ipstr: String, port: Int? = null): HostName? {
        return try {
            if (port == null) {
                HostName(ipstr)
            } else {
                HostName(IPAddressString(ipstr).address, port)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG_FIREWALL, "err ip-rule($ipstr, $port): ${e.message}")
            null
        }
    }

    fun getMostSpecificRuleMatch(uid: Int, ipstr: String, port: Int = 0): IpRuleStatus {
        val k = treeKey(ipstr)
        if (!k.isNullOrEmpty()) {
            val vlike = treeValLike(uid, port)
            // rules at the end of the list have higher precedence as they're more specific
            // (think: 0.0.0.0/0 vs 1.1.1.1/32)
            val x = iptree.getLike(k, vlike)
            logd("getMostSpecificRuleMatch: $uid, $k, $vlike => $x")
            return treeValsFromCsv(x)
                .map { treeValStatus(it) }
                .lastOrNull { it != IpRuleStatus.NONE } ?: IpRuleStatus.NONE
        }
        return IpRuleStatus.NONE
    }

    private fun getMostSpecificRouteMatch(uid: Int, ipstr: String, port: Int = 0): IpRuleStatus {
        val k = treeKey(ipstr)
        if (!k.isNullOrEmpty()) {
            val vlike = treeValLike(uid, port)
            // rules at the end of the list have higher precedence as they're more specific
            // (think: 0.0.0.0/0 vs 1.1.1.1/32)
            val x = iptree.valuesLike(k, vlike)
            logd("getMostSpecificRouteMatch: $uid, $k, $vlike => $x")
            return treeValsFromCsv(x)
                .map { treeValStatus(it) }
                .lastOrNull { it != IpRuleStatus.NONE } ?: IpRuleStatus.NONE
        }
        return IpRuleStatus.NONE
    }

    suspend fun deleteRulesByUid(uid: Int) {
        db.getRulesByUid(uid).forEach {
            val ipstr = it.getCustomIpAddress().toNormalizedString()
            val k = treeKey(ipstr)
            if (!k.isNullOrEmpty()) {
                iptree.esc(k, treeVal(it.uid, it.port, it.status))
            }
        }
        db.deleteRulesByUid(uid)
        resultsCache.invalidateAll()
    }

    suspend fun deleteAllAppsRules() {
        db.deleteAllAppsRules()
        iptree.clear()
        resultsCache.invalidateAll()
    }

    private fun makeCustomIp(
        uid: Int,
        ipAddress: String,
        port: Int?,
        status: IpRuleStatus,
        wildcard: Boolean = false
    ): CustomIp {
        val customIp = CustomIp()
        customIp.setCustomIpAddress(ipAddress)
        customIp.port = port ?: Constants.UNSPECIFIED_PORT
        customIp.protocol = ""
        customIp.isActive = true
        customIp.status = status.id
        customIp.wildcard = wildcard
        customIp.modifiedDateTime = System.currentTimeMillis()

        // TODO: is this needed in database?
        customIp.ruleType =
            if (customIp.getCustomIpAddress().asAddress()?.isIPv6 == true) {
                IPRuleType.IPV6.id
            } else {
                IPRuleType.IPV4.id
            }
        customIp.uid = uid
        return customIp
    }

    suspend fun addIpRule(uid: Int, ipstr: String, port: Int?, status: IpRuleStatus) {
        Log.i(
            LOG_TAG_FIREWALL,
            "ip rule, add rule for ($uid) ip: $ipstr, $port with status: ${status.name}"
        )
        val c = makeCustomIp(uid, ipstr, port, status)
        db.insert(c)
        val k = treeKey(ipstr)
        if (!k.isNullOrEmpty()) {
            iptree.escLike(k, treeValLike(uid, port ?: 0))
            iptree.add(k, treeVal(uid, port ?: 0, status.id))
        }
        resultsCache.invalidateAll()
    }

    suspend fun updateUids(uids: List<Int>, newUids: List<Int>) {
        for (i in uids.indices) {
            val u = uids[i]
            val n = newUids[i]
            db.updateUid(u, n)
        }
        resultsCache.invalidateAll()
        load()
        Log.i(LOG_TAG_FIREWALL, "ip rules updated")
    }

    suspend fun replaceIpRule(
        prevRule: CustomIp,
        ipString: String,
        newStatus: IpRuleStatus
    ) {
        val host = HostName(ipString)
        val prevIpAddrStr = prevRule.getCustomIpAddress().asAddress().toNormalizedString()
        val newIpAddrStr = host.asAddress().toNormalizedString()
        Log.i(
            LOG_TAG_FIREWALL,
            "ip rule, replace (${prevRule.uid}); ${prevIpAddrStr}:${prevRule.port}; new: $newIpAddrStr, ${newStatus.name}"
        )
        db.deleteRule(prevRule.uid, prevIpAddrStr, prevRule.port)
        val newRule = makeCustomIp(prevRule.uid, ipString, host.port, newStatus)
        db.insert(newRule)
        val pk = treeKey(prevIpAddrStr)
        if (!pk.isNullOrEmpty()) {
            iptree.escLike(pk, treeValLike(prevRule.uid, prevRule.port))
        }
        val nk = treeKey(newIpAddrStr)
        if (!nk.isNullOrEmpty()) {
            iptree.escLike(nk, treeValLike(prevRule.uid, host.port ?: 0))
            iptree.add(nk, treeVal(prevRule.uid, host.port ?: 0, newStatus.id))
        }
        resultsCache.invalidateAll()
    }
}
