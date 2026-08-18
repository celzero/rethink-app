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

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celzero.bravedns.data.BlockFreeDnsItem
import com.celzero.bravedns.data.BlockFreeDnsType
import com.celzero.bravedns.database.DnsCryptEndpointDAO
import com.celzero.bravedns.database.DnsProxyEndpointDAO
import com.celzero.bravedns.database.DoHEndpointDAO
import com.celzero.bravedns.database.DoTEndpointDAO
import com.celzero.bravedns.database.ODoHEndpointDAO
import com.celzero.bravedns.database.RethinkDnsEndpointDao
import com.celzero.bravedns.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BlockFreeDnsViewModel(
    private val rethinkDao: RethinkDnsEndpointDao,
    private val dohDao: DoHEndpointDAO,
    private val dotDao: DoTEndpointDAO,
    private val dnsCryptDao: DnsCryptEndpointDAO,
    private val odohDao: ODoHEndpointDAO,
    private val dnsProxyDao: DnsProxyEndpointDAO
) : ViewModel() {

    private val _allItems = MutableLiveData<List<BlockFreeDnsItem>>()
    val allItems: LiveData<List<BlockFreeDnsItem>> = _allItems

    /** Current filter type; null means show all */
    var activeFilter: BlockFreeDnsType? = null
        private set

    val filteredItems: LiveData<List<BlockFreeDnsItem>> get() = _filteredItems
    private val _filteredItems = MutableLiveData<List<BlockFreeDnsItem>>()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            val items = mutableSetOf<BlockFreeDnsItem>()

            // TODO: missing Rethink's DOT option which need to be included
            // 0. Load default DNS options
            Constants.DEFAULT_DNS_LIST.forEach {
                val type = when (it.type.uppercase()) {
                    "RETHINK" -> BlockFreeDnsType.RETHINK
                    "DOH" -> BlockFreeDnsType.DOH
                    "DOT" -> BlockFreeDnsType.DOT
                    "DNSCRYPT" -> BlockFreeDnsType.DNSCRYPT
                    "ODOH" -> BlockFreeDnsType.ODOH
                    "DNS_PROXY" -> BlockFreeDnsType.DNS_PROXY
                    "SYSTEM" -> BlockFreeDnsType.SYSTEM
                    "NONE" -> BlockFreeDnsType.SYSTEM // Treat "NONE" as "SYSTEM" for backward compatibility
                    else -> null
                } ?: return@forEach

                items.add(
                    BlockFreeDnsItem(
                        key = BlockFreeDnsType.buildKey(type, it.url),
                        type = type,
                        name = it.name,
                        url = it.url
                    )
                )
            }

            // 1. Rethink
            rethinkDao.getAllForBlockFree().forEach { ep ->
                items.add(
                    BlockFreeDnsItem(
                        key = BlockFreeDnsType.buildKey(BlockFreeDnsType.RETHINK, ep.url),
                        type = BlockFreeDnsType.RETHINK,
                        name = ep.name,
                        url = ep.url
                    )
                )
            }

            // 2. DoH
            dohDao.getAll().forEach { ep ->
                items.add(
                    BlockFreeDnsItem(
                        key = BlockFreeDnsType.buildKey(BlockFreeDnsType.DOH, ep.dohURL),
                        type = BlockFreeDnsType.DOH,
                        name = ep.dohName,
                        url = ep.dohURL
                    )
                )
            }

            // 3. DoT
            dotDao.getAll().forEach { ep ->
                items.add(
                    BlockFreeDnsItem(
                        key = BlockFreeDnsType.buildKey(BlockFreeDnsType.DOT, ep.url),
                        type = BlockFreeDnsType.DOT,
                        name = ep.name,
                        url = ep.url
                    )
                )
            }

            // 4. DNSCrypt
            dnsCryptDao.getAll().forEach { ep ->
                items.add(
                    BlockFreeDnsItem(
                        key = BlockFreeDnsType.buildKey(BlockFreeDnsType.DNSCRYPT, ep.dnsCryptURL),
                        type = BlockFreeDnsType.DNSCRYPT,
                        name = ep.dnsCryptName,
                        url = ep.dnsCryptURL
                    )
                )
            }

            // 5. ODoH
            odohDao.getAll().forEach { ep ->
                items.add(
                    BlockFreeDnsItem(
                        key = BlockFreeDnsType.buildKey(BlockFreeDnsType.ODOH, ep.proxy),
                        type = BlockFreeDnsType.ODOH,
                        name = ep.name,
                        url = ep.resolver
                    )
                )
            }

            // 6. DNS Proxy
            dnsProxyDao.getAll().forEach { ep ->
                val identifier = "${ep.proxyIP}:${ep.proxyPort}"
                items.add(
                    BlockFreeDnsItem(
                        key = BlockFreeDnsType.buildKey(BlockFreeDnsType.DNS_PROXY, identifier),
                        type = BlockFreeDnsType.DNS_PROXY,
                        name = ep.proxyName,
                        url = identifier
                    )
                )
            }

            _allItems.postValue(items.toList())
            applyFilter(activeFilter, items.toList())
        }
    }

    fun setFilter(type: BlockFreeDnsType?) {
        activeFilter = type
        val current = _allItems.value ?: return
        applyFilter(type, current)
    }

    private fun applyFilter(type: BlockFreeDnsType?, items: List<BlockFreeDnsItem>) {
        val filtered = if (type == null) items else items.filter { it.type == type }
        _filteredItems.postValue(filtered)
    }
}
