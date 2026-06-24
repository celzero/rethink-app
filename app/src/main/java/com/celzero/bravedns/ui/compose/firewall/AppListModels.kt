/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.compose.firewall

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.celzero.bravedns.R
import com.celzero.bravedns.service.FirewallManager.ConnectionStatus
import com.celzero.bravedns.service.FirewallManager.FirewallStatus

enum class BlockType {
    UNMETER,
    METER,
    BYPASS,
    LOCKDOWN,
    EXCLUDE,
    BYPASS_DNS_FIREWALL
}

enum class TopLevelFilter(val id: Int) {
    ALL(0),
    INSTALLED(1),
    SYSTEM(2);

    @Composable
    fun getLabel(): String {
        return when (this) {
            ALL -> ""
            INSTALLED -> stringResource(id = R.string.fapps_filter_parent_installed)
            SYSTEM -> stringResource(id = R.string.fapps_filter_parent_system)
        }
    }
}

@Suppress("MagicNumber")
enum class FirewallFilter(val id: Int) {
    ALL(0),
    ALLOWED(1),
    BLOCKED(2),
    BLOCKED_WIFI(3),
    BLOCKED_MOBILE_DATA(4),
    BYPASS(5),
    EXCLUDED(6),
    LOCKDOWN(7);

    fun getFilter(): Set<Int> {
        return when (this) {
            ALL -> FirewallStatus.values().map { it.id }.toSet() + setOf(0, 1)
            ALLOWED -> setOf(FirewallStatus.NONE.id)
            BLOCKED_WIFI -> setOf(FirewallStatus.NONE.id)
            BLOCKED_MOBILE_DATA -> setOf(FirewallStatus.NONE.id)
            BLOCKED -> setOf(FirewallStatus.NONE.id)
            BYPASS -> setOf(FirewallStatus.BYPASS_UNIVERSAL.id, FirewallStatus.BYPASS_DNS_FIREWALL.id)
            EXCLUDED -> setOf(FirewallStatus.EXCLUDE.id)
            LOCKDOWN -> setOf(FirewallStatus.ISOLATE.id)
        }
    }

    fun getConnectionStatusFilter(): Set<Int> {
        return when (this) {
            ALL -> ConnectionStatus.values().map { it.id }.toSet()
            ALLOWED -> setOf(ConnectionStatus.ALLOW.id)
            BLOCKED_WIFI -> setOf(ConnectionStatus.UNMETERED.id)
            BLOCKED_MOBILE_DATA -> setOf(ConnectionStatus.METERED.id)
            BLOCKED ->
                setOf(
                    ConnectionStatus.UNMETERED.id,
                    ConnectionStatus.METERED.id,
                    ConnectionStatus.BOTH.id
                )
            BYPASS -> ConnectionStatus.values().map { it.id }.toSet()
            EXCLUDED -> ConnectionStatus.values().map { it.id }.toSet()
            LOCKDOWN -> ConnectionStatus.values().map { it.id }.toSet()
        }
    }

    @Composable
    fun getLabel(): String {
        return when (this) {
            ALL -> stringResource(id = R.string.lbl_all)
            ALLOWED -> stringResource(id = R.string.lbl_allowed)
            BLOCKED_WIFI ->
                stringResource(
                    R.string.two_argument_colon,
                    stringResource(id = R.string.lbl_blocked),
                    stringResource(id = R.string.firewall_rule_block_unmetered)
                )
            BLOCKED_MOBILE_DATA ->
                stringResource(
                    R.string.two_argument_colon,
                    stringResource(id = R.string.lbl_blocked),
                    stringResource(id = R.string.firewall_rule_block_metered)
                )
            BLOCKED -> stringResource(id = R.string.lbl_blocked)
            BYPASS -> stringResource(id = R.string.fapps_firewall_filter_bypass_universal)
            EXCLUDED -> stringResource(id = R.string.fapps_firewall_filter_excluded)
            LOCKDOWN -> stringResource(id = R.string.fapps_firewall_filter_isolate)
        }
    }

    companion object {
        fun filter(id: Int): FirewallFilter {
            return when (id) {
                ALL.id -> ALL
                ALLOWED.id -> ALLOWED
                BLOCKED_WIFI.id -> BLOCKED_WIFI
                BLOCKED_MOBILE_DATA.id -> BLOCKED_MOBILE_DATA
                BLOCKED.id -> BLOCKED
                BYPASS.id -> BYPASS
                EXCLUDED.id -> EXCLUDED
                LOCKDOWN.id -> LOCKDOWN
                else -> ALL
            }
        }
    }
}

data class Filters(
    val categoryFilters: Set<String> = emptySet(),
    val topLevelFilter: TopLevelFilter = TopLevelFilter.INSTALLED,
    val firewallFilter: FirewallFilter = FirewallFilter.ALL,
    val searchString: String = ""
)
