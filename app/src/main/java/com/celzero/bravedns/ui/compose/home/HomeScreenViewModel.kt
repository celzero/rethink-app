package com.celzero.bravedns.ui.compose.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.DnsLogTracker
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyStateManager
import com.celzero.bravedns.service.WireguardManager
import com.celzero.firestack.backend.Backend

class HomeScreenViewModel(
    private val persistentState: PersistentState,
    private val appConfig: AppConfig
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeScreenUiState())
    val uiState: StateFlow<HomeScreenUiState> = _uiState.asStateFlow()

    private var proxyStateListenerJob: Job? = null
    private var dnsLatencyJob: Job? = null

    init {
        observeVpnState()
        observeDnsStates()
        observeFirewallStates()
        observeLogsCount()
        observeProxyStates()
        observeAppStates()
    }

    private fun observeVpnState() {
        persistentState.vpnEnabledLiveData.asFlow().onEach { enabled ->
            _uiState.update { it.copy(isVpnActive = enabled) }
            syncDnsStatus()
        }.launchIn(viewModelScope)

        VpnController.connectionStatus.asFlow().onEach {
            if (VpnController.isAppPaused()) return@onEach
            syncDnsStatus()
        }.launchIn(viewModelScope)
    }

    private fun observeDnsStates() {
        appConfig.getConnectedDnsObservable().asFlow().onEach { dnsName ->
            _uiState.update { it.copy(dnsConnectedName = dnsName) }
        }.launchIn(viewModelScope)

        // DNS Latency polling
        dnsLatencyJob?.cancel()
        dnsLatencyJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                if (appConfig.getBraveMode().isDnsActive()) {
                    val dnsId = getDnsId()
                    val p50 = VpnController.p50(dnsId)
                    val latencyText = formatLatency(p50)
                    _uiState.update { it.copy(dnsLatency = latencyText) }
                } else {
                    _uiState.update { it.copy(dnsConnectedName = "Disabled", dnsLatency = "-- ms") }
                }
                delay(5000L)
            }
        }
    }

    private fun getDnsId(): String {
        return if (WireguardManager.oneWireGuardEnabled()) {
            val id = WireguardManager.getOneWireGuardProxyId()
            if (id == null) {
                if (appConfig.isSmartDnsEnabled()) Backend.Plus else Backend.Preferred
            } else {
                "${ProxyManager.ID_WG_BASE}${id}"
            }
        } else {
            if (appConfig.isSmartDnsEnabled()) Backend.Plus else Backend.Preferred
        }
    }

    private fun formatLatency(p50: Long): String {
        return when (p50) {
            in 0L..19L -> "Very Fast"
            in 20L..50L -> "Fast"
            in 51L..100L -> "Slow"
            else -> "Very Slow"
        }
    }

    private fun observeFirewallStates() {
        persistentState.universalRulesCount.asFlow().onEach { count ->
            _uiState.update { it.copy(firewallUniversalRules = count) }
        }.launchIn(viewModelScope)

        IpRulesManager.getCustomIpsLiveData().asFlow().onEach { count ->
            _uiState.update { it.copy(firewallIpRules = count) }
        }.launchIn(viewModelScope)

        DomainRulesManager.getUniversalCustomDomainCount().asFlow().onEach { count ->
            _uiState.update { it.copy(firewallDomainRules = count) }
        }.launchIn(viewModelScope)
    }

    private fun observeLogsCount() {
        appConfig.dnsLogsCount.asFlow().onEach { count ->
            _uiState.update { it.copy(dnsLogsCount = count) }
        }.launchIn(viewModelScope)

        appConfig.networkLogsCount.asFlow().onEach { count ->
            _uiState.update { it.copy(networkLogsCount = count) }
        }.launchIn(viewModelScope)
    }

    private fun observeProxyStates() {
        persistentState.getProxyStatus().asFlow().distinctUntilChanged().onEach { status ->
            proxyStateListenerJob?.cancel()
            if (status != -1) {
                proxyStateListenerJob = viewModelScope.launch(Dispatchers.IO) {
                    while (true) {
                        updateUiWithProxyStates()
                        delay(1500L)
                    }
                }
            } else {
                _uiState.update { it.copy(proxyStatus = "Inactive") }
            }
        }.launchIn(viewModelScope)
    }

    private suspend fun updateUiWithProxyStates() {
        if (!persistentState.getVpnEnabled()) {
            _uiState.update { it.copy(proxyStatus = "Inactive") }
            return
        }

        val proxyType = AppConfig.ProxyType.of(appConfig.getProxyType())
        if (proxyType.isProxyTypeWireguard()) {
            val status = ProxyStateManager.calculateWireguardProxyStatus()
            _uiState.update { it.copy(proxyStatus = status.statusText) }
        } else {
            val status = if (appConfig.isProxyEnabled()) "Active" else "Inactive"
            _uiState.update { it.copy(proxyStatus = status) }
        }
    }

    private fun observeAppStates() {
        FirewallManager.getApplistObserver().asFlow().onEach { list ->
            val blockedCount = list.count { it.connectionStatus != FirewallManager.ConnectionStatus.ALLOW.id }
            val bypassCount = list.count { it.firewallStatus == FirewallManager.FirewallStatus.BYPASS_UNIVERSAL.id || it.firewallStatus == FirewallManager.FirewallStatus.BYPASS_DNS_FIREWALL.id }
            val excludedCount = list.count { it.firewallStatus == FirewallManager.FirewallStatus.EXCLUDE.id }
            val isolatedCount = list.count { it.firewallStatus == FirewallManager.FirewallStatus.ISOLATE.id }
            val allApps = list.size
            val allowedApps = allApps - (blockedCount + bypassCount + excludedCount + isolatedCount)

            _uiState.update { 
                it.copy(
                    appsAllowed = allowedApps,
                    appsTotal = allApps,
                    appsBlocked = blockedCount,
                    appsBypassed = bypassCount,
                    appsExcluded = excludedCount,
                    appsIsolated = isolatedCount
                )
            }
        }.launchIn(viewModelScope)
    }

    fun syncDnsStatus() {
        viewModelScope.launch {
            val vpnState = VpnController.state()
            val isEch = vpnState.serverName?.contains(DnsLogTracker.ECH, true) == true
            var isFailing = false
            var statusString = "Protected"

            if (vpnState.on) {
                when (vpnState.connectionState) {
                    BraveVPNService.State.APP_ERROR,
                    BraveVPNService.State.DNS_ERROR,
                    BraveVPNService.State.DNS_SERVER_DOWN,
                    BraveVPNService.State.NO_INTERNET -> {
                        isFailing = true
                        statusString = "Failing"
                    }
                    BraveVPNService.State.WORKING,
                    BraveVPNService.State.NEW -> {
                        statusString = if (isEch) "Ultra Secure" else "Protected"
                    }
                    else -> {
                        isFailing = true
                        statusString = "Failing"
                    }
                }
            } else if (persistentState.getVpnEnabled()) {
                isFailing = true
                statusString = "Waiting"
            } else {
                isFailing = true
                statusString = "Exposed"
            }

            if (VpnController.isUnderlyingVpnNetworkEmpty()) {
                isFailing = true
                statusString = "No Network"
            }

            _uiState.update { 
                it.copy(protectionStatus = statusString, isProtectionFailing = isFailing)
            }
        }
    }
}
