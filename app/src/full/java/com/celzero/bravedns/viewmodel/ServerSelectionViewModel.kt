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

import Logger
import Logger.LOG_TAG_UI
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * ViewModel for [com.celzero.bravedns.ui.fragment.ServerSelectionFragment] and its child
 * Owns the server-refresh coroutine so that a bottom-sheet dismissal never cancels an
 * in-progress IO operation. Both views observe [refreshState] to keep their UI in sync.
 */
class ServerSelectionViewModel : ViewModel() {

    companion object {
        private const val TAG = "ServerSelectionVM"
        /**
         * Maximum time (ms) allowed for [reset] to complete before it is considered
         * timed-out and [ResetState.Done] is emitted with a [RpnProxyManager.ResetResult.Failure].
         * Kept in sync with the fragment's dialog progress-bar maximum.
         */
        const val RESET_TIMEOUT_MS = 20_000L
    }

    /**
     * Lifecycle of a user-initiated server refresh.
     *
     * - [Idle]        – no refresh running; initial and post-consume state.
     * - [InProgress]  – IO is in flight; callers should disable the refresh button.
     * - [Done]        – IO succeeded; servers and selected are ready to initialize the list.
     * - [NeedsLoading]– [RpnProxyManager.updateWinProxy] returned an empty list; the fragment
     *                   should show the server-loading dialog.
     * - [NoTunnel]    – The VPN tunnel is not running; registration/refresh is impossible until
     *                   the user starts Rethink.
     *
     * [Done], [NeedsLoading], and [NoTunnel] are one-shot results: the Fragment must call
     * [onRefreshConsumed] after handling them so the flow returns to [Idle] and late
     * subscribers (e.g. a re-created sheet) do not replay a stale result.
     */
    sealed class RefreshState {
        object Idle : RefreshState()
        object InProgress : RefreshState()
        data class Done(
            val servers: List<CountryConfig>,
            val selected: Set<CountryConfig>
        ) : RefreshState()
        /** Proxy returned no servers; fragment should show the loading / registration dialog. */
        object NeedsLoading : RefreshState()
        /** VPN tunnel is not running; fragment should show the "Start Rethink" error. */
        object NoTunnel : RefreshState()
    }

    private val _refreshState = MutableStateFlow<RefreshState>(RefreshState.Idle)
    /** Observed by the Fragment (to reload the server list) and the BottomSheet (to animate). */
    val refreshState: StateFlow<RefreshState> = _refreshState.asStateFlow()

