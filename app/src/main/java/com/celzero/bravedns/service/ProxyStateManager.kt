/*
 * Copyright 2024 RethinkDNS and its authors
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
import Logger.LOG_TAG_VPN
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.util.UIUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages proxy state and status updates.
 * Extracted from BraveVPNService for better separation of concerns.
 */
class ProxyStateManager(
    private val appConfig: AppConfig,
    private val persistentState: PersistentState,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "ProxyState"

        suspend fun calculateWireguardProxyStatus(now: Long = System.currentTimeMillis()): ProxyStatus {
            val proxies = WireguardManager.getActiveConfigs()
            if (proxies.isEmpty()) {
                return ProxyStatus(isActive = true, statusText = "Active")
            }

            var active = 0
            var failing = 0
            var idle = 0

            proxies.forEach { config ->
                val proxyId = "${ProxyManager.ID_WG_BASE}${config.getId()}"
                val stats = VpnController.getProxyStats(proxyId)
                val statusPair = VpnController.getProxyStatusById(proxyId)

                when (statusPair.first) {
                    UIUtils.ProxyStatus.TPU.id -> {
                        idle++
                    }
                    UIUtils.ProxyStatus.TUP.id -> {
                        active++
                    }
                    UIUtils.ProxyStatus.TOK.id -> {
                        val lastOk = stats?.lastOK ?: 0L
                        val since = stats?.since ?: now

                        if (lastOk > 0L && (now - lastOk < WireguardManager.WG_HANDSHAKE_TIMEOUT)) {
                            active++
                        } else if (lastOk > 0L) {
                            idle++
                        } else if (now - since < WireguardManager.WG_UPTIME_THRESHOLD) {
                            active++
                        } else {
                            failing++
                        }
                    }
                    else -> {
                        val lastOk = stats?.lastOK ?: 0L
                        val since = stats?.since ?: now

                        if (lastOk > 0L || now - since < WireguardManager.WG_UPTIME_THRESHOLD) {
                            idle++
                        } else {
                            failing++
                        }
                    }
                }
            }

            return ProxyStatus(
                isActive = active > 0,
                statusText = buildStatusText(active, failing, idle),
                activeCount = active,
                failingCount = failing,
                idleCount = idle
            )
        }

        private fun buildStatusText(active: Int, failing: Int, idle: Int): String {
            val parts = mutableListOf<String>()
            if (active > 0) parts.add("$active Active")
            if (failing > 0) parts.add("$failing Failing")
            if (idle > 0) parts.add("$idle Idle")
            return if (parts.isEmpty()) "Inactive" else parts.joinToString("\n")
        }
    }

    private val _proxyStatus = MutableStateFlow(ProxyStatus())
    val proxyStatus: StateFlow<ProxyStatus> = _proxyStatus.asStateFlow()

    private val _activeProxiesCount = MutableStateFlow(0)
    val activeProxiesCount: StateFlow<Int> = _activeProxiesCount.asStateFlow()

    private val _failingProxiesCount = MutableStateFlow(0)
    val failingProxiesCount: StateFlow<Int> = _failingProxiesCount.asStateFlow()

    data class ProxyStatus(
        val isActive: Boolean = false,
        val statusText: String = "Inactive",
        val activeCount: Int = 0,
        val failingCount: Int = 0,
        val idleCount: Int = 0
    )

    suspend fun updateProxyStatus() {
        withContext(Dispatchers.IO) {
            if (!persistentState.getVpnEnabled()) {
                _proxyStatus.value = ProxyStatus(statusText = "Inactive")
                return@withContext
            }

            val proxyType = AppConfig.ProxyType.of(appConfig.getProxyType())

            if (!proxyType.isProxyTypeWireguard()) {
                val isEnabled = appConfig.isProxyEnabled()
                _proxyStatus.value = ProxyStatus(
                    isActive = isEnabled,
                    statusText = if (isEnabled) "Active" else "Inactive"
                )
                return@withContext
            }

            val wgStatus = calculateWireguardProxyStatus()
            _proxyStatus.value = wgStatus
            _activeProxiesCount.value = wgStatus.activeCount
            _failingProxiesCount.value = wgStatus.failingCount
        }
    }

    fun isProxyEnabled(): Boolean {
        return appConfig.isProxyEnabled()
    }

    fun getProxyType(): AppConfig.ProxyType {
        return AppConfig.ProxyType.of(appConfig.getProxyType())
    }
}
