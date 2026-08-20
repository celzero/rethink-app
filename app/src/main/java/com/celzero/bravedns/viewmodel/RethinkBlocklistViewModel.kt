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
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

class RethinkBlocklistViewModel(
    private val persistentState: PersistentState,
    private val appConfig: AppConfig
) : ViewModel() {

    enum class BlocklistSelectionFilter(val id: Int) {
        ALL(0),
        SELECTED(1)
    }

    class Filters {
        var query: String = "%%"
        var filterSelected: BlocklistSelectionFilter = BlocklistSelectionFilter.ALL
        var subGroups: MutableSet<String> = mutableSetOf()
    }

    private var type: RethinkBlocklistManager.RethinkBlocklistType =
        RethinkBlocklistManager.RethinkBlocklistType.REMOTE
    private var remoteName: String = ""
    private var remoteUrl: String = ""

    val filters = MutableLiveData<Filters>()

    private val _selectedFileTags = MutableLiveData<Set<Int>>()
    val selectedFileTags: LiveData<Set<Int>> get() = _selectedFileTags

    var modifiedStamp: String = ""
        private set

    // base64 stamp: appears as a URL path segment, e.g. https://max.rethinkdns.com/1:IAAgAA==
    // anchored with / before and end-of-string/. / ? / # after so it can't match mid-path
    private val base64StampRegex =
        Pattern.compile("/1:([A-Za-z0-9+/=]+)(?:[.?#]|$)")

    // base32 stamp: appears as a subdomain prefix, e.g. //1-acaabaa.max.rethinkdns.com
    // or 1-acaabaa.max.rethinkdns.com (bare); an optional subdomain (max/sky) sits
    // between the stamp and .rethinkdns.com
    private val base32StampRegex =
        Pattern.compile("(?:^|//)1-([a-z2-7]+)(?:\\.[^/]+)?\\.rethinkdns\\.com")

    fun configure(type: RethinkBlocklistManager.RethinkBlocklistType, name: String, url: String) {
        this.type = type
        this.remoteName = name
        this.remoteUrl = url
        this.modifiedStamp = getInitialStamp()
        viewModelScope.launch(Dispatchers.IO) {
            val tags = RethinkBlocklistManager.getTagsFromStamp(modifiedStamp, type)
            updateSelectedFileTags(tags.toMutableSet())
        }
    }

    private fun getInitialStamp(): String {
        return if (type.isLocal()) {
            persistentState.localBlocklistStamp
        } else {
            Utilities.getRemoteBlocklistStamp(remoteUrl)
        }
    }

    fun isLocal() = type.isLocal()
    fun isRemote() = type.isRemote()
    fun getType() = type

    fun updateSelectedFileTags(tags: Set<Int>) {
        if (_selectedFileTags.value == tags) return

        _selectedFileTags.postValue(tags)
        viewModelScope.launch(Dispatchers.IO) {
            val recomputed = RethinkBlocklistManager.getStamp(tags, type)
            if (recomputed.isNotEmpty()) {
                modifiedStamp = recomputed
            } else if (tags.isNotEmpty()) {
                // RDNS unavailable (e.g. Remote DNS configured while the VPN
                // is stopped). Keep the previously known good stamp instead
                // of overwriting it with "" and losing the user's selection.
                // The authoritative selection set is `selectedFileTags`; the
                // stamp is recomputed at Apply time (see setStamp/Apply).
                Logger.w(
                    LOG_TAG_UI,
                    "skip stamp overwrite: ${tags.size} tags selected but stamp encode failed for ${type.name}; keeping modifiedStamp='${modifiedStamp.take(32)}'"
                )
            } else {
                // user genuinely cleared the selection
                modifiedStamp = recomputed
            }
        }
    }

    fun isStampChanged(): Boolean {
        if (Constants.DEFAULT_RDNS_REMOTE_DNS_NAMES.contains(remoteName)) {
            return false
        }
        return getInitialStamp() != modifiedStamp
    }

    suspend fun updateFileTagsInBackingStore(selectedTags: Set<Int>) {
        withContext(Dispatchers.IO) {
            if (selectedTags.isEmpty()) {
                if (type.isLocal()) {
                    RethinkBlocklistManager.clearTagsSelectionLocal()
                } else {
                    RethinkBlocklistManager.clearTagsSelectionRemote()
                }
                return@withContext
            }

            if (type.isLocal()) {
                RethinkBlocklistManager.clearTagsSelectionLocal()
                RethinkBlocklistManager.updateFiletagsLocal(selectedTags, 1)
                val list = RethinkBlocklistManager.getSelectedFileTagsLocal().toSet()
                // guard: never replace a non-empty selection with an empty read
                // (happens when the stamp contained a phantom "0" or when RDNS was
                // unavailable while decoding). Keep the caller's selection intact.
                _selectedFileTags.postValue(if (list.isEmpty()) selectedTags else list)
            } else {
                RethinkBlocklistManager.clearTagsSelectionRemote()
                RethinkBlocklistManager.updateFiletagsRemote(selectedTags, 1)
                val list = RethinkBlocklistManager.getSelectedFileTagsRemote().toSet()
                _selectedFileTags.postValue(if (list.isEmpty()) selectedTags else list)
            }
        }
    }

    fun applyStamp() {
        viewModelScope.launch(Dispatchers.IO) {
            // update rethink stamp. Recompute from the authoritative selection
            // set so that an empty `modifiedStamp` (caused by RDNS being briefly
            // unavailable while toggling) does not discard the user's selections.
            val tags = selectedFileTags.value ?: emptySet()
            val stampToApply = if (modifiedStamp.isNotEmpty()) modifiedStamp
            else RethinkBlocklistManager.getStamp(tags, type).also { modifiedStamp = it }

            if (stampToApply.isEmpty() && tags.isNotEmpty()) {
                Logger.w(LOG_TAG_UI, "Apply: cannot encode ${tags.size} selected tags for ${type.name}; stamp stays empty")
            }
            
            setStamp(stampToApply)
        }
    }

    private suspend fun setStamp(stamp: String) {
        withContext(Dispatchers.IO) {
            val blocklistCount = RethinkBlocklistManager.getTagsFromStamp(stamp, type).size
            if (type.isLocal()) {
                persistentState.localBlocklistStamp = stamp
                persistentState.numberOfLocalBlocklists = blocklistCount
                persistentState.blocklistEnabled = true
            } else {
                appConfig.updateRethinkEndpoint(
                    Constants.RETHINK_DNS_PLUS,
                    getRemoteUrlWithStamp(stamp),
                    blocklistCount
                )
                appConfig.enableRethinkDnsPlus()
            }
        }
    }

    private fun getRemoteUrlWithStamp(stamp: String): String {
        return if (remoteUrl.contains(Constants.MAX_ENDPOINT)) {
            Constants.RETHINK_BASE_URL_MAX + stamp
        } else {
            Constants.RETHINK_BASE_URL_SKY + stamp
        }
    }

    fun revertStamp() {
        viewModelScope.launch(Dispatchers.IO) {
            // Revert to the old stamp for the blocklist type
            val stamp = getInitialStamp()
            val tags = RethinkBlocklistManager.getTagsFromStamp(stamp, type)
            updateFileTagsInBackingStore(tags)
            setStamp(stamp)
            Logger.i(LOG_TAG_UI, "revert to old stamp for blocklist type: ${type.name}, $stamp, $tags")
        }
    }

    fun extractStamp(t: String): String? {
        // format 1: https://max.rethinkdns.com/1:IAAgAA== (base64 after ":")
        // format 2: //1-acaabaa.max.rethinkdns.com (base32 after "-")
        val base64Match = base64StampRegex.matcher(t)
        if (base64Match.find()) {
            return "${Constants.RETHINK_STAMP_VERSION}:${base64Match.group(1)}"
        }

        val base32Match = base32StampRegex.matcher(t)
        if (base32Match.find()) {
            return "${Constants.RETHINK_STAMP_VERSION}-${base32Match.group(1)}"
        }
        return null
    }

    fun isRethinkStampSearch(t: String): Boolean {
        // do not proceed if rethinkdns.com is not available
        return t.contains(Constants.RETHINKDNS_DOMAIN)
    }

    fun restoreStamp(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val stamp = extractStamp(query)
            if (stamp != null) {
                val tags = RethinkBlocklistManager.getTagsFromStamp(stamp, type)
                updateSelectedFileTags(tags)
            }
        }
    }

    fun addQueryToFilters(query: String) {
        val currentFilters = filters.value ?: Filters()
        currentFilters.query = "%$query%"
        filters.postValue(currentFilters)
    }

    fun applySelectionFilter(filterId: Int) {
        val currentFilters = filters.value ?: Filters()
        currentFilters.filterSelected = if (filterId == BlocklistSelectionFilter.ALL.id) {
            BlocklistSelectionFilter.ALL
        } else {
            BlocklistSelectionFilter.SELECTED
        }
        filters.postValue(currentFilters)
    }
}
