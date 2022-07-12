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
import androidx.paging.toLiveData
import com.celzero.bravedns.data.FileTag
import com.celzero.bravedns.database.RethinkLocalFileTagDao
import com.celzero.bravedns.ui.RethinkBlocklistFragment
import com.celzero.bravedns.util.Constants.Companion.LIVEDATA_PAGE_SIZE

class RethinkLocalFileTagViewModel(private val rethinkLocalDao: RethinkLocalFileTagDao) :
        ViewModel() {

    private var list: MutableLiveData<String> = MutableLiveData()
    private var blocklistFilter: RethinkBlocklistFragment.Filters? = null

    init {
        list.value = ""
    }

    val localFiletags = Transformations.switchMap(list, ({ input: String ->
        if (blocklistFilter != null) {
            val query = blocklistFilter?.query ?: "%%"
            val groups = blocklistFilter?.groups ?: mutableSetOf()
            val subg = blocklistFilter?.subGroups ?: mutableSetOf()

            if (groups.isNotEmpty() && subg.isNotEmpty()) {
                rethinkLocalDao.getLocalFileTags(query, groups, subg).toLiveData(
                    pageSize = LIVEDATA_PAGE_SIZE)
            } else if (groups.isNotEmpty()) {
                rethinkLocalDao.getLocalFileTagsGroup(query, groups).toLiveData(
                    pageSize = LIVEDATA_PAGE_SIZE)
            } else if (subg.isNotEmpty()) {
                rethinkLocalDao.getLocalFileTagsSubg(query, subg).toLiveData(
                    pageSize = LIVEDATA_PAGE_SIZE)
            } else {
                rethinkLocalDao.getLocalFileTagsWithFilter(query).toLiveData(
                    pageSize = LIVEDATA_PAGE_SIZE)
            }
        } else if (input.isBlank()) {
            rethinkLocalDao.getLocalFileTags().toLiveData(pageSize = LIVEDATA_PAGE_SIZE)
        } else {
            rethinkLocalDao.getLocalFileTagsWithFilter("%$input%").toLiveData(
                pageSize = LIVEDATA_PAGE_SIZE)
        }

        if (input.isBlank()) {
            rethinkLocalDao.getLocalFileTags().toLiveData(pageSize = LIVEDATA_PAGE_SIZE)
        } else {
            rethinkLocalDao.getLocalFileTagsWithFilter("%$input%").toLiveData(
                pageSize = LIVEDATA_PAGE_SIZE)
        }
    }))

    suspend fun allFileTags(): List<FileTag> {
        return rethinkLocalDao.getAllTags()
    }

    fun setFilter(searchText: String = "") {
        list.value = searchText
    }

    fun setFilter(filter: RethinkBlocklistFragment.Filters) {
        this.blocklistFilter = filter
        list.value = filter.query
    }


}
