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
package com.celzero.bravedns.service

import android.os.SystemClock
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.RpnType
import com.celzero.bravedns.service.ProxyManager.ID_HTTP_BASE
import com.celzero.bravedns.service.ProxyManager.ID_ORBOT_BASE
import com.celzero.bravedns.service.ProxyManager.ID_S5_BASE
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_PROXY
import com.celzero.firestack.backend.Backend
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Global proxy handler that keeps the proxies added to the tunnel healthy.
 *
 * Every proxy add to the tunnel (WireGuard from [WireguardManager], RPN win/country
 * servers from [RpnProxyManager], and the default S5/HTTP/Orbot adds from
 * [com.celzero.bravedns.net.go.GoVpnAdapter.initResolverProxiesPcap]) is recorded
 * here with the add-time ([SystemClock.elapsedRealtime]) via [track]. A periodic
 * checker (every [INTERVAL_MS]) verifies that each tracked proxy is still present
 * in the tunnel (using [VpnController.hasProxy] / [VpnController.hasRpnProxy], with
 * the AUTO special-case resolving to [Backend.RpnWin]) and re-adds the missing ones.
 *
 * Re-add policy:
 *  1. If the proxy was added less than [GRACE_MS] ago, wait for the next iteration.
 *  2. A proxy is re-added at most [MAX_ATTEMPTS] times, with an exponential backoff
 *     of [BACKOFF_BASE_MS] * 2^(attempt-1) between attempts (1, 2, 4, 8, 16 min).
 *
 * The handler is a process-wide singleton so that [WireguardManager] (full source
 * set), [RpnProxyManager] and [GoVpnAdapter] can all record adds/removals. Lifecycle
 * (start/stop) is owned by [BraveVPNService].
 */
object GlobalProxyHandler : KoinComponent {

    private const val TAG = "GlobalProxyHandler"

    // cadence of the periodic checker
    private const val INTERVAL_MS = 2 * 60 * 1000L

    // no re-add attempts within the grace period after an add
    private const val GRACE_MS = 2 * 60 * 1000L

    // maximum number of re-add attempts before giving up on a proxy
    private const val MAX_ATTEMPTS = 5

    // exponential backoff base (1 min): delays of 1, 2, 4, 8, 16 min
    private const val BACKOFF_BASE_MS = 60 * 1000L

    private val eventLogger: EventLogger by inject()

    class ProxyEntry(@Volatile var addedAtElapsed: Long) {
        @Volatile var attempts: Int = 0
        @Volatile var lastAttemptAtElapsed: Long = 0L
    }

    private val proxies = ConcurrentHashMap<String, ProxyEntry>()

    @Volatile private var job: Job? = null

    /**
     * Record an add of [id] to the tunnel (or refresh an existing record). Resets the
     * attempt budget and the grace period so that a freshly added proxy is not
     * re-added until [GRACE_MS] has passed.
     */
    fun track(id: String) {
        proxies[id] = ProxyEntry(SystemClock.elapsedRealtime())
        Logger.vv(LOG_TAG_PROXY, "$TAG track $id, total: ${proxies.size}")
    }

    /**
     * Remove [id] from the tracked set. Called when the proxy is explicitly removed
     * from the tunnel (e.g. user disabled the config), so that it is never re-added.
     */
    fun untrack(id: String) {
        proxies.remove(id)?.let {
            Logger.v(LOG_TAG_PROXY, "$TAG untrack $id, total: ${proxies.size}")
        }
    }

    /**
     * Remove the RPN win proxy (AUTO) and all forked country servers. Called when the
     * win is unregistered from the tunnel.
     */
    fun untrackRpn() {
        proxies.keys.removeAll {
            it == Backend.RpnWin || it.startsWith(Backend.RpnWin)
        }
        Logger.v(LOG_TAG_PROXY, "$TAG untracked rpn proxies, total: ${proxies.size}")
    }

