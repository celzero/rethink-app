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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@ExperimentalCoroutinesApi
class LogActivityAggregatorTest {

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
    fun `one blocked dns event lands in its ten-minute bucket`() = runTest {
        val agg = aggregator()
        // 3h05m ago -> floor lands in the 3h10m-ago bucket
        agg.record(
            listOf(LogActivityEvent(minutesAgoMs(185), LogActivitySource.DNS, blocked = true))
        )

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
        val s = agg.activity.value
        assertEquals(0L, s.intervals.sumOf { it.dnsBlocked })
        assertEquals(1L, at(s, 1).networkBlocked)
    }

    @Test
    fun `window slide drops the oldest buckets and keeps history`() = runTest {
        val agg = aggregator()
        // an event one hour ago and one 30 minutes in the future
        agg.record(listOf(LogActivityEvent(minutesAgoMs(60), LogActivitySource.DNS, blocked = true)))
        assertEquals(1L, at(agg.activity.value, 60).blocked)

        agg.record(listOf(LogActivityEvent(minutesAgoMs(-30), LogActivitySource.DNS, blocked = true)))

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
    fun `blocked to allowed reclassification moves the count`() = runTest {
        val agg = aggregator()
        val e = LogActivityEvent(minutesAgoMs(35), LogActivitySource.DNS, blocked = true)
        agg.record(listOf(e))
        assertEquals(1L, at(agg.activity.value, 35).blocked)

        agg.reclassify(previous = e, new = e.copy(blocked = false))

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
        agg.reclassify(previous = e, new = e.copy(timestampMs = minutesAgoMs(51)))

        assertEquals(1L, at(agg.activity.value, 51).dnsBlocked)
        assertEquals(1L, agg.activity.value.intervals.sumOf { it.blocked })
    }

    @Test
    fun `duplicate network events are not double counted`() = runTest {
        val agg = aggregator()
        // both timestamps floor into the same ten-minute bucket
        val e = LogActivityEvent(minutesAgoMs(29), LogActivitySource.NETWORK, blocked = true, key = "dup")
        agg.record(listOf(e, e.copy(timestampMs = minutesAgoMs(21))))
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
        val seen = mutableListOf<LogActivityState>()
        // UNDISPATCHED: the collector receives the current StateFlow value
        // synchronously before suspending; no virtual-time advance needed
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            agg.activity.collect { seen.add(it) }
        }
        assertTrue(seen.isNotEmpty())
        val latest = seen.last()
        assertEquals(2L, at(latest, 1).blocked)
        assertEquals(1L, at(latest, 1).allowed)
        job.cancel()
    }
}
