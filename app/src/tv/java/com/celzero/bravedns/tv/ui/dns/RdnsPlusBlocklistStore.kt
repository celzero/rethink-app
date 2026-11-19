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

import android.content.Context
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.RemoteBlocklistPacksMap
import com.celzero.bravedns.database.RemoteBlocklistPacksMapDao
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.service.RethinkBlocklistManager.RethinkBlocklistType
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Data access for the TV RDNS Plus blocklist screen: pack reads,
 * selection toggles and the live stamp apply. Mirrors the phone's
 * RethinkBlocklistFragment + RethinkBlocklistViewModel flow minus the
 * Apply/Cancel step — every selection change is applied directly.
 */
internal object RdnsPlusBlocklistStore : KoinComponent {

    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()
    private val appDownloadManager by inject<AppDownloadManager>()
    private val remoteBlocklistPacksMapDao by inject<RemoteBlocklistPacksMapDao>()
    private val applicationContext by inject<Context>()

    fun hasBlocklists(): Boolean {
        return Utilities.hasRemoteBlocklists(applicationContext, persistentState.remoteBlocklistTimestamp)
    }

    fun isDownloadActive(): Boolean {
        return appDownloadManager.isDownloadWorkActive(RethinkBlocklistManager.DownloadType.REMOTE)
    }

    suspend fun startDownload(): Boolean {
        return appDownloadManager.downloadRemoteBlocklist(
            persistentState.remoteBlocklistTimestamp,
            isRedownload = true
        )
    }

    fun cancelDownload() {
        appDownloadManager.cancelDownload(RethinkBlocklistManager.DownloadType.REMOTE)
    }

    suspend fun packs(): List<RemoteBlocklistPacksMap> {
        return remoteBlocklistPacksMapDao.getTagsList()
    }

    // Entry-point selection: decoded from the RDNS Plus endpoint URL stamp,
    // not the DB flags (which only sync once the user toggles).
    suspend fun initialTags(): Set<Int> {
        val endpoint = appConfig.getRethinkPlusEndpoint() ?: return emptySet()
        val stamp = Utilities.getRemoteBlocklistStamp(endpoint.url)
        return RethinkBlocklistManager.getTagsFromStamp(stamp, RethinkBlocklistType.REMOTE)
    }

    // Persists the pack's tags, then returns the re-read selection set
    // (DB is the source of truth after the first toggle, same as phone).
    suspend fun toggle(tagIds: List<Int>, selected: Boolean): Set<Int> {
        RethinkBlocklistManager.updateFiletagsRemote(tagIds.toSet(), if (selected) 1 else 0)
        return RethinkBlocklistManager.getSelectedFileTagsRemote().toSet()
    }

    // Recomputes the stamp from the selection and writes it onto the
    // RDNS Plus endpoint, activating it as the connected resolver.
    // Returns false when the stamp cannot be encoded (RDNS/Go resolver
    // unavailable, e.g. VPN stopped); the selection stays persisted and a
    // later apply succeeds. An empty selection yields the bare base URL.
    suspend fun apply(tags: Set<Int>): Boolean {
        val endpoint = appConfig.getRethinkPlusEndpoint() ?: return false
        val stamp = RethinkBlocklistManager.getStamp(tags, RethinkBlocklistType.REMOTE)
        if (stamp.isEmpty() && tags.isNotEmpty()) return false

        val baseUrl = if (endpoint.url.contains(Constants.MAX_ENDPOINT)) {
            Constants.RETHINK_BASE_URL_MAX
        } else {
            Constants.RETHINK_BASE_URL_SKY
        }
        val count = RethinkBlocklistManager.getTagsFromStamp(stamp, RethinkBlocklistType.REMOTE).size
        appConfig.updateRethinkEndpoint(Constants.RETHINK_DNS_PLUS, baseUrl + stamp, count)
        appConfig.enableRethinkDnsPlus()
        return true
    }
}
