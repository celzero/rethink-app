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
package com.celzero.bravedns.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.firestack.backend.RpnEntitlement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

class EntitlementDetailViewModel : ViewModel() {

    companion object {
        private const val TAG = "EntitlementDetailVM"
        const val RESET_TIMEOUT_MS = 20_000L
    }

    private var loadJob: Job? = null
    private var resetJob: Job? = null

    /**
     * Lifecycle of the entitlement-details load.
     *
     * - [Idle]     – nothing loaded; initial state.
     * - [Loading]  – fetch in flight.
     * - [Done]     – fetch finished; [entitlement] is null when the stored
     *                entitlement could not be read or parsed (sheet shows an
     *                error toast and dismisses).
     *
     * [Done] is sticky on purpose: a recreated sheet (e.g. after rotation) replays
     * the last result without a redundant fetch. The sheet calls [resetEntitlement]
     * before [loadEntitlement] every time it opens so a result from a previous
     * session is never shown as stale data.
     */
    sealed class EntitlementState {
        object Idle : EntitlementState()
        object Loading : EntitlementState()
        data class Done(
            val entitlement: RpnEntitlement?,
            val activeEntitlement: RpnEntitlement?,
            val who: String?
        ) : EntitlementState()
    }

    private val _entitlementState = MutableStateFlow<EntitlementState>(EntitlementState.Idle)
    val entitlementState: StateFlow<EntitlementState> = _entitlementState.asStateFlow()

    /** Drops any previous [EntitlementState.Done] so a reopened sheet never renders stale data. */
    fun resetEntitlement() {
        loadJob?.cancel()
        _entitlementState.value = EntitlementState.Idle
    }

    /**
     * Fetches the stored and active entitlements (and the WIN identifier) on the
     * ViewModel scope, unless a load is already in flight. The fetch survives sheet
     * dismissal; the sheet observes [entitlementState] to render the rows.
     */
    fun loadEntitlement() {
        if (_entitlementState.value is EntitlementState.Loading) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _entitlementState.value = EntitlementState.Loading
            Logger.i(LOG_TAG_UI, "$TAG.loadEntitlement: fetching entitlement details")

            val entitlement = withContext(Dispatchers.IO) {
                try {
                    RpnProxyManager.getEntitlementDetails()
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_UI, "$TAG.loadEntitlement: getEntitlementDetails error: ${e.message}", e)
                    null
                }
            }
            val who = withContext(Dispatchers.IO) {
                try {
                    VpnController.getWinIdentifier()
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_UI, "$TAG.loadEntitlement: getWinIdentifier error: ${e.message}")
                    null
                }
            }
            val activeEntitlement = withContext(Dispatchers.IO) {
                try {
                    VpnController.getActiveEntitlement()
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_UI, "$TAG.loadEntitlement: getActiveEntitlement error: ${e.message}")
                    null
                }
            }

            Logger.i(
                LOG_TAG_UI,
                "$TAG.loadEntitlement: done, entitlement=${entitlement != null}, active=${activeEntitlement != null}"
            )
            _entitlementState.value = EntitlementState.Done(entitlement, activeEntitlement, who)
        }
    }

    sealed class ResetState {
        object Idle : ResetState()
        object InProgress : ResetState()
        data class Done(val result: RpnProxyManager.ResetResult) : ResetState()
        object NoTunnel : ResetState()
    }

    private val _resetState = MutableStateFlow<ResetState>(ResetState.Idle)
    val resetState: StateFlow<ResetState> = _resetState.asStateFlow()

    fun reset() {
        if (_resetState.value is ResetState.InProgress) return
        resetJob?.cancel()
        resetJob = viewModelScope.launch {
            _resetState.value = ResetState.InProgress
            Logger.i(LOG_TAG_UI, "$TAG.reset: starting RPN reset")

            val hasTunnel = withContext(Dispatchers.IO) { VpnController.hasTunnel() }
            if (!hasTunnel) {
                Logger.w(LOG_TAG_UI, "$TAG.reset: no VPN tunnel available, aborting reset")
                _resetState.value = ResetState.NoTunnel
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                try {
                    withTimeoutOrNull(RESET_TIMEOUT_MS.milliseconds) {
                        RpnProxyManager.resetAndRefetchRpn()
                    } ?: run {
                        Logger.w(LOG_TAG_UI, "$TAG.reset: timed out after ${RESET_TIMEOUT_MS / 1000}s")
                        RpnProxyManager.ResetResult.Failure("Timed out")
                    }
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_UI, "$TAG.reset: unexpected error: ${e.message}", e)
                    RpnProxyManager.ResetResult.Failure(e.message ?: "Unexpected error")
                }
            }

            Logger.i(LOG_TAG_UI, "$TAG.reset: done, result=$result")
            _resetState.value = ResetState.Done(result)
        }
    }

    fun onResetConsumed() {
        resetJob?.cancel()
        _resetState.value = ResetState.Idle
    }
}
