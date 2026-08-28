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
package com.celzero.bravedns.service

import com.celzero.bravedns.database.ActivityBucketRow
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.DnsLogRepository
import com.celzero.bravedns.database.RethinkLogRepository
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_VPN
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class LogActivityEvent(
    val timestampMs: Long,
    val source: LogActivitySource,
    val blocked: Boolean,
    // unique per-event identity used to guard against duplicate callbacks;
    // null when the underlying record has no stable id (dns logs)
    val key: String? = null
)

enum class LogActivitySource {
    DNS,
    NETWORK
}

/**
 * Immutable counts for one cell of the activity wall: a single local hour of a
 * single local day.
 */
data class LogActivityInterval(
    val startTimestamp: Long,
    val blocked: Long,
    val allowed: Long,
    val dnsBlocked: Long,
    val dnsAllowed: Long,
    val networkBlocked: Long,
    val networkAllowed: Long
)

/**
 * Immutable snapshot of the activity wall: [intervals] holds
 * [LogActivityAggregator.GRID_DAYS] x [LogActivityAggregator.HOURS_PER_DAY]
 * entries in row-major order (oldest day first, hours 00..23 within each day),
 * ending on [date] (today).
 */
data class LogActivityState(
    val date: LocalDate,
    val intervals: List<LogActivityInterval>
) {
    companion object {
        fun empty(date: LocalDate): LogActivityState {
            val emptyInterval = LogActivityInterval(0L, 0L, 0L, 0L, 0L, 0L, 0L)
            return LogActivityState(
                date,
                List(LogActivityAggregator.TOTAL_SLOTS) { emptyInterval }
            )
        }
    }
}

/**
 * In-memory aggregation of blocked/allowed DNS and network activity into the
 * home-screen activity wall: [GRID_DAYS] rows (one per local day, oldest
 * first) x [HOURS_PER_DAY] columns (local hour of day), ending today.
 *
 * This is a cache over the log databases, not a second source of truth:
 * - The database is authoritative; [restoreFromDatabase] rebuilds the whole
 *   wall via grouped SQL queries (never by loading individual rows). It runs
 *   once when BraveVPNService is created, not on every UI resume.
 * - [recordOnArrival] is invoked by the log-producing callers
 *   (TunDnsManager.handleOnResponse / TunFlowManager write sites) immediately
 *   when a log arrives, decoupled from the batched database write. A failed or
 *   dropped db write can leave the wall briefly optimistic until the next
 *   reconciliation. The trackers themselves stay persistence-only.
 * - Postflow updates never change the isBlocked classification of an existing
 *   row (verified against ConnectionTrackerDAO/RethinkLogDao updateSummary);
 *   should that ever change, [reclassify] applies previous->new as a delta.
 *
 * Threading: producers run concurrently on arbitrary Go-bridge threads, so all
 * mutations are serialized behind [mutex] and published as immutable snapshots
 * on a StateFlow. Consumers must only read [activity].
 */
