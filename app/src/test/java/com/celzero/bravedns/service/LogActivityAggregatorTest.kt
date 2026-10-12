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

import android.util.Log
import com.celzero.bravedns.database.ActivityBucketRow
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.DnsLogRepository
import com.celzero.bravedns.database.RethinkLogRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@ExperimentalCoroutinesApi
class LogActivityAggregatorTest {

    private class MutableTestClock(
        private var nowMs: Long,
        private val zone: ZoneId = ZoneOffset.UTC
    ) : Clock() {
        fun setMillis(value: Long) {
            nowMs = value
        }

        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId): Clock = MutableTestClock(nowMs, zone)

        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
    }

    private lateinit var dnsRepo: DnsLogRepository
    private lateinit var ctRepo: ConnectionTrackerRepository
    private lateinit var rlRepo: RethinkLogRepository

    private val zone = ZoneOffset.UTC
    // fixed "now", ten-minute aligned; the wall covers the trailing 24 hours
    // ending at the current bucket
    private val nowMs: Long = Instant.parse("2026-08-25T12:00:00Z").toEpochMilli()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-25T12:00:00Z"), zone)

    private val BUCKET_MS = LogActivityAggregator.BUCKET_MS
    private val TOTAL_SLOTS = LogActivityAggregator.TOTAL_SLOTS

    // epoch-ms helpers relative to "now"; minutesAgo may be negative (future)
    private fun minutesAgoMs(minutes: Long): Long = nowMs - minutes * 60_000L

    private fun slotIndexFor(minutesAgo: Long): Int {
        return LogActivityAggregator.slotIndex(
            minutesAgoMs(minutesAgo),
            LogActivityAggregator.bucketFloor(nowMs)
        ).toInt()
    }

    private fun at(state: LogActivityState, minutesAgo: Long): LogActivityInterval =
        state.intervals[slotIndexFor(minutesAgo)]

    private fun restoreRange(): Pair<Long, Long> {
        val currentBucketStart = LogActivityAggregator.bucketFloor(nowMs)
        return Pair(
            currentBucketStart - (TOTAL_SLOTS - 1) * BUCKET_MS,
            currentBucketStart + BUCKET_MS
        )
    }

    @Before
    fun setUp() {
        // Logger's level is global mutable state; other test classes in the same
        // JVM may raise it, causing android.util.Log calls. Mock them so this
        // pure-JVM test stays deterministic regardless of execution order.
        mockkStatic(android.util.Log::class)
        every { Log.v(any<String>(), any<String>()) } returns 0
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.w(any<String>(), any<Throwable>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0

        dnsRepo = mockk(relaxed = true)
        ctRepo = mockk(relaxed = true)
        rlRepo = mockk(relaxed = true)
        coEvery {
            dnsRepo.getActivityBuckets(any(), any(), any())
        } returns emptyList()
        coEvery {
            ctRepo.getActivityBuckets(any(), any(), any())
        } returns emptyList()
        coEvery {
            rlRepo.getActivityBuckets(any(), any(), any())
        } returns emptyList()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun TestScope.aggregator(
        arrivalDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler)
    ): LogActivityAggregator {
        return LogActivityAggregator(dnsRepo, ctRepo, rlRepo, clock, arrivalDispatcher)
    }

    private suspend fun TestScope.flushPendingSnapshot() {
        testScheduler.advanceTimeBy(LogActivityAggregator.SNAPSHOT_PUBLISH_INTERVAL_MS)
        testScheduler.runCurrent()
    }

    @Test
    fun `empty wall has 144 ten-minute slots spanning 24 hours`() = runTest {
        val agg = aggregator()
        val s = agg.activity.value
        assertEquals(144, s.intervals.size)
        assertEquals(nowMs + BUCKET_MS, s.windowEndMs)
        assertTrue(s.intervals.all { it.blocked == 0L && it.allowed == 0L })
        assertTrue(agg.isStale()) // never reconciled with db yet

        // first slot starts 23h50m ago; last slot is the current bucket
        assertEquals(nowMs - (TOTAL_SLOTS - 1) * BUCKET_MS, s.intervals.first().startTimestamp)
        assertEquals(
            LogActivityAggregator.bucketFloor(nowMs),
            s.intervals.last().startTimestamp
        )
    }

    @Test
    fun `event changes are conflated into one snapshot after ten seconds`() = runTest {
        val agg = aggregator()
        val initial = agg.activity.value
        val seen = mutableListOf<LogActivityState>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            agg.activity.collect { seen.add(it) }
        }
        testScheduler.runCurrent()

        agg.record(
            listOf(LogActivityEvent(minutesAgoMs(5), LogActivitySource.DNS, blocked = true))
        )
        agg.record(
            listOf(LogActivityEvent(minutesAgoMs(4), LogActivitySource.DNS, blocked = false))
        )
        agg.record(
            listOf(
                LogActivityEvent(
                    minutesAgoMs(3),
                    LogActivitySource.NETWORK,
                    blocked = true,
                    key = "coalesced"
                )
            )
        )

        assertSame(initial, agg.activity.value)
        testScheduler.advanceTimeBy(LogActivityAggregator.SNAPSHOT_PUBLISH_INTERVAL_MS - 1)
        testScheduler.runCurrent()
        assertSame(initial, agg.activity.value)
        assertEquals(1, seen.size)

        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()

        val latest = agg.activity.value
        assertEquals(2L, latest.intervals.sumOf { it.blocked })
        assertEquals(1L, latest.intervals.sumOf { it.allowed })
        assertEquals(2, seen.size)
        collector.cancel()
    }

    @Test
    fun `out of window event does not publish an unchanged snapshot`() = runTest {
        val agg = aggregator()
        val initial = agg.activity.value
        agg.record(
            listOf(
                LogActivityEvent(
                    minutesAgoMs(24 * 60 + 1),
                    LogActivitySource.DNS,
                    blocked = true
                )
            )
        )

        flushPendingSnapshot()

        assertSame(initial, agg.activity.value)
    }

    @Test
    fun `restore publishes a changed window anchor even when counters are unchanged`() = runTest {
        val movingClock = MutableTestClock(nowMs)
        val agg = LogActivityAggregator(
            dnsRepo,
            ctRepo,
            rlRepo,
            movingClock,
            UnconfinedTestDispatcher(testScheduler)
        )
        agg.restoreFromDatabase()
        val before = agg.activity.value

        movingClock.setMillis(nowMs + BUCKET_MS)
        agg.restoreFromDatabase()

        val after = agg.activity.value
        assertNotSame(before, after)
        assertEquals(before.intervals.map { it.blocked }, after.intervals.map { it.blocked })
        assertEquals(before.windowEndMs + BUCKET_MS, after.windowEndMs)
    }

    @Test
    fun `one blocked dns event lands in its ten-minute bucket`() = runTest {
        val agg = aggregator()
        // 3h05m ago -> floor lands in the 3h10m-ago bucket
        agg.record(
            listOf(LogActivityEvent(minutesAgoMs(185), LogActivitySource.DNS, blocked = true))
        )
        flushPendingSnapshot()

        val s = agg.activity.value
        assertEquals(1L, at(s, 185).dnsBlocked)
        assertEquals(1L, at(s, 185).blocked)
        assertEquals(0L, s.intervals.sumOf { it.allowed })
        assertEquals(1L, s.intervals.sumOf { it.blocked })
    }

    @Test
    fun `allowed network event lands in network counters`() = runTest {
        val agg = aggregator()
        agg.record(
            listOf(
                LogActivityEvent(
                    minutesAgoMs(582),
                    LogActivitySource.NETWORK,
                    blocked = false,
                    key = "abc"
                )
            )
        )
        flushPendingSnapshot()
        val s = agg.activity.value
        assertEquals(1L, at(s, 582).networkAllowed)
        assertEquals(1L, at(s, 582).allowed)
        assertEquals(0L, at(s, 582).blocked)
    }

    @Test
    fun `bucket boundaries separate adjacent rows`() = runTest {
        val agg = aggregator()
        agg.record(
            listOf(
                LogActivityEvent(minutesAgoMs(21), LogActivitySource.DNS, blocked = true),
                LogActivityEvent(minutesAgoMs(20), LogActivitySource.DNS, blocked = false)
            )
        )
        flushPendingSnapshot()
        val s = agg.activity.value
        // :21 floors into the :30-ago bucket, :20 into the :20-ago bucket
        assertEquals(1L, at(s, 21).dnsBlocked)
        assertEquals(1L, at(s, 20).dnsAllowed)
    }

    @Test
    fun `events outside the 24 hour window are ignored`() = runTest {
        val agg = aggregator()
        val beforeWall = minutesAgoMs(24 * 60 + 1) // one minute older than the wall
        agg.record(
            listOf(
                LogActivityEvent(beforeWall, LogActivitySource.DNS, blocked = true),
                LogActivityEvent(minutesAgoMs(1), LogActivitySource.NETWORK, blocked = true, key = "x")
            )
        )
        flushPendingSnapshot()
        val s = agg.activity.value
        assertEquals(0L, s.intervals.sumOf { it.dnsBlocked })
        assertEquals(1L, at(s, 1).networkBlocked)
    }

    @Test
    fun `window slide drops the oldest buckets and keeps history`() = runTest {
        val agg = aggregator()
        // an event one hour ago and one 30 minutes in the future
        agg.record(listOf(LogActivityEvent(minutesAgoMs(60), LogActivitySource.DNS, blocked = true)))
        flushPendingSnapshot()
        assertEquals(1L, at(agg.activity.value, 60).blocked)

        agg.record(listOf(LogActivityEvent(minutesAgoMs(-30), LogActivitySource.DNS, blocked = true)))
        flushPendingSnapshot()

        val s = agg.activity.value
        assertEquals(nowMs + 40 * 60_000L, s.windowEndMs)
        // history shifted left by three buckets instead of being wiped:
        // the event was at slot 137 (age 6) before the slide, 134 (age 9) after
        assertEquals(1L, s.intervals[134].blocked)
        // new event sits in the fresh newest bucket
        assertEquals(1L, s.intervals[TOTAL_SLOTS - 1].dnsBlocked)
    }

    @Test
    fun `restore rebuilds the whole wall from ten-minute database buckets`() = runTest {
        val (rangeStart, rangeEnd) = restoreRange()
        coEvery {
            dnsRepo.getActivityBuckets(rangeStart, rangeEnd, BUCKET_MS)
        } returns listOf(
            ActivityBucketRow(0L, 1, 7),      // oldest bucket, 24h back
            ActivityBucketRow(0L, 0, 4)
        )
        coEvery {
            ctRepo.getActivityBuckets(rangeStart, rangeEnd, BUCKET_MS)
        } returns listOf(ActivityBucketRow(TOTAL_SLOTS - 1L, 0, 2)) // current bucket
        coEvery {
            rlRepo.getActivityBuckets(rangeStart, rangeEnd, BUCKET_MS)
        } returns listOf(ActivityBucketRow(72L, 1, 5)) // middle of the wall

        val agg = aggregator()
        assertTrue(agg.isStale())
        agg.restoreFromDatabase()

        org.junit.Assert.assertFalse(agg.isStale())
        val s = agg.activity.value
        assertEquals(7L, s.intervals[0].dnsBlocked)
        assertEquals(4L, s.intervals[0].dnsAllowed)
        assertEquals(7L, s.intervals[0].blocked)

        assertEquals(2L, s.intervals[TOTAL_SLOTS - 1].networkAllowed)
        assertEquals(2L, s.intervals[TOTAL_SLOTS - 1].allowed)

        assertEquals(5L, s.intervals[72].networkBlocked)
        assertEquals(5L, s.intervals[72].blocked)
    }

    @Test
    fun `restore is idempotent across repeated calls`() = runTest {
        coEvery { dnsRepo.getActivityBuckets(any(), any(), any()) } returns listOf(
            ActivityBucketRow(10L, 1, 3)
        )
        val agg = aggregator()
        agg.restoreFromDatabase()
        agg.restoreFromDatabase()
        assertEquals(3L, agg.activity.value.intervals[10].dnsBlocked)
    }

    @Test
    fun `restore does not wipe arrivals that are not yet written to the database`() = runTest {
        // regression: an arrival updates the wall immediately, but database
        // writes are batched — a restore in between replaced the wall with a
        // db snapshot that did not contain the event, silently dropping it
        val agg = aggregator()
        agg.record(listOf(LogActivityEvent(minutesAgoMs(5), LogActivitySource.DNS, blocked = true)))
        // db snapshot comes back empty: the batched write has not landed yet
        agg.restoreFromDatabase()

        assertEquals(1L, at(agg.activity.value, 5).blocked)
        assertEquals(1L, agg.activity.value.intervals.sumOf { it.blocked })
    }

    @Test
    fun `restore re-anchors the wall for recorded events without a rebuild`() = runTest {
        val agg = aggregator()
        agg.record(listOf(LogActivityEvent(minutesAgoMs(5), LogActivitySource.DNS, blocked = true)))

        // restore must keep the recorded event and re-anchor (isStale false)
        // instead of wiping the wall with an empty db snapshot
        agg.restoreFromDatabase()

        val s = agg.activity.value
        assertEquals(1L, at(s, 5).blocked)
        assertEquals(
            LogActivityAggregator.bucketFloor(nowMs) + BUCKET_MS,
            s.windowEndMs
        )
        org.junit.Assert.assertFalse(agg.isStale())

        // subsequent arrivals still land after the live restore; both events
        // floor into the same 11:50 bucket
        agg.record(listOf(LogActivityEvent(minutesAgoMs(3), LogActivitySource.NETWORK, blocked = true, key = "post")))
        flushPendingSnapshot()
        assertEquals(1L, at(agg.activity.value, 3).networkBlocked)
        assertEquals(1L, at(agg.activity.value, 5).dnsBlocked)
    }

    @Test
    fun `restore rebuilds from the database when no arrivals happened since the last snapshot`() = runTest {
        // with a clean (idle) wall a rebuild is safe: nothing applied since
        // the last restore can be missing from the db
        val (rangeStart, rangeEnd) = restoreRange()
        coEvery {
            dnsRepo.getActivityBuckets(rangeStart, rangeEnd, BUCKET_MS)
        } returns listOf(ActivityBucketRow(72L, 1, 5))

        val agg = aggregator()
        agg.restoreFromDatabase() // first snapshot, nothing recorded
        // simulate wall loss without recording (fresh aggregator restores the
        // same way); here verify a second idle restore still rebuilds
        agg.restoreFromDatabase()
        assertEquals(5L, agg.activity.value.intervals[72].dnsBlocked)
    }

    @Test
    fun `force restore rebuilds from the database even when the wall is live`() = runTest {
        // log-deletion flow: after rows are removed the database is
        // authoritative again, so the rebuild must bypass the "wall is live"
        // guard that a plain restore uses to protect unflushed arrivals
        val (rangeStart, rangeEnd) = restoreRange()
        coEvery {
            dnsRepo.getActivityBuckets(rangeStart, rangeEnd, BUCKET_MS)
        } returns listOf(ActivityBucketRow(72L, 1, 5))

        val agg = aggregator()
        // applied (but unflushed) arrival keeps the wall live: a plain
        // restore keeps the event instead of rebuilding
        agg.record(listOf(LogActivityEvent(minutesAgoMs(5), LogActivitySource.DNS, blocked = true)))
        agg.restoreFromDatabase()
        assertEquals(1L, at(agg.activity.value, 5).blocked)

        agg.forceRestoreFromDatabase()

        // snapshot is published immediately, without waiting for the
        // coalescing window
        val s = agg.activity.value
        assertEquals(5L, s.intervals[72].dnsBlocked)
        // documented trade-off: the unflushed arrival is wiped by the rebuild
        // and will not re-record (dedupe)
        assertEquals(0L, at(s, 5).blocked)
        assertEquals(5L, s.intervals.sumOf { it.blocked })
        org.junit.Assert.assertFalse(agg.isStale())
    }

    @Test
    fun `force restore applies arrivals queued behind the rebuild exactly once`() = runTest {
        val (rangeStart, rangeEnd) = restoreRange()
        val agg = aggregator()

        // enqueue an arrival from inside the rebuild's own db query: the
        // consumer wakes up while the mutex is held by the forced restore, so
        // it suspends and must be applied after the rebuild, not wiped by it
        coEvery {
            dnsRepo.getActivityBuckets(rangeStart, rangeEnd, BUCKET_MS)
        } coAnswers {
            agg.recordOnArrival(
                LogActivityEvent(minutesAgoMs(5), LogActivitySource.NETWORK, blocked = true, key = "q")
            )
            listOf(ActivityBucketRow(72L, 1, 5))
        }

        agg.forceRestoreFromDatabase()
        testScheduler.advanceUntilIdle()
        flushPendingSnapshot()

        val s = agg.activity.value
        assertEquals(5L, s.intervals[72].dnsBlocked)
        assertEquals(1L, at(s, 5).networkBlocked)
        assertEquals(6L, s.intervals.sumOf { it.blocked })
        org.junit.Assert.assertFalse(agg.isStale())
    }

    @Test
    fun `blocked to allowed reclassification moves the count`() = runTest {
        val agg = aggregator()
        val e = LogActivityEvent(minutesAgoMs(35), LogActivitySource.DNS, blocked = true)
        agg.record(listOf(e))
        flushPendingSnapshot()
        assertEquals(1L, at(agg.activity.value, 35).blocked)

        agg.reclassify(previous = e, new = e.copy(blocked = false))
        flushPendingSnapshot()

        val cell = at(agg.activity.value, 35)
        assertEquals(0L, cell.blocked)
        assertEquals(1L, cell.allowed)
    }

    @Test
    fun `reclassification without classification change is a no-op`() = runTest {
        val agg = aggregator()
        // both timestamps floor into the same ten-minute bucket
        val e = LogActivityEvent(minutesAgoMs(54), LogActivitySource.DNS, blocked = true)
        agg.record(listOf(e))
        flushPendingSnapshot()
        val published = agg.activity.value
        agg.reclassify(previous = e, new = e.copy(timestampMs = minutesAgoMs(51)))
        flushPendingSnapshot()

        assertSame(published, agg.activity.value)
        assertEquals(1L, at(agg.activity.value, 51).dnsBlocked)
        assertEquals(1L, agg.activity.value.intervals.sumOf { it.blocked })
    }

    @Test
    fun `duplicate network events are not double counted`() = runTest {
        val agg = aggregator()
        // both timestamps floor into the same ten-minute bucket
        val e = LogActivityEvent(minutesAgoMs(29), LogActivitySource.NETWORK, blocked = true, key = "dup")
        agg.record(listOf(e, e.copy(timestampMs = minutesAgoMs(21))))
        flushPendingSnapshot()
        assertEquals(1L, at(agg.activity.value, 29).networkBlocked)
    }

    @Test
    fun `concurrent records are all counted exactly once`() = runTest {
        val agg = aggregator()
        coroutineScope {
            (0 until 100).map { n ->
                async {
                    agg.record(
                        listOf(
                            LogActivityEvent(
                                minutesAgoMs(((n % 24) * 60L + (n * 7) % 60L)),
                                if (n % 2 == 0) LogActivitySource.DNS else LogActivitySource.NETWORK,
                                blocked = n % 3 == 0,
                                key = "conn-$n"
                            )
                        )
                    )
                }
            }.awaitAll()
        }

        flushPendingSnapshot()
        val s = agg.activity.value
        assertEquals(100L, s.intervals.sumOf { it.blocked + it.allowed })
        assertEquals(34L, s.intervals.sumOf { it.blocked }) // multiples of 3 in 0..99
        assertEquals(66L, s.intervals.sumOf { it.allowed })
    }

    @Test
    fun `stateflow exposes both blocked and allowed counts`() = runTest {
        val agg = aggregator()
        agg.record(
            listOf(
                LogActivityEvent(minutesAgoMs(3), LogActivitySource.DNS, blocked = true),
                LogActivityEvent(minutesAgoMs(2), LogActivitySource.DNS, blocked = false),
                LogActivityEvent(minutesAgoMs(1), LogActivitySource.NETWORK, blocked = true, key = "a")
            )
        )
        flushPendingSnapshot()
        val seen = mutableListOf<LogActivityState>()
        // UNDISPATCHED receives the latest StateFlow value before suspending.
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            agg.activity.collect { seen.add(it) }
        }
        assertTrue(seen.isNotEmpty())
        val latest = seen.last()
        assertEquals(2L, at(latest, 1).blocked)
        assertEquals(1L, at(latest, 1).allowed)
        job.cancel()
    }

    // --- recordOnArrival (queued batched-consumer path) ---

    @Test
    fun `arrivals via recordOnArrival land in their buckets`() = runTest {
        val agg = aggregator()
        agg.recordOnArrival(LogActivityEvent(minutesAgoMs(5), LogActivitySource.DNS, blocked = true))
        agg.recordOnArrival(
            LogActivityEvent(minutesAgoMs(2), LogActivitySource.NETWORK, blocked = false, key = "n1")
        )

        testScheduler.advanceUntilIdle()
        flushPendingSnapshot()

        val s = agg.activity.value
        assertEquals(1L, at(s, 5).dnsBlocked)
        assertEquals(1L, at(s, 2).networkAllowed)
        assertEquals(1L, s.intervals.sumOf { it.blocked })
        assertEquals(1L, s.intervals.sumOf { it.allowed })
    }

    @Test
    fun `arrivals are coalesced into one snapshot publish`() = runTest {
        val agg = aggregator()
        val seen = mutableListOf<LogActivityState>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            agg.activity.collect { seen.add(it) }
        }
        testScheduler.runCurrent()

        repeat(10) { n ->
            agg.recordOnArrival(
                LogActivityEvent(minutesAgoMs((n % 50).toLong() + 1), LogActivitySource.DNS, blocked = true)
            )
        }
        testScheduler.advanceUntilIdle()

        // all 10 arrivals share the snapshot-pending window: one publish after 10 s
        testScheduler.advanceTimeBy(LogActivityAggregator.SNAPSHOT_PUBLISH_INTERVAL_MS)
        testScheduler.runCurrent()
        assertEquals(2, seen.size)
        assertEquals(10L, agg.activity.value.intervals.sumOf { it.blocked })
        collector.cancel()
    }

    @Test
    fun `restore does not wipe arrivals queued via recordOnArrival`() = runTest {
        val agg = aggregator()
        agg.recordOnArrival(LogActivityEvent(minutesAgoMs(5), LogActivitySource.DNS, blocked = true))
        testScheduler.advanceUntilIdle()

        agg.restoreFromDatabase()

        assertEquals(1L, at(agg.activity.value, 5).blocked)
        org.junit.Assert.assertFalse(agg.isStale())
    }

    @Test
    fun `duplicate arrivals with the same key are not double counted`() = runTest {
        val agg = aggregator()
        repeat(3) {
            agg.recordOnArrival(
                LogActivityEvent(minutesAgoMs(29), LogActivitySource.NETWORK, blocked = true, key = "dup")
            )
        }
        testScheduler.advanceUntilIdle()
        flushPendingSnapshot()

        assertEquals(1L, at(agg.activity.value, 29).networkBlocked)
    }

    @Test
    fun `burst larger than the arrival queue capacity does not throw`() = runTest {
        val agg = aggregator()
        // 2x capacity + margin; under a paused/contending dispatcher the queue overflows and
        // DROP_OLDEST discards queued (not applied) events, so counts must never exceed the
        // number of sent events. With the eager Unconfined dispatcher the consumer drains
        // inline, so all events may be counted — both outcomes are valid; the contract under
        // test is "no crash, no over-counting".
        val sent = LogActivityAggregator.ARRIVAL_QUEUE_CAPACITY * 2 + 64
        repeat(sent) { n ->
            agg.recordOnArrival(
                LogActivityEvent(minutesAgoMs(((n % 60) + 1).toLong()), LogActivitySource.DNS, blocked = true)
            )
        }
        testScheduler.advanceUntilIdle()
        flushPendingSnapshot()

        val total = agg.activity.value.intervals.sumOf { it.blocked }
        assertTrue("expected events counted, got $total", total > 0L)
        assertTrue("counted $total, sent $sent", total <= sent.toLong())
    }

    @Test
    fun `arrivals batch larger than max batch size are all applied`() = runTest {
        val agg = aggregator()
        val count = LogActivityAggregator.ARRIVAL_MAX_BATCH * 2 + 1
        // distinct keys to bypass dedupe; distinct buckets to keep every event countable
        repeat(count) { n ->
            agg.recordOnArrival(
                LogActivityEvent(
                    minutesAgoMs(((n % 1440) + 1).toLong()),
                    LogActivitySource.NETWORK,
                    blocked = true,
                    key = "k-$n"
                )
            )
        }
        testScheduler.advanceUntilIdle()
        flushPendingSnapshot()

        assertEquals(count.toLong(), agg.activity.value.intervals.sumOf { it.networkBlocked })
    }
}
