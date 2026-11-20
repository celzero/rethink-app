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
package com.celzero.bravedns.rpnproxy

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.celzero.bravedns.database.SubscriptionStateHistoryDao
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusRepository
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.PurchaseDetail
import com.celzero.bravedns.service.PersistentState
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [StateMachineDatabaseSyncService.savePurchaseDetail]: latest resolved
 * identity follows confirmed purchase data and expiry changes only on authoritative plan correction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StateMachineDatabaseSyncServiceTest : KoinTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var mockRepository: SubscriptionStatusRepository
    private lateinit var mockHistoryDao: SubscriptionStateHistoryDao
    private lateinit var mockPersistentState: PersistentState
    private lateinit var service: StateMachineDatabaseSyncService

    companion object {
        private const val PRODUCT = "onetime.tier"
        private const val TOKEN = "tok-sync"
        private const val RESOLVED_PLAN = "proxy-yearly-5"

        private const val DAY = 24 * 60 * 60 * 1000L
        private const val YEAR = 365 * DAY
    }

    @Before
    fun setUp() {
        try { stopKoin() } catch (_: Exception) { /* Koin wasn't started */ }

        mockRepository      = mockk(relaxed = true)
        mockHistoryDao      = mockk(relaxed = true)
        mockPersistentState = mockk(relaxed = true)
        every { mockPersistentState.goLoggerLevel } returns 5L

        coEvery { mockRepository.getByPurchaseToken(any()) } returns null
        coEvery { mockRepository.upsert(any()) } returns 1L
        coEvery { mockRepository.insert(any()) } returns 1L

        startKoin {
            modules(module {
                single<SubscriptionStatusRepository> { mockRepository }
                single<SubscriptionStateHistoryDao> { mockHistoryDao }
                single<PersistentState> { mockPersistentState }
            })
        }
        service = StateMachineDatabaseSyncService()
    }

    @After
    fun tearDown() {
        stopKoin()
        unmockkAll()
        clearAllMocks()
    }

    private fun makeRow(planId: String, title: String, expiry: Long): SubscriptionStatus =
        SubscriptionStatus().apply {
            id = 7
            purchaseToken = TOKEN
            orderId = "order-test"
            productId = PRODUCT
            this.planId = planId
            productTitle = title
            billingExpiry = expiry
            accountExpiry = expiry
            status = SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id
            windowDays = if (planId == "proxy-yearly-5") {
                InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_5YRS_DAYS
            } else {
                InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_2YRS_DAYS
            }
        }

    private fun makeDetail(
        planId: String,
        title: String,
        expiry: Long,
        serverConfirmed: Boolean = planId != PRODUCT
    ): PurchaseDetail =
        PurchaseDetail(
            productId = PRODUCT,
            planId = planId,
            productTitle = title,
            state = 1,
            planTitle = title,
            purchaseToken = TOKEN,
            productType = "inapp",
            purchaseTime = "2026-11-20",
            purchaseTimeMillis = System.currentTimeMillis(),
            isAutoRenewing = false,
            accountId = "acc-test",
            deviceId = "",
            payload = "",
            expiryTime = expiry,
            status = 1,
            windowDays = if (planId == "proxy-yearly-5") {
                InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_5YRS_DAYS
            } else {
                InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_2YRS_DAYS
            },
            orderId = "order-test",
            isPlanIdServerConfirmed = serverConfirmed
        )

    @Test
    fun `savePurchaseDetail keeps resolved identity and does not shorten expiry`() = runBlocking {
        val now = System.currentTimeMillis()
        val fiveYear = now + 5 * YEAR
        val row = makeRow(RESOLVED_PLAN, "5 years", fiveYear)
        coEvery { mockRepository.getByPurchaseToken(TOKEN) } returns row

        val id = service.savePurchaseDetail(makeDetail(PRODUCT, "2 years", now + 2 * YEAR))

        assertEquals(7L, id)
        coVerify(exactly = 0) { mockRepository.upsert(any()) }
        assertEquals(RESOLVED_PLAN, row.planId)
        assertEquals("5 years", row.productTitle)
        assertEquals(fiveYear, row.billingExpiry)
        assertEquals(InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_5YRS_DAYS, row.windowDays)
        assertEquals(fiveYear, row.accountExpiry)
    }

    @Test
    fun `savePurchaseDetail accepts resolved incoming over generic stored`() = runBlocking {
        val now = System.currentTimeMillis()
        val fiveYear = now + 5 * YEAR
        val row = makeRow(PRODUCT, "2 years", now + 2 * YEAR)
        coEvery { mockRepository.getByPurchaseToken(TOKEN) } returns row

        val saved = slot<SubscriptionStatus>()
        val id = service.savePurchaseDetail(makeDetail(RESOLVED_PLAN, "5 years", fiveYear))

        assertEquals(7L, id)
        coVerify { mockRepository.upsert(capture(saved)) }
        assertEquals(RESOLVED_PLAN, saved.captured.planId)
        assertEquals("5 years", saved.captured.productTitle)
        assertEquals(fiveYear, saved.captured.billingExpiry)
    }

    @Test
    fun `savePurchaseDetail extends expiry forward only`() = runBlocking {
        val now = System.currentTimeMillis()
        val fiveYear = now + 5 * YEAR
        val sixYear = now + 6 * YEAR
        val row = makeRow(RESOLVED_PLAN, "5 years", fiveYear)
        coEvery { mockRepository.getByPurchaseToken(TOKEN) } returns row

        val saved = slot<SubscriptionStatus>()
        val id = service.savePurchaseDetail(makeDetail(RESOLVED_PLAN, "5 years", sixYear))

        assertEquals(7L, id)
        coVerify { mockRepository.upsert(capture(saved)) }
        assertEquals(sixYear, saved.captured.billingExpiry)
        assertEquals(sixYear, saved.captured.accountExpiry)
    }

    @Test
    fun `savePurchaseDetail applies exact option expiry even when it shortens`() = runBlocking {
        val now = System.currentTimeMillis()
        val fiveYear = now + 5 * YEAR
        val twoYear = now + 2 * YEAR
        val row = makeRow("proxy-yearly-5", "5 years", fiveYear)
        coEvery { mockRepository.getByPurchaseToken(TOKEN) } returns row

        val saved = slot<SubscriptionStatus>()
        val id = service.savePurchaseDetail(makeDetail("proxy-yearly-2", "2 years", twoYear))

        assertEquals(7L, id)
        coVerify { mockRepository.upsert(capture(saved)) }
        assertEquals("proxy-yearly-2", saved.captured.planId)
        assertEquals("2 years", saved.captured.productTitle)
        assertEquals(twoYear, saved.captured.billingExpiry)
        assertEquals(twoYear, saved.captured.accountExpiry)
        assertEquals(InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_2YRS_DAYS, saved.captured.windowDays)
    }

    @Test
    fun `savePurchaseDetail ignores unconfirmed cached option`() = runBlocking {
        val now = System.currentTimeMillis()
        val fiveYear = now + 5 * YEAR
        val row = makeRow("proxy-yearly-5", "5 years", fiveYear)
        coEvery { mockRepository.getByPurchaseToken(TOKEN) } returns row

        val id = service.savePurchaseDetail(
            makeDetail(
                planId = "proxy-yearly-2",
                title = "2 years",
                expiry = now + 2 * YEAR,
                serverConfirmed = false
            )
        )

        assertEquals(7L, id)
        coVerify(exactly = 0) { mockRepository.upsert(any()) }
        assertEquals("proxy-yearly-5", row.planId)
        assertEquals(fiveYear, row.billingExpiry)
        assertEquals(InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_5YRS_DAYS, row.windowDays)
    }

    @Test
    fun `savePurchaseDetail reports failed update instead of returning a successful row id`() = runBlocking {
        val now = System.currentTimeMillis()
        val row = makeRow(PRODUCT, "2 years", now + 2 * YEAR)
        coEvery { mockRepository.getByPurchaseToken(TOKEN) } returns row
        coEvery { mockRepository.upsert(any()) } returns 0L

        val id = service.savePurchaseDetail(
            makeDetail("proxy-yearly-5", "5 years", now + 5 * YEAR)
        )

        assertEquals(-1L, id)
    }
}
