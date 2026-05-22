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
package com.celzero.bravedns.data

/**
 * model representing a DNS endpoint from any type (Default, Rethink, DoH, DoT, DNSCrypt,
 * ODoH, DNS Proxy) for use in the Block-Free DNS selector UI.
 *
 * The [key] encodes both the type and identifier: "TYPE::url"
 * e.g. "DOH::https://dns.google/dns-query" or "RETHINK::https://basic.rethinkdns.com/"
 */
data class BlockFreeDnsItem(
    /** Composite key stored in PersistentState: "TYPE::url_or_identifier" */
    val key: String,
    val type: BlockFreeDnsType,
    val name: String,
    val url: String
)

enum class BlockFreeDnsType(val label: String, val prefix: String) {
    RETHINK("Rethink", "RETHINK"),
    DOH("DoH", "DOH"),
    DOT("DoT", "DOT"),
    DNSCRYPT("DNSCrypt", "DNSCRYPT"),
    ODOH("ODoH", "ODOH"),
    DNS_PROXY("DNS Proxy", "DNS_PROXY"),
    SYSTEM("System", "SYSTEM");

    companion object {
        fun fromKey(key: String): BlockFreeDnsType? {
            if (key.isEmpty()) return null
            val prefix = key.substringBefore("::")
            return entries.find { it.prefix == prefix }
        }

        fun urlFromKey(key: String): String = key.substringAfter("::", "")

        fun buildKey(type: BlockFreeDnsType, url: String): String = "${type.prefix}::$url"
    }
}
