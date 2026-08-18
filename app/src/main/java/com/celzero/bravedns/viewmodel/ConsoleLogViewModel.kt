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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.celzero.bravedns.database.ConsoleLog
import com.celzero.bravedns.database.ConsoleLogDAO
import com.celzero.bravedns.util.Constants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest

class ConsoleLogViewModel(private val dao: ConsoleLogDAO) : ViewModel() {

    private data class QueryParams(
        val filter: String = "",
        val minLevel: Int = 0,
        val sessionId: Long = 0L,
    )

    private val queryParams = MutableStateFlow(QueryParams())

    private val pagingConfig = PagingConfig(
        pageSize = Constants.LIVEDATA_PAGE_SIZE,
        enablePlaceholders = false,
        prefetchDistance = 10
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val logs: LiveData<PagingData<ConsoleLog>> = queryParams
        .flatMapLatest { params ->
            Pager(pagingConfig) {
                dao.getLogs("%${params.filter}%", params.minLevel)
            }.flow
        }
        .cachedIn(viewModelScope)
        .asLiveData()

    suspend fun sinceTime(): Long {
        return try {
            dao.sinceTime()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err getting since time: ${e.message}")
            0L
        }
    }

    fun setLogLevel(level: Long) {
        queryParams.value = queryParams.value.copy(minLevel = level.toInt())
    }

    fun setFilter(filter: String) {
        queryParams.value = queryParams.value.copy(filter = filter)
    }

    fun restartLogStream() {
        queryParams.value = queryParams.value.copy(sessionId = queryParams.value.sessionId + 1)
    }
}
