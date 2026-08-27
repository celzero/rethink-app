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
import java.time.LocalDate
import java.time.ZoneOffset

@ExperimentalCoroutinesApi
class LogActivityAggregatorTest {

    private lateinit var dnsRepo: DnsLogRepository
    private lateinit var ctRepo: ConnectionTrackerRepository
    private lateinit var rlRepo: RethinkLogRepository

    private val zone = ZoneOffset.UTC
    // wall window ends "today"; rows cover today-5 .. today
    private val today: LocalDate = LocalDate.of(2026, 8, 25)
    private val windowStart: LocalDate = today.minusDays(LogActivityAggregator.GRID_DAYS.toLong() - 1)
    private val clock: Clock = Clock.fixed(Instant.parse("2026-08-25T12:00:00Z"), zone)

    // epoch-ms helpers relative to the wall window
    private fun dayStartMs(dayOffsetFromWindowStart: Int): Long {
        return LogActivityAggregator.startOfLocalDay(
            windowStart.plusDays(dayOffsetFromWindowStart.toLong()), zone
        )
    }

    private fun ts(dayOffset: Int, hour: Int, minute: Int = 0): Long {
        return dayStartMs(dayOffset) + hour * HOUR_MS + minute * 60_000L
    }

    private fun slot(dayOffset: Int, hour: Int): Long =
        dayOffset * LogActivityAggregator.HOURS_PER_DAY + hour.toLong()

    private fun at(state: LogActivityState, dayOffset: Int, hour: Int): LogActivityInterval =
        state.intervals[slot(dayOffset, hour).toInt()]

    private val HOUR_MS = LogActivityAggregator.HOUR_MS

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
    fun `empty wall has 144 zero slots spanning six days`() = runTest {
        val agg = aggregator()
        val s = agg.activity.value
        assertEquals(144, s.intervals.size)
        assertEquals(today, s.date)
        assertTrue(s.intervals.all { it.blocked == 0L && it.allowed == 0L })
        assertTrue(agg.isStale()) // never reconciled with db yet

        // first slot starts at the oldest day's midnight; last slot ends at
        // today's final hour boundary
        assertEquals(dayStartMs(0), s.intervals.first().startTimestamp)
        assertEquals(
            dayStartMs(5) + 23 * HOUR_MS,
            s.intervals.last().startTimestamp
        )
    }

    @Test
    fun `one blocked dns event lands in its day row and hour column`() = runTest {
        val agg = aggregator()
        // 3 days into the window, 13:05 local -> row 3, col 13
        agg.record(listOf(LogActivityEvent(ts(3, 13, 5), LogActivitySource.DNS, blocked = true)))

        val s = agg.activity.value
        assertEquals(1L, at(s, 3, 13).dnsBlocked)
        assertEquals(1L, at(s, 3, 13).blocked)
        assertEquals(0L, s.intervals.sumOf { it.allowed })
        assertEquals(1L, s.intervals.sumOf { it.blocked })
    }

    @Test
    fun `allowed network event lands in network counters`() = runTest {
        val agg = aggregator()
        agg.record(
            listOf(
                LogActivityEvent(ts(0, 9, 42), LogActivitySource.NETWORK, blocked = false, key = "abc")
            )
        )
        val s = agg.activity.value
        assertEquals(1L, at(s, 0, 9).networkAllowed)
        assertEquals(1L, at(s, 0, 9).allowed)
        assertEquals(0L, at(s, 0, 9).blocked)
    }

    @Test
    fun `hour boundaries separate adjacent columns`() = runTest {
        val agg = aggregator()
        agg.record(
            listOf(
                LogActivityEvent(ts(2, 12, 59), LogActivitySource.DNS, blocked = true),
                LogActivityEvent(ts(2, 13, 0), LogActivitySource.DNS, blocked = false)
            )
        )
        val s = agg.activity.value
        assertEquals(1L, at(s, 2, 12).dnsBlocked)
        assertEquals(1L, at(s, 2, 13).dnsAllowed)
    }

    @Test
    fun `events outside the six day window are ignored`() = runTest {
        val agg = aggregator()
        val beforeWall = dayStartMs(0) - 60_000L // one minute older than the wall
        agg.record(
            listOf(
                LogActivityEvent(beforeWall, LogActivitySource.DNS, blocked = true),
                LogActivityEvent(ts(5, 23, 59, ), LogActivitySource.NETWORK, blocked = true, key = "x")
            )
        )
        val s = agg.activity.value
        assertEquals(0L, s.intervals.sumOf { it.dnsBlocked })
        assertEquals(1L, at(s, 5, 23).networkBlocked)
    }

