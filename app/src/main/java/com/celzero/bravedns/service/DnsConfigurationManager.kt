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
import android.content.Context
import android.content.SharedPreferences
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages DNS configuration and resolver updates.
 * Extracted from BraveVPNService for better separation of concerns.
 */
class DnsConfigurationManager(
    private val context: Context,
    private val persistentState: PersistentState,
    private val appConfig: AppConfig,
    private val scope: CoroutineScope
) : SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val TAG = "DnsConfig"
    }

    private val _dnsLatency = MutableStateFlow("-- ms")
    val dnsLatency: StateFlow<String> = _dnsLatency.asStateFlow()

    private val _connectedDnsName = MutableStateFlow("")
    val connectedDnsName: StateFlow<String> = _connectedDnsName.asStateFlow()

    private val _dnsStatus = MutableStateFlow<DnsStatus>(DnsStatus.Idle)
    val dnsStatus: StateFlow<DnsStatus> = _dnsStatus.asStateFlow()

    sealed class DnsStatus {
        data object Idle : DnsStatus()
        data object Connecting : DnsStatus()
        data class Connected(val name: String, val latency: Long) : DnsStatus()
        data class Error(val message: String) : DnsStatus()
    }

    init {
        persistentState.sharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    fun updateDnsLatency(latency: Long) {
        _dnsLatency.value = if (latency > 0) "${latency}ms" else "-- ms"
    }

    fun updateConnectedDnsName(name: String) {
        _connectedDnsName.value = name
    }

    suspend fun refreshResolvers() {
        withContext(Dispatchers.IO) {
            try {
                // Refresh is handled by the VPN adapter directly
                Logger.i(LOG_TAG_VPN, "$TAG Resolvers refresh requested")
            } catch (e: Exception) {
                Logger.e(LOG_TAG_VPN, "$TAG Failed to refresh resolvers: ${e.message}")
            }
        }
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        when (key) {
            PersistentState.DNS_CHANGE -> {
                scope.launch(Dispatchers.IO) {
                    handleDnsTypeChange()
                }
            }
            PersistentState.LOCAL_BLOCK_LIST -> {
                scope.launch(Dispatchers.IO) {
                    handleLocalBlocklistChange()
                }
            }
            PersistentState.REMOTE_BLOCKLIST_UPDATE -> {
                scope.launch(Dispatchers.IO) {
                    handleRemoteBlocklistChange()
                }
            }
        }
    }

    private suspend fun handleDnsTypeChange() {
        _dnsStatus.value = DnsStatus.Connecting
        
        when (appConfig.getDnsType()) {
            AppConfig.DnsType.DOH -> Logger.i(LOG_TAG_VPN, "$TAG DNS type changed to DoH")
            AppConfig.DnsType.DNSCRYPT -> Logger.i(LOG_TAG_VPN, "$TAG DNS type changed to DNSCrypt")
            AppConfig.DnsType.DNS_PROXY -> Logger.i(LOG_TAG_VPN, "$TAG DNS type changed to DNS Proxy")
            AppConfig.DnsType.RETHINK_REMOTE -> Logger.i(LOG_TAG_VPN, "$TAG DNS type changed to RDNS Remote")
            AppConfig.DnsType.SYSTEM_DNS -> Logger.i(LOG_TAG_VPN, "$TAG DNS type changed to System DNS")
            AppConfig.DnsType.SMART_DNS -> Logger.i(LOG_TAG_VPN, "$TAG DNS type changed to Smart DNS")
            AppConfig.DnsType.DOT -> Logger.i(LOG_TAG_VPN, "$TAG DNS type changed to DoT")
            AppConfig.DnsType.ODOH -> Logger.i(LOG_TAG_VPN, "$TAG DNS type changed to ODoH")
        }
    }

    private suspend fun handleLocalBlocklistChange() {
        Logger.i(LOG_TAG_VPN, "$TAG Local blocklist changed")
    }

    private suspend fun handleRemoteBlocklistChange() {
        Logger.i(LOG_TAG_VPN, "$TAG Remote blocklist changed")
    }

    fun cleanup() {
        persistentState.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
    }
}
