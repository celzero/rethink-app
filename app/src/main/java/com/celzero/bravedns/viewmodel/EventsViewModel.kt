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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.celzero.bravedns.database.Event
import com.celzero.bravedns.database.EventDao
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EventsViewModel(private val eventDao: EventDao) : ViewModel() {

    private val _filterQuery = MutableStateFlow("")
    private val _filterSeverity = MutableStateFlow<Severity?>(null)
    private val _filterSources = MutableStateFlow<Set<EventSource>>(emptySet())

    companion object {
        private const val PAGE_SIZE = 50
    }

    enum class TopLevelFilter(val id: Int) {
        ALL(0),
        SEVERITY(1),
        SOURCE(2)
    }

    private var filterType: TopLevelFilter = TopLevelFilter.ALL

    val eventsFlow: kotlinx.coroutines.flow.Flow<PagingData<Event>> =
        kotlinx.coroutines.flow.combine(
            _filterQuery,
            _filterSeverity,
            _filterSources
        ) { query, severity, sources ->
            Triple(query, severity, sources)
        }.flatMapLatest { (query, severity, sources) ->
            getEventsPagingData(query, severity, sources)
        }.cachedIn(viewModelScope)

    private fun getEventsPagingData(
        query: String,
        severity: Severity?,
        sources: Set<EventSource>
    ): kotlinx.coroutines.flow.Flow<PagingData<Event>> {
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
        ).flow
    }

    fun setFilter(query: String, sources: Set<EventSource>, severity: Severity?) {
        _filterSources.value = sources
        _filterSeverity.value = severity
        _filterQuery.value = query
    }

    fun setFilterType(type: TopLevelFilter) {
        filterType = type
    }

    fun getFilterType(): TopLevelFilter {
        return filterType
    }

    fun getCurrentSeverity(): Severity? {
        return _filterSeverity.value
    }

    fun getCurrentSources(): Set<EventSource> {
        return _filterSources.value
    }

    fun getCurrentQuery(): String {
        return _filterQuery.value
    }
}