    @Test
    fun `midnight rollover slides the wall and keeps history`() = runTest {
        val agg = aggregator()
        // an event yesterday (row 4) and one just after tomorrow's midnight
        agg.record(listOf(LogActivityEvent(ts(4, 10, 0), LogActivitySource.DNS, blocked = true)))
        assertEquals(1L, at(agg.activity.value, 4, 10).blocked)

        val tomorrowEarly = dayStartMs(5) + 24 * HOUR_MS + 60_000L
        agg.record(listOf(LogActivityEvent(tomorrowEarly, LogActivitySource.DNS, blocked = true)))

        val s = agg.activity.value
        assertEquals(today.plusDays(1), s.date)
        // history slid down one row instead of being wiped
        assertEquals(1L, at(s, 3, 10).blocked)
        // new event sits in the fresh last row
        assertEquals(1L, at(s, 5, 0).dnsBlocked)
    }

    @Test
    fun `restore rebuilds the whole wall from hourly database buckets`() = runTest {
        val rangeStart = dayStartMs(0)
        val rangeEnd = dayStartMs(5) + 24 * HOUR_MS
        coEvery {
            dnsRepo.getActivityBuckets(rangeStart, rangeEnd, LogActivityAggregator.HOUR_MS)
        } returns listOf(
            ActivityBucketRow(slot(0, 3), 1, 7),   // oldest day, 03:00-04:00
            ActivityBucketRow(slot(0, 3), 0, 4)
        )
        coEvery {
            ctRepo.getActivityBuckets(rangeStart, rangeEnd, LogActivityAggregator.HOUR_MS)
        } returns listOf(ActivityBucketRow(slot(5, 14), 0, 2)) // today, 14:00-15:00
        coEvery {
            rlRepo.getActivityBuckets(rangeStart, rangeEnd, LogActivityAggregator.HOUR_MS)
        } returns listOf(ActivityBucketRow(slot(2, 23), 1, 5))

        val agg = aggregator()
        assertTrue(agg.isStale())
        agg.restoreFromDatabase()

        org.junit.Assert.assertFalse(agg.isStale())
        val s = agg.activity.value
        assertEquals(7L, at(s, 0, 3).dnsBlocked)
        assertEquals(4L, at(s, 0, 3).dnsAllowed)
        assertEquals(7L, at(s, 0, 3).blocked)

        assertEquals(2L, at(s, 5, 14).networkAllowed)
        assertEquals(2L, at(s, 5, 14).allowed)

        assertEquals(5L, at(s, 2, 23).networkBlocked)
        assertEquals(5L, at(s, 2, 23).blocked)
    }

    @Test
    fun `restore is idempotent across repeated calls`() = runTest {
        coEvery { dnsRepo.getActivityBuckets(any(), any(), any()) } returns listOf(
            ActivityBucketRow(slot(1, 1), 1, 3)
        )
        val agg = aggregator()
        agg.restoreFromDatabase()
        agg.restoreFromDatabase()
        assertEquals(3L, at(agg.activity.value, 1, 1).dnsBlocked)
    }

    @Test
    fun `blocked to allowed reclassification moves the count`() = runTest {
        val agg = aggregator()
        val e = LogActivityEvent(ts(5, 5, 30), LogActivitySource.DNS, blocked = true)
        agg.record(listOf(e))
        assertEquals(1L, at(agg.activity.value, 5, 5).blocked)

        agg.reclassify(previous = e, new = e.copy(blocked = false))

        val cell = at(agg.activity.value, 5, 5)
        assertEquals(0L, cell.blocked)
        assertEquals(1L, cell.allowed)
    }

    @Test
    fun `reclassification without classification change is a no-op`() = runTest {
        val agg = aggregator()
        val e = LogActivityEvent(ts(1, 7, 15), LogActivitySource.DNS, blocked = true)
        agg.record(listOf(e))
        agg.reclassify(previous = e, new = e.copy(timestampMs = ts(1, 7, 45)))

        assertEquals(1L, at(agg.activity.value, 1, 7).dnsBlocked)
        assertEquals(1L, agg.activity.value.intervals.sumOf { it.blocked })
    }

    @Test
    fun `duplicate network events are not double counted`() = runTest {
        val agg = aggregator()
        val e = LogActivityEvent(ts(3, 11, 11), LogActivitySource.NETWORK, blocked = true, key = "dup")
        agg.record(listOf(e, e.copy(timestampMs = ts(3, 11, 20))))
        assertEquals(1L, at(agg.activity.value, 3, 11).networkBlocked)
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
                                ts(n % 6, n % 24, (n * 7) % 60),
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
                LogActivityEvent(ts(5, 0, 1), LogActivitySource.DNS, blocked = true),
                LogActivityEvent(ts(5, 0, 2), LogActivitySource.DNS, blocked = false),
                LogActivityEvent(ts(5, 0, 3), LogActivitySource.NETWORK, blocked = true, key = "a")
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
        assertEquals(2L, at(latest, 5, 0).blocked)
        assertEquals(1L, at(latest, 5, 0).allowed)
        job.cancel()
    }
}