    /**
     * Start the periodic checker on [scope]. Idempotent; survives tunnel restarts
     * (restarts re-track via the Go adapter's default proxy adds).
     */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO + CoroutineName("global-proxy-handler")) {
            while (isActive) {
                delay(INTERVAL_MS.milliseconds)
                try {
                    checkAndReadd()
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_PROXY, "$TAG check failed: ${e.message}")
                }
            }
        }
        Logger.i(LOG_TAG_PROXY, "$TAG started, tracking: ${proxies.size}")
    }

    /** Stop the periodic checker and forget all tracked proxies. */
    fun stop() {
        job?.cancel()
        job = null
        proxies.clear()
        Logger.i(LOG_TAG_PROXY, "$TAG stopped")
    }

    /**
     * One pass of the checker: for each tracked proxy, verify presence in the tunnel
     * and re-add (with grace period, attempt budget and exponential backoff) when
     * missing. No-op when the tunnel is not running.
     */
    suspend fun checkAndReadd() {
        if (!VpnController.hasTunnel()) return
        val now = SystemClock.elapsedRealtime()
        for ((id, entry) in proxies) {
            try {
                processProxy(id, entry, now)
            } catch (e: Exception) {
                Logger.w(LOG_TAG_PROXY, "$TAG err processing $id: ${e.message}")
            }
        }
    }

    private suspend fun processProxy(id: String, entry: ProxyEntry, now: Long) {
        // (1) freshly added proxy: wait for the next iteration
        if (now - entry.addedAtElapsed < GRACE_MS) return

        // healthy proxy: reset the attempt budget and carry on monitoring
        if (isProxyPresent(id)) {
            if (entry.attempts != 0) {
                entry.attempts = 0
                Logger.v(LOG_TAG_PROXY, "$TAG proxy present again, attempts reset: $id")
            }
            return
        }

        // (2) attempt budget exhausted: give up on this proxy
        if (entry.attempts >= MAX_ATTEMPTS) {
            proxies.remove(id)
            Logger.w(LOG_TAG_PROXY, "$TAG giving up on $id after $MAX_ATTEMPTS attempts")
            eventLogger.log(
                type = EventType.TUN_UPDATE,
                severity = Severity.HIGH,
                message = "proxy re-add failed: $id",
                source = EventSource.VPN,
                userAction = false,
                details = "gave up after $MAX_ATTEMPTS attempts"
            )
            return
        }

        // exponential backoff between attempts: 1, 2, 4, 8, 16 min
        val backoffMs = if (entry.attempts == 0) 0L else (BACKOFF_BASE_MS shl (entry.attempts - 1))
        if (now - entry.lastAttemptAtElapsed < backoffMs) return

        // the config may have been deleted/disabled meanwhile: drop instead of re-adding
        if (!isProxyStillWanted(id)) {
            proxies.remove(id)
            Logger.i(LOG_TAG_PROXY, "$TAG proxy no longer wanted, dropped: $id")
            return
        }

        entry.lastAttemptAtElapsed = now
        entry.attempts++
        Logger.i(LOG_TAG_PROXY, "$TAG re-adding proxy $id, attempt ${entry.attempts}/$MAX_ATTEMPTS")
        readdProxy(id)
    }

    private suspend fun isProxyPresent(id: String): Boolean {
        return when {
            // spl-case: AUTO resolves to the main win proxy
            id == Backend.RpnWin -> VpnController.hasRpnProxy(Backend.RpnWin)
            id.startsWith(Backend.RpnWin) -> VpnController.hasRpnProxy(id)
            else -> VpnController.hasProxy(id)
        }
    }

    private suspend fun isProxyStillWanted(id: String): Boolean {
        return when {
            id == Backend.RpnWin -> RpnProxyManager.isRpnActive()
            id.startsWith(Backend.RpnWin) -> {
                val key = id.removePrefix(Backend.RpnWin)
                RpnProxyManager.getEnabledConfigs().any { it.key == key }
            }
            id.startsWith(ID_WG_BASE) -> {
                val proxyId = id.substring(ID_WG_BASE.length).toIntOrNull()
                proxyId != null && WireguardManager.getConfigById(proxyId) != null
            }
            // S5/HTTP/Orbot have no persistent config to reconcile against
            else -> true
        }
    }

    private suspend fun readdProxy(id: String) {
        when {
            id == Backend.RpnWin -> {
                if (RpnProxyManager.isRpnActive()) {
                    RpnProxyManager.registerProxy(RpnType.WIN)
                } else {
                    proxies.remove(id)
                    Logger.w(LOG_TAG_PROXY, "$TAG rpn inactive, skip re-add: $id")
                }
            }
            id.startsWith(Backend.RpnWin) -> {
                VpnController.addNewWinServer(id.removePrefix(Backend.RpnWin))
            }
            id.startsWith(ID_WG_BASE) -> {
                VpnController.addWireGuardProxy(id, force = true)
            }
            else -> {
                Logger.w(LOG_TAG_PROXY, "$TAG unknown proxy id, skip re-add: $id")
            }
        }
    }

    /** Debug view of the tracked proxies for bug reports. */
    fun stats(): String {
        val sb = StringBuilder()
        val now = SystemClock.elapsedRealtime()
        sb.append("   Global proxy handler: ${proxies.size} tracked\n")
        proxies.forEach { (id, entry) ->
            val graceLeft = GRACE_MS - (now - entry.addedAtElapsed)
            sb.append("   id: $id, attempts: ${entry.attempts}/$MAX_ATTEMPTS, ")
                .append("added: ${(now - entry.addedAtElapsed) / 1000}s ago")
            if (graceLeft > 0) sb.append(", grace: ${graceLeft / 1000}s left")
            sb.append("\n")
        }
        return sb.toString()
    }
}