class LogActivityAggregator(
    private val dnsLogRepository: DnsLogRepository,
    private val connectionTrackerRepository: ConnectionTrackerRepository,
    private val rethinkLogRepository: RethinkLogRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    arrivalDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    companion object {
        // wall granularity: one column == one local hour
        const val HOUR_MS = 60L * 60L * 1000L
        const val HOURS_PER_DAY = 24

        // wall height: how many local days the wall spans (rows), oldest top
        const val GRID_DAYS = 6
        const val TOTAL_SLOTS = GRID_DAYS * HOURS_PER_DAY

        private const val TAG = "LogActivityAggregator;"
        // dedupe window for network events (connId based); bounded to keep
        // memory flat
        private const val DEDUPE_CAPACITY = 4096

        /**
         * Canonical epoch-millis of local midnight for the day containing
         * [timestampMs].
         */
        fun startOfLocalDay(timestampMs: Long, zone: ZoneId): Long {
            return Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
                .atStartOfDay(zone).toInstant().toEpochMilli()
        }

        /**
         * Canonical epoch-millis of local midnight of [date].
         */
        fun startOfLocalDay(date: LocalDate, zone: ZoneId): Long {
            return date.atStartOfDay(zone).toInstant().toEpochMilli()
        }

        /**
         * Wall slot for an event: row = day offset from [windowStartDate],
         * column = local hour. Returns -1 when the event lies outside the wall
         * (before the oldest day or after today).
         */
        fun slotIndex(timestampMs: Long, windowStartDate: LocalDate, zone: ZoneId): Long {
            val zdt = Instant.ofEpochMilli(timestampMs).atZone(zone)
            val dayOffset = ChronoUnit.DAYS.between(windowStartDate, zdt.toLocalDate())
            if (dayOffset < 0 || dayOffset >= GRID_DAYS) return -1L
            return dayOffset * HOURS_PER_DAY + zdt.hour
        }

        /**
         * Epoch-millis at which slot [index] of the wall begins (DST-safe:
         * derived from the actual local day/hour, not fixed offsets).
         */
        fun slotStart(windowStartDate: LocalDate, zone: ZoneId, index: Int): Long {
            val day = windowStartDate.plusDays((index / HOURS_PER_DAY).toLong())
            val hour = index % HOURS_PER_DAY
            return day.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()
        }
    }

    private val zone: ZoneId = clock.zone

    private val mutex = Mutex()

    // fire-and-forget entry point for arrival-time recording from callers that
    // have no ambient coroutine scope (go-bridge callbacks, cache listeners);
    // never canceled: the aggregator is a process-wide singleton
    private val arrivalScope = CoroutineScope(SupervisorJob() + arrivalDispatcher)

    // wall window: rows cover [windowStartDate .. currentDate] (== today)
    private var currentDate: LocalDate = LocalDate.now(clock)
    private var windowStartDate: LocalDate =
        currentDate.minusDays(GRID_DAYS.toLong() - 1)

    @Volatile
    private var restoredForEpochDay: Long = Long.MIN_VALUE

    // flat wall counters, index = dayOffset * HOURS_PER_DAY + hour
    private val dnsBlocked = LongArray(TOTAL_SLOTS)
    private val dnsAllowed = LongArray(TOTAL_SLOTS)
    private val nwBlocked = LongArray(TOTAL_SLOTS)
    private val nwAllowed = LongArray(TOTAL_SLOTS)

    // connId-based dedupe; insertion-order preserving with bounded capacity
    private val seenNetworkKeys = object : LinkedHashMap<String, Boolean>(
        DEDUPE_CAPACITY, 0.75f, false
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
            return size > DEDUPE_CAPACITY
        }
    }

    private val _activity = MutableStateFlow(LogActivityState.empty(LocalDate.now(clock)))
    val activity: StateFlow<LogActivityState> = _activity.asStateFlow()

    init {
        publishSnapshot()
    }

    /**
     * Non-suspend, non-blocking arrival hook for log-producing callers.
     * Applies the event on [arrivalScope] and returns immediately; counts are
     * commutative so cross-event ordering is irrelevant. See [record] for
     * rollover/dedupe semantics.
     */
    fun recordOnArrival(event: LogActivityEvent) {
        arrivalScope.launch { record(listOf(event)) }
    }

    suspend fun record(events: List<LogActivityEvent>) {
        mutex.withLock {
            var mutated = false
            for (event in events) {
                if (!shouldRecord(event)) continue
                rolloverIfNeeded(event.timestampMs)
                applyEvent(event, +1)
                mutated = true
            }
            if (mutated) publishSnapshot()
        }
    }

    /**
     * Applies previous -> new as a delta, moving counts between slots when the
     * classification (or timestamp) changed during an update of an existing
     * record. No-op when both sides are null.
     */
    suspend fun reclassify(previous: LogActivityEvent?, new: LogActivityEvent?) {
        if (previous == null && new == null) return
        mutex.withLock {
            if (previous != null) applyEvent(previous, -1)
            if (new != null) {
                rolloverIfNeeded(new.timestampMs)
                applyEvent(new, +1)
            }
            publishSnapshot()
        }
    }

    /**
     * Rebuilds the entire wall from the databases using grouped queries (one
     * per table over the full [GRID_DAYS]-day range; never by loading
     * individual rows). Called from BraveVPNService.onCreate; also serves
     * midnight rollover for long-lived processes. Idempotent.
     */
    suspend fun restoreFromDatabase() {
        try {
            mutex.withLock {
                val today = LocalDate.now(clock)
                if (today.toEpochDay() == restoredForEpochDay && loaded) return
                currentDate = today
                windowStartDate = today.minusDays(GRID_DAYS.toLong() - 1)
                val rangeStart = startOfLocalDay(windowStartDate, zone)
                val rangeEnd = startOfLocalDay(today.plusDays(1), zone)

                dnsBlocked.fill(0); dnsAllowed.fill(0)
                nwBlocked.fill(0); nwAllowed.fill(0)
                mergeInto(
                    dnsLogRepository.getActivityBuckets(rangeStart, rangeEnd, HOUR_MS),
                    dnsBlocked, dnsAllowed
                )
                mergeInto(
                    connectionTrackerRepository.getActivityBuckets(rangeStart, rangeEnd, HOUR_MS),
                    nwBlocked, nwAllowed
                )
                mergeInto(
                    rethinkLogRepository.getActivityBuckets(rangeStart, rangeEnd, HOUR_MS),
                    nwBlocked, nwAllowed
                )

                loaded = true
                restoredForEpochDay = today.toEpochDay()
                publishSnapshot()
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG restore failed: ${e.message}", e)
        }
    }

    /**
     * True while the in-memory wall has not (yet) been reconciled with the
     * databases for the current wall-clock day.
     */
    fun isStale(): Boolean {
        return restoredForEpochDay != LocalDate.now(clock).toEpochDay()
    }

    private var loaded: Boolean = false

    private fun shouldRecord(event: LogActivityEvent): Boolean {
        val key = event.key ?: return true
        // put returns the previous value: null only when the key is new
        return seenNetworkKeys.put(key, true) == null
    }

    /**
     * Advances the wall window when time has moved past [currentDate]: slides
     * all counters left by the elapsed day count so history stays aligned to
     * real calendar days and yesterday's rows become visible history instead
     * of being wiped.
     */
    private fun rolloverIfNeeded(timestampMs: Long) {
        val eventDate = Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
        if (!eventDate.isAfter(currentDate)) return
        val delta = ChronoUnit.DAYS.between(currentDate, eventDate).toInt()
        Logger.i(LOG_TAG_VPN, "$TAG wall rolled over $currentDate -> $eventDate ($delta d)")
        slide(delta)
        currentDate = eventDate
        windowStartDate = windowStartDate.plusDays(delta.toLong())
        loaded = false
    }

    private fun slide(deltaDays: Int) {
        if (deltaDays <= 0) return
        val shift = (deltaDays.toLong() * HOURS_PER_DAY).toInt()
        slideLeft(dnsBlocked, shift)
        slideLeft(dnsAllowed, shift)
        slideLeft(nwBlocked, shift)
        slideLeft(nwAllowed, shift)
    }

    private fun slideLeft(arr: LongArray, shift: Int) {
        if (shift >= arr.size) {
            arr.fill(0)
            return
        }
        System.arraycopy(arr, shift, arr, 0, arr.size - shift)
        arr.fill(0, arr.size - shift, arr.size)
    }

    private fun applyEvent(event: LogActivityEvent, sign: Int) {
        val idx = slotIndex(event.timestampMs, windowStartDate, zone)
        if (idx < 0) return // outside the wall window
        val i = idx.toInt() // arrays are bounded to TOTAL_SLOTS; idx fits Int
        val magnitude = if (sign > 0) 1L else -1L
        when (event.source) {
            LogActivitySource.DNS -> {
                if (event.blocked) dnsBlocked[i] += magnitude
                else dnsAllowed[i] += magnitude
            }
            LogActivitySource.NETWORK -> {
                if (event.blocked) nwBlocked[i] += magnitude
                else nwAllowed[i] += magnitude
            }
        }
    }

    private fun mergeInto(rows: List<ActivityBucketRow>, blocked: LongArray, allowed: LongArray) {
        for (row in rows) {
            val idx = row.bucketIndex.coerceIn(0L, TOTAL_SLOTS.toLong() - 1L).toInt()
            if (row.blocked != 0) {
                blocked[idx] += row.total
            } else {
                allowed[idx] += row.total
            }
        }
    }

    private fun publishSnapshot() {
        val intervals = List(TOTAL_SLOTS) { i ->
            LogActivityInterval(
                startTimestamp = slotStart(windowStartDate, zone, i),
                blocked = dnsBlocked[i] + nwBlocked[i],
                allowed = dnsAllowed[i] + nwAllowed[i],
                dnsBlocked = dnsBlocked[i],
                dnsAllowed = dnsAllowed[i],
                networkBlocked = nwBlocked[i],
                networkAllowed = nwAllowed[i]
            )
        }
        _activity.value = LogActivityState(currentDate, intervals)
    }
}
