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
import com.celzero.bravedns.database.AppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Manages app statistics for firewall.
 * Extracted from BraveVPNService for better separation of concerns.
 */
class AppsStatsManager(
    private val firewallManager: FirewallManager,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "AppsStats"
    }

    private val _appsStats = MutableStateFlow(AppsStats())
    val appsStats: StateFlow<AppsStats> = _appsStats.asStateFlow()

    data class AppsStats(
        val totalApps: Int = 0,
        val allowedApps: Int = 0,
        val blockedApps: Int = 0,
        val bypassedApps: Int = 0,
        val isolatedApps: Int = 0,
        val excludedApps: Int = 0
    )

    init {
        observeAppChanges()
    }

    private fun observeAppChanges() {
        firewallManager.getApplistObserver().asFlow()
            .onEach { appList ->
                updateStats(appList)
            }
            .launchIn(scope)
    }

    private fun updateStats(appList: Collection<AppInfo>) {
        val blockedCount = appList.count { appInfo -> 
            appInfo.connectionStatus != FirewallManager.ConnectionStatus.ALLOW.id 
        }
        val bypassCount = appList.count { appInfo -> 
            appInfo.firewallStatus == FirewallManager.FirewallStatus.BYPASS_UNIVERSAL.id || 
            appInfo.firewallStatus == FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL.id 
        }
        val excludedCount = appList.count { appInfo -> 
            appInfo.firewallStatus == FirewallManager.FirewallStatus.EXCLUDE.id 
        }
        val isolatedCount = appList.count { appInfo -> 
            appInfo.firewallStatus == FirewallManager.FirewallStatus.ISOLATE.id 
        }
        val totalApps = appList.size
        val allowedApps = totalApps - (blockedCount + bypassCount + excludedCount + isolatedCount)

        _appsStats.update { state ->
            state.copy(
                totalApps = totalApps,
                allowedApps = allowedApps,
                blockedApps = blockedCount,
                bypassedApps = bypassCount,
                excludedApps = excludedCount,
                isolatedApps = isolatedCount
            )
        }

        Logger.d(LOG_TAG_VPN, "$TAG Updated: total=$totalApps, allowed=$allowedApps, blocked=$blockedCount")
    }

    suspend fun refreshStats() {
        // Stats are updated automatically via observer, no manual refresh needed
        Logger.d(LOG_TAG_VPN, "$TAG Stats refresh via observer")
    }

    fun getStatsSnapshot(): AppsStats {
        return _appsStats.value
    }
}
