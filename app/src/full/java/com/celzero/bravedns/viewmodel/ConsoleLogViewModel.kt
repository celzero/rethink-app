/*
 * Copyright 2024 RethinkDNS and its authors
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
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.liveData
import com.celzero.bravedns.database.ConsoleLog
import com.celzero.bravedns.database.ConsoleLogDAO
import com.celzero.bravedns.util.Constants

class ConsoleLogViewModel(private val consoleLogDAO: ConsoleLogDAO) : ViewModel() {
    private var filter: MutableLiveData<String> = MutableLiveData()

    init {
        filter.value = ""
    }

    val logs = filter.switchMap { input: String -> getLogs(input) }

    private fun getLogs(filter: String): LiveData<PagingData<ConsoleLog>> {
        // filter is unused for now
        return Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) { consoleLogDAO.getLogs() }
            .liveData
            .cachedIn(viewModelScope)
    }

    suspend fun sinceTime(): Long {
        return consoleLogDAO.sinceTime()
    }
}
