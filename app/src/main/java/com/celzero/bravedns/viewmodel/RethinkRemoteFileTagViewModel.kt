/*
 * Copyright 2022 RethinkDNS and its authors
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

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.liveData
import com.celzero.bravedns.data.FileTag
import com.celzero.bravedns.database.RethinkRemoteFileTagDao
import com.celzero.bravedns.ui.RethinkBlocklistFragment
import com.celzero.bravedns.util.Constants.Companion.LIVEDATA_PAGE_SIZE

class RethinkRemoteFileTagViewModel(private val rethinkRemoteDao: RethinkRemoteFileTagDao) :
        ViewModel() {

    private var list: MutableLiveData<String> = MutableLiveData()
    private var blocklistFilter: RethinkBlocklistFragment.Filters? = null

    init {
        list.value = ""
    }

    val remoteFileTags = Transformations.switchMap(list, ({ input: String ->
        if (blocklistFilter != null) {
            val query = blocklistFilter?.query ?: "%%"
            val groups = blocklistFilter?.groups ?: mutableSetOf()
            val subg = blocklistFilter?.subGroups ?: mutableSetOf()

            if (groups.isNotEmpty() && subg.isNotEmpty()) {
                Pager(PagingConfig(LIVEDATA_PAGE_SIZE)) {
                    rethinkRemoteDao.getRemoteFileTags(query, groups, subg)
                }.liveData.cachedIn(viewModelScope)
            } else if (groups.isNotEmpty()) {
                Pager(PagingConfig(LIVEDATA_PAGE_SIZE)) {
                    rethinkRemoteDao.getRemoteFileTagsGroup(query, groups)
                }.liveData.cachedIn(viewModelScope)
            } else if (subg.isNotEmpty()) {
                Pager(PagingConfig(LIVEDATA_PAGE_SIZE)) {
                    rethinkRemoteDao.getRemoteFileTagsSubg(query, subg)
                }.liveData.cachedIn(viewModelScope)
            } else {
                Pager(PagingConfig(LIVEDATA_PAGE_SIZE)) {
                    rethinkRemoteDao.getRemoteFileTagsWithFilter(query)
                }.liveData.cachedIn(viewModelScope)
            }
        } else if (input.isBlank()) {
            Pager(PagingConfig(LIVEDATA_PAGE_SIZE)) {
                rethinkRemoteDao.getRemoteFileTags()
            }.liveData.cachedIn(viewModelScope)
        } else {
            Pager(PagingConfig(LIVEDATA_PAGE_SIZE)) {
                rethinkRemoteDao.getRemoteFileTagsWithFilter("%$input%")
            }.liveData.cachedIn(viewModelScope)
        }
    }))

    suspend fun allFileTags(): List<FileTag> {
        return rethinkRemoteDao.getAllTags()
    }

    fun setFilter(searchText: String = "") {
        list.value = searchText
    }

    fun setFilter(filter: RethinkBlocklistFragment.Filters) {
        this.blocklistFilter = filter
        list.value = filter.query
    }

}
