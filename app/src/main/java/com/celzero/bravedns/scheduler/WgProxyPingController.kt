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
package com.celzero.bravedns.scheduler

import Logger
import Logger.LOG_TAG_PROXY
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.VpnController
import com.celzero.firestack.backend.Backend
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class WgProxyPingController(private val scope: CoroutineScope) {
    private val activeProxies = ConcurrentHashMap<String, PingConfig>()

    private val intervalMs = 60_000L
    private val durationMs = 5 * 60 * 1000L

    @Volatile private var schedulerJob: Job? = null

    companion object {
        private const val TAG = "WgProxyPingController"
    }

    // startTime is a val, captured once in startPing and never modified.
    class PingConfig(val startTime: Long) {
        @Volatile var running: Boolean = false
    }

    fun startPing(proxyId: String) {
        Logger.vv(LOG_TAG_PROXY, "$TAG request to start ping: $proxyId, already active proxies=${activeProxies.keys}")
        val existing = activeProxies[proxyId]

        val newConfig = PingConfig(startTime = System.currentTimeMillis())
        if (existing != null) newConfig.running = existing.running
        activeProxies[proxyId] = newConfig

        ensureScheduler()
        if (existing != null) {
            Logger.vv(LOG_TAG_PROXY, "$TAG restarted ping timer for $proxyId; " +
                    "first ping in ~${intervalMs / 1000}s, " +
                    "duration window reset to ${durationMs / 1000}s from now")
        } else {
            Logger.vv(LOG_TAG_PROXY, "$TAG started ping for $proxyId; " +
                    "first ping in ~${intervalMs / 1000}s, " +
                    "then every ${intervalMs / 1000}s for ${durationMs / 1000}s")
        }
    }

    fun stopPing(proxyId: String) {
        activeProxies.remove(proxyId)
        Logger.vv(LOG_TAG_PROXY, "$TAG stopped ping for $proxyId")

        if (activeProxies.isEmpty()) stopScheduler()
    }

    fun stopAll() {
        activeProxies.clear()
        stopScheduler()
        Logger.vv(LOG_TAG_PROXY, "$TAG stopped all ping jobs")
    }

    private fun ensureScheduler() {
        if (schedulerJob?.isActive == true) return

        schedulerJob = scope.launch(Dispatchers.IO + CoroutineName("ping-scheduler")) {
            var nextTick = alignToNextInterval(System.currentTimeMillis())

            while (isActive && activeProxies.isNotEmpty()) {
                val delayMs = nextTick - System.currentTimeMillis()
                if (delayMs > 0) delay(delayMs)

                tick(System.currentTimeMillis())

                nextTick += intervalMs
            }
        }
    }

    private fun stopScheduler() {
        schedulerJob?.cancel()
        schedulerJob = null
    }

    private fun tick(now: Long) {
        for ((proxyId, config) in activeProxies) {

            // skip if a previous ping coroutine is still running (prevents overlap).
            if (config.running) {
                Logger.vv(LOG_TAG_PROXY, "$TAG tick skipped for $proxyId: ping already in-flight")
                continue
            }

            val elapsed = now - config.startTime
            val remaining = durationMs - elapsed

            // enforce a minimum delay of intervalMs before the very first ping.
            if (elapsed < intervalMs) {
                Logger.vv(LOG_TAG_PROXY, "$TAG tick: $proxyId waiting for first ping; " +
                        "starts in ~${(intervalMs - elapsed) / 1000}s")
                continue
            }

            if (elapsed >= durationMs) {
                Logger.vv(LOG_TAG_PROXY, "$TAG ping window expired for $proxyId, removing")
                activeProxies.remove(proxyId)
                continue
            }

            Logger.vv(LOG_TAG_PROXY, "$TAG tick for $proxyId: " +
                    "elapsed=${elapsed / 1000}s, remaining=${remaining / 1000}s " +
                    "(window=${durationMs / 1000}s, interval=${intervalMs / 1000}s)")

            // set running to true before launching so that even if the coroutine is scheduled
            // immediately, the next tick iteration already sees it as running
            config.running = true

            scope.launch(Dispatchers.IO + CoroutineName("ping-$proxyId")) {
                try {
                    pingProxy(proxyId)
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_PROXY, "$TAG ping failed: $proxyId err=${e.message}")
                } finally {
                    activeProxies[proxyId]?.running = false
                }
            }
        }
    }

    private fun alignToNextInterval(now: Long): Long {
        return ((now / intervalMs) + 1) * intervalMs
    }

    private suspend fun pingProxy(proxyId: String) {
        when {
            proxyId.startsWith(ID_WG_BASE) -> {
                VpnController.initiateWgPing(proxyId)
            }

            // spl-case: for auto proxy send empty proxyId which will ping main proxy
            proxyId == Backend.RpnWin -> {
                VpnController.initiateRpnPing("")
            }

            proxyId.startsWith(Backend.RpnWin) -> {
                VpnController.initiateRpnPing(proxyId)
            }
        }

        Logger.vv(LOG_TAG_PROXY, "$TAG ping triggered: $proxyId at ${System.currentTimeMillis()}")
    }
}
