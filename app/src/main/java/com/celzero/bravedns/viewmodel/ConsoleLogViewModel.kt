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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.room.InvalidationTracker
import com.celzero.bravedns.database.ConsoleLog
import com.celzero.bravedns.database.ConsoleLogDAO
import com.celzero.bravedns.database.ConsoleLogDatabase
import com.celzero.bravedns.database.RpnLog
import com.celzero.bravedns.database.RpnLogDAO
import com.celzero.bravedns.database.RpnLogDatabase
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * Presents console logs and RPN logs as one chronologically sorted list.
 *
 * The two sources live in separate in-memory databases, so a single Room PagingSource
 * cannot cover both. Instead the ViewModel loads the newest rows of each table
 * (text/level filtered), merges them sorted by timestamp, and emits the result as
 * [PagingData.from]. The list reloads whenever [queryParams] change or when either
 * table is invalidated by an insert (debounced).
 *
 * RPN rows are mapped to [ConsoleLog] with a negated id so they are unique within the
 * merged list (console ids are positive auto-generated values), keeping the adapter's
 * id-based DiffUtil stable across reloads.
 */
class ConsoleLogViewModel(
    private val dao: ConsoleLogDAO,
    private val rpnDao: RpnLogDAO,
    private val consoleDb: ConsoleLogDatabase,
    private val rpnDb: RpnLogDatabase,
) : ViewModel() {

    private data class QueryParams(
        val filter: String = "",
        val minLevel: Int = 0,
        val sessionId: Long = 0L,
    )

    companion object {
        // newest N rows per source; matches the bug-report console log export cap
        private const val MAX_UI_LOG_ROWS = 10_000
        // batch rapid table invalidations (high-frequency console flushes) into one reload
        private const val RELOAD_DEBOUNCE_MS = 500L
    }

    private val queryParams = MutableStateFlow(QueryParams())

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val logs: LiveData<PagingData<ConsoleLog>> = queryParams
        .flatMapLatest { params ->
            flow {
                emit(loadMerged(params))
                // reload whenever a row is written to either log table
                tableInvalidations().collect {
                    emit(loadMerged(params))
                }
            }
        }
        .cachedIn(viewModelScope)
        .asLiveData()

    /** Emits whenever ConsoleLog or RpnLog changes, coalesced by [RELOAD_DEBOUNCE_MS]. */
    @OptIn(FlowPreview::class)
    private fun tableInvalidations(): Flow<Long> = callbackFlow {
        val consoleObserver = object : InvalidationTracker.Observer("ConsoleLog") {
            override fun onInvalidated(tables: Set<String>) {
                trySend(System.currentTimeMillis())
            }
        }
        val rpnObserver = object : InvalidationTracker.Observer("RpnLog") {
            override fun onInvalidated(tables: Set<String>) {
                trySend(System.currentTimeMillis())
            }
        }
        consoleDb.invalidationTracker.addObserver(consoleObserver)
        rpnDb.invalidationTracker.addObserver(rpnObserver)
        awaitClose {
            consoleDb.invalidationTracker.removeObserver(consoleObserver)
            rpnDb.invalidationTracker.removeObserver(rpnObserver)
        }
    }.debounce(RELOAD_DEBOUNCE_MS.milliseconds)

    private suspend fun loadMerged(params: QueryParams): PagingData<ConsoleLog> {
        val input = "%${params.filter}%"
        val console = runCatchingList("console") {
            dao.getLogsForUi(input, params.minLevel, MAX_UI_LOG_ROWS)
        }
        val rpn = runCatchingList("rpn") {
            rpnDao.getLogsForUi(input, params.minLevel, MAX_UI_LOG_ROWS)
        }

        val rpnAsConsole = rpn.map { it.toConsoleLog() }
        val merged = mergeNewestFirst(console, rpnAsConsole)
        return PagingData.from(merged)
    }

    private fun mergeNewestFirst(a: List<ConsoleLog>, b: List<ConsoleLog>): List<ConsoleLog> {
        val merged = ArrayList<ConsoleLog>(a.size + b.size)
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            val x = a[i]
            val y = b[j]
            val takeA = when {
                x.timestamp != y.timestamp -> x.timestamp > y.timestamp
                else -> x.id >= y.id
            }
            if (takeA) {
                merged.add(x)
                i++
            } else {
                merged.add(y)
                j++
            }
        }
        while (i < a.size) {
            merged.add(a[i])
            i++
        }
        while (j < b.size) {
            merged.add(b[j])
            j++
        }
        return merged
    }

    // RPN rows share the screen with console rows; use negated ids to keep them unique
    // in the merged list (DiffUtil in ConsoleLogAdapter keys on id).
    private fun RpnLog.toConsoleLog(): ConsoleLog {
        return ConsoleLog(id = -id, message = message, level = level, timestamp = timestamp)
    }

    private suspend fun <T> runCatchingList(source: String, query: suspend () -> List<T>): List<T> {
        return try {
            withContext(Dispatchers.IO) { query() }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err loading $source logs: ${e.message}")
            emptyList()
        }
    }

    /**
     * Oldest timestamp across both tables (0 = none); drives the "logs since" header.
     */
    suspend fun sinceTime(): Long {
        val consoleSince = try {
            withContext(Dispatchers.IO) { dao.sinceTime() }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err getting since time: ${e.message}")
            0L
        }
        val rpnSince = try {
            withContext(Dispatchers.IO) { rpnDao.sinceTime() }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err getting rpn since time: ${e.message}")
            0L
        }
        return listOf(consoleSince, rpnSince).filter { it > 0L }.minOrNull() ?: 0L
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
