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

import Logger
import Logger.LOG_TAG_FIREWALL
import android.content.Context
import androidx.lifecycle.LiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.database.CustomIpRepository
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.UNSPECIFIED_PORT
import com.celzero.bravedns.util.Utilities.togs
import com.celzero.bravedns.util.Utilities.tos
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.Gostr
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object IpRulesManager : KoinComponent {

    private val db by inject<CustomIpRepository>()

    // max size of ip request look-up cache
    private const val CACHE_MAX_SIZE = 10000L

    private var iptree = Backend.newIpTree()

    // key-value object for ip look-up
    data class CacheKey(val ipNetPort: String, val uid: Int)

    // stores the response for the look-up request from the BraveVpnService
    // especially useful for storing results of subnetMatch() function as it is expensive
    private val resultsCache: Cache<CacheKey, IpRuleStatus> =
        CacheBuilder.newBuilder().maximumSize(CACHE_MAX_SIZE).build()

    private val selectedCCs = mutableSetOf<String>()

    enum class IPRuleType(val id: Int) {
        IPV4(0),
        IPV6(2)
    }

    private const val KV_SEP = ":"

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

    private fun logv(msg: String) {
        Logger.v(LOG_TAG_FIREWALL, msg)
    }

    suspend fun load(): Long {
        iptree.clear()
        db.getIpRules().forEach {
            // adding as part of defensive programming, even adding these rules to cache will
            // not cause any issues, but to avoid unnecessary entries in the trie, skipping these
            // entries
            if (it.uid < 0 && it.uid != Constants.UID_EVERYBODY) {
                Logger.i(LOG_TAG_FIREWALL, "skipping ip rule for uid: ${it.uid}")
                return@forEach
            }
            val pair = it.getCustomIpAddress()
            if (pair == null) {
                Logger.w(LOG_TAG_FIREWALL, "invalid ip address for rule: ${it.ipAddress}")
                return@forEach
            }
            val ipaddr = pair.first
            val port = pair.second
            val k = normalize(ipaddr)
            val v = treeVal(it.uid, port, it.status, it.proxyId, it.proxyCC)
            if (k != null) {
                try {
                    Logger.i(LOG_TAG_FIREWALL, "iptree.add($k, $v)")
                    iptree.add(k.togs(), v)
                    if (it.proxyCC.isNotEmpty()) selectedCCs.add(it.proxyCC)
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_FIREWALL, "err iptree.add($k, $v)", e)
                }
            }
        }
        return iptree.len()
    }

    fun getAllUniqueCCs(): Set<String> {
        logv( "ip selectedCCs: $selectedCCs")
        return selectedCCs
    }

    suspend fun getRulesCountByCC(cc: String): Int {
        return db.getRulesCountByCC(cc)
    }

    fun getCustomIpsLiveData(): LiveData<Int> {
        return db.getCustomIpsLiveData()
    }

    private fun normalize(ipaddr: IPAddress?): String? {
        if (ipaddr == null) return null
        return treeKey(ipaddr.toNormalizedString())
    }

    private fun treeKey(ipstr: String?): String? {
        if (ipstr == null) return null
        // "192/8" -> 0.0.0.192/32
        // "192.0.0.0" -> 192.0.0.0/32
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
        val pair = hostAddr(ipstr)
        val ipAddr = pair.first
        return if (ipstr.contains("*")) {
            ipAddr.assignPrefixForSingleBlock()?.toCanonicalString()
        } else {
            ipAddr.toNormalizedString()
        }
    }

    private fun treeValLike(uid: Int, port: Int): Gostr? {
        return ("$uid$KV_SEP$port").togs()
    }

    private fun treeVal(uid: Int, port: Int, rule: Int, proxyId: String, proxyCC: String): Gostr? {
        return ("$uid$KV_SEP$port$KV_SEP$rule$KV_SEP$proxyId$KV_SEP$proxyCC").togs()
    }

    suspend fun removeIpRule(uid: Int, ipstr: String, port: Int) {
        Logger.i(LOG_TAG_FIREWALL, "ip rule, rmv: $ipstr for uid: $uid")
        if (ipstr.isEmpty()) {
            return
        }

        db.deleteRule(uid, ipstr, port)

        val k = treeKey(ipstr)
        if (!k.isNullOrEmpty()) iptree.escLike(k.togs(), treeValLike(uid, port))

        resultsCache.invalidateAll()
    }

    private suspend fun updateRule(ci: CustomIp) {
        Logger.i(LOG_TAG_FIREWALL, "ip rule, update: ${ci.ipAddress} for uid: ${ci.uid}; status: ${ci.status}")
        // ensure modified time is updated for ordering
        ci.modifiedDateTime = System.currentTimeMillis()
        db.update(ci)
        val k = treeKey(ci.ipAddress)
        if (!k.isNullOrEmpty()) {
            // escape old entries and add updated rule using ci.port (not android attr)
            iptree.escLike(k.togs(), treeValLike(ci.uid, ci.port))
            iptree.add(k.togs(), treeVal(ci.uid, ci.port, ci.status, ci.proxyId, ci.proxyCC))
        }
        resultsCache.invalidateAll()
    }

    suspend fun updateBypass(c: CustomIp) {
        c.status = IpRuleStatus.BYPASS_UNIVERSAL.id
        return updateRule(c)
    }

    suspend fun updateTrust(c: CustomIp) {
        c.status = IpRuleStatus.TRUST.id
        return updateRule(c)
    }

    suspend fun updateNoRule(c: CustomIp) {
        c.status = IpRuleStatus.NONE.id
        return updateRule(c)
    }

    suspend fun updateBlock(c: CustomIp) {
        c.status = IpRuleStatus.BLOCK.id
        return updateRule(c)
    }

    suspend fun updateProxyId(c: CustomIp, proxyId: String) {
        c.proxyId = proxyId
        return updateRule(c)
    }

    suspend fun updateProxyCC(c: CustomIp, proxyCC: String) {
        c.proxyCC = proxyCC
        return updateRule(c)
    }

    fun hasRule(uid: Int, ipstr: String, port: Int): IpRuleStatus {
        val pair = hostAddr(ipstr, port)
        val ipNetPort = joinIpNetPort(normalize(pair.first) + pair.second)
        val ck = CacheKey(ipNetPort, uid)

        resultsCache.getIfPresent(ck)?.let {
            // return only if both ip and app(uid) matches
            Logger.i(LOG_TAG_FIREWALL, "match in cache $uid $ipstr: $it")
            return it
        }

        getMostSpecificRuleMatch(uid, ipstr, port).let {
            logv("ip rule for $uid $ipstr $port => ${it.name}")
            if (it != IpRuleStatus.NONE) {
                resultsCache.put(ck, it)
                return it
            }
        }
        getMostSpecificRuleMatch(uid, ipstr).let {
            logv("ip rule for $uid $ipstr => ${it.name} ??")
            if (it != IpRuleStatus.NONE) {
                resultsCache.put(ck, it)
                return it
            }
        }
        getMostSpecificRouteMatch(uid, ipstr, port).let {
            logv("route rule for $uid $ipstr $port => ${it.name} ??")
            if (it != IpRuleStatus.NONE) {
                resultsCache.put(ck, it)
                return it
            }
        }
        getMostSpecificRouteMatch(uid, ipstr).let {
            logv("route rule for $uid $ipstr => ${it.name} ??")
            if (it != IpRuleStatus.NONE) {
                resultsCache.put(ck, it)
                return it
            }
        }

        Logger.i(LOG_TAG_FIREWALL, "hasRule? NO $uid, $ipstr, $port")
        resultsCache.put(ck, IpRuleStatus.NONE)
        return IpRuleStatus.NONE
    }


    fun hasProxy(uid: Int, ipstr: String, port: Int): Pair<String, String> {
        getMostSpecificMatchProxies(uid, ipstr, port).let {
            logv("proxy for $uid $ipstr $port => ${it.first}, ${it.second}")
            if (it.first.isNotEmpty() && it.second.isNotEmpty()) {
                return it
            }
        }
        getMostSpecificMatchProxies(uid, ipstr).let {
            logv("proxy for $uid $ipstr => ${it.first}, ${it.second}")
            if (it.first.isNotEmpty() && it.second.isNotEmpty()) {
                return it
            }
        }
        getMostSpecificRouteProxies(uid, ipstr, port).let {
            logv("route rule for $uid $ipstr $port => ${it.first}, ${it.second}")
            if (it.first.isNotEmpty() && it.second.isNotEmpty()) {
                return it
            }
        }
        getMostSpecificRouteProxies(uid, ipstr).let {
            logv("route rule for $uid $ipstr => ${it.first}, ${it.second}")
            if (it.first.isNotEmpty() && it.second.isNotEmpty()) {
                return it
            }
        }

        Logger.i(LOG_TAG_FIREWALL, "hasProxy? NO $uid, $ipstr, $port")
        return Pair("","")
    }

    private fun hostAddr(ipstr: String, p: Int? = null): Pair<IPAddress, Int> {
        try {
            val ip: IPAddress? = IPAddressString(ipstr).address
            val port: Int = p ?: 0
            if (ip == null) {
                Logger.w(LOG_TAG_FIREWALL, "Invalid IP address; ip:port $ipstr:$port")
                return Pair(IPAddressString("0.0.0.0").address, 0)
            }
            return Pair(ip, port)
        } catch (e: Exception) { // AddressStringException, IncompatibleAddressException
            Logger.w(LOG_TAG_FIREWALL, "Invalid IP address; ip:port $ipstr:$p", e)
            return Pair(IPAddressString("0.0.0.0").address, 0)
        }
    }

    data class TreeVal(
        val uid: Int,
        val port: Int,
        val status: IpRuleStatus,
        val proxyId: String,
        val proxyCC: String
    )

    fun getMostSpecificRuleMatch(uid: Int, ipstr: String, port: Int = 0): IpRuleStatus {
        val k = treeKey(ipstr)
        if (!k.isNullOrEmpty()) {
            val vlike = treeValLike(uid, port)
            // rules at the end of the list have higher precedence as they're more specific
            // (think: 0.0.0.0/0 vs 1.1.1.1/32)
            val x = iptree.getLike(k.togs(), vlike).tos()
            logv("getMostSpecificRuleMatch: $uid, $k, $vlike => $x")
            val treeValues = x?.split(Backend.Vsep) ?: return IpRuleStatus.NONE
            treeValues.forEach {
                val treeVal = convertStringToTreeVal(it)
                if (treeVal == null) {
                    logv("getMostSpecificRuleMatch: $uid, $k, $vlike => treeVal is null for $it")
                    return@forEach
                }
                if (treeVal.uid == uid && treeVal.port == port) {
                    logv("getMostSpecificRuleMatch: $uid, $k, $vlike($it) => status ${treeVal.status}")
                    return treeVal.status
                }
            }
        }
        return IpRuleStatus.NONE
    }

    private fun convertStringToTreeVal(s: String): TreeVal? {
        val items = s.split(KV_SEP)
        if (items.size == 3) {
            // backward compatibility, return a default TreeVal
            val uid = items[0].toIntOrNull() ?: 0
            val port = items[1].toIntOrNull() ?: 0
            val status = IpRuleStatus.getStatus(items[2].toIntOrNull())
            return TreeVal(uid, port, status, "", "")
        }
        if (items.size != 5) {
            // backward compatibility, return a default TreeVal
            return null
        }
        val uid = items[0].toIntOrNull() ?: 0
        val port = items[1].toIntOrNull() ?: 0
        val status = IpRuleStatus.getStatus(items[2].toIntOrNull())
        val proxyId = items[3]
        val proxyCC = items[4]
        return TreeVal(uid, port, status, proxyId, proxyCC)
    }

    fun getMostSpecificMatchProxies(uid: Int, ipstr: String, port: Int = 0): Pair<String, String> {
        val k = treeKey(ipstr)
        if (!k.isNullOrEmpty()) {
            val vlike = treeValLike(uid, port)
            // rules at the end of the list have higher precedence as they're more specific
            // (think: 0.0.0.0/0 vs 1.1.1.1/32)
            val x = iptree.getLike(k.togs(), vlike).tos()
            if (DEBUG) logv("getMostSpecificRuleMatch: $uid, $k, $vlike => $x")
            val treeVals = x?.split(Backend.Vsep) ?: return Pair("","")

            treeVals.forEach {
                val treeVal = convertStringToTreeVal(it)
                if (treeVal == null) {
                    logv("getMostSpecificMatchProxies: $uid, $k, $vlike => treeVal is null for $it")
                    return Pair("", "")
                }
                if (treeVal.uid == uid && treeVal.port == port) {
                    logv("getMostSpecificMatchProxies: $uid, $k, $vlike => found match for $it")
                    return Pair(treeVal.proxyId, treeVal.proxyCC)
                }
            }
        }
        return Pair("","")
    }

    private fun getMostSpecificRouteMatch(uid: Int, ipstr: String, port: Int = 0): IpRuleStatus {
        val k = treeKey(ipstr)
        if (!k.isNullOrEmpty()) {
            val vlike = treeValLike(uid, port)
            // rules at the end of the list have higher precedence as they're more specific
            // (think: 0.0.0.0/0 vs 1.1.1.1/32)
            val x = iptree.valuesLike(k.togs(), vlike).tos()
            // ex: uid: 10169, k: 142.250.67.78, vlike: 10169:443 => x: 10169:443:0
            // (10169:443:0) => (uid : port : rule[0->none, 1-> block, 2 -> trust, 3 -> bypass])
            logv("getMostSpecificRouteMatch: $uid, $k, $vlike => $x")
            val treeVals = x?.split(Backend.Vsep) ?: return IpRuleStatus.NONE
            treeVals.forEach {
                val treeVal = convertStringToTreeVal(it)
                if (treeVal == null) {
                    logv("getMostSpecificRouteMatch: $uid, $k, $vlike => treeVal is null for $it")
                    return@forEach
                }
                if (treeVal.uid == uid && treeVal.port == port) {
                    logv("getMostSpecificRouteMatch: $uid, $k, $vlike => found match for $it")
                    return treeVal.status
                }
            }
        }
        return IpRuleStatus.NONE
    }

    private fun getMostSpecificRouteProxies(uid: Int, ipstr: String, port: Int = 0): Pair<String, String> {
        val k = treeKey(ipstr)
        if (!k.isNullOrEmpty()) {
            val vlike = treeValLike(uid, port)
            // rules at the end of the list have higher precedence as they're more specific
            // (think: 0.0.0.0/0 vs 1.1.1.1/32)
            val x = iptree.valuesLike(k.togs(), vlike).tos()
            // ex: uid: 10169, k: 142.250.67.78, vlike: 10169:443 => x: 10169:443:0
            // (10169:443:0) => (uid : port : rule[0->none, 1-> block, 2 -> trust, 3 -> bypass])
            logv("getMostSpecificRouteMatch: $uid, $k, $vlike => $x")
            val treeVals = x?.split(Backend.Vsep) ?: return Pair("","")
            treeVals.forEach {
                val treeVal = convertStringToTreeVal(it)
                if (treeVal == null) {
                    logv("getMostSpecificRouteProxies: $uid, $k, $vlike => treeVal is null for $it")
                    return Pair("", "")
                }
                if (treeVal.uid == uid && treeVal.port == port) {
                    logv("getMostSpecificRouteProxies: $uid, $k, $vlike => $it")
                    return Pair(treeVal.proxyId, treeVal.proxyCC)
                }
            }
        }
        return Pair("","")
    }

    suspend fun deleteRulesByUid(uid: Int) {
        db.getRulesByUid(uid).forEach {
            val pair = it.getCustomIpAddress() ?: return@forEach
            val ipaddr = pair.first
            val port = pair.second
            val k = normalize(ipaddr)
            if (!k.isNullOrEmpty()) {
                iptree.esc(k.togs(), treeVal(it.uid, port, it.status, it.proxyId, it.proxyCC))
            }
        }
        db.deleteRulesByUid(uid)
        resultsCache.invalidateAll()
        Logger.i(LOG_TAG_FIREWALL, "deleted all ip rules for uid: $uid")
    }

    suspend fun deleteRules(list: List<CustomIp>) {
        list.forEach {
            val pair = it.getCustomIpAddress() ?: return@forEach
            val ipaddr = pair.first
            val port = pair.second
            val k = normalize(ipaddr)
            if (!k.isNullOrEmpty()) {
                iptree.esc(k.togs(), treeVal(it.uid, port, it.status, it.proxyId, it.proxyCC))
            }
        }
        db.deleteRules(list)
        resultsCache.invalidateAll()
    }

    suspend fun deleteAllAppsRules() {
        db.deleteAllAppsRules()
        iptree.clear()
        resultsCache.invalidateAll()
    }

    suspend fun getObj(uid: Int, ipAddress: String, port: Int = 0): CustomIp? {
        return db.getCustomIpDetail(uid, ipAddress, port)
    }

    suspend fun mkCustomIp(uid: Int, ipAddress: String, port: Int = UNSPECIFIED_PORT): CustomIp {
        return makeCustomIp(
            uid = uid,
            ipAddress = ipAddress,
            port = port,
            status = IpRuleStatus.NONE,
            wildcard = false,
            proxyId = "",
            proxyCC = ""
        )
    }

    private fun makeCustomIp(
        uid: Int,
        ipAddress: String,
        port: Int?,
        status: IpRuleStatus,
        wildcard: Boolean = false,
        proxyId: String,
        proxyCC: String
    ): CustomIp {
        val customIp = CustomIp()
        customIp.ipAddress = ipAddress // empty for port-only rules, always normalized
        customIp.port = port ?: UNSPECIFIED_PORT
        customIp.protocol = ""
        customIp.isActive = true
        customIp.status = status.id
        customIp.wildcard = wildcard
        customIp.proxyId = proxyId
        customIp.proxyCC = proxyCC
        customIp.modifiedDateTime = System.currentTimeMillis()

        val ipaddr = customIp.getCustomIpAddress()?.first
        if (ipaddr == null) {
            Logger.w(LOG_TAG_FIREWALL, "Invalid IP address added")
            customIp.uid = uid
            customIp.ruleType = IPRuleType.IPV4.id
            return customIp
        }

        // TODO: is this needed in database?
        customIp.ruleType =
            if (ipaddr.isIPv6) {
                IPRuleType.IPV6.id
            } else {
                IPRuleType.IPV4.id
            }
        customIp.uid = uid
        return customIp
    }

    // chances of null pointer exception while converting the string object to
    // IPAddress().address ref: https://seancfoley.github.io/IPAddress/
    private fun padAndNormalize(ipaddr: IPAddress): String {
        var ipStr: String = ipaddr.toNormalizedString()
        try {
            if (ipaddr.isIPv4) {
                ipStr = padIpv4Cidr(ipaddr.toNormalizedString())
            }
            val pair = hostAddr(ipStr)
            return normalize(pair.first) ?: ""
        } catch (e: NullPointerException) {
            Logger.e(Logger.LOG_TAG_VPN, "Invalid IP address added", e)
        }
        return "" // empty ips mean its a port-only rule
    }

    private fun padIpv4Cidr(cidr: String): String {
        // remove port number from the IP address
        // [192.x.y/24]:80
        // ip => [192.x.y/24]
        val ip = cidr.split(":")[0]
        // plaincidr => 192.x.y/24
        val hasbraces = ip.contains("[") and ip.contains("]")
        val plaincidr = ip.replace("[", "").replace("]", "")
        // parts => [192.x.y, 24]
        val parts = plaincidr.split("/")
        // ipparts => [192, x, y]
        val ipParts = parts[0].split(".").toMutableList()
        if (ipParts.size == 4) {
            return cidr
        }
        // Pad the IP address with zeros if not fully specified
        while (ipParts.size < 4) {
            // ipparts => [192, x, y, *]
            ipParts.add("*")
        }
        // Remove the last part of the IP address if it is 0
        // 192.x.y.0 => 192.x.y.*; 192.x.0.* => 192.x.*.*
        for (i in (ipParts.size - 1) downTo 0) {
            if (ipParts[i] == "*") {
                continue
            } else if (ipParts[i] == "0") {
                ipParts[i] = "*"
            } else {
                break
            }
        }
        // Reassemble the IP address; paddedIp => 192.x.y.*
        val paddedIp = ipParts.joinToString(".")
        // Reassemble the CIDR string
        if (parts.size == 1) return paddedIp
        return if (hasbraces) {
            "[$paddedIp/${parts[1]}]"
        } else {
            "$paddedIp/${parts[1]}"
        }
    }

    suspend fun addIpRule(uid: Int, ipstr: IPAddress, port: Int?, status: IpRuleStatus, proxyId: String, proxyCC: String): CustomIp {
        Logger.i(
            LOG_TAG_FIREWALL,
            "ip rule, add rule for ($uid) ip: $ipstr, $port with status: ${status.name}"
        )
        val normalizedIp = padAndNormalize(ipstr)
        val c = makeCustomIp(uid, normalizedIp, port, status, wildcard = false, proxyId, proxyCC)
        db.insert(c)
        val k = treeKey(normalizedIp)
        if (!k.isNullOrEmpty()) {
            iptree.escLike(k.togs(), treeValLike(uid, port ?: 0))
            iptree.add(k.togs(), treeVal(uid, port ?: 0, status.id, proxyId, proxyCC))
            Logger.d(LOG_TAG_FIREWALL, "iptree.add($k, ${treeVal(uid, port ?: 0, status.id, proxyId, proxyCC)})")
        }
        resultsCache.invalidateAll()
        return c
    }

    suspend fun updateUids(uids: List<Int>, newUids: List<Int>) {
        val ips = db.getIpRules()
        for (i in uids.indices) {
            val u = uids[i]
            val n = newUids[i]
            if (ips.any { it.uid == u }) {
                db.updateUid(u, n)
            }
        }
        resultsCache.invalidateAll()
        load()
        Logger.i(LOG_TAG_FIREWALL, "ip rules updated")
    }

    suspend fun updateUid(oldUid: Int, newUid: Int) {
        db.updateUid(oldUid, newUid)
        resultsCache.invalidateAll()
        load()
        Logger.i(LOG_TAG_FIREWALL, "ip rules updated for $oldUid to $newUid")
    }

    suspend fun replaceIpRule(
        prevRule: CustomIp,
        ipaddr: IPAddress,
        port: Int?,
        newStatus: IpRuleStatus,
        proxyId: String,
        proxyCC: String
    ) {
        val pair = prevRule.getCustomIpAddress()
        if (pair == null) {
            Logger.e(LOG_TAG_FIREWALL, "invalid IP address on replaceIpRule ${prevRule.ipAddress}, ${prevRule.port}")
            return
        }

        val prevIpaddr = pair.first
        val prevPort = pair.second
        val prevIpAddrStr = normalize(prevIpaddr)
        val newIpAddrStr = padAndNormalize(ipaddr)
        Logger.i(
            LOG_TAG_FIREWALL,
            "ip rule, replace (${prevRule.uid}); ${prevIpAddrStr}:${prevPort}; new: $ipaddr:$port, ${newStatus.name}"
        )
        if (prevIpAddrStr != null) { // prev addr should never be null
            val isDeleted = db.deleteRule(prevRule.uid, prevIpAddrStr, prevRule.port)
            if (isDeleted == 0) {
                // delete didn't occur with normalized addr, use ip from prevRule obj
                db.deleteRule(prevRule.uid, prevRule.ipAddress, prevRule.port)
            }
        }
        val newRule = makeCustomIp(prevRule.uid, newIpAddrStr, port, newStatus, wildcard = false, proxyId, proxyCC)
        db.insert(newRule)
        val pk = treeKey(prevIpAddrStr)
        if (!pk.isNullOrEmpty()) {
            iptree.escLike(pk.togs(), treeValLike(prevRule.uid, prevRule.port))
        }
        val nk = treeKey(newIpAddrStr)
        if (!nk.isNullOrEmpty()) {
            iptree.escLike(nk.togs(), treeValLike(newRule.uid, port ?: 0))
            iptree.add(nk.togs(), treeVal(newRule.uid, port ?: 0, newStatus.id, proxyId, proxyCC))
        }
        resultsCache.invalidateAll()
    }

    // translated from go, net.SplitHostPort()
    class AddrError(val err: String, val addr: String) : Exception()

    fun addrErr(addr: String, why: String): Triple<String, String, Exception?> {
        return Triple("", "", AddrError(why, addr))
    }

    fun splitHostPort(hostport: String): Triple<String, String, Exception?> {
        val missingPort = "missing port in address"
        val tooManyColons = "too many colons in address"

        var host = ""
        var port = ""
        val err: Exception? = null
        var j = 0
        var k = 0

        // The port starts after the last colon.
        val i = hostport.lastIndexOf(':')
        if (i < 0) {
            return addrErr(hostport, missingPort)
        }

        if (hostport[0] == '[') {
            // Expect the first ']' just before the last ':'.
            val end = hostport.indexOf(']')
            if (end < 0) {
                return addrErr(hostport, "missing ']' in address")
            }
            when (end + 1) {
                hostport.length -> {
                    // There can't be a ':' behind the ']' now.
                    return addrErr(hostport, missingPort)
                }
                i -> {
                    // The expected result.
                }
                else -> {
                    // Either ']' isn't followed by a colon, or it is
                    // followed by a colon that is not the last one.
                    if (hostport[end + 1] == ':') {
                        return addrErr(hostport, tooManyColons)
                    }
                    return addrErr(hostport, missingPort)
                }
            }
            host = hostport.substring(1, end)
            j = 1
            k = end + 1 // there can't be a '[' resp. ']' before these positions
        } else {
            host = hostport.substring(0, i)
            if (host.contains(':')) {
                return addrErr(hostport, tooManyColons)
            }
        }
        if (hostport.substring(j).contains('[')) {
            return addrErr(hostport, "unexpected '[' in address")
        }
        if (hostport.substring(k).contains(']')) {
            return addrErr(hostport, "unexpected ']' in address")
        }

        port = hostport.substring(i + 1)
        return Triple(host, port, err)
    }

    fun getIpNetPort(inp: String): Pair<IPAddress?, Int> {
        val h = splitHostPort(inp)
        var ipNet: IPAddress? = null
        var port = 0
        if (h.first.isEmpty()) {
            try {
                val ips = IPAddressString(inp)
                ips.validate()
                ipNet = ips.address
            } catch (e: Exception) {
                Logger.w(LOG_TAG_FIREWALL, "err: getIpNetPort, ${e.message}", e)
            }
        } else {
            try {
                ipNet = IPAddressString(h.first).address
                port = h.second.toIntOrNull() ?: 0
            } catch (e: Exception) {
                Logger.w(LOG_TAG_FIREWALL, "err: getIpNetPort, ${e.message}", e)
            }
        }
        return Pair(ipNet, port)
    }

    suspend fun tombstoneRulesByUid(oldUid: Int) {
        Logger.i(LOG_TAG_FIREWALL, "tombstone rules for uid: $oldUid")
        // here tombstone means negating the uid of the rule
        // this is used when the app is uninstalled, so that the rules are not deleted
        // but the uid is set to (-1 * uid), so that the rules are not applied
        val newUid = if (oldUid > 0) -1 * oldUid else oldUid
        if (newUid == oldUid) {
            Logger.w(LOG_TAG_FIREWALL, "tombstone: same uids, old: $oldUid, new: $newUid, no-op")
            return
        }
        db.tombstoneRulesByUid(oldUid, newUid)
        resultsCache.invalidateAll()
        load()
    }

    fun joinIpNetPort(ipNet: String, port: Int = 0): String {
        return if (ipNet.contains(":") || ipNet.contains("/")) {
            "[$ipNet]:$port"
        } else {
            "$ipNet:$port"
        }
    }

    suspend fun stats(): String {
        val sb = StringBuilder()
        sb.append("   iptree len: ${iptree.len()}\n")
        sb.append("   db len: ${db.getRulesCount()}\n")
        sb.append("   cache len: ${resultsCache.size()}\n")

        return sb.toString()
    }
}
