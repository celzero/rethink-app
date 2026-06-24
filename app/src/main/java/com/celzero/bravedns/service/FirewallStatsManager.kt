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
import androidx.lifecycle.asFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * Manages firewall statistics including rules counts.
 * Extracted from BraveVPNService for better separation of concerns.
 */
class FirewallStatsManager(
    private val persistentState: PersistentState,
    private val ipRulesManager: IpRulesManager,
    private val domainRulesManager: DomainRulesManager,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "FirewallStats"
    }

    private val _universalRulesCount = MutableStateFlow(0)
    val universalRulesCount: StateFlow<Int> = _universalRulesCount.asStateFlow()

    private val _ipRulesCount = MutableStateFlow(0)
    val ipRulesCount: StateFlow<Int> = _ipRulesCount.asStateFlow()

    private val _domainRulesCount = MutableStateFlow(0)
    val domainRulesCount: StateFlow<Int> = _domainRulesCount.asStateFlow()

    private val _firewallStats = MutableStateFlow(FirewallStats())
    val firewallStats: StateFlow<FirewallStats> = _firewallStats.asStateFlow()

    data class FirewallStats(
        val universalRules: Int = 0,
        val ipRules: Int = 0,
        val domainRules: Int = 0,
        val totalRules: Int = 0
    )

    init {
        observeRulesChanges()
    }

    private fun observeRulesChanges() {
        persistentState.universalRulesCount.asFlow()
            .onEach { count ->
                _universalRulesCount.update { count }
                updateTotalStats()
                Logger.d(LOG_TAG_VPN, "$TAG Universal rules: $count")
            }
            .launchIn(scope)

        ipRulesManager.getCustomIpsLiveData().asFlow()
            .onEach { count ->
                _ipRulesCount.update { count }
                updateTotalStats()
                Logger.d(LOG_TAG_VPN, "$TAG IP rules: $count")
            }
            .launchIn(scope)

        domainRulesManager.getUniversalCustomDomainCount().asFlow()
            .onEach { count ->
                _domainRulesCount.update { count }
                updateTotalStats()
                Logger.d(LOG_TAG_VPN, "$TAG Domain rules: $count")
            }
            .launchIn(scope)
    }

    private fun updateTotalStats() {
        _firewallStats.update {
            it.copy(
                universalRules = _universalRulesCount.value,
                ipRules = _ipRulesCount.value,
                domainRules = _domainRulesCount.value,
                totalRules = _universalRulesCount.value + _ipRulesCount.value + _domainRulesCount.value
            )
        }
    }

    fun getStatsSnapshot(): FirewallStats {
        return _firewallStats.value
    }

    fun getTotalRulesCount(): Int {
        return _firewallStats.value.totalRules
    }
}
