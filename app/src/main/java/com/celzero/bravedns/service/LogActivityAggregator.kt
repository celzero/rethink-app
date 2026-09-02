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
 * Immutable counts for one cell of the activity wall: a single 10-minute
 * bucket within the last 24 hours.
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
 * [LogActivityAggregator.TOTAL_SLOTS] (= [LogActivityAggregator.HOURS_IN_WINDOW]
 * x [LogActivityAggregator.BUCKETS_PER_HOUR]) entries in chronological order
 * (oldest bucket first, newest bucket last), ending at [windowEndMs]. The
 * grid maps a flat index i to column = i / [LogActivityAggregator.BUCKETS_PER_HOUR]
 * (hour, oldest left) and row = i % [LogActivityAggregator.BUCKETS_PER_HOUR]
 * (10-minute bucket within that hour, :00 at top).
 */
data class LogActivityState(
    val windowEndMs: Long,
    val intervals: List<LogActivityInterval>
) {
    companion object {
        fun empty(windowEndMs: Long): LogActivityState {
            val emptyInterval = LogActivityInterval(0L, 0L, 0L, 0L, 0L, 0L, 0L)
            return LogActivityState(
                windowEndMs,
                List(LogActivityAggregator.TOTAL_SLOTS) { emptyInterval }
            )
        }
    }
}

