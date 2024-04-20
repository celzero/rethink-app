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
import backend.Backend
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.database.CustomIpRepository
import com.celzero.bravedns.util.Constants
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import inet.ipaddr.AddressStringException
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
        Logger.d(LOG_TAG_FIREWALL, msg)
    }

    suspend fun load(): Long {
        iptree.clear()
        db.getIpRules().forEach {
            val pair = it.getCustomIpAddress()
            val ipaddr = pair.first
            val port = pair.second
            val k = normalize(ipaddr)
            val v = treeVal(it.uid, port, it.status)
            if (!k.isNullOrEmpty()) {
                try {
                    logd("iptree.add($k, $v)")
                    iptree.add(k, v)
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_FIREWALL, "err iptree.add($k, $v)", e)
                }
            }
        }
        return iptree.len()
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
            Logger.e(LOG_TAG_FIREWALL, "err treeValStatus: ${e.message}")
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
        Logger.i(LOG_TAG_FIREWALL, "ip rule, rmv: $ipstr for uid: $uid")
        if (ipstr.isEmpty()) {
            return
        }

        db.deleteRule(uid, ipstr, port)

        val k = treeKey(ipstr)
        if (!k.isNullOrEmpty()) iptree.escLike(k, treeValLike(uid, port))

        resultsCache.invalidateAll()
    }

    private suspend fun updateRule(uid: Int, ipaddr: String, port: Int, status: IpRuleStatus) {
        Logger.i(LOG_TAG_FIREWALL, "ip rule, update: $ipaddr for uid: $uid; status: ${status.name}")
        // ipaddr is expected to be normalized
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

    suspend fun updateBlock(c: CustomIp) {
        return updateRule(c.uid, c.ipAddress, c.port, IpRuleStatus.BLOCK)
    }

    fun hasRule(uid: Int, ipstr: String, port: Int): IpRuleStatus {
        val pair = hostAddr(ipstr, port)
        val ipNetPort = joinIpNetPort(normalize(pair.first) + pair.second)
        val ck = CacheKey(ipNetPort, uid)

        resultsCache.getIfPresent(ck)?.let {
            // return only if both ip and app(uid) matches
            logd("match in cache $uid $ipstr: $it")
            return it
        }

        getMostSpecificRuleMatch(uid, ipstr, port).let {
            logd("ip rule for $uid $ipstr $port => ${it.name} ??")
            if (it != IpRuleStatus.NONE) {
                resultsCache.put(ck, it)
                return it
            }
        }
        getMostSpecificRuleMatch(uid, ipstr).let {
            logd("ip rule for $uid $ipstr => ${it.name} ??")
            if (it != IpRuleStatus.NONE) {
                resultsCache.put(ck, it)
                return it
            }
        }
        getMostSpecificRouteMatch(uid, ipstr, port).let {
            logd("route rule for $uid $ipstr $port => ${it.name} ??")
            if (it != IpRuleStatus.NONE) {
                resultsCache.put(ck, it)
                return it
            }
        }
        getMostSpecificRouteMatch(uid, ipstr).let {
            logd("route rule for $uid $ipstr => ${it.name} ??")
            if (it != IpRuleStatus.NONE) {
                resultsCache.put(ck, it)
                return it
            }
        }

        logd("hasRule? NO $uid, $ipstr, $port")
        resultsCache.put(ck, IpRuleStatus.NONE)
        return IpRuleStatus.NONE
    }

    private fun hostAddr(ipstr: String, p: Int? = null): Pair<IPAddress, Int> {
        val ip: IPAddress = IPAddressString(ipstr).address
        val port: Int = p ?: 0
        return Pair(ip, port)
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
            // ex: uid: 10169, k: 142.250.67.78, vlike: 10169:443 => x: 10169:443:0
            // (10169:443:0) => (uid : port : rule[0->none, 1-> block, 2 -> trust, 3 -> bypass])
            logd("getMostSpecificRouteMatch: $uid, $k, $vlike => $x")
            return treeValsFromCsv(x)
                .map { treeValStatus(it) }
                .lastOrNull { it != IpRuleStatus.NONE } ?: IpRuleStatus.NONE
        }
        return IpRuleStatus.NONE
    }

    suspend fun deleteRulesByUid(uid: Int) {
        db.getRulesByUid(uid).forEach {
            val pair = it.getCustomIpAddress()
            val ipaddr = pair.first
            val port = pair.second
            val k = normalize(ipaddr)
            if (!k.isNullOrEmpty()) {
                iptree.esc(k, treeVal(it.uid, port, it.status))
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
        customIp.ipAddress = ipAddress // empty for port-only rules, always normalized
        customIp.port = port ?: Constants.UNSPECIFIED_PORT
        customIp.protocol = ""
        customIp.isActive = true
        customIp.status = status.id
        customIp.wildcard = wildcard
        customIp.modifiedDateTime = System.currentTimeMillis()

        val ipaddr = customIp.getCustomIpAddress().first
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
        } catch (ignored: NullPointerException) {
            Logger.e(Logger.LOG_TAG_VPN, "Invalid IP address added", ignored)
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

    suspend fun addIpRule(uid: Int, ipstr: IPAddress, port: Int?, status: IpRuleStatus) {
        Logger.i(
            LOG_TAG_FIREWALL,
            "ip rule, add rule for ($uid) ip: $ipstr, $port with status: ${status.name}"
        )
        val normalizedIp = padAndNormalize(ipstr)
        val c = makeCustomIp(uid, normalizedIp, port, status)
        db.insert(c)
        val k = treeKey(normalizedIp)
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
        Logger.i(LOG_TAG_FIREWALL, "ip rules updated")
    }

    suspend fun replaceIpRule(
        prevRule: CustomIp,
        ipaddr: IPAddress,
        port: Int?,
        newStatus: IpRuleStatus
    ) {
        val pair = prevRule.getCustomIpAddress()
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
        val newRule = makeCustomIp(prevRule.uid, newIpAddrStr, port, newStatus)
        db.insert(newRule)
        val pk = treeKey(prevIpAddrStr)
        if (!pk.isNullOrEmpty()) {
            iptree.escLike(pk, treeValLike(prevRule.uid, prevRule.port))
        }
        val nk = treeKey(newIpAddrStr)
        if (!nk.isNullOrEmpty()) {
            iptree.escLike(nk, treeValLike(newRule.uid, port ?: 0))
            iptree.add(nk, treeVal(newRule.uid, port ?: 0, newStatus.id))
        }
        resultsCache.invalidateAll()
    }

    // translated from go, net.SplitHostPort()
    class AddrError(val err: String, val addr: String) : Exception()

    fun splitHostPort(hostport: String): Triple<String, String, Exception?> {
        val missingPort = "missing port in address"
        val tooManyColons = "too many colons in address"

        fun addrErr(addr: String, why: String): Triple<String, String, Exception?> {
            return Triple("", "", AddrError(why, addr))
        }

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
            } catch (e: AddressStringException) {
                Logger.w(LOG_TAG_FIREWALL, "err: getIpNetPort, ${e.message}", e)
            }
        } else {
            ipNet = IPAddressString(h.first).address
            port = h.second.toIntOrNull() ?: 0
        }
        return Pair(ipNet, port)
    }

    fun joinIpNetPort(ipNet: String, port: Int = 0): String {
        return if (ipNet.contains(":") || ipNet.contains("/")) {
            "[$ipNet]:$port"
        } else {
            "$ipNet:$port"
        }
    }
}
