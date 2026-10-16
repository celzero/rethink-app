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

package com.celzero.bravedns.receiver

import com.celzero.bravedns.R
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN

/**
 * Why a single wireguard command could not be carried out. [msgRes] reuses the
 * same user-facing messages the in-app WireGuard screens show (see
 * WgConfigAdapter's error toasts) so the wording stays consistent.
 */
enum class WgCommandError(val msgRes: Int) {
    /** Rethink VPN is not running. */
    VPN_OFF(R.string.settings_socks5_vpn_disabled_error),

    /** Proxy cannot be enabled in DNS-only mode. */
    DNS_MODE(R.string.wireguard_dns_mode_conflict),

    /** Another one-wireguard config is active. */
    ONE_WG_ACTIVE(R.string.wireguard_one_wg_active_conflict),

    /** Config missing, invalid, or not active (for pause/resume). */
    INVALID_CONFIG(R.string.wireguard_invalid_config_message),

    /** Catch-all configs cannot be disabled. */
    DISABLE_CATCH_ALL(R.string.wireguard_disable_failure),

    /** Hop src/via configs cannot be disabled. */
    DISABLE_HOP(R.string.wireguard_disable_failure_relay),

    /** Unexpected error while executing the command. */
    ACTION_FAILED(R.string.wg_automation_action_failed)
}

/**
 * Executes wireguard start/stop/pause/resume commands received from external
 * automation apps via [VpnControlReceiver].
 *
 * Semantics:
 *  - start  -> [WireguardManager.enableConfig] (config becomes active, proxy added to tunnel)
 *  - stop   -> [WireguardManager.disableConfig] (config becomes inactive, proxy removed)
 *  - pause  -> live-proxy pause (config must be active, vpn tunnel up)
 *  - resume -> live-proxy resume (config must be active, vpn tunnel up)
 *
 * Every command is idempotent: no-op states (start on active, stop on inactive)
 * succeed silently. Genuine failures are returned as [Failure]s (one entry per
 * config) so the caller can surface them, see [WgAutomationNotifier]; a failure
 * for one id never aborts the rest of the batch.
 */
class WgCommands {

    companion object {
        private const val TAG = "WgCommands"
    }

    /** A single config a command could not be applied to, and why. */
    data class Failure(val id: Int, val error: WgCommandError)

    suspend fun start(ids: List<Int>): List<Failure> {
        return ids.mapNotNull { id -> attempt("start", id) { startOne(id) } }
    }

    suspend fun stop(ids: List<Int>): List<Failure> {
        return ids.mapNotNull { id -> attempt("stop", id) { stopOne(id) } }
    }

    suspend fun pause(ids: List<Int>): List<Failure> {
        return ids.mapNotNull { id -> attempt("pause", id) { pauseOne(id) } }
    }

    suspend fun resume(ids: List<Int>): List<Failure> {
        return ids.mapNotNull { id -> attempt("resume", id) { resumeOne(id) } }
    }

    private suspend fun attempt(
        op: String,
        id: Int,
        cmd: suspend () -> WgCommands.Failure?
    ): WgCommands.Failure? {
        return try {
            cmd()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG err $op wg($id): ${e.message}", e)
            WgCommands.Failure(id, WgCommandError.ACTION_FAILED)
        }
    }