/**
 * In-memory aggregation of blocked/allowed DNS and network activity into the
 * home-screen activity wall: the last [HOURS_IN_WINDOW] hours (24 columns,
 * oldest left, latest right) split into [BUCKETS_PER_HOUR] ten-minute buckets
 * per hour (6 rows within each column, :00 at top, :50 at bottom). The newest
 * cell (current 10-minute bucket) is the bottom-right cell.
 *
 * This is a cache over the log databases, not a second source of truth:
 * - The database is authoritative; [restoreFromDatabase] rebuilds the whole
 *   wall via grouped SQL queries (one per table over the full 24-hour range;
 *   never by loading individual rows). It runs once when BraveVPNService is
 *   created, not on every UI resume.
 * - [recordOnArrival] is invoked by the log-producing callers
 *   (TunDnsManager.handleOnResponse / TunFlowManager write sites) immediately
 *   when a log arrives, decoupled from the batched database write. A failed or
 *   dropped db write can leave the wall briefly optimistic until the next
 *   reconciliation. The trackers themselves stay persistence-only.
 * - Postflow updates never change the isBlocked classification of an existing
 *   row (verified against ConnectionTrackerDAO/RethinkLogDao updateSummary);
 *   should that ever change, [reclassify] applies previous->new as a delta.
 * - The window slides on every 10-minute boundary: as time advances, the
 *   oldest bucket is dropped and history shifts left by one, so the wall
 *   always covers the trailing 24 hours.
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
        // wall granularity: one cell == one ten-minute interval
        const val BUCKET_MS = 10L * 60L * 1000L

        // rows within one hour-column: 6 x 10 min == 1 hour
        const val BUCKETS_PER_HOUR = 6

        // wall width: how many hours the wall spans (columns), oldest first
        const val HOURS_IN_WINDOW = 24

        const val TOTAL_SLOTS = BUCKETS_PER_HOUR * HOURS_IN_WINDOW

        // convenience for callers that reason in whole hours
        const val HOUR_MS = BUCKET_MS * BUCKETS_PER_HOUR

        private const val TAG = "LogActivityAggregator;"
        // dedupe window for network events (connId based); bounded to keep
        // memory flat
        private const val DEDUPE_CAPACITY = 4096

        /**
         * Epoch-millis of the 10-minute bucket containing [timestampMs]
         * (buckets are epoch-aligned, so no timezone involvement).
         */
        fun bucketFloor(timestampMs: Long): Long {
            if (timestampMs < 0) return 0
            return timestampMs - (timestampMs % BUCKET_MS)
        }

        /**
         * Wall slot for an event given the newest bucket start
         * [currentBucketStart]: index TOTAL_SLOTS-1 is the current bucket,
         * index 0 the oldest (a full 24 hours back). Returns -1 when the
         * event lies outside the wall (older than the window or in the
         * future).
         */
        fun slotIndex(timestampMs: Long, currentBucketStart: Long): Long {
            val bucket = bucketFloor(timestampMs) / BUCKET_MS
            val current = currentBucketStart / BUCKET_MS
            val ageBuckets = current - bucket
            if (ageBuckets < 0 || ageBuckets >= TOTAL_SLOTS) return -1L
            return TOTAL_SLOTS - 1 - ageBuckets
        }

        /**
         * Epoch-millis at which slot [index] of the wall begins; index
         * TOTAL_SLOTS-1 starts at [currentBucketStart].
         */
        fun slotStart(currentBucketStart: Long, index: Int): Long {
            return currentBucketStart - (TOTAL_SLOTS - 1 - index) * BUCKET_MS
        }
    }

    // newest 10-minute bucket of the wall (epoch-aligned floor of "now");
    // the wall covers [currentBucketStart - (TOTAL_SLOTS-1)*BUCKET_MS,
    // currentBucketStart + BUCKET_MS)
    private var currentBucketStart: Long = bucketFloor(clock.millis())

    private val mutex = Mutex()

    // fire-and-forget entry point for arrival-time recording from callers that
    // have no ambient coroutine scope (go-bridge callbacks, cache listeners);
    // never canceled: the aggregator is a process-wide singleton
    private val arrivalScope = CoroutineScope(SupervisorJob() + arrivalDispatcher)

    @Volatile
    private var restoredForBucketStart: Long = Long.MIN_VALUE

    // flat wall counters, chronological: index 0 = oldest bucket,
    // TOTAL_SLOTS-1 = current bucket
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

    private val _activity = MutableStateFlow(
        LogActivityState.empty(bucketFloor(clock.millis()) + BUCKET_MS)
    )
    val activity: StateFlow<LogActivityState> = _activity.asStateFlow()

    init {
        publishSnapshot()
    }

    /**
     * Non-suspend, non-blocking arrival hook for log-producing callers.
     * Applies the event on [arrivalScope] and returns immediately; counts are
     * commutative so cross-event ordering is irrelevant. See [record] for
     * slide/dedupe semantics.
     */
    fun recordOnArrival(event: LogActivityEvent) {
        arrivalScope.launch { record(listOf(event)) }
    }

    suspend fun record(events: List<LogActivityEvent>) {
        mutex.withLock {
            var mutated = false
            for (event in events) {
                if (!shouldRecord(event)) continue
                slideWindowIfNeeded(event.timestampMs)
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
                slideWindowIfNeeded(new.timestampMs)
                applyEvent(new, +1)
            }
            publishSnapshot()
        }
    }

    /**
     * Rebuilds the entire wall from the databases using grouped queries (one
     * per table over the full trailing-24h range; never by loading individual
     * rows). Called from BraveVPNService.onCreate; also re-anchors the window
     * for long-lived processes. Idempotent.
     */
    suspend fun restoreFromDatabase() {
        try {
            mutex.withLock {
                currentBucketStart = bucketFloor(clock.millis())
                if (restoredForBucketStart == currentBucketStart && loaded) return
                val rangeStart = currentBucketStart - (TOTAL_SLOTS - 1) * BUCKET_MS
                val rangeEnd = currentBucketStart + BUCKET_MS

                dnsBlocked.fill(0); dnsAllowed.fill(0)
                nwBlocked.fill(0); nwAllowed.fill(0)
                mergeInto(
                    dnsLogRepository.getActivityBuckets(rangeStart, rangeEnd, BUCKET_MS),
                    dnsBlocked, dnsAllowed
                )
                mergeInto(
                    connectionTrackerRepository.getActivityBuckets(rangeStart, rangeEnd, BUCKET_MS),
                    nwBlocked, nwAllowed
                )
                mergeInto(
                    rethinkLogRepository.getActivityBuckets(rangeStart, rangeEnd, BUCKET_MS),
                    nwBlocked, nwAllowed
                )

                loaded = true
                restoredForBucketStart = currentBucketStart
                publishSnapshot()
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "$TAG restore failed: ${e.message}", e)
        }
    }

    /**
     * True while the in-memory wall has not (yet) been reconciled with the
     * databases for the current 10-minute bucket.
     */
    fun isStale(): Boolean {
        return restoredForBucketStart != bucketFloor(clock.millis())
    }

    private var loaded: Boolean = false

    private fun shouldRecord(event: LogActivityEvent): Boolean {
        val key = event.key ?: return true
        // put returns the previous value: null only when the key is new
        return seenNetworkKeys.put(key, true) == null
    }

    /**
     * Slides the wall forward when time has moved past [currentBucketStart]:
     * shifts all counters left by the elapsed bucket count so the wall always
     * covers the trailing 24 hours instead of growing stale. Never moves the
     * window backward (late events simply land in their existing slot or get
     * dropped when older than the window).
     */
    private fun slideWindowIfNeeded(timestampMs: Long) {
        val bucket = bucketFloor(timestampMs)
        if (bucket <= currentBucketStart) return
        val delta = ((bucket - currentBucketStart) / BUCKET_MS).toInt()
        Logger.v(
            LOG_TAG_VPN,
            "$TAG wall slid forward $currentBucketStart -> $bucket ($delta buckets)"
        )
        slide(delta)
        currentBucketStart = bucket
    }

    private fun slide(deltaBuckets: Int) {
        if (deltaBuckets <= 0) return
        val shift = deltaBuckets.coerceAtMost(TOTAL_SLOTS)
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
        val idx = slotIndex(event.timestampMs, currentBucketStart)
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
                startTimestamp = slotStart(currentBucketStart, i),
                blocked = dnsBlocked[i] + nwBlocked[i],
                allowed = dnsAllowed[i] + nwAllowed[i],
                dnsBlocked = dnsBlocked[i],
                dnsAllowed = dnsAllowed[i],
                networkBlocked = nwBlocked[i],
                networkAllowed = nwAllowed[i]
            )
        }
        _activity.value = LogActivityState(currentBucketStart + BUCKET_MS, intervals)
    }
}
