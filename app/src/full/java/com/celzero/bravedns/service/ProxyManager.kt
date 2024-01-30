/*
 * Copyright 2023 RethinkDNS and its authors
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

import android.util.Log
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.ProxyAppMappingRepository
import com.celzero.bravedns.database.ProxyApplicationMapping
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_PROXY
import ipn.Ipn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.CopyOnWriteArraySet

object ProxyManager : KoinComponent {

    private val db: ProxyAppMappingRepository by inject()
    private val appConfig: AppConfig by inject()

    const val ID_ORBOT_BASE = "ORBOT"
    const val ID_WG_BASE = "wg"
    const val ID_TCP_BASE = "TCP"
    const val ID_S5_BASE = "S5"
    const val ID_HTTP_BASE = "HTTP"
    const val ID_NONE = "SYSTEM"

    const val TCP_PROXY_NAME = "Rethink-Proxy"
    const val ORBOT_PROXY_NAME = "Orbot"

    // TODO: consider adding other proxy modes (e.g, Wireguard, Rethink, etc.)
    enum class ProxyMode(val value: Int) {
        SOCKS5(0),
        HTTP(1),
        ORBOT_SOCKS5(2),
        ORBOT_HTTP(3);

        companion object {
            fun get(v: Int?): ProxyMode? {
                if (v == null) return null
                return when (v) {
                    SOCKS5.value -> SOCKS5
                    HTTP.value -> HTTP
                    ORBOT_SOCKS5.value -> ORBOT_SOCKS5
                    ORBOT_HTTP.value -> ORBOT_HTTP
                    else -> null
                }
            }
        }

        fun isCustomSocks5(): Boolean {
            return this == SOCKS5
        }

        fun isCustomHttp(): Boolean {
            return this == HTTP
        }

        fun isOrbotSocks5(): Boolean {
            return this == ORBOT_SOCKS5
        }

        fun isOrbotHttp(): Boolean {
            return this == ORBOT_HTTP
        }
    }

    init {
        io { WireguardManager.load() }
    }

    private var proxyMapCache = CopyOnWriteArraySet<ProxyApplicationMapping>()

    suspend fun load() {
        proxyMapCache = CopyOnWriteArraySet(db.getApps())
    }

    fun getProxyIdForApp(uid: Int): String {
        val m = proxyMapCache.find { it.uid == uid }
        return m?.proxyId ?: ID_NONE
    }

    suspend fun updateProxyIdForApp(uid: Int, proxyId: String, proxyName: String) {
        val m = proxyMapCache.filter { it.uid == uid }
        if (!isValidProxyPrefix(proxyId)) {
            Log.e(LOG_TAG_PROXY, "Invalid config id: $proxyId")
            return
        }

        if (m.isNotEmpty()) {
            m.forEach {
                if (DEBUG) Log.d(LOG_TAG_PROXY, "add $proxyId for ${it.packageName} / uid $uid")
                it.proxyId = proxyId
                it.proxyName = proxyName
            }
            db.updateProxyIdForApp(uid, proxyId, proxyName)
        } else {
            Log.e(LOG_TAG_PROXY, "updateProxyIdForApp: map not found for uid $uid")
        }
    }

    fun isProxyActive(proxyId: String): Boolean {
        return if (proxyId.contains(ID_NONE)) {
            false
        } else if (proxyId.contains(ID_ORBOT_BASE)) {
            appConfig.isOrbotProxyEnabled()
        } else if (proxyId.contains(ID_WG_BASE)) {
            WireguardManager.isConfigActive(proxyId)
        } else if (proxyId.contains(ID_TCP_BASE)) {
            TcpProxyHelper.isTcpProxyEnabled()
        } else if (proxyId.contains(ID_S5_BASE)) {
            appConfig.isCustomSocks5Enabled()
        } else if (proxyId.contains(ID_HTTP_BASE)) {
            appConfig.isCustomHttpProxyEnabled()
        } else {
            false
        }
    }

    fun getProxyMapping(): MutableSet<FirewallManager.AppInfoTuple> {
        return proxyMapCache
            .map { FirewallManager.AppInfoTuple(it.uid, it.packageName) }
            .toMutableSet()
    }

    suspend fun setProxyIdForAllApps(proxyId: String, proxyName: String) {
        // ID_NONE or empty proxy-id is not allowed; see removeProxyForAllApps()
        if (!isValidProxyPrefix(proxyId)) {
            Log.e(LOG_TAG_PROXY, "Invalid proxy id: $proxyId")
            return
        }
        proxyMapCache.forEach { it.proxyId = proxyId }
        db.updateProxyForAllApps(proxyId, proxyName)
        Log.i(LOG_TAG_PROXY, "added all apps to proxy: $proxyId")
    }

    suspend fun setProxyIdForUnselectedApps(proxyId: String, proxyName: String) {
        // ID_NONE or empty proxy-id is not allowed
        if (!isValidProxyPrefix(proxyId)) {
            Log.e(LOG_TAG_PROXY, "Invalid proxy id: $proxyId")
            return
        }
        proxyMapCache.filter { it.proxyId == "" }.forEach { it.proxyId = proxyId }
        db.updateProxyForUnselectedApps(proxyId, proxyName)
        Log.i(LOG_TAG_PROXY, "added unselected apps to interface: $proxyId")
    }

    suspend fun removeProxyIdForApp(uid: Int) {
        val m = proxyMapCache.filter { it.uid == uid }
        if (m.isNotEmpty()) {
            m.forEach { it.proxyId = "" }
            // update the id as empty string to remove the proxy
            db.updateProxyIdForApp(uid, "", "")
        } else {
            Log.e(LOG_TAG_PROXY, "app config mapping is null for uid $uid on removeProxyIdForApp")
        }
    }

    suspend fun removeProxyForAllApps() {
        Log.i(LOG_TAG_PROXY, "Removing all apps from proxy")
        proxyMapCache.forEach { it.proxyId = "" }
        db.updateProxyForAllApps("", "")
    }

    suspend fun removeProxyForAllApps(proxyId: String) {
        Log.i(LOG_TAG_PROXY, "Removing all apps from proxy with id: $proxyId")
        proxyMapCache.filter { it.proxyId == proxyId }.forEach { it.proxyId = "" }
        db.removeAllAppsForProxy(proxyId)
    }

    suspend fun addNewApp(appInfo: AppInfo?, proxyId: String = "", proxyName: String = "") {
        if (appInfo == null) return
        val pam =
            ProxyApplicationMapping(
                appInfo.uid,
                appInfo.packageName,
                appInfo.appName,
                proxyId,
                true,
                proxyName
            )
        db.insert(pam)
        proxyMapCache.add(pam)
    }

    suspend fun deleteApp(appInfo: AppInfo) {
        val pam =
            ProxyApplicationMapping(
                appInfo.uid,
                appInfo.packageName,
                appInfo.appName,
                "",
                false,
                ""
            )
        db.delete(pam)
        deleteFromCache(pam)
        if (DEBUG) Log.d(LOG_TAG_PROXY, "Deleting app for proxy: ${pam.appName}, ${pam.uid}")
    }

    private fun deleteFromCache(pam: ProxyApplicationMapping) {
        proxyMapCache.forEach() {
            if (it.uid == pam.uid && it.packageName == pam.packageName) {
                proxyMapCache.remove(it)
            }
        }
    }

    suspend fun deleteApp(appInfoTuple: FirewallManager.AppInfoTuple) {
        val pam =
            ProxyApplicationMapping(appInfoTuple.uid, appInfoTuple.packageName, "", "", false, "")
        db.delete(pam)
        deleteFromCache(pam)
        if (DEBUG) Log.d(LOG_TAG_PROXY, "Deleting app for mapping: ${pam.appName}, ${pam.uid}")
    }

    suspend fun clear() {
        db.deleteAll()
        proxyMapCache.clear()
    }

    fun isAnyAppSelected(proxyId: String): Boolean {
        return proxyMapCache.any { it.proxyId == proxyId }
    }

    private fun isValidProxyPrefix(pid: String): Boolean {
        if (pid == ID_NONE || pid == "") return false
        return pid.startsWith(ID_ORBOT_BASE) ||
            pid.startsWith(ID_WG_BASE) ||
            pid.startsWith(ID_TCP_BASE) ||
            pid.startsWith(ID_S5_BASE) ||
            pid.startsWith(ID_HTTP_BASE)
    }

    fun getAppCountForProxy(proxyId: String): Int {
        return proxyMapCache.count { it.proxyId == proxyId }
    }

    suspend fun removeWgProxies() {
        // remove all the wg proxies from the app config mappings, during restore process
        db.removeAllWgProxies()
        proxyMapCache.filter { it.proxyId.startsWith(ID_WG_BASE) }.forEach { it.proxyId = "" }
    }

    fun isIpnProxy(ipnProxyId: String): Boolean {
        // if id is not Ipn.Base, Ipn.Block, Ipn.Exit then it is proxied
        return ipnProxyId != Ipn.Base && ipnProxyId != Ipn.Block && ipnProxyId != Ipn.Exit
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }
}
