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
package com.celzero.bravedns.tv.ui.dns

import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.database.DoHEndpointDAO
import com.celzero.bravedns.database.DoTEndpoint
import com.celzero.bravedns.database.DoTEndpointDAO
import com.celzero.bravedns.database.DnsCryptEndpoint
import com.celzero.bravedns.database.DnsCryptEndpointDAO
import com.celzero.bravedns.database.DnsProxyEndpoint
import com.celzero.bravedns.database.DnsProxyEndpointDAO
import com.celzero.bravedns.database.ODoHEndpoint
import com.celzero.bravedns.database.ODoHEndpointDAO
import com.celzero.bravedns.database.RethinkDnsEndpoint
import com.celzero.bravedns.database.RethinkDnsEndpointRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Generic, type-erased row rendered by the DNS picker. Each concrete
 * endpoint type (RDNS / DoH / DoT / DNSCrypt / DNS Proxy / ODoH) is
 * converted to one of these so the row composable is type-blind.
 */
internal data class DnsRow(
    val key: String,
    val name: String,
    val url: String,
    val isSelected: Boolean,
    val isCustom: Boolean,
)

/**
 * DNS protocol tabs — RDNS first, then the encrypted protocols, in
 * the same order as the phone's six-option list. [addRoute] is null
 * for RDNS: its presets are seeded, no custom endpoints.
 */
internal enum class DnsTab(
    val label: String,
    val addRoute: String?,
) {
    RDNS("RDNS", null),
    DOH("DoH", "dns/doh/add"),
    DOT("DoT", "dns/dot/add"),
    DNSCRYPT("DNSCrypt", "dns/dnscrypt/add"),
    DNS_PROXY("DNS Proxy", "dns/proxy/add"),
    ODOH("ODoH", "dns/odoh/add"),
}

/**
 * Single data access point for the DNS picker: per-tab endpoint reads
 * and activation writes, so the screen holds no DAO/repository edges.
 */
internal object DnsEndpointStore : KoinComponent {

    private val appConfig by inject<AppConfig>()
    private val rethinkRepository by inject<RethinkDnsEndpointRepository>()
    private val dohDao by inject<DoHEndpointDAO>()
    private val dotDao by inject<DoTEndpointDAO>()
    private val dnscryptDao by inject<DnsCryptEndpointDAO>()
    private val dnsProxyDao by inject<DnsProxyEndpointDAO>()
    private val odohDao by inject<ODoHEndpointDAO>()

    // Active row first, seeded before custom, then by name.
    suspend fun rows(tab: DnsTab): List<DnsRow> {
        val fetched = when (tab) {
            DnsTab.RDNS -> rethinkRepository.getGlobalRethinkEndpoints()
                .filterNot { it.name == RethinkDnsEndpoint.RETHINK_PLUS }
                .map { it.toRow() }
            DnsTab.DOH -> dohDao.getAll().map { it.toRow() }
            DnsTab.DOT -> dotDao.getAll().map { it.toRow() }
            DnsTab.DNSCRYPT -> dnscryptDao.getAll().map { it.toRow() }
            DnsTab.DNS_PROXY -> dnsProxyDao.getAll().map { it.toRow() }
            DnsTab.ODOH -> odohDao.getAllAsList().map { it.toRow() }
        }
        return fetched.sortedWith(
            compareBy({ !it.isSelected }, { it.isCustom }, { it.name.lowercase() }),
        )
    }

    // No-op for unknown keys (e.g. endpoint uninstalled mid-session).
    suspend fun activate(tab: DnsTab, key: String) {
        when (tab) {
            DnsTab.RDNS -> {
                val name = key.substringAfter('/')
                val ep = rethinkRepository.getGlobalRethinkEndpoints()
                    .firstOrNull { it.name == name } ?: return
                ep.isActive = true
                appConfig.handleRethinkChanges(ep)
            }
            DnsTab.DOH -> {
                val id = key.substringAfter('/').toIntOrNull()
                val ep = dohDao.getAll().firstOrNull { it.id == id } ?: return
                ep.isSelected = true
                appConfig.handleDoHChanges(ep)
            }
            DnsTab.DOT -> {
                val id = key.substringAfter('/').toIntOrNull()
                val ep = dotDao.getAll().firstOrNull { it.id == id } ?: return
                ep.isSelected = true
                appConfig.handleDoTChanges(ep)
            }
            DnsTab.DNSCRYPT -> {
                val id = key.substringAfter('/').toIntOrNull()
                val ep = dnscryptDao.getAll().firstOrNull { it.id == id } ?: return
                ep.isSelected = true
                appConfig.handleDnscryptChanges(ep)
            }
            DnsTab.DNS_PROXY -> {
                val id = key.substringAfter('/').toIntOrNull()
                val ep = dnsProxyDao.getAll().firstOrNull { it.id == id } ?: return
                ep.isSelected = true
                appConfig.handleDnsProxyChanges(ep)
            }
            DnsTab.ODOH -> {
                val id = key.substringAfter('/').toIntOrNull()
                val ep = odohDao.getAllAsList().firstOrNull { it.id == id } ?: return
                ep.isSelected = true
                appConfig.handleODoHChanges(ep)
            }
        }
    }
}

private fun RethinkDnsEndpoint.toRow() = DnsRow(
    key = "rdns/$name",
    name = name,
    url = url,
    isSelected = isActive,
    isCustom = isCustom,
)

private fun DoHEndpoint.toRow() = DnsRow(
    key = "doh/$id",
    name = dohName,
    url = dohURL,
    isSelected = isSelected,
    isCustom = isCustom,
)

private fun DoTEndpoint.toRow() = DnsRow(
    key = "dot/$id",
    name = name,
    url = url,
    isSelected = isSelected,
    isCustom = isCustom,
)

private fun DnsCryptEndpoint.toRow() = DnsRow(
    key = "dnscrypt/$id",
    name = dnsCryptName,
    url = dnsCryptURL,
    isSelected = isSelected,
    isCustom = isCustom,
)

private fun DnsProxyEndpoint.toRow() = DnsRow(
    key = "dnsproxy/$id",
    name = proxyName,
    url = "$proxyIP:$proxyPort",
    isSelected = isSelected,
    isCustom = isCustom,
)

private fun ODoHEndpoint.toRow() = DnsRow(
    key = "odoh/$id",
    name = name,
    url = "",
    isSelected = isSelected,
    isCustom = isCustom,
)
