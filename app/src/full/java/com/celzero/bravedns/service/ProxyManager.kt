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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object ProxyManager : KoinComponent {

    private val proxyAppMappingRepository: ProxyAppMappingRepository by inject()
    private val appConfig: AppConfig by inject()
    private val mutex: Mutex = Mutex()

    const val ID_ORBOT_BASE = "ORBOT"
    const val ID_WG_BASE = "wg"
    const val ID_TCP_BASE = "TCP"
    const val ID_S5_BASE = "S5"
    const val ID_HTTP_BASE = "HTTP"
    const val ID_SYSTEM = "SYSTEM"

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

    private var appConfigMappings = mutableSetOf<ProxyApplicationMapping>()

    suspend fun load() {
        WireguardManager.load()
        mutex.withLock { appConfigMappings = proxyAppMappingRepository.getApps().toMutableSet() }
    }

    suspend fun getProxyIdForApp(uid: Int): String {
        mutex.withLock {
            val appConfigMapping = appConfigMappings.find { it.uid == uid }
            return appConfigMapping?.proxyId ?: ID_SYSTEM
        }
    }

    suspend fun updateProxyIdForApp(uid: Int, proxyId: String, proxyName: String) {
        mutex.withLock {
            val appConfigMapping = appConfigMappings.filter { it.uid == uid }
            if (!isValidProxyId(proxyId)) {
                Log.e(LOG_TAG_PROXY, "Invalid config id: $proxyId")
                return
            }

            if (appConfigMapping.isNotEmpty()) {
                appConfigMapping.forEach {
                    if (DEBUG)
                        Log.d(LOG_TAG_PROXY, "add $proxyId to app ${it.packageName} with uid $uid")
                    it.proxyId = proxyId
                    it.proxyName = proxyName
                }
            } else {
                Log.e(LOG_TAG_PROXY, "updateProxyIdForApp - appConfigMapping is null for uid $uid")
            }
        }
        proxyAppMappingRepository.updateProxyIdForApp(uid, proxyId, proxyName)
    }

    suspend fun isProxyActive(proxyId: String): Boolean {
        return if (proxyId.contains(ID_SYSTEM)) {
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

    suspend fun getProxyMapping(): MutableSet<FirewallManager.AppInfoTuple> {
        mutex.withLock {
            return appConfigMappings
                .map { FirewallManager.AppInfoTuple(it.uid, it.packageName) }
                .toMutableSet()
        }
    }

    suspend fun updateProxyIdForAllApps(proxyId: String, proxyName: String) {
        if (proxyId == "" || !isValidProxyId(proxyId)) {
            Log.e(LOG_TAG_PROXY, "Invalid proxy id: $proxyId")
            return
        }
        Log.i(LOG_TAG_PROXY, "Adding all apps to interface: $proxyId")
        mutex.withLock { appConfigMappings.forEach { it.proxyId = proxyId } }
        proxyAppMappingRepository.updateProxyForAllApps(proxyId, proxyName)
    }

    suspend fun removeProxyIdForApp(uid: Int) {
        mutex.withLock {
            val appConfigMapping = appConfigMappings.filter { it.uid == uid }
            if (appConfigMapping.isNotEmpty()) {
                appConfigMapping.forEach { it.proxyId = "" }
            } else {
                Log.e(LOG_TAG_PROXY, "app config mapping is null for uid $uid on removeProxyIdForApp")
            }
        }
        // update the id as empty string to remove the proxy
        proxyAppMappingRepository.updateProxyIdForApp(uid, "", "")
    }

    suspend fun removeProxyForAllApps() {
        Log.i(LOG_TAG_PROXY, "Removing all apps from proxy")
        mutex.withLock { appConfigMappings.forEach { it.proxyId = "" } }
        proxyAppMappingRepository.updateProxyForAllApps("", "")
    }

    suspend fun removeProxyForAllApps(proxyId: String) {
        Log.i(LOG_TAG_PROXY, "Removing all apps from proxy with id: $proxyId")
        mutex.withLock { appConfigMappings.filter { it.proxyId == proxyId }.forEach { it.proxyId = "" } }
        proxyAppMappingRepository.removeAllAppsForProxy(proxyId)
    }

    suspend fun addNewApp(appInfo: AppInfo, proxyId: String = "", proxyName: String = "") {
        val pam =
            ProxyApplicationMapping(
                appInfo.uid,
                appInfo.packageName,
                appInfo.appName,
                proxyId,
                true,
                proxyName
            )
        mutex.withLock { appConfigMappings.add(pam) }
        proxyAppMappingRepository.insert(pam)
    }

    suspend fun deleteApp(appInfo: AppInfo, proxyId: String = "", proxyName: String = "") {
        val pam =
            ProxyApplicationMapping(
                appInfo.uid,
                appInfo.packageName,
                appInfo.appName,
                proxyName,
                false,
                proxyId
            )
        mutex.withLock { appConfigMappings.remove(pam) }
        proxyAppMappingRepository.delete(pam)
        if (DEBUG) Log.d(LOG_TAG_PROXY, "Deleting app for mapping: ${pam.appName}, ${pam.uid}")
    }

    suspend fun deleteApp(appInfoTuple: FirewallManager.AppInfoTuple) {
        val pam =
            ProxyApplicationMapping(appInfoTuple.uid, appInfoTuple.packageName, "", "", false, "")
        mutex.withLock {
            appConfigMappings.remove(pam)
        }
        proxyAppMappingRepository.delete(pam)
        if (DEBUG) Log.d(LOG_TAG_PROXY, "Deleting app for mapping: ${pam.appName}, ${pam.uid}")
    }

    suspend fun isAnyAppSelected(proxyId: String): Boolean {
        mutex.withLock {
            return appConfigMappings.any { it.proxyId == proxyId }
        }
    }

    private fun isValidProxyId(proxyId: String): Boolean {
        return proxyId.contains(ID_ORBOT_BASE) ||
            proxyId.contains(ID_WG_BASE) ||
            proxyId.contains(ID_TCP_BASE) ||
            proxyId.contains(ID_S5_BASE) ||
            proxyId.contains(ID_HTTP_BASE)
    }

    suspend fun getAppCountForProxy(proxyId: String): Int {
        mutex.withLock {
            return appConfigMappings.count { it.proxyId == proxyId }
        }
    }

    suspend fun removeWgProxies() {
        // remove all the wg proxies from the app config mappings, during restore process
        mutex.withLock { appConfigMappings.filter { it.proxyId.contains(ID_WG_BASE) }.forEach { it.proxyId = "" } }
        proxyAppMappingRepository.removeAllWgProxies()
    }

    fun isProxied(proxyId: String): Boolean {
        if (proxyId == "") return false

        // determine whether the connection is proxied or not
        // if the connection is not Ipn.Base, Ipn.Block, Ipn.Exit then it is proxied
        return proxyId != Ipn.Base && proxyId != Ipn.Block && proxyId != Ipn.Exit
    }
}
