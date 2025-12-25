/*
 * Copyright 2025 RethinkDNS and its authors
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
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.liveData
import com.celzero.bravedns.database.Event
import com.celzero.bravedns.database.EventDao
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.Severity

class EventsViewModel(private val eventDao: EventDao) : ViewModel() {

    private var filteredQuery = MutableLiveData<String>()
    private var filteredSeverity = MutableLiveData<Severity?>()
    private var filteredSources = MutableLiveData<Set<EventSource>>()

    companion object {
        private const val PAGE_SIZE = 50
    }

    enum class TopLevelFilter(val id: Int) {
        ALL(0),
        SEVERITY(1),
        SOURCE(2)
    }

    private var filterType: TopLevelFilter = TopLevelFilter.ALL

    init {
        filteredQuery.value = ""
        filteredSeverity.value = null
        filteredSources.value = emptySet()
    }

    val eventsList: LiveData<PagingData<Event>> =
        filteredQuery.switchMap { query ->
            filteredSeverity.switchMap { severity ->
                filteredSources.switchMap { sources ->
                    getEventsLiveData(query, severity, sources)
                }
            }
        }

    private fun getEventsLiveData(
        query: String,
        severity: Severity?,
        sources: Set<EventSource>
    ): LiveData<PagingData<Event>> {
        return Pager<Int, Event>(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                enablePlaceholders = false,
                maxSize = PAGE_SIZE * 3
            ),
            pagingSourceFactory = {
                when {
                    severity != null -> {
                        if (query.isEmpty()) {
                            eventDao.getEventsBySeverityPaged(severity)
                        } else {
                            eventDao.getEventsBySeverityAndSearchPaged(severity, "%$query%")
                        }
                    }

                    sources.isNotEmpty() -> {
                        if (query.isEmpty()) {
                            eventDao.getEventsBySourcesPaged(sources.toList())
                        } else {
                            eventDao.getEventsBySourcesAndSearchPaged(sources.toList(), "%$query%")
                        }
                    }

                    query.isNotEmpty() -> {
                        eventDao.getEventsBySearchPaged("%$query%")
                    }

                    else -> {
                        eventDao.getAllEventsPaged()
                    }
                }
            }
        ).liveData
    }

    fun setFilter(query: String, sources: Set<EventSource>, severity: Severity?) {
        filteredSources.value = sources
        filteredSeverity.value = severity
        filteredQuery.value = "%$query%"
    }

    fun setFilterType(type: TopLevelFilter) {
        filterType = type
    }

    fun getFilterType(): TopLevelFilter {
        return filterType
    }

    fun getCurrentSeverity(): Severity? {
        return filteredSeverity.value
    }

    fun getCurrentSources(): Set<EventSource> {
        return filteredSources.value ?: emptySet()
    }

    fun getCurrentQuery(): String {
        return filteredQuery.value ?: ""
    }
}

