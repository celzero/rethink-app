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
package com.celzero.bravedns.rpnproxy

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusRepository
import com.celzero.bravedns.iab.PurchaseDetail
import com.celzero.bravedns.service.PersistentState
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2].
 *
 * ### Test strategy
 * - All Koin dependencies ([SubscriptionStatusRepository], [com.celzero.bravedns.rpnproxy.StateMachineDatabaseSyncService],
 *   [PersistentState]) are replaced with MockK relaxed mocks.
 * - [RpnProxyManager] is a Kotlin `object`; mocked via [mockkObject].
 * - [createMachine] constructs a fresh machine and waits [MACHINE_INIT_WAIT_MS] for the
 *   `initializeStateMachine()` coroutine (launched on Dispatchers.IO) to complete.
 * - By default `loadStateFromDatabase()` returns `null` so init is a no-op and the machine
 *   settles in [com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2.SubscriptionState.Initial].
 * - Tests that need a specific starting state call [transitionToActive] /
 *   [transitionToActiveWithData] helpers.
 */
@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SubscriptionStateMachineV2Test : KoinTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var mockRepository: SubscriptionStatusRepository
    private lateinit var mockDbSyncService: StateMachineDatabaseSyncService
    private lateinit var mockPersistentState: PersistentState

    companion object {
        /** Time (ms) to wait after constructing a machine for its IO-dispatched init to complete. */
        private const val MACHINE_INIT_WAIT_MS = 300L
        /** Time (ms) to wait for `scope.launch { }` side-effects (deactivateRpn, processRpnPurchase). */
        private const val SIDE_EFFECT_WAIT_MS = 500L

        private const val STD_PRODUCT  = "standard.tier"
        private const val INAPP_2YRS   = "proxy-yearly-2"
        private const val INAPP_ONETIME = "onetime.tier"
        private const val SUBS_MONTHLY  = "proxy-monthly"
    }

    // =========================================================================
    // setUp / tearDown
    // =========================================================================

    @Before
    fun setUp() {
        try { stopKoin() } catch (_: Exception) { /* ignore Koin wasn't started */ }

        mockRepository      = mockk(relaxed = true)
        mockDbSyncService   = mockk(relaxed = true)
        mockPersistentState = mockk(relaxed = true)

        // Logger.logLevel falls back gracefully if PersistentState is unavailable,
        // but providing the mock makes log-level reads explicit and avoids noise.
        every { mockPersistentState.goLoggerLevel } returns 5L  // Logger.LoggerLevel.ERROR.id = 5

        // Default: empty DB init restores nothing, machine settles at Initial.
        coEvery { mockDbSyncService.loadStateFromDatabase() }                   returns null
        coEvery { mockDbSyncService.recordHistoryOnly(any(), any(), any(), any()) } just Runs
        coEvery { mockDbSyncService.savePurchaseDetail(any()) }                 returns 1L
        coEvery { mockDbSyncService.performSystemCheck() }                      returns null
        coEvery { mockDbSyncService.savePurchaseFailureHistory(any(), any()) }  just Runs
        coEvery { mockDbSyncService.saveUserCancelledPurchaseHistory(any()) }   just Runs

        coEvery { mockRepository.upsert(any()) }                     returns 1L
        coEvery { mockRepository.insert(any()) }                     returns 1L
        coEvery { mockRepository.getCurrentSubscription() }          returns null
        coEvery { mockRepository.getByPurchaseToken(any()) }         returns null
        coEvery { mockRepository.getSubscriptionsByStates(any()) }   returns emptyList()

        // RpnProxyManager is a Kotlin object, must be mocked with mockkObject.
        mockkObject(RpnProxyManager)
        coEvery { RpnProxyManager.getSessionTokenFromPayload(any()) } returns ""
        coEvery { RpnProxyManager.activateRpn(any(), any()) }        just Runs
        coEvery { RpnProxyManager.deactivateRpn(any()) }             just Runs
        coEvery { RpnProxyManager.processRpnPurchase(any(), any()) } returns true

        startKoin {
            modules(module {
                single<SubscriptionStatusRepository>    { mockRepository }
                single<StateMachineDatabaseSyncService> { mockDbSyncService }
                single<PersistentState>                 { mockPersistentState }
            })
        }
    }

    @After
    fun tearDown() {
        stopKoin()
        unmockkAll()
        clearAllMocks()
    }

    // =========================================================================
    // 1. SubscriptionState property tests (pure unit — no Koin / machine needed)
    // =========================================================================

    @Test fun `Active isActive is true`()  { assertTrue(SubscriptionStateMachineV2.SubscriptionState.Active.isActive) }
    @Test fun `Grace isActive is true`()   { assertTrue(SubscriptionStateMachineV2.SubscriptionState.Grace.isActive) }
    @Test fun `Expired isActive is false`(){ assertFalse(SubscriptionStateMachineV2.SubscriptionState.Expired.isActive) }
    @Test fun `Revoked isActive is false`(){ assertFalse(SubscriptionStateMachineV2.SubscriptionState.Revoked.isActive) }
    @Test fun `Cancelled isActive is false`(){ assertFalse(SubscriptionStateMachineV2.SubscriptionState.Cancelled.isActive) }

    @Test fun `Active hasValidSubscription is true`()    { assertTrue(SubscriptionStateMachineV2.SubscriptionState.Active.hasValidSubscription) }
    @Test fun `Grace hasValidSubscription is true`()     { assertTrue(SubscriptionStateMachineV2.SubscriptionState.Grace.hasValidSubscription) }
    @Test fun `Cancelled hasValidSubscription is true`() { assertTrue(SubscriptionStateMachineV2.SubscriptionState.Cancelled.hasValidSubscription) }
    @Test fun `OnHold hasValidSubscription is true`()    { assertTrue(SubscriptionStateMachineV2.SubscriptionState.OnHold.hasValidSubscription) }
    @Test fun `Paused hasValidSubscription is true`()    { assertTrue(SubscriptionStateMachineV2.SubscriptionState.Paused.hasValidSubscription) }
    @Test fun `Expired hasValidSubscription is false`()  { assertFalse(SubscriptionStateMachineV2.SubscriptionState.Expired.hasValidSubscription) }
    @Test fun `Revoked hasValidSubscription is false`()  { assertFalse(SubscriptionStateMachineV2.SubscriptionState.Revoked.hasValidSubscription) }
    @Test fun `Initial hasValidSubscription is false`()  { assertFalse(SubscriptionStateMachineV2.SubscriptionState.Initial.hasValidSubscription) }

    @Test fun `Initial canMakePurchase is true`()  { assertTrue(SubscriptionStateMachineV2.SubscriptionState.Initial.canMakePurchase) }
    @Test fun `Expired canMakePurchase is true`()  { assertTrue(SubscriptionStateMachineV2.SubscriptionState.Expired.canMakePurchase) }
    @Test fun `Revoked canMakePurchase is true`()  { assertTrue(SubscriptionStateMachineV2.SubscriptionState.Revoked.canMakePurchase) }
    @Test fun `Active canMakePurchase is false`()  { assertFalse(SubscriptionStateMachineV2.SubscriptionState.Active.canMakePurchase) }
    @Test fun `Grace canMakePurchase is false`()   { assertFalse(SubscriptionStateMachineV2.SubscriptionState.Grace.canMakePurchase) }

    @Test fun `Cancelled isCancelled is true`()    { assertTrue(SubscriptionStateMachineV2.SubscriptionState.Cancelled.isCancelled) }
    @Test fun `Active isCancelled is false`()      { assertFalse(SubscriptionStateMachineV2.SubscriptionState.Active.isCancelled) }
    @Test fun `Expired isExpired is true`()        { assertTrue(SubscriptionStateMachineV2.SubscriptionState.Expired.isExpired) }
    @Test fun `Active isExpired is false`()        { assertFalse(SubscriptionStateMachineV2.SubscriptionState.Active.isExpired) }
    @Test fun `Revoked isRevoked is true`()        { assertTrue(SubscriptionStateMachineV2.SubscriptionState.Revoked.isRevoked) }
    @Test fun `Active isRevoked is false`()        { assertFalse(SubscriptionStateMachineV2.SubscriptionState.Active.isRevoked) }

    // =========================================================================
    // 2. Machine initialization / handleSystemCheckAndDatabaseRestoration
    // =========================================================================

    @Test
    fun `machine is Initial after init when DB is empty`() {
        val machine = createMachine()
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Initial, machine.getCurrentState())
    }

    @Test
    fun `init with Active DB state restores Active in memory - no DB write`() {
        val sub  = makeActiveSub()
        coEvery { mockDbSyncService.loadStateFromDatabase() } returns makeDatabaseStateInfo(sub, SubscriptionStateMachineV2.SubscriptionState.Active)
        coEvery { mockRepository.getCurrentSubscription() }          returns sub
        coEvery { mockRepository.getByPurchaseToken(sub.purchaseToken) } returns sub

        val machine = createMachine()

        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Active, machine.getCurrentState())
        // Restoration is memory-only — upsert must NOT be called
        coVerify(exactly = 0) { mockRepository.upsert(any()) }
    }

    @Test
    fun `init with Expired DB state restores Expired in memory - no DB write`() {
        val sub = makeActiveSub().also {
            it.status       = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
            it.billingExpiry = System.currentTimeMillis() - 1_000L
        }
        coEvery { mockDbSyncService.loadStateFromDatabase() } returns makeDatabaseStateInfo(sub, SubscriptionStateMachineV2.SubscriptionState.Expired)
        coEvery { mockRepository.getCurrentSubscription() } returns sub

        val machine = createMachine()

        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Expired, machine.getCurrentState())
        coVerify(exactly = 0) { mockRepository.upsert(any()) }
    }

    @Test
    fun `init with Revoked DB state restores Revoked in memory - no DB write`() {
        val sub = makeActiveSub().also {
            it.status = SubscriptionStatus.SubscriptionState.STATE_REVOKED.id
        }
        coEvery { mockDbSyncService.loadStateFromDatabase() } returns makeDatabaseStateInfo(sub, SubscriptionStateMachineV2.SubscriptionState.Revoked)
        coEvery { mockRepository.getCurrentSubscription() } returns sub

        val machine = createMachine()

        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Revoked, machine.getCurrentState())
        coVerify(exactly = 0) { mockRepository.upsert(any()) }
    }

    @Test
    fun `init with Cancelled DB state and future billingExpiry restores Active in memory`() {
        // Cancelled but still in billing period → user keeps access in memory.
        val sub = makeActiveSub().also {
            it.status       = SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
            it.billingExpiry = System.currentTimeMillis() + 7 * 24 * 60 * 60 * 1000L
        }
        coEvery { mockDbSyncService.loadStateFromDatabase() } returns makeDatabaseStateInfo(sub, SubscriptionStateMachineV2.SubscriptionState.Cancelled)
        coEvery { mockRepository.getCurrentSubscription() }          returns sub
        coEvery { mockRepository.getByPurchaseToken(sub.purchaseToken) } returns sub

        val machine = createMachine()

        // Cancelled + billingExpiry in the future → Active in memory
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Active, machine.getCurrentState())
        // DB status must NOT be overwritten during memory-only restoration
        coVerify(exactly = 0) { mockRepository.upsert(any()) }
    }

    @Test
    fun `init with Cancelled DB state and past billingExpiry restores Cancelled in memory`() {
        val sub = makeActiveSub().also {
            it.status       = SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
            it.billingExpiry = System.currentTimeMillis() - 1_000L
        }
        coEvery { mockDbSyncService.loadStateFromDatabase() } returns makeDatabaseStateInfo(sub, SubscriptionStateMachineV2.SubscriptionState.Cancelled)
        coEvery { mockRepository.getCurrentSubscription() }          returns sub
        coEvery { mockRepository.getByPurchaseToken(sub.purchaseToken) } returns sub

        val machine = createMachine()

        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Cancelled, machine.getCurrentState())
        coVerify(exactly = 0) { mockRepository.upsert(any()) }
    }

    @Test
    fun `init with billingExpiry=0 and Expired DB recommendedState treats as Active`() {
        // When billingExpiry is 0 (unknown), the machine should not expire — Play will correct.
        val sub = makeActiveSub().also {
            it.status       = SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id
            it.billingExpiry = 0L  // expiry unknown
        }
        coEvery { mockDbSyncService.loadStateFromDatabase() } returns makeDatabaseStateInfo(sub, SubscriptionStateMachineV2.SubscriptionState.Expired)
        coEvery { mockRepository.getCurrentSubscription() }          returns sub
        coEvery { mockRepository.getByPurchaseToken(sub.purchaseToken) } returns sub

        val machine = createMachine()

        // billingExpiry=0 → override Expired → Active (Play will reconcile)
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Active, machine.getCurrentState())
    }

    // =========================================================================
    // 3. paymentSuccessful — handlePaymentSuccessful
    // =========================================================================

    @Test
    fun `paymentSuccessful from Initial transitions to Active and upserts DB`() = runBlocking {
        val machine = createMachine()
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Initial, machine.getCurrentState())

        val pd = makePurchaseDetail(STD_PRODUCT)
        machine.paymentSuccessful(pd)
        delay(100)

        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Active, machine.getCurrentState())
        coVerify(atLeast = 1) { mockRepository.upsert(any()) }
    }

    @Test
    fun `paymentSuccessful dedup guard - same token, expiry, status - is no-op`() = runBlocking {
        val machine = createMachine()
        val expiry  = System.currentTimeMillis() + 30 * 24 * 60 * 60 * 1000L
        val token   = "tok-dedup"
        val pd      = makePurchaseDetail(STD_PRODUCT, purchaseToken = token, expiryTime = expiry)

        // Existing ACTIVE row with every dedup field matching
        val existing = makeActiveSub(purchaseToken = token).also {
            it.billingExpiry    = expiry
            it.productId        = pd.productId
            it.planId           = pd.planId
            it.productTitle     = pd.productTitle
            it.developerPayload = pd.payload
            it.status           = SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id
        }
        coEvery { mockRepository.getByPurchaseToken(token) } returns existing
        coEvery { mockRepository.getCurrentSubscription() }  returns existing

        machine.paymentSuccessful(pd)
        delay(100)

        // All fields already current → no DB write
        coVerify(exactly = 0) { mockRepository.upsert(any()) }
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Active, machine.getCurrentState())
    }

    @Test
    fun `paymentSuccessful guard bypassed when isAutoRenewing=true within 5 min window (genuine resubscription)`() = runBlocking {
        val machine = createMachine()
        val token   = "tok-resubscribe"
        // isAutoRenewing=true: Play has re-enabled auto-renewal — genuine resubscription
        val pd      = makePurchaseDetail(STD_PRODUCT, purchaseToken = token, isAutoRenewing = true)

        // DB row shows CANCELLED just 1 minute ago — within the 5-minute guard window
        val existing = makeActiveSub(purchaseToken = token).also {
            it.status        = SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
            it.lastUpdatedTs = System.currentTimeMillis() - 60_000L
        }
        coEvery { mockRepository.getByPurchaseToken(token) } returns existing
        coEvery { mockRepository.getCurrentSubscription() }  returns existing

        machine.paymentSuccessful(pd)
        delay(100)

        // Guard bypassed for isAutoRenewing=true — DB must be updated to ACTIVE
        coVerify(atLeast = 1) {
            mockRepository.upsert(match {
                it.status == SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id
            })
        }
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Active, machine.getCurrentState())
    }

    @Test
    fun `paymentSuccessful LOCAL_CANCEL_REVOKE_GUARD prevents DB write when token CANCELLED within 5 min and still not auto-renewing`() = runBlocking {
        val machine = createMachine()
        val token   = "tok-guard-cancel"
        // isAutoRenewing=false: subscription is still canceled — guard should protect
        val pd      = makePurchaseDetail(STD_PRODUCT, purchaseToken = token, isAutoRenewing = false)

        // DB row shows CANCELLED just 1 minute ago — within the 5-minute guard
        val existing = makeActiveSub(purchaseToken = token).also {
            it.status       = SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
            it.lastUpdatedTs = System.currentTimeMillis() - 60_000L
        }
        coEvery { mockRepository.getByPurchaseToken(token) } returns existing
        coEvery { mockRepository.getCurrentSubscription() }  returns existing

        machine.paymentSuccessful(pd)
        delay(100)

        // Guard fires — no DB upsert; state still transitions to Active in memory
        coVerify(exactly = 0) { mockRepository.upsert(any()) }
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Active, machine.getCurrentState())
    }

    @Test
    fun `paymentSuccessful LOCAL_CANCEL_REVOKE_GUARD prevents DB write when token REVOKED within 5 min`() = runBlocking {
        val machine = createMachine()
        val token   = "tok-guard-revoke"
        // isAutoRenewing=false: revoked tokens are never auto-renewing
        val pd      = makePurchaseDetail(STD_PRODUCT, purchaseToken = token, isAutoRenewing = false)

        val existing = makeActiveSub(purchaseToken = token).also {
            it.status       = SubscriptionStatus.SubscriptionState.STATE_REVOKED.id
            it.lastUpdatedTs = System.currentTimeMillis() - 120_000L  // 2 minutes ago
        }
        coEvery { mockRepository.getByPurchaseToken(token) } returns existing
        coEvery { mockRepository.getCurrentSubscription() }  returns existing

        machine.paymentSuccessful(pd)
        delay(100)

        coVerify(exactly = 0) { mockRepository.upsert(any()) }
    }

    @Test
    fun `paymentSuccessful allows DB write when CANCELLED token is older than 5 min guard`() = runBlocking {
        val machine = createMachine()
        val token   = "tok-guard-old"
        val pd      = makePurchaseDetail(STD_PRODUCT, purchaseToken = token)

        // Updated 10 minutes ago — OUTSIDE the 5-minute guard window
        val existing = makeActiveSub(purchaseToken = token).also {
            it.status       = SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
            it.lastUpdatedTs = System.currentTimeMillis() - 10 * 60_000L
            // All other dedup fields differ from pd so dedup guard won't fire
            it.productTitle  = "OldTitle"
        }
        coEvery { mockRepository.getByPurchaseToken(token) } returns existing
        coEvery { mockRepository.getCurrentSubscription() }  returns existing

        machine.paymentSuccessful(pd)
        delay(100)

        // Guard window expired — DB write should happen
        coVerify(atLeast = 1) { mockRepository.upsert(any()) }
    }

    @Test
    fun `paymentSuccessful plan change marks old token EXPIRED and saves new token`() = runBlocking {
        val machine  = createMachine()
        val oldToken = "tok-old-plan"
        val newToken = "tok-new-plan"
        val expiry   = System.currentTimeMillis() + 30 * 24 * 60 * 60 * 1000L

        // Current DB subscription: ACTIVE with the old token
        val existingActive = makeActiveSub(purchaseToken = oldToken, productId = STD_PRODUCT)
        coEvery { mockRepository.getByPurchaseToken(newToken) } returns null
        coEvery { mockRepository.getByPurchaseToken(oldToken) } returns null
        coEvery { mockRepository.getCurrentSubscription() }     returns existingActive

        val newPd = makePurchaseDetail(SUBS_MONTHLY, purchaseToken = newToken, expiryTime = expiry)
        machine.paymentSuccessful(newPd)
        delay(100)

        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Active, machine.getCurrentState())

        // Old token must be marked EXPIRED (plan-change expiry)
        coVerify {
            mockRepository.upsert(match {
                it.purchaseToken == oldToken &&
                it.status == SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
            })
        }
        // New token must be saved as ACTIVE
        coVerify {
            mockRepository.upsert(match {
                it.productId == SUBS_MONTHLY &&
                it.status == SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id
            })
        }
    }

    @Test
    fun `paymentSuccessful launches processRpnPurchase`() = runBlocking {
        val machine = createMachine()
        val pd = makePurchaseDetail(STD_PRODUCT)

        machine.paymentSuccessful(pd)
        Thread.sleep(SIDE_EFFECT_WAIT_MS)  // Wait for scope.launch (IO) side-effect

        coVerify { RpnProxyManager.processRpnPurchase(any(), any()) }
    }

    // =========================================================================
    // 4. reconcileWithPlayBilling — SUBS
    // =========================================================================

    @Test
    fun `reconcile empty SUBS snapshot expires old non-recently-active rows`() = runBlocking {
        val machine  = createMachine()
        val staleSub = makeActiveSub(purchaseToken = "tok-stale", productId = STD_PRODUCT).also {
            // Updated 2 days ago — well outside the 24h RECENTLY_ACTIVE_GUARD_MS window
            it.lastUpdatedTs = System.currentTimeMillis() - 2 * 24 * 60 * 60 * 1000L
        }
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns listOf(staleSub)

        machine.reconcileWithPlayBilling(
            purchases = emptyList(),
            queriedProductType = BillingClient.ProductType.SUBS
        )

        coVerify {
            mockRepository.upsert(match { it.status == SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id })
        }
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Expired, machine.getCurrentState())
    }

    @Test
    fun `reconcile empty SUBS snapshot skips recently-active row within 24h guard`() = runBlocking {
        val machine    = createMachine()
        val recentSub  = makeActiveSub(purchaseToken = "tok-recent", productId = STD_PRODUCT).also {
            // Updated 10 minutes ago — within RECENTLY_ACTIVE_GUARD_MS (24 hours)
            it.lastUpdatedTs = System.currentTimeMillis() - 10 * 60 * 1000L
        }
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns listOf(recentSub)

        machine.reconcileWithPlayBilling(
            purchases = emptyList(),
            queriedProductType = BillingClient.ProductType.SUBS
        )

        // Guard fires: row is NOT expired, state remains Initial
        coVerify(exactly = 0) { mockRepository.upsert(any()) }
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Initial, machine.getCurrentState())
    }

    @Test
    fun `reconcile empty INAPP snapshot is a no-op`() = runBlocking {
        val machine = createMachine()

        machine.reconcileWithPlayBilling(
            purchases = emptyList(),
            queriedProductType = BillingClient.ProductType.INAPP
        )

        coVerify(exactly = 0) { mockRepository.upsert(any()) }
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Initial, machine.getCurrentState())
    }

    @Test
    fun `reconcile active SUBS (acknowledged, autoRenewing) transitions machine to Active`() = runBlocking {
        val machine = createMachine()
        val token   = "tok-active-subs"
        val expiry  = System.currentTimeMillis() + 30 * 24 * 60 * 60 * 1000L

        val purchase = makeMockPurchase(
            productId      = STD_PRODUCT,
            purchaseToken  = token,
            purchaseState  = Purchase.PurchaseState.PURCHASED,
            isAcknowledged = true,
            isAutoRenewing = true
        )
        coEvery { mockRepository.getByPurchaseToken(token) }     returns null
        coEvery { mockRepository.getCurrentSubscription() }      returns null
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns emptyList()

        machine.reconcileWithPlayBilling(
            purchases         = listOf(purchase),
            purchaseExpiryMap = mapOf(token to expiry),
            queriedProductType = BillingClient.ProductType.SUBS
        )
        delay(100)

        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Active, machine.getCurrentState())
    }

    @Test
    fun `reconcile SUBS with isAutoRenewing=false fires PaymentSuccessful then writes CANCELLED to DB`() = runBlocking {
        val machine = createMachine()
        val token   = "tok-cancelled-subs"
        val expiry  = System.currentTimeMillis() + 10 * 24 * 60 * 60 * 1000L

        val purchase = makeMockPurchase(
            productId      = STD_PRODUCT,
            purchaseToken  = token,
            purchaseState  = Purchase.PurchaseState.PURCHASED,
            isAcknowledged = true,
            isAutoRenewing = false  // cancelled but still in billing period
        )
        val existingRow = makeActiveSub(purchaseToken = token)
        coEvery { mockRepository.getByPurchaseToken(token) }       returns existingRow
        coEvery { mockRepository.getCurrentSubscription() }        returns existingRow
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns emptyList()

        machine.reconcileWithPlayBilling(
            purchases         = listOf(purchase),
            purchaseExpiryMap = mapOf(token to expiry),
            queriedProductType = BillingClient.ProductType.SUBS
        )
        delay(100)

        // Machine → Active (user still has access during cancelled billing period)
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Active, machine.getCurrentState())
        // DB row updated to CANCELLED
        coVerify {
            mockRepository.upsert(match { it.status == SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id })
        }
    }

    @Test
    fun `reconcile SUBS PENDING purchase maps machine to PurchasePending`() = runBlocking {
        val machine  = createMachine()
        val purchase = makeMockPurchase(
            productId      = STD_PRODUCT,
            purchaseToken  = "tok-pending",
            purchaseState  = Purchase.PurchaseState.PENDING,
            isAcknowledged = false,
            isAutoRenewing = false
        )
        coEvery { mockRepository.getByPurchaseToken(any()) }       returns null
        coEvery { mockRepository.getCurrentSubscription() }        returns null
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns emptyList()

        machine.reconcileWithPlayBilling(
            purchases          = listOf(purchase),
            queriedProductType = BillingClient.ProductType.SUBS
        )
        delay(100)

        // PENDING → PurchaseCompleted event → PurchasePending state
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.PurchasePending, machine.getCurrentState())
    }

    @Test
    fun `reconcile SUBS PURCHASED but unacknowledged maps to PurchasePending`() = runBlocking {
        val machine  = createMachine()
        val purchase = makeMockPurchase(
            productId      = STD_PRODUCT,
            purchaseToken  = "tok-unack",
            purchaseState  = Purchase.PurchaseState.PURCHASED,
            isAcknowledged = false,  // not yet acknowledged
            isAutoRenewing = true
        )
        coEvery { mockRepository.getByPurchaseToken(any()) }       returns null
        coEvery { mockRepository.getCurrentSubscription() }        returns null
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns emptyList()

        machine.reconcileWithPlayBilling(
            purchases          = listOf(purchase),
            queriedProductType = BillingClient.ProductType.SUBS
        )
        delay(100)

        assertEquals(SubscriptionStateMachineV2.SubscriptionState.PurchasePending, machine.getCurrentState())
    }

    @Test
    fun `reconcile already-Active same token and expiry is a fast-path no-op on second call`() = runBlocking {
        val machine = createMachine()
        val token   = "tok-same"
        val expiry  = System.currentTimeMillis() + 30 * 24 * 60 * 60 * 1000L

        val purchase = makeMockPurchase(
            productId      = STD_PRODUCT,
            purchaseToken  = token,
            purchaseState  = Purchase.PurchaseState.PURCHASED,
            isAcknowledged = true,
            isAutoRenewing = true
        )
        coEvery { mockRepository.getByPurchaseToken(token) }       returns null
        coEvery { mockRepository.getCurrentSubscription() }        returns null
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns emptyList()

        // First reconcile: activates the subscription
        machine.reconcileWithPlayBilling(
            purchases         = listOf(purchase),
            purchaseExpiryMap = mapOf(token to expiry),
            queriedProductType = BillingClient.ProductType.SUBS
        )
        delay(100)
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Active, machine.getCurrentState())

        // Second reconcile: same token + same expiry → fast-path skip
        val savedRow = makeActiveSub(purchaseToken = token).also { it.billingExpiry = expiry }
        coEvery { mockRepository.getByPurchaseToken(token) }       returns savedRow
        coEvery { mockRepository.getCurrentSubscription() }        returns savedRow
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns emptyList()
        clearMocks(mockRepository, answers = false)
        coEvery { mockRepository.upsert(any()) }               returns 1L
        coEvery { mockRepository.getByPurchaseToken(token) }   returns savedRow
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns emptyList()

        machine.reconcileWithPlayBilling(
            purchases         = listOf(purchase),
            purchaseExpiryMap = mapOf(token to expiry),
            queriedProductType = BillingClient.ProductType.SUBS
        )
        delay(100)

        // No DB writes on the second (no-op) reconcile
        coVerify(exactly = 0) { mockRepository.upsert(any()) }
    }

    @Test
    fun `reconcile SUBS orphaned token absent from Play snapshot is expired in DB`() = runBlocking {
        val machine   = createMachine()
        val expiry    = System.currentTimeMillis() + 30 * 24 * 60 * 60 * 1000L
        val orphanTok = "tok-orphan"
        val newTok    = "tok-new"

        // DB has an ACTIVE SUBS row with orphanTok — NOT in the Play snapshot
        val orphanSub = makeActiveSub(purchaseToken = orphanTok, productId = STD_PRODUCT)
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns listOf(orphanSub)
        coEvery { mockRepository.getByPurchaseToken(newTok) }      returns null
        coEvery { mockRepository.getCurrentSubscription() }        returns null

        val purchase = makeMockPurchase(
            productId      = STD_PRODUCT,
            purchaseToken  = newTok,
            purchaseState  = Purchase.PurchaseState.PURCHASED,
            isAcknowledged = true,
            isAutoRenewing = true
        )

        machine.reconcileWithPlayBilling(
            purchases         = listOf(purchase),
            purchaseExpiryMap = mapOf(newTok to expiry),
            queriedProductType = BillingClient.ProductType.SUBS
        )
        delay(100)

        // Orphaned row must be marked EXPIRED
        coVerify {
            mockRepository.upsert(match {
                it.purchaseToken == orphanTok &&
                it.status        == SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
            })
        }
    }

    // =========================================================================
    // 5. reconcileWithPlayBilling — INAPP
    // =========================================================================

    @Test
    fun `reconcile active INAPP purchase not yet expired transitions machine to Active`() = runBlocking {
        val machine      = createMachine()
        val futureExpiry = System.currentTimeMillis() + 2 * 365 * 24 * 60 * 60 * 1000L
        val token        = "tok-inapp-active"

        val purchase = makeMockPurchase(
            productId      = INAPP_2YRS,
            purchaseToken  = token,
            purchaseState  = Purchase.PurchaseState.PURCHASED,
            isAcknowledged = true,
            isAutoRenewing = false  // INAPP: no auto-renewal
        )
        coEvery { mockRepository.getByPurchaseToken(token) }       returns null
        coEvery { mockRepository.getCurrentSubscription() }        returns null
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns emptyList()

        machine.reconcileWithPlayBilling(
            purchases         = listOf(purchase),
            purchaseExpiryMap = mapOf(token to futureExpiry),
            queriedProductType = BillingClient.ProductType.INAPP
        )
        delay(100)

        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Active, machine.getCurrentState())
    }

    @Test
    fun `reconcile INAPP purchase with past local expiry maps machine to Expired`() = runBlocking {
        val machine     = createMachine()
        val pastExpiry  = System.currentTimeMillis() - 1_000L  // 1 second in the past
        val token       = "tok-inapp-expired"

        val purchase = makeMockPurchase(
            productId      = INAPP_2YRS,
            purchaseToken  = token,
            purchaseState  = Purchase.PurchaseState.PURCHASED,
            isAcknowledged = true,
            isAutoRenewing = false
        )
        coEvery { mockRepository.getByPurchaseToken(any()) }       returns null
        coEvery { mockRepository.getCurrentSubscription() }        returns null
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns emptyList()

        machine.reconcileWithPlayBilling(
            purchases         = listOf(purchase),
            purchaseExpiryMap = mapOf(token to pastExpiry),
            queriedProductType = BillingClient.ProductType.INAPP
        )
        delay(100)

        // INAPP: local clock is the sole authority for expiry
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Expired, machine.getCurrentState())
    }

    @Test
    fun `reconcile INAPP purchase with Long_MAX_VALUE expiry (unknown) maps to Active`() = runBlocking {
        val machine = createMachine()
        val token   = "tok-inapp-unknown-expiry"

        val purchase = makeMockPurchase(
            productId      = INAPP_2YRS,
            purchaseToken  = token,
            purchaseState  = Purchase.PurchaseState.PURCHASED,
            isAcknowledged = true,
            isAutoRenewing = false
        )
        coEvery { mockRepository.getByPurchaseToken(any()) }       returns null
        coEvery { mockRepository.getCurrentSubscription() }        returns null
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns emptyList()

        // Long.MAX_VALUE = "expiry not yet computed by Play"
        machine.reconcileWithPlayBilling(
            purchases         = listOf(purchase),
            purchaseExpiryMap = mapOf(token to Long.MAX_VALUE),
            queriedProductType = BillingClient.ProductType.INAPP
        )
        delay(100)

        // hasRealExpiry = false (MAX_VALUE) → not considered expired → Active
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Active, machine.getCurrentState())
    }

    // =========================================================================
    // 6. expireStaleInAppFromDb
    // =========================================================================

    @Test
    fun `expireStaleInAppFromDb locally-expired INAPP row is marked EXPIRED`() = runBlocking {
        val machine   = createMachine()
        transitionToActive(machine)

        val inAppSub = makeActiveSub(purchaseToken = "tok-expired-inapp", productId = INAPP_2YRS).also {
            it.billingExpiry = System.currentTimeMillis() - 1_000L  // expired locally
        }
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns listOf(inAppSub)

        machine.expireStaleInAppFromDb(playTokens = setOf("tok-expired-inapp"))

        coVerify {
            mockRepository.upsert(match {
                it.purchaseToken == "tok-expired-inapp" &&
                it.status == SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
            })
        }
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Expired, machine.getCurrentState())
    }

    @Test
    fun `expireStaleInAppFromDb token absent from non-empty Play snapshot is expired`() = runBlocking {
        val machine  = createMachine()
        transitionToActive(machine)

        val inAppSub = makeActiveSub(purchaseToken = "tok-absent", productId = INAPP_2YRS).also {
            it.billingExpiry = System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L  // not locally expired
        }
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns listOf(inAppSub)

        // Pass a non-empty set that does NOT contain "tok-absent" → absent-from-Play
        machine.expireStaleInAppFromDb(playTokens = setOf("tok-other"))

        coVerify {
            mockRepository.upsert(match {
                it.purchaseToken == "tok-absent" &&
                it.status == SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
            })
        } // All INAPP rows were expired → state should be Expired
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Expired, machine.getCurrentState())
    }

    /**
     * Regression test for the bug where a user with two INAPP purchases (e.g. bought a new
     * plan after the original one) had their subscription forcibly expired.
     *
     * Scenario:
     *  - DB contains two active INAPP rows: an OLD superseded token (absent from Play) and a
     *    NEW active token (present in Play).
     *  - Play snapshot returns only the NEW token.
     *  - expireStaleInAppFromDb should expire the OLD row (DB cleanup) but MUST NOT fire
     *    SubscriptionExpired because the NEW purchase is still active.
     */
    @Test
    fun `expireStaleInAppFromDb old superseded token expired but newer active token keeps state Active`() = runBlocking {
        val machine = createMachine()
        transitionToActive(machine)

        val now = System.currentTimeMillis()
        val oldSub = makeActiveSub(purchaseToken = "tok-old-superseded", productId = INAPP_2YRS).also {
            it.billingExpiry = now + 365 * 24 * 60 * 60 * 1000L  // still future — was superseded, not expired
        }
        val newSub = makeActiveSub(purchaseToken = "tok-new-active", productId = INAPP_2YRS).also {
            it.billingExpiry = now + 2 * 365 * 24 * 60 * 60 * 1000L  // further-future new purchase
        }
        // DB returns both rows as active
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns listOf(oldSub, newSub)

        // Play snapshot only contains the NEW token; old one is absent (superseded)
        machine.expireStaleInAppFromDb(playTokens = setOf("tok-new-active"))

        // Old row must be expired in DB
        coVerify {
            mockRepository.upsert(match {
                it.purchaseToken == "tok-old-superseded" &&
                it.status == SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
            })
        }
        // New row must NOT be touched
        coVerify(exactly = 0) {
            mockRepository.upsert(match { it.purchaseToken == "tok-new-active" })
        }
        // State machine MUST remain Active — the user still has a valid purchase
        assertEquals(
            "State must remain Active when a newer INAPP purchase is still valid in Play snapshot",
            SubscriptionStateMachineV2.SubscriptionState.Active,
            machine.getCurrentState()
        )
    }

    @Test
    fun `expireStaleInAppFromDb empty playTokens (no INAPP records) expires all active INAPP rows`() = runBlocking {
        val machine  = createMachine()
        transitionToActive(machine)

        val inAppSub = makeActiveSub(purchaseToken = "tok-any-inapp", productId = INAPP_ONETIME).also {
            it.billingExpiry = System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        }
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns listOf(inAppSub)

        machine.expireStaleInAppFromDb(playTokens = emptySet())

        coVerify {
            mockRepository.upsert(match { it.status == SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id })
        }
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Expired, machine.getCurrentState())
    }

    @Test
    fun `expireStaleInAppFromDb valid INAPP present in Play snapshot and not locally expired is NOT touched`() = runBlocking {
        val machine  = createMachine()
        val inAppSub = makeActiveSub(purchaseToken = "tok-valid-inapp", productId = INAPP_2YRS).also {
            it.billingExpiry = System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L
        }
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns listOf(inAppSub)

        // Token IS in the snapshot and not locally expired → no action
        machine.expireStaleInAppFromDb(playTokens = setOf("tok-valid-inapp"))

        coVerify(exactly = 0) { mockRepository.upsert(any()) }
    }

    @Test
    fun `expireStaleInAppFromDb does not touch SUBS rows`() = runBlocking {
        val machine = createMachine()
        transitionToActive(machine)

        // SUBS row that would be locally expired — must NOT be touched by expireStaleInAppFromDb
        val subRow = makeActiveSub(purchaseToken = "tok-subs", productId = STD_PRODUCT).also {
            it.billingExpiry = System.currentTimeMillis() - 1_000L
        }
        coEvery { mockRepository.getSubscriptionsByStates(any()) } returns listOf(subRow)

        machine.expireStaleInAppFromDb(playTokens = emptySet())

        // SUBS row is filtered out — no upsert
        coVerify(exactly = 0) { mockRepository.upsert(any()) }
    }

    // =========================================================================
    // 7. State lifecycle — Cancel / Expire / Revoke from Active
    // =========================================================================

    @Test
    fun `userCancelled transitions Active to Cancelled and writes CANCELLED to DB`() = runBlocking {
        val machine = createMachine()
        val sub     = makeActiveSub()
        transitionToActiveWithData(machine, sub)

        coEvery { mockRepository.upsert(any()) }            returns 1L
        coEvery { mockRepository.getCurrentSubscription() } returns sub

        machine.userCancelled()
        delay(100)

        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Cancelled, machine.getCurrentState())
        coVerify {
            mockRepository.upsert(match { it.status == SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id })
        }
    }

    @Test
    fun `subscriptionExpired transitions Active to Expired and writes EXPIRED to DB`() = runBlocking {
        val machine = createMachine()
        val sub     = makeActiveSub()
        transitionToActiveWithData(machine, sub)

        coEvery { mockRepository.upsert(any()) }            returns 1L
        coEvery { mockRepository.getCurrentSubscription() } returns sub

        machine.subscriptionExpired()
        delay(100)

        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Expired, machine.getCurrentState())
        coVerify {
            mockRepository.upsert(match { it.status == SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id })
        }
    }

    @Test
    fun `subscriptionRevoked transitions Active to Revoked and writes REVOKED to DB`() = runBlocking {
        val machine = createMachine()
        val sub     = makeActiveSub()
        transitionToActiveWithData(machine, sub)

        coEvery { mockRepository.upsert(any()) }            returns 1L
        coEvery { mockRepository.getCurrentSubscription() } returns sub

        machine.subscriptionRevoked()
        delay(100)

        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Revoked, machine.getCurrentState())
        coVerify {
            mockRepository.upsert(match { it.status == SubscriptionStatus.SubscriptionState.STATE_REVOKED.id })
        }
    }

    @Test
    fun `subscriptionExpired launches deactivateRpn with 'expired' reason`() = runBlocking {
        val machine = createMachine()
        val sub     = makeActiveSub()
        transitionToActiveWithData(machine, sub)

        coEvery { mockRepository.upsert(any()) }            returns 1L
        coEvery { mockRepository.getCurrentSubscription() } returns sub

        machine.subscriptionExpired()
        Thread.sleep(SIDE_EFFECT_WAIT_MS)  // scope.launch (IO) side-effect

        coVerify { RpnProxyManager.deactivateRpn("expired") }
    }

    @Test
    fun `subscriptionRevoked launches deactivateRpn with 'revoked' reason`() = runBlocking {
        val machine = createMachine()
        val sub     = makeActiveSub()
        transitionToActiveWithData(machine, sub)

        coEvery { mockRepository.upsert(any()) }            returns 1L
        coEvery { mockRepository.getCurrentSubscription() } returns sub

        machine.subscriptionRevoked()
        Thread.sleep(SIDE_EFFECT_WAIT_MS)

        coVerify { RpnProxyManager.deactivateRpn("revoked") }
    }

    @Test
    fun `subscriptionExpired already-EXPIRED row is idempotent - no second DB write`() = runBlocking {
        val machine = createMachine()
        val sub     = makeActiveSub()
        transitionToActiveWithData(machine, sub)

        // Row already marked EXPIRED
        sub.status = SubscriptionStatus.SubscriptionState.STATE_EXPIRED.id
        coEvery { mockRepository.upsert(any()) }            returns 1L
        coEvery { mockRepository.getCurrentSubscription() } returns sub
        clearMocks(mockRepository, answers = false)
        coEvery { mockRepository.upsert(any()) }            returns 1L
        coEvery { mockRepository.getCurrentSubscription() } returns sub

        machine.subscriptionExpired()
        delay(100)

        // Already EXPIRED → no duplicate upsert
        coVerify(exactly = 0) { mockRepository.upsert(any()) }
    }

    @Test
    fun `Expired to Active via paymentSuccessful (re-subscribe after expiry)`() = runBlocking {
        val machine = createMachine()
        val sub     = makeActiveSub()
        transitionToActiveWithData(machine, sub)

        // Expire
        coEvery { mockRepository.upsert(any()) }            returns 1L
        coEvery { mockRepository.getCurrentSubscription() } returns sub
        machine.subscriptionExpired()
        delay(50)
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Expired, machine.getCurrentState())

        // Re-subscribe with a new token
        val newToken = "tok-renew-after-expire"
        val newPd    = makePurchaseDetail(STD_PRODUCT, purchaseToken = newToken)
        coEvery { mockRepository.getByPurchaseToken(newToken) } returns null
        coEvery { mockRepository.getCurrentSubscription() }     returns sub

        machine.paymentSuccessful(newPd)
        delay(100)

        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Active, machine.getCurrentState())
    }

    // =========================================================================
    // 8. Utility methods
    // =========================================================================

    @Test
    fun `getCurrentState returns Initial after empty-DB machine creation`() {
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Initial, createMachine().getCurrentState())
    }

    @Test
    fun `canMakePurchase returns true when in Initial state`() {
        assertTrue(createMachine().canMakePurchase())
    }

    @Test
    fun `hasValidSubscription returns false when in Initial state`() {
        assertFalse(createMachine().hasValidSubscription())
    }

    @Test
    fun `isSubscriptionActive returns false when in Initial state`() {
        assertFalse(createMachine().isSubscriptionActive())
    }

    @Test
    fun `isNewPurchase returns true when token not in DB`() = runBlocking {
        val machine = createMachine()
        coEvery { mockRepository.getByPurchaseToken("tok-brand-new") } returns null

        assertTrue(machine.isNewPurchase("tok-brand-new"))
    }

    @Test
    fun `isNewPurchase returns false when token already in DB`() = runBlocking {
        val machine = createMachine()
        coEvery { mockRepository.getByPurchaseToken("tok-existing") } returns makeActiveSub(purchaseToken = "tok-existing")

        assertFalse(machine.isNewPurchase("tok-existing"))
    }

    @Test
    fun `createPurchaseDetailFromSubscription maps SUBS productId to SUBS productType`() {
        val machine = createMachine()
        val sub     = makeActiveSub(productId = STD_PRODUCT)

        val pd = machine.createPurchaseDetailFromSubscription(sub)

        assertEquals(BillingClient.ProductType.SUBS, pd.productType)
        assertTrue("SUBS should be auto-renewing unless cancelled", pd.isAutoRenewing)
    }

    @Test
    fun `createPurchaseDetailFromSubscription maps INAPP productId to INAPP productType`() {
        val machine = createMachine()
        val sub     = makeActiveSub(productId = INAPP_2YRS)

        val pd = machine.createPurchaseDetailFromSubscription(sub)

        assertEquals(BillingClient.ProductType.INAPP, pd.productType)
        assertFalse("INAPP should never auto-renew", pd.isAutoRenewing)
    }

    @Test
    fun `createPurchaseDetailFromSubscription SUBS CANCELLED status isAutoRenewing=false`() {
        val machine = createMachine()
        val sub     = makeActiveSub(productId = STD_PRODUCT).also {
            it.status = SubscriptionStatus.SubscriptionState.STATE_CANCELLED.id
        }

        val pd = machine.createPurchaseDetailFromSubscription(sub)

        assertFalse("Cancelled SUBS should not be auto-renewing", pd.isAutoRenewing)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Constructs a fresh [SubscriptionStateMachineV2] and waits [MACHINE_INIT_WAIT_MS] for the
     * `initializeStateMachine()` coroutine (which runs on [kotlinx.coroutines.Dispatchers.IO]) to
     * complete.  With the default mock of `loadStateFromDatabase() = null`, the machine settles at
     * [SubscriptionStateMachineV2.SubscriptionState.Initial].
     */
    private fun createMachine(): SubscriptionStateMachineV2 {
        val machine = SubscriptionStateMachineV2()
        Thread.sleep(MACHINE_INIT_WAIT_MS)
        return machine
    }

    /** Creates a [PurchaseDetail] representing a freshly-purchased active subscription. */
    private fun makePurchaseDetail(
        productId: String,
        purchaseToken: String  = "test-token-${System.nanoTime()}",
        expiryTime: Long       = System.currentTimeMillis() + 30 * 24 * 60 * 60 * 1000L,
        isAutoRenewing: Boolean = true
    ) = PurchaseDetail(
        productId        = productId,
        planId           = productId,
        productTitle     = "Test Product",
        state            = Purchase.PurchaseState.PURCHASED,
        planTitle        = "Test Plan",
        purchaseToken    = purchaseToken,
        productType      = if (productId.contains("onetime", true) || productId.contains("yearly-"))
                               BillingClient.ProductType.INAPP
                           else
                               BillingClient.ProductType.SUBS,
        purchaseTime     = "2025-01-01",
        purchaseTimeMillis = System.currentTimeMillis() - 24 * 60 * 60 * 1000L,
        isAutoRenewing   = isAutoRenewing,
        accountId        = "acc-test",
        deviceId         = "",
        payload          = "",
        expiryTime       = expiryTime,
        status           = SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id,
        windowDays       = 3,
        orderId          = "order-test"
    )

    /** Creates a [SubscriptionStatus] row representing an active SUBS subscription. */
    private fun makeActiveSub(
        purchaseToken: String = "test-token",
        productId: String     = STD_PRODUCT
    ) = SubscriptionStatus().apply {
        id            = 1
        this.purchaseToken = purchaseToken
        this.productId     = productId
        planId        = productId
        productTitle  = "Test Product"
        status        = SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id
        state         = Purchase.PurchaseState.PURCHASED
        purchaseTime  = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        billingExpiry = System.currentTimeMillis() + 30 * 24 * 60 * 60 * 1000L
        accountExpiry = billingExpiry
        lastUpdatedTs = System.currentTimeMillis()
    }

    /** Wraps a [SubscriptionStatus] into the [StateMachineDatabaseSyncService.DatabaseStateInfo] type. */
    private fun makeDatabaseStateInfo(
        sub: SubscriptionStatus,
        recommendedState: SubscriptionStateMachineV2.SubscriptionState
    ) = StateMachineDatabaseSyncService.DatabaseStateInfo(
        currentSubscription = sub,
        recommendedState    = recommendedState,
        lastTransitionTime  = System.currentTimeMillis()
    )

    /** Creates a mock [Purchase] with the given parameters. */
    private fun makeMockPurchase(
        productId: String,
        purchaseToken: String,
        purchaseState: Int,
        isAcknowledged: Boolean,
        isAutoRenewing: Boolean
    ): Purchase {
        val p = mockk<Purchase>()
        every { p.products }           returns listOf(productId)
        every { p.purchaseToken }      returns purchaseToken
        every { p.purchaseState }      returns purchaseState
        every { p.isAcknowledged }     returns isAcknowledged
        every { p.isAutoRenewing }     returns isAutoRenewing
        every { p.developerPayload }   returns ""
        every { p.originalJson }       returns ""
        every { p.accountIdentifiers } returns null
        every { p.orderId }            returns "order-mock"
        every { p.purchaseTime }       returns System.currentTimeMillis() - 3_600_000L
        return p
    }

    /** Transitions the machine from [Initial] to [Active] via a synthetic [paymentSuccessful]. */
    private suspend fun transitionToActive(machine: SubscriptionStateMachineV2) {
        val pd = makePurchaseDetail(STD_PRODUCT)
        coEvery { mockRepository.getByPurchaseToken(pd.purchaseToken) } returns null
        coEvery { mockRepository.getCurrentSubscription() }             returns null
        coEvery { mockRepository.upsert(any()) }                        returns 1L
        machine.paymentSuccessful(pd)
        delay(100)
    }

    /**
     * Transitions the machine to Active using a [PurchaseDetail] derived from [sub].
     * After the call returns, [machine] is in [Active] state and the state machine's internal
     * [SubscriptionStateMachineV2.SubscriptionData] holds a row with [sub]'s token/productId.
     */
    private suspend fun transitionToActiveWithData(
        machine: SubscriptionStateMachineV2,
        sub: SubscriptionStatus
    ) {
        val pd = makePurchaseDetail(sub.productId, purchaseToken = sub.purchaseToken)
        coEvery { mockRepository.getByPurchaseToken(pd.purchaseToken) } returns null
        coEvery { mockRepository.getCurrentSubscription() }             returns null
        coEvery { mockRepository.upsert(any()) }                        returns 1L
        machine.paymentSuccessful(pd)
        delay(100)
        sub.status = SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id
    }
}









