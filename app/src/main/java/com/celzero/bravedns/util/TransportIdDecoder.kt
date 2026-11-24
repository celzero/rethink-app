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
package com.celzero.bravedns.util

import com.celzero.bravedns.service.ProxyManager
import com.celzero.firestack.backend.Backend

// decodes raw transport/proxy id csvs (as sent to firestack in DNSOpts.tidcsv) into
// human-readable lines for the connection simulator ui; unknown ids fall back to raw text
object TransportIdDecoder {

    // splits "tid1:pids,tid2:pids" into display lines, one per transport
    fun decodeTidCsv(tidcsv: String): List<String> {
        if (tidcsv.isBlank()) return emptyList()
        return tidcsv.split(",").filter { it.isNotBlank() }.map { token ->
            val parts = token.split(":")
            val transport = decodeTransport(parts[0])
            val pids = parts.drop(1).filter { it.isNotBlank() }
            if (pids.isEmpty()) {
                transport
            } else {
                "$transport (dns via: ${pids.joinToString(", ") { decodeProxyId(it) }})"
            }
        }
    }

    fun decodeProxyCsv(pidcsv: String): List<String> {
        if (pidcsv.isBlank()) return emptyList()
        return pidcsv.split(":").filter { it.isNotBlank() }.map { decodeProxyId(it) }
    }

    // single transport token, e.g. "CT+Preferred", "wg1", "RPNWIN<..>", "System"
    fun decodeTransport(rawId: String): String {
        val id = rawId.removePrefix(Backend.CT)
        val cacheSuffix = if (rawId.startsWith(Backend.CT)) " (cached)" else ""
        val base = when {
            id == Backend.Preferred -> "Rethink DNS (preferred resolver)"
            id == Backend.Plus -> "Rethink Plus / Smart DNS"
            id == Backend.BlockFree -> "Block-free resolver (bypasses blocklists)"
            id == Backend.BlockAll -> "Blocked (block-all)"
            id == Backend.Block -> "Blocked"
            id == Backend.System -> "System DNS"
            id == Backend.Default -> "Auto (default resolver)"
            id == Backend.Fixed -> "Fixed-IP synthesis (split DNS)"
            id == Backend.Goos -> "GoOS resolver"
            id == Backend.Bootstrap -> "Bootstrap resolver"
            id.startsWith(ProxyManager.ID_WG_BASE, ignoreCase = true) -> "WireGuard tunnel DNS ($id)"
            id.startsWith(Backend.RpnWin, ignoreCase = true) -> "Rethink Plus proxy DNS (WIN)"
            else -> id
        }
        return base + cacheSuffix
    }

    // single proxy token, e.g. "Base", "wg1", "S5:Base", "ORBOT"
    fun decodeProxyId(rawId: String): String {
        val id = rawId.split(":").firstOrNull().orEmpty()
        return when {
            id == Backend.Base -> "direct (no proxy)"
            id.startsWith(ProxyManager.ID_WG_BASE, ignoreCase = true) -> "WireGuard tunnel ($id)"
            id.startsWith(Backend.RpnWin, ignoreCase = true) -> "Rethink Plus proxy (WIN)"
            id.startsWith(ProxyManager.ID_S5_BASE, ignoreCase = true) -> "SOCKS5 proxy"
            id.startsWith(ProxyManager.ID_HTTP_BASE, ignoreCase = true) -> "HTTP proxy"
            id.startsWith(ProxyManager.ID_ORBOT_BASE, ignoreCase = true) -> "Orbot"
            id == ProxyManager.ID_NONE -> "system (no proxy)"
            else -> id
        }
    }
}
