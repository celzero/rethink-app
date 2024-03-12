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

import com.celzero.bravedns.database.WgConfigFiles
import com.celzero.bravedns.wireguard.Config
import org.koin.core.component.KoinComponent

object WireguardManager : KoinComponent {
    const val SEC_WARP_NAME = "SEC_WARP"
    const val SEC_WARP_ID = 0
    const val SEC_WARP_FILE_NAME = "wg0.conf"
    const val WARP_NAME = "WARP"
    const val WARP_ID = 1
    const val WARP_FILE_NAME = "wg1.conf"

    const val INVALID_CONF_ID = -1

    fun load() {}

    fun disableConfig(proxyId: String) {}

    fun getSecWarpConfig(): Config? {
        return null
    }

    fun getConfigById(id: Int): Config? {
        return null
    }

    fun restoreProcessDeleteWireGuardEntries() {}

    fun getEnabledConfigs(): List<Config> {
        return emptyList()
    }

    fun getOneWireGuardProxyId(): Int? {
        return null
    }

    fun oneWireGuardEnabled(): Boolean {
        return false
    }

    fun catchAllEnabled(): Boolean {
        return false
    }

    fun getCatchAllWireGuardProxyId(): Int? {
        return null
    }

    fun canRouteIp(configId: Int?, ip: String?): Boolean {
        return false
    }

    fun getConfigIdForApp(uid: Int): WgConfigFiles? {
        return null
    }

    fun getActiveConfigs(): List<Config> {
        return emptyList()
    }

    fun disableConfig(configFiles: WgConfigFiles) {}

    fun getActiveConfigIdForApp(uid: Int): Int {
        return INVALID_CONF_ID
    }

    fun isConfigActive(configId: String): Boolean {
        return false
    }
}
