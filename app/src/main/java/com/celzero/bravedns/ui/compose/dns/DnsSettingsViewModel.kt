package com.celzero.bravedns.ui.compose.dns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Utilities.isAtleastR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DnsSettingsUiState(
    val connectedDnsName: String = "--",
    val connectedDnsType: String = "--",
    val dnsLatency: String = "",
    val dnsType: AppConfig.DnsType = AppConfig.DnsType.DOH,
    val isSmartDnsEnabled: Boolean = false,
    val isSystemDnsEnabled: Boolean = false,
    val isRethinkDnsConnected: Boolean = false,
    val fetchFavIcon: Boolean = false,
    val preventDnsLeaks: Boolean = false,
    val enableDnsAlg: Boolean = false,
    val periodicallyCheckBlocklistUpdate: Boolean = false,
    val useCustomDownloadManager: Boolean = false,
    val enableDnsCache: Boolean = false,
    val proxyDns: Boolean = false,
    val useSystemDnsForUndelegatedDomains: Boolean = false,
    val useFallbackDnsToBypass: Boolean = false,
    val blocklistEnabled: Boolean = false,
    val numberOfLocalBlocklists: Int = 0,
    val bypassBlockInDns: Boolean = false,
    val splitDns: Boolean = false,
    val dnsRecordTypesAutoMode: Boolean = false,
    val allowedDnsRecordTypesSize: Int = 0,
    val isShowSplitDns: Boolean = false,
    val isShowBypassDnsBlock: Boolean = false,
    val isRefreshing: Boolean = false
)

class DnsSettingsViewModel(
    private val persistentState: PersistentState,
    private val appConfig: AppConfig,
    private val workScheduler: WorkScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(DnsSettingsUiState())
    val uiState: StateFlow<DnsSettingsUiState> = _uiState.asStateFlow()

    init {
        updateUiState()
        // We might want to observe appConfig.getConnectedDnsObservable() 
        // but for now we'll just pull it.
    }

    fun updateUiState() {
        viewModelScope.launch {
            val type = appConfig.getDnsType()
            val isSmart = appConfig.isSmartDnsEnabled()
            val isSystem = appConfig.isSystemDns()
            val isRethink = appConfig.isRethinkDnsConnected()
            val connectedName = persistentState.connectedDnsName
            
            _uiState.update {
                it.copy(
                    dnsType = type,
                    isSmartDnsEnabled = isSmart,
                    isSystemDnsEnabled = isSystem,
                    isRethinkDnsConnected = isRethink,
                    connectedDnsName = connectedName,
                    fetchFavIcon = persistentState.fetchFavIcon,
                    preventDnsLeaks = persistentState.preventDnsLeaks,
                    enableDnsAlg = persistentState.enableDnsAlg,
                    periodicallyCheckBlocklistUpdate = persistentState.periodicallyCheckBlocklistUpdate,
                    useCustomDownloadManager = persistentState.useCustomDownloadManager,
                    enableDnsCache = persistentState.enableDnsCache,
                    proxyDns = persistentState.proxyDns,
                    useSystemDnsForUndelegatedDomains = persistentState.useSystemDnsForUndelegatedDomains,
                    useFallbackDnsToBypass = persistentState.useFallbackDnsToBypass,
                    blocklistEnabled = persistentState.blocklistEnabled,
                    numberOfLocalBlocklists = persistentState.numberOfLocalBlocklists,
                    bypassBlockInDns = persistentState.bypassBlockInDns,
                    splitDns = persistentState.splitDns,
                    dnsRecordTypesAutoMode = persistentState.dnsRecordTypesAutoMode,
                    allowedDnsRecordTypesSize = persistentState.getAllowedDnsRecordTypes().size,
                    isShowSplitDns = isAtleastR() || persistentState.enableDnsAlg,
                    isShowBypassDnsBlock = persistentState.enableDnsAlg
                )
            }
            updateLatency()
        }
    }

    private fun updateLatency() {
        viewModelScope.launch(Dispatchers.IO) {
            val p50 = VpnController.p50("preferred") // simplified
            if (p50 > 0) {
                _uiState.update { it.copy(dnsLatency = "($p50 ms)") }
            } else {
                _uiState.update { it.copy(dnsLatency = "") }
            }
        }
    }

    fun refreshDns() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            VpnController.refresh()
            delay(2000)
            _uiState.update { it.copy(isRefreshing = false) }
            updateUiState()
        }
    }

    fun setFavIconEnabled(enabled: Boolean) {
        persistentState.fetchFavIcon = enabled
        updateUiState()
    }

    fun setPreventDnsLeaksEnabled(enabled: Boolean) {
        persistentState.preventDnsLeaks = enabled
        updateUiState()
    }

    fun setDnsAlgEnabled(enabled: Boolean) {
        persistentState.enableDnsAlg = enabled
        updateUiState()
    }

    fun setPeriodicallyCheckBlocklistUpdate(enabled: Boolean) {
        persistentState.periodicallyCheckBlocklistUpdate = enabled
        if (enabled) {
            workScheduler.scheduleBlocklistUpdateCheckJob()
        } else {
            workScheduler.cancelBlocklistUpdateCheckJob()
        }
        updateUiState()
    }

    fun setUseCustomDownloadManager(enabled: Boolean) {
        persistentState.useCustomDownloadManager = enabled
        updateUiState()
    }

    fun setEnableDnsCache(enabled: Boolean) {
        persistentState.enableDnsCache = enabled
        updateUiState()
    }

    fun setProxyDns(enabled: Boolean) {
        persistentState.proxyDns = enabled
        updateUiState()
    }

    fun setUseSystemDnsForUndelegatedDomains(enabled: Boolean) {
        persistentState.useSystemDnsForUndelegatedDomains = enabled
        updateUiState()
    }

    fun setUseFallbackDnsToBypass(enabled: Boolean) {
        persistentState.useFallbackDnsToBypass = enabled
        updateUiState()
    }

    fun setBypassBlockInDns(enabled: Boolean) {
        persistentState.bypassBlockInDns = enabled
        updateUiState()
    }

    fun setSplitDns(enabled: Boolean) {
        persistentState.splitDns = enabled
        updateUiState()
    }

    fun enableSystemDns() {
        viewModelScope.launch(Dispatchers.IO) {
            appConfig.enableSystemDns()
            withContext(Dispatchers.Main) { updateUiState() }
        }
    }

    fun enableSmartDns() {
        viewModelScope.launch(Dispatchers.IO) {
            appConfig.enableSmartDns()
            withContext(Dispatchers.Main) { updateUiState() }
        }
    }
}