    private suspend fun startOne(id: Int): WgCommands.Failure? {
        val mapping = WireguardManager.getConfigFilesById(id)
        if (mapping == null) {
            Logger.w(LOG_TAG_VPN, "$TAG start: no wg config with id $id")
            return Failure(id, WgCommandError.INVALID_CONFIG)
        }
        if (mapping.isActive) {
            Logger.i(LOG_TAG_VPN, "$TAG start: wg $id already active, no-op")
            return null
        }
        if (!VpnController.hasTunnel()) {
            Logger.w(LOG_TAG_VPN, "$TAG start: vpn not active, cannot enable wg $id")
            return Failure(id, WgCommandError.VPN_OFF)
        }
        if (!WireguardManager.canEnableProxy()) {
            Logger.w(LOG_TAG_VPN, "$TAG start: not in DNS+Firewall mode, cannot enable wg $id")
            return Failure(id, WgCommandError.DNS_MODE)
        }
        if (!WireguardManager.isValidConfig(id)) {
            Logger.w(LOG_TAG_VPN, "$TAG start: invalid wg config $id")
            return Failure(id, WgCommandError.INVALID_CONFIG)
        }
        // one-wg is mutually exclusive: swap out any other active one-wg config
        if (mapping.oneWireGuard) {
            if (WireguardManager.isAnyOtherOneWgEnabled(id)) {
                Logger.i(LOG_TAG_VPN, "$TAG start: replacing other active one-wg config")
                WireguardManager.disableOtherOneWireGuardConfigs(id)
            }
        } else if (WireguardManager.oneWireGuardEnabled()) {
            Logger.w(LOG_TAG_VPN, "$TAG start: one-wg already enabled, cannot enable wg $id")
            return Failure(id, WgCommandError.ONE_WG_ACTIVE)
        }
        // key overlap is a warning only: log it and allow the enable to proceed
        if (!WireguardManager.canEnableProxy(id)) {
            Logger.w(
                LOG_TAG_VPN,
                "$TAG start: wg keys overlap with an active config, allowing anyway, id: $id"
            )
        }
        WireguardManager.enableConfig(mapping)
        Logger.i(LOG_TAG_VPN, "$TAG started wg config: $id, ${mapping.name}")
        return null
    }

    private suspend fun stopOne(id: Int): WgCommands.Failure? {
        val mapping = WireguardManager.getConfigFilesById(id)
        if (mapping == null) {
            Logger.w(LOG_TAG_VPN, "$TAG stop: no wg config with id $id")
            return Failure(id, WgCommandError.INVALID_CONFIG)
        }
        if (!mapping.isActive) {
            Logger.i(LOG_TAG_VPN, "$TAG stop: wg $id already inactive, no-op")
            return null
        }
        if (!VpnController.hasTunnel()) {
            Logger.w(LOG_TAG_VPN, "$TAG stop: vpn not active, cannot disable wg $id")
            return Failure(id, WgCommandError.VPN_OFF)
        }
        if (!WireguardManager.canDisableConfig(mapping)) {
            // catch-all configs and hop src/via configs cannot be disabled
            Logger.w(LOG_TAG_VPN, "$TAG stop: wg $id cannot be disabled (catch-all/hop)")
            return if (mapping.isCatchAll) {
                Failure(id, WgCommandError.DISABLE_CATCH_ALL)
            } else {
                Failure(id, WgCommandError.DISABLE_HOP)
            }
        }
        if (mapping.oneWireGuard) {
            WireguardManager.updateOneWireGuardConfig(id, false)
        }
        WireguardManager.disableConfig(mapping)
        Logger.i(LOG_TAG_VPN, "$TAG stopped wg config: $id, ${mapping.name}")
        return null
    }

    private suspend fun pauseOne(id: Int): WgCommands.Failure? {
        if (!VpnController.hasTunnel()) {
            Logger.w(LOG_TAG_VPN, "$TAG pause: vpn not active, cannot pause wg $id")
            return Failure(id, WgCommandError.VPN_OFF)
        }
        val mapping = requireActive(id, "pause") ?: return Failure(id, WgCommandError.INVALID_CONFIG)
        VpnController.pauseWireGuardProxy(ID_WG_BASE + id)
        Logger.i(LOG_TAG_VPN, "$TAG paused wg proxy: $id, active: ${mapping.name}")
        return null
    }

    private suspend fun resumeOne(id: Int): WgCommands.Failure? {
        if (!VpnController.hasTunnel()) {
            Logger.w(LOG_TAG_VPN, "$TAG resume: vpn not active, cannot resume wg $id")
            return Failure(id, WgCommandError.VPN_OFF)
        }
        val mapping = requireActive(id, "resume") ?: return Failure(id, WgCommandError.INVALID_CONFIG)
        VpnController.resumeWireGuardProxy(ID_WG_BASE + id)
        Logger.i(LOG_TAG_VPN, "$TAG resumed wg proxy: $id, active: ${mapping.name}")
        return null
    }

    private fun requireActive(id: Int, op: String): WgConfigFilesImmutable? {
        val mapping = WireguardManager.getConfigFilesById(id)
        if (mapping == null) {
            Logger.w(LOG_TAG_VPN, "$TAG $op: no wg config with id $id")
            return null
        }
        if (!mapping.isActive) {
            Logger.w(LOG_TAG_VPN, "$TAG $op: wg $id is not active")
            return null
        }
        return mapping
    }
}