    /**
     * Starts a server refresh unless one is already in progress.
     * Emits [RefreshState.NoTunnel] immediately if [VpnController.hasTunnel] is false so the
     * fragment surface the "Start Rethink" error without waiting for a network timeout.
     */
    fun refresh() {
        if (_refreshState.value is RefreshState.InProgress) return
        viewModelScope.launch(Dispatchers.IO) {
            _refreshState.value = RefreshState.InProgress
            Logger.i(LOG_TAG_UI, "$TAG.refresh: starting server refresh")

            // Guard: registration/fetch requires an active VPN tunnel.
            if (!VpnController.hasTunnel()) {
                Logger.w(LOG_TAG_UI, "$TAG.refresh: no VPN tunnel available, aborting refresh")
                _refreshState.value = RefreshState.NoTunnel
                return@launch
            }

            val selectedList: Set<CountryConfig> = try {
                RpnProxyManager.getEnabledConfigs()
            } catch (e: Exception) {
                Logger.w(LOG_TAG_UI, "$TAG.refresh: getEnabledConfigs failed: ${e.message}")
                emptySet()
            }

            val refreshed: List<CountryConfig> = try {
                RpnProxyManager.updateWinProxy() ?: emptyList()
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "$TAG.refresh: updateWinProxy error: ${e.message}", e)
                emptyList()
            }

            _refreshState.value = if (refreshed.isNotEmpty()) {
                Logger.i(LOG_TAG_UI, "$TAG.refresh: done, ${refreshed.size} servers")
                RefreshState.Done(refreshed, selectedList)
            } else {
                Logger.w(LOG_TAG_UI, "$TAG.refresh: no servers returned, triggering loading dialog")
                RefreshState.NeedsLoading
            }
        }
    }

    /**
     * Must be called by the Fragment **after** it has handled a [RefreshState.Done] or
     * [RefreshState.NeedsLoading] result. Resets the state to [RefreshState.Idle] so
     * that late re-subscribers (e.g. a recreated bottom sheet) do not replay a stale result.
     */
    fun onRefreshConsumed() {
        _refreshState.value = RefreshState.Idle
    }

    /**
     * Lifecycle of a user-initiated RPN reset.
     *
     * - [Idle]       – no reset running; initial and post-consume state.
     * - [InProgress] – In progress. The fragment should show a progress dialog.
     * - [Done]       – Completed (success or failure). The fragment must call
     *                  [onResetConsumed] after handling this state so late re-subscribers
     *                  (e.g. a recreated fragment after rotation) do not replay stale results.
     * - [NoTunnel]   – VPN tunnel is not running; reset cannot proceed. Fragment should show
     *                  the "Start Rethink" error.
     */
    sealed class ResetState {
        object Idle : ResetState()
        object InProgress : ResetState()
        data class Done(
            val result: RpnProxyManager.ResetResult,
            /** Freshly-fetched server list (may be empty on failure). */
            val servers: List<CountryConfig>,
            /** Currently-enabled configs fetched after the reset. */
            val selected: Set<CountryConfig>
        ) : ResetState()
        /** VPN tunnel is not running; fragment should show the "Start Rethink" error. */
        object NoTunnel : ResetState()
    }

    private val _resetState = MutableStateFlow<ResetState>(ResetState.Idle)
    /** Observed by [com.celzero.bravedns.ui.fragment.ServerSelectionFragment] to drive the
     *  reset-progress dialog and handle the result. */
    val resetState: StateFlow<ResetState> = _resetState.asStateFlow()

    /**
     * Starts the RPN reset unless one is already in progress.
     * Emits [ResetState.NoTunnel] immediately if [VpnController.hasTunnel] is false so the
     * fragment surfaces the "Start Rethink" error without waiting for a reset timeout.
     *
     * Result data ([ResetState.Done.servers] / [ResetState.Done.selected]) is fetched
     * while still inside this scope so the fragment receives everything it needs to call
     * [com.celzero.bravedns.ui.fragment.ServerSelectionFragment.initServers] in one shot.
     */
    fun reset() {
        if (_resetState.value is ResetState.InProgress) return
        viewModelScope.launch {
            _resetState.value = ResetState.InProgress
            Logger.i(LOG_TAG_UI, "$TAG.reset: starting RPN reset")

            // Guard: reset requires an active VPN tunnel; fail fast instead of timing out.
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

            // Always fetch the latest server list so the fragment can refresh the UI
            // regardless of whether the reset succeeded or failed.
            val servers = withContext(Dispatchers.IO) {
                try { RpnProxyManager.getWinServers() } catch (_: Exception) { emptyList() }
            }
            val selected = withContext(Dispatchers.IO) {
                try { RpnProxyManager.getEnabledConfigs() } catch (_: Exception) { emptySet() }
            }

            Logger.i(LOG_TAG_UI, "$TAG.reset: done, result=$result, servers=${servers.size}")
            _resetState.value = ResetState.Done(result, servers, selected)
        }
    }

    /**
     * Must be called by the Fragment **after** it has handled a [ResetState.Done] or
     * [ResetState.NoTunnel] result. Resets to [ResetState.Idle] so late re-subscribers do
     * not replay a stale result.
     */
    fun onResetConsumed() {
        _resetState.value = ResetState.Idle
    }
}
