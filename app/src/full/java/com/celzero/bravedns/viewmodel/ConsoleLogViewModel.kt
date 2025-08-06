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

import Logger
import Logger.LOG_TAG_UI
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ConsoleLogViewModel(private val dao: ConsoleLogDAO) : ViewModel() {
    private var filter: MutableLiveData<String> = MutableLiveData()
    private var logLevel: Long = Logger.LoggerLevel.ERROR.id
    init {
        filter.postValue("")
    }

    val logs = filter.switchMap { input: String -> getLogs(input) }

    private fun getLogs(filter: String): LiveData<PagingData<ConsoleLog>> {
        val query = "%$filter%"
        return Pager(pagingConfig) { dao.getLogs(query) }
            .liveData
            .cachedIn(viewModelScope)
    }
    
    val pagingConfig = PagingConfig(
        pageSize = Constants.LIVEDATA_PAGE_SIZE,
        enablePlaceholders = false,  // Prevents position issues
        prefetchDistance = 10
    )

    suspend fun sinceTime(): Long {
        return try {
            dao.sinceTime()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err getting since time: ${e.message}")
            0L
        }
    }

    fun setLogLevel(level: Long) {
        logLevel = level
    }

    fun setFilter(filter: String) {
        viewModelScope.launch {
            delay(100) // Prevent rapid updates
            this@ConsoleLogViewModel.filter.postValue(filter)
        }
    }
}
