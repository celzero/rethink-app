/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.rethinkdns.retrixed.util

import Logger.LOG_TAG_UI
import com.rethinkdns.retrixed.service.PersistentState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object NewSettingsManager: KoinComponent {

    private val persistentState: PersistentState by inject<PersistentState>()

    private const val EXPIRY_DAYS = 7
    // wireguard
    const val WG_HOP_SETTING = "hop"
    const val WG_MOBILE_SETTING = "wg_mobile"
    const val RANDOMIZE_WG_PORT = "randomize_wg_port"
    const val ENDPOINT_INDEPENDENT = "endpoint_independent_mapping"

    // tcp
    const val TCP_KEEP_ALIVE = "tcp_keep_alive"
    const val TCP_IDLE_TIMEOUT = "tcp_idle_timeout"

    // general
    const val PROVIDER_INFO = "provider_info"
    const val APP_LOGS = "app_logs"
    const val AUTOMATION = "automation"

    // dns
    const val SMART_DNS = "smart_dns"
    const val TREAT_DNS_FIREWALL = "treat_dns_as_firewall"
    const val SPLIT_DNS = "split_dns"
    const val USE_SYS_DNS_UNDELEGATED = "use_sys_dns_undelegated"
    const val USE_FALLBACK_TO_BYPASS = "use_fallback_to_bypass"

    // network
    const val LOOP_BACK_PROXY_FORWARDER = "loop_back_proxy_forwarder"
    const val MARK_MOBILE_METERED = "mark_mobile_metered"
    const val DO_NOT_STALL = "do_not_stall"
    const val PERFORM_CONNECTION_CHECK = "perform_connection_check"
    const val TUN_NETWORK_POLICY = "tun_network_handling_policy"

    const val ANTI_CENSORSHIP = "anti_censorship"

    private val newSettingsList = listOf(
        WG_HOP_SETTING,
        WG_MOBILE_SETTING,
        RANDOMIZE_WG_PORT,
        ENDPOINT_INDEPENDENT,
        TCP_KEEP_ALIVE,
        TCP_IDLE_TIMEOUT,
        PROVIDER_INFO,
        APP_LOGS,
        AUTOMATION,
        SMART_DNS,
        TREAT_DNS_FIREWALL,
        SPLIT_DNS,
        USE_SYS_DNS_UNDELEGATED,
        LOOP_BACK_PROXY_FORWARDER,
        MARK_MOBILE_METERED,
        DO_NOT_STALL,
        PERFORM_CONNECTION_CHECK,
        ANTI_CENSORSHIP,
        TUN_NETWORK_POLICY
    )

    init {
        handleNewSettings()
    }


    fun shouldShowBadge(key: String): Boolean {
        val newSettings = persistentState.newSettings.split(",").filter { it.isNotEmpty() }.toSet()
        val seenSettings =
            persistentState.newSettingsSeen.split(",").filter { it.isNotEmpty() }.toSet()
        val contains = newSettings.contains(key)
        val seen = seenSettings.contains(key)
        val show = contains && !seen
        return show
    }

    fun markSettingSeen(key: String) {
        val seenSettings =
            persistentState.newSettingsSeen.split(",").filter { it.isNotEmpty() }.toMutableSet()
        if (seenSettings.contains(key)) {
            return
        }
        seenSettings.add(key)
        saveSeenSettings(seenSettings)
    }

    fun initializeNewSettings() {
        persistentState.newSettings = newSettingsList.joinToString(",")
        persistentState.newSettingsSeen = ""
    }

    private fun isExpired(): Boolean {
        val appUpdatedTs = persistentState.appUpdateTimeTs
        val now = System.currentTimeMillis()
        val diff = now - appUpdatedTs
        if (appUpdatedTs == 0L) {
            // if app update time is not set, consider it as expired
            return true
        }
        return diff >= EXPIRY_DAYS * 24 * 60 * 60 * 1000L
    }

    private fun saveSeenSettings(set: Set<String>) {
        persistentState.newSettingsSeen = set.joinToString(",")
        // remove the setting from new settings if it exists
        val newSettings =
            persistentState.newSettings.split(",").filter { it.isNotEmpty() }.toMutableSet()
        newSettings.removeAll(set)
        persistentState.newSettings = newSettings.joinToString(",")
    }

    fun clearAll() {
        persistentState.newSettings = ""
        persistentState.newSettingsSeen = ""
        Logger.v(LOG_TAG_UI, "NewSettingsManager: cleared all new settings")
    }

    fun handleNewSettings() {
        if (isExpired()) {
            // reset all new settings
            clearAll()
            Logger.v(LOG_TAG_UI, "NewSettingsManager: new settings expired, resetting all settings")
        }
    }
}
