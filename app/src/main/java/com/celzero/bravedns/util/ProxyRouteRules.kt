/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.util

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * fork: destination rules for the custom socks5 / http proxies, the counterpart of a
 * WireGuard config's "allowed ips". One rule per line (or comma / space separated):
 *  - an ip or cidr, ex: 100.64.0.0/10, 10.0.0.5, fd7a:115c:a1e0::/48
 *  - a domain suffix, ex: example.com, *.ts.net, .onion
 * lines starting with # are ignored.
 */
object ProxyRouteRules {
    private class Cidr(val addr: ByteArray, val prefix: Int)

    private class Parsed(val cidrs: List<Cidr>, val domains: List<String>) {
        val size: Int
            get() = cidrs.size + domains.size
    }

    private val cache = ConcurrentHashMap<String, Parsed>()

    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val ipv6 = Regex("^[0-9a-fA-F:.]+$")

    /** Number of valid rules in [rules]. */
    fun count(rules: String): Int {
        if (rules.isBlank()) return 0
        return parsed(rules).size
    }

    /** True if the destination [ip] or [domain] matches any rule in [rules]. */
    fun matches(rules: String, ip: String?, domain: String?): Boolean {
        if (rules.isBlank()) return false
        val p = parsed(rules)
        if (p.size == 0) return false

        if (!ip.isNullOrBlank() && p.cidrs.isNotEmpty()) {
            val addr = literal(ip.trim())?.address
            if (addr != null && p.cidrs.any { contains(it, addr) }) return true
        }

        if (!domain.isNullOrBlank() && p.domains.isNotEmpty()) {
            val d = domain.trim().trimEnd('.').lowercase()
            if (d.isNotEmpty() && p.domains.any { d == it || d.endsWith(".$it") }) return true
        }
        return false
    }

    private fun parsed(rules: String): Parsed {
        if (cache.size > 16) cache.clear()
        return cache.getOrPut(rules) { parse(rules) }
    }

    private fun parse(rules: String): Parsed {
        val cidrs = mutableListOf<Cidr>()
        val domains = mutableListOf<String>()
        rules.lines().forEach { line ->
            if (line.trimStart().startsWith("#")) return@forEach
            line.split(',', ' ', '\t', ';').forEach tokens@{ raw ->
                val t = raw.trim()
                if (t.isEmpty()) return@tokens

                val ipPart = t.substringBefore('/')
                val addr = literal(ipPart)?.address
                if (addr != null) {
                    val max = addr.size * 8
                    val prefix =
                        if (t.contains('/')) {
                            t.substringAfter('/').toIntOrNull() ?: return@tokens
                        } else {
                            max
                        }
                    if (prefix !in 0..max) return@tokens
                    cidrs.add(Cidr(addr, prefix))
                    return@tokens
                }

                val d = t.removePrefix("*.").removePrefix(".").trimEnd('.').lowercase()
                if (d.isNotEmpty() && !d.contains('/') && !d.contains(':')) domains.add(d)
            }
        }
        return Parsed(cidrs, domains)
    }

    // only parse ip literals; InetAddress.getByName() on a hostname would do a dns lookup
    private fun literal(s: String): InetAddress? {
        val isV4 = ipv4.matches(s)
        val isV6 = s.contains(':') && ipv6.matches(s)
        if (!isV4 && !isV6) return null
        return try {
            InetAddress.getByName(s)
        } catch (_: Exception) {
            null
        }
    }

    private fun contains(c: Cidr, addr: ByteArray): Boolean {
        if (c.addr.size != addr.size) return false
        var bits = c.prefix
        var i = 0
        while (bits > 0 && i < addr.size) {
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((c.addr[i].toInt() and mask) != (addr[i].toInt() and mask)) return false
            bits -= 8
            i++
        }
        return true
    }
}
