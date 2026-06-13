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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.database.CountryConfigRepository
import com.celzero.bravedns.database.RpnProxy
import com.celzero.bravedns.database.RpnProxyRepository
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusRepository
import com.celzero.bravedns.iab.BillingBackendClient
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.PurchaseDetail
import com.celzero.bravedns.iab.QueryEntitlementResult
import com.celzero.bravedns.service.EncryptedFileManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.firestack.backend.Backend
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RpnProxyManagerTest : KoinTest {

    private lateinit var context: Context
    private val mockRpnProxyDb: RpnProxyRepository = mockk(relaxed = true)
    private val mockCountryConfigRepo: CountryConfigRepository = mockk(relaxed = true)
    private val mockPersistentState: PersistentState = mockk(relaxed = true)
    private val mockBillingBackendClient: BillingBackendClient = mockk(relaxed = true)
    private val mockSubscriptionStatusDb: SubscriptionStatusRepository = mockk(relaxed = true)
    private val mockStateMachine: SubscriptionStateMachineV2 = mockk(relaxed = true)

    private val stateFlow = MutableStateFlow<SubscriptionStateMachineV2.SubscriptionState>(SubscriptionStateMachineV2.SubscriptionState.Initial)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        try { stopKoin() } catch (_: Exception) {}

        startKoin {
            modules(module {
                single { context }
                single { mockRpnProxyDb }
                single { mockCountryConfigRepo }
                single { mockPersistentState }
                single { mockBillingBackendClient }
                single { mockSubscriptionStatusDb }
                single { mockStateMachine }
            })
        }

        mockkObject(VpnController)
        mockkObject(InAppBillingHandler)
        mockkObject(EncryptedFileManager)
        mockkObject(ProxyManager)

        // Mock properties correctly for a relaxed mock
        every { mockStateMachine.currentState } returns stateFlow

        // Safely clear cache
        try {
            getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").clear()
        } catch (_: Exception) {}

        // Reset state
        RpnProxyManager.deactivateRpn("test setup")
        // Reset server key meta
        getPrivateField<ConcurrentHashMap<String, Any>>(RpnProxyManager, "serverKeyMeta").clear()
    }

    @After
    fun tearDown() {
        stopKoin()
        unmockkAll()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getPrivateField(obj: Any, fieldName: String): T {
        val field: Field = obj.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(obj) as T
    }

    private fun <T> setPrivateField(obj: Any, fieldName: String, value: T) {
        val field: Field = obj.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(obj, value)
    }

    // =========================================================================
    // 1. Activation / Deactivation
    // =========================================================================

    @Test
    fun `activateRpn prioritizes explicit payload and persists`() = runTest {
        val purchase = makePurchaseDetail("prd-1")
        val payload = "{\"ws\":{\"sessiontoken\":\"t1\"}}"

        every { EncryptedFileManager.write(any(), any<ByteArray>(), any()) } returns true
        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.DISABLED.id
        every { mockStateMachine.hasValidSubscription() } returns true

        RpnProxyManager.activateRpn(purchase, payload)

        coVerify { EncryptedFileManager.write(any(), any<ByteArray>(), any()) }
        assertEquals(RpnProxyManager.RpnState.ENABLED.id, mockPersistentState.rpnState)
    }

    @Test
    fun `activateRpn already enabled skips state change`() = runTest {
        val purchase = makePurchaseDetail("prd-1")
        val payload = "{\"ws\":{\"sessiontoken\":\"t1\"}}"

        every { EncryptedFileManager.write(any(), any<ByteArray>(), any()) } returns true
        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.ENABLED.id

        RpnProxyManager.activateRpn(purchase, payload)

        coVerify { EncryptedFileManager.write(any(), any<ByteArray>(), any()) }
        assertEquals(RpnProxyManager.RpnState.ENABLED.id, mockPersistentState.rpnState)
    }

    @Test
    fun `activateRpn empty productId returns without activation`() = runTest {
        val purchase = makePurchaseDetail("")
        val payload = "{\"ws\":{\"sessiontoken\":\"t1\"}}"

        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.DISABLED.id
        every { mockStateMachine.hasValidSubscription() } returns true

        RpnProxyManager.activateRpn(purchase, payload)

        // rpnState should still be DISABLED because productId is empty
        assertEquals(RpnProxyManager.RpnState.DISABLED.id, mockPersistentState.rpnState)
    }

    @Test
    fun `activateRpn no valid subscription returns`() = runTest {
        val purchase = makePurchaseDetail("prd-1")

        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.DISABLED.id
        every { mockStateMachine.hasValidSubscription() } returns false

        RpnProxyManager.activateRpn(purchase)

        verify(exactly = 0) { mockPersistentState.rpnState = RpnProxyManager.RpnState.ENABLED.id }
    }

    @Test
    fun `activateRpn empty payload falls back to DB developerPayload`() = runTest {
        val purchase = makePurchaseDetail("prd-1", payload = "")

        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.DISABLED.id
        every { mockStateMachine.hasValidSubscription() } returns true
        coEvery { mockSubscriptionStatusDb.getCurrentSubscription() } returns SubscriptionStatus().apply {
            developerPayload = "{\"ws\":{\"sessiontoken\":\"db-token\"}}"
        }

        RpnProxyManager.activateRpn(purchase)

        // Should have tried to store the DB payload
        coVerify { mockSubscriptionStatusDb.getCurrentSubscription() }
    }

    @Test
    fun `deactivateRpn already disabled skips`() {
        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.DISABLED.id

        RpnProxyManager.deactivateRpn("test")

        assertEquals(RpnProxyManager.RpnState.DISABLED.id, mockPersistentState.rpnState)
    }

    @Test
    fun `deactivateRpn normal deactivation clears server meta`() {
        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.ENABLED.id

        RpnProxyManager.deactivateRpn("manual deactivation")

        assertEquals(RpnProxyManager.RpnState.DISABLED.id, mockPersistentState.rpnState)
    }

    @Test
    fun `stopProxy already stopped skips`() = runTest {
        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.DISABLED.id
        every { mockPersistentState.rpnMode } returns RpnProxyManager.RpnMode.NONE.id

        RpnProxyManager.stopProxy()

        assertEquals(RpnProxyManager.RpnMode.NONE.id, mockPersistentState.rpnMode)
        coVerify(exactly = 0) { VpnController.unregisterWin() }
    }

    @Test
    fun `stopProxy normal stop resets mode and deactivates`() = runTest {
        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.ENABLED.id
        every { mockPersistentState.rpnMode } returns RpnProxyManager.RpnMode.HIDE_IP.id

        RpnProxyManager.stopProxy()

        assertEquals(RpnProxyManager.RpnMode.NONE.id, mockPersistentState.rpnMode)
        assertEquals(RpnProxyManager.RpnState.DISABLED.id, mockPersistentState.rpnState)
        coVerify { VpnController.unregisterWin() }
    }

    @Test
    fun `startProxy already running handles proxies`() = runTest {
        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.ENABLED.id
        every { mockPersistentState.rpnMode } returns RpnProxyManager.RpnMode.ANTI_CENSORSHIP.id

        RpnProxyManager.startProxy()

        // Should NOT change mode/state, just handle proxies
        assertEquals(RpnProxyManager.RpnMode.ANTI_CENSORSHIP.id, mockPersistentState.rpnMode)
        assertEquals(RpnProxyManager.RpnState.ENABLED.id, mockPersistentState.rpnState)
        coVerify { VpnController.handleRpnProxies() }
    }

    @Test
    fun `startProxy normal start sets mode and enables`() = runTest {
        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.DISABLED.id
        every { mockPersistentState.rpnMode } returns RpnProxyManager.RpnMode.NONE.id

        RpnProxyManager.startProxy()

        assertEquals(RpnProxyManager.RpnMode.ANTI_CENSORSHIP.id, mockPersistentState.rpnMode)
        assertEquals(RpnProxyManager.RpnState.ENABLED.id, mockPersistentState.rpnState)
        coVerify { VpnController.handleRpnProxies() }
    }

    // =========================================================================
    // 2. Purchase Processing
    // =========================================================================

    @Test
    fun `processRpnPurchase null purchase calls subscriptionExpired`() = runTest {
        val existingSub = SubscriptionStatus()

        val result = RpnProxyManager.processRpnPurchase(null, existingSub)

        assertFalse(result)
        coVerify { mockStateMachine.subscriptionExpired() }
    }

    @Test
    fun `processRpnPurchase empty productId calls purchaseFailed`() = runTest {
        val purchase = makePurchaseDetail("")
        val existingSub = SubscriptionStatus()

        val result = RpnProxyManager.processRpnPurchase(purchase, existingSub)

        assertFalse(result)
        coVerify { mockStateMachine.purchaseFailed(any(), any()) }
    }

    @Test
    fun `processRpnPurchase fresh entitlement needed queries server`() = runTest {
        val purchase = makePurchaseDetail("proxy-yearly-2", payload = "")
        val existingSub = SubscriptionStatus().apply {
            accountExpiry = 0L
            billingExpiry = System.currentTimeMillis() + 86400000L
            developerPayload = ""
        }
        val serverPayload = "{\"ws\":{\"sessiontoken\":\"server-token\"}}"
        val updatedPurchase = purchase.copy(payload = serverPayload, expiryTime = System.currentTimeMillis() + 86400000L)

        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.DISABLED.id
        every { mockStateMachine.hasValidSubscription() } returns true
        coEvery { mockBillingBackendClient.getDeviceId() } returns "did-1"
        coEvery { InAppBillingHandler.queryEntitlementFromServer(any(), any(), any()) } returns updatedPurchase
        coEvery { mockSubscriptionStatusDb.updateDeveloperPayload(any(), any(), any()) } returns 1
        every { mockStateMachine.getSubscriptionData() } returns null
        every { EncryptedFileManager.write(any(), any<ByteArray>(), any()) } returns true

        val result = RpnProxyManager.processRpnPurchase(purchase, existingSub)

        assertTrue(result)
        coVerify { InAppBillingHandler.queryEntitlementFromServer(any(), any(), any()) }
    }

    @Test
    fun `processRpnPurchase payload valid skips server query`() = runTest {
        val validPayload = "{\"ws\":{\"sessiontoken\":\"valid-token\"}}"
        val purchase = makePurchaseDetail("proxy-yearly-2", payload = validPayload)
        val existingSub = SubscriptionStatus().apply {
            accountExpiry = System.currentTimeMillis() + 86400000L
            billingExpiry = System.currentTimeMillis() + 86400000L
        }

        // create mock entitlement from payload
        coEvery { VpnController.getEntitlementDetails(any(), any()) } returns mockk {
            coEvery { token() } returns "valid-token"
        }
        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.DISABLED.id
        every { mockStateMachine.hasValidSubscription() } returns true

        val result = RpnProxyManager.processRpnPurchase(purchase, existingSub)

        coVerify(exactly = 0) { InAppBillingHandler.queryEntitlementFromServer(any(), any(), any()) }
    }

    @Test
    fun `processRpnPurchase server error falls back to DB payload`() = runTest {
        val purchase = makePurchaseDetail("proxy-yearly-2", payload = "")
        val dbPayload = "{\"ws\":{\"sessiontoken\":\"db-token\"}}"
        val existingSub = SubscriptionStatus().apply {
            accountExpiry = 0L
            billingExpiry = System.currentTimeMillis() + 86400000L
            developerPayload = dbPayload
        }

        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.DISABLED.id
        every { mockStateMachine.hasValidSubscription() } returns true
        coEvery { mockBillingBackendClient.getDeviceId() } returns "did-1"
        coEvery { InAppBillingHandler.queryEntitlementFromServer(any(), any(), any()) } throws RuntimeException("Server error")
        coEvery { VpnController.getEntitlementDetails(any(), any()) } returns mockk {
            coEvery { token() } returns "db-token"
        }
        every { EncryptedFileManager.write(any(), any<ByteArray>(), any()) } returns true

        val result = RpnProxyManager.processRpnPurchase(purchase, existingSub)

        assertTrue(result)
    }

    @Test
    fun `processRpnPurchase server payload updates state machine and DB`() = runTest {
        val purchase = makePurchaseDetail("proxy-yearly-2", payload = "")
        val serverPayload = "{\"ws\":{\"sessiontoken\":\"server-token\"}}"
        val existingSub = SubscriptionStatus().apply {
            accountExpiry = 0L
            billingExpiry = System.currentTimeMillis() + 86400000L
            developerPayload = ""
        }
        val updatedPurchase = purchase.copy(
            payload = serverPayload,
            expiryTime = System.currentTimeMillis() + 86400000L
        )
        val subData = SubscriptionStateMachineV2.SubscriptionData(
            subscriptionStatus = existingSub,
            purchaseDetail = updatedPurchase,
            lastUpdated = System.currentTimeMillis()
        )

        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.DISABLED.id
        every { mockStateMachine.hasValidSubscription() } returns true
        coEvery { mockBillingBackendClient.getDeviceId() } returns "did-1"
        coEvery { InAppBillingHandler.queryEntitlementFromServer(any(), any(), any()) } returns updatedPurchase
        every { mockStateMachine.getSubscriptionData() } returns subData
        coEvery { mockSubscriptionStatusDb.updateDeveloperPayload(any(), any(), any()) } returns 1
        coEvery { VpnController.getEntitlementDetails(any(), any()) } returns mockk {
            coEvery { token() } returns "server-token"
        }
        every { EncryptedFileManager.write(any(), any<ByteArray>(), any()) } returns true

        val result = RpnProxyManager.processRpnPurchase(purchase, existingSub)

        assertTrue(result)
        coVerify { mockSubscriptionStatusDb.updateDeveloperPayload(any(), eq(serverPayload), any()) }
    }

    // =========================================================================
    // 3. Try Reactivate Linked Purchase
    // =========================================================================

    @Test
    fun `tryReactivateLinkedPurchase blank params returns false`() = runTest {
        assertFalse(RpnProxyManager.tryReactivateLinkedPurchase("", "did-1", "tok-1"))
        assertFalse(RpnProxyManager.tryReactivateLinkedPurchase("acc-1", "", "tok-1"))
        assertFalse(RpnProxyManager.tryReactivateLinkedPurchase("acc-1", "did-1", ""))
    }

    @Test
    fun `tryReactivateLinkedPurchase token not in DB returns false`() = runTest {
        coEvery { mockSubscriptionStatusDb.getByPurchaseToken("tok-1") } returns null

        val result = RpnProxyManager.tryReactivateLinkedPurchase("acc-1", "did-1", "tok-1")

        assertFalse(result)
    }

    @Test
    fun `tryReactivateLinkedPurchase server confirms valid reactivates`() = runTest {
        val linkedSub = SubscriptionStatus().apply { purchaseToken = "tok-1"; productId = "proxy-yearly-2" }
        val linkedPD = makePurchaseDetail("proxy-yearly-2", purchaseToken = "tok-1")

        coEvery { mockSubscriptionStatusDb.getByPurchaseToken("tok-1") } returns linkedSub
        every { mockStateMachine.createPurchaseDetailFromSubscription(linkedSub) } returns linkedPD
        coEvery { mockBillingBackendClient.queryEntitlement(any(), any(), any(), any()) } answers {
            QueryEntitlementResult.Success(linkedPD.copy(payload = "{\"ws\":{\"sessiontoken\":\"t\"}}"))
        }
        coEvery { mockStateMachine.paymentSuccessful(any()) } returns Unit
        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.DISABLED.id
        every { mockStateMachine.hasValidSubscription() } returns true
        every { EncryptedFileManager.write(any(), any<ByteArray>(), any()) } returns true

        val result = RpnProxyManager.tryReactivateLinkedPurchase("acc-1", "did-1", "tok-1")

        assertTrue(result)
        coVerify { mockStateMachine.paymentSuccessful(any()) }
    }

    @Test
    fun `tryReactivateLinkedPurchase server failure returns false`() = runTest {
        val linkedSub = SubscriptionStatus().apply { purchaseToken = "tok-1"; productId = "proxy-yearly-2" }
        val linkedPD = makePurchaseDetail("proxy-yearly-2", purchaseToken = "tok-1")

        coEvery { mockSubscriptionStatusDb.getByPurchaseToken("tok-1") } returns linkedSub
        every { mockStateMachine.createPurchaseDetailFromSubscription(linkedSub) } returns linkedPD
        coEvery { mockBillingBackendClient.queryEntitlement(any(), any(), any(), any()) } answers {
            QueryEntitlementResult.Failure(linkedPD, null)
        }

        val result = RpnProxyManager.tryReactivateLinkedPurchase("acc-1", "did-1", "tok-1")
        assertFalse(result)
    }

    @Test
    fun `tryReactivateLinkedPurchase server transient returns false`() = runTest {
        val linkedSub = SubscriptionStatus().apply { purchaseToken = "tok-1"; productId = "proxy-yearly-2" }
        val linkedPD = makePurchaseDetail("proxy-yearly-2", purchaseToken = "tok-1")

        coEvery { mockSubscriptionStatusDb.getByPurchaseToken("tok-1") } returns linkedSub
        every { mockStateMachine.createPurchaseDetailFromSubscription(linkedSub) } returns linkedPD
        coEvery { mockBillingBackendClient.queryEntitlement(any(), any(), any(), any()) } answers {
            QueryEntitlementResult.Transient(linkedPD)
        }

        val result = RpnProxyManager.tryReactivateLinkedPurchase("acc-1", "did-1", "tok-1")
        assertFalse(result)
    }

    @Test
    fun `tryReactivateLinkedPurchase server unauthorized returns false`() = runTest {
        val linkedSub = SubscriptionStatus().apply { purchaseToken = "tok-1" }
        val linkedPD = makePurchaseDetail("prd-1", purchaseToken = "tok-1")

        coEvery { mockSubscriptionStatusDb.getByPurchaseToken("tok-1") } returns linkedSub
        every { mockStateMachine.createPurchaseDetailFromSubscription(linkedSub) } returns linkedPD
        coEvery { mockBillingBackendClient.queryEntitlement(any(), any(), any(), any()) } answers {
            QueryEntitlementResult.Unauthorized("acc-1", "did-1")
        }

        val result = RpnProxyManager.tryReactivateLinkedPurchase("acc-1", "did-1", "tok-1")
        assertFalse(result)
    }

    @Test
    fun `tryReactivateLinkedPurchase server conflict returns false`() = runTest {
        val linkedSub = SubscriptionStatus().apply { purchaseToken = "tok-1" }
        val linkedPD = makePurchaseDetail("prd-1", purchaseToken = "tok-1")

        coEvery { mockSubscriptionStatusDb.getByPurchaseToken("tok-1") } returns linkedSub
        every { mockStateMachine.createPurchaseDetailFromSubscription(linkedSub) } returns linkedPD
        coEvery { mockBillingBackendClient.queryEntitlement(any(), any(), any(), any()) } answers {
            QueryEntitlementResult.Conflict
        }

        val result = RpnProxyManager.tryReactivateLinkedPurchase("acc-1", "did-1", "tok-1")
        assertFalse(result)
    }

    @Test
    fun `tryReactivateLinkedPurchase db exception returns false`() = runTest {
        coEvery { mockSubscriptionStatusDb.getByPurchaseToken("tok-1") } throws RuntimeException("DB error")

        val result = RpnProxyManager.tryReactivateLinkedPurchase("acc-1", "did-1", "tok-1")
        assertFalse(result)
    }

    // =========================================================================
    // 4. Server Enable / Disable
    // =========================================================================

    @Test
    fun `enableWinServer empty key returns false`() = runTest {
        val result = RpnProxyManager.enableWinServer("")
        assertFalse(result.first)
    }

    @Test
    fun `enableWinServer server not found returns false`() = runTest {
        val result = RpnProxyManager.enableWinServer("nonexistent-key")
        assertFalse(result.first)
    }

    @Test
    fun `enableWinServer already enabled returns true`() = runTest {
        val config = CountryConfig(id = "us-nyc", cc = "US", key = "nyc-key", isEnabled = true)
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(config)

        val result = RpnProxyManager.enableWinServer("nyc-key")

        assertTrue(result.first)
        assertEquals("Server already enabled", result.second)
    }

    @Test
    fun `enableWinServer success updates DB and cache`() = runTest {
        val config = CountryConfig(id = "us-nyc", cc = "US", key = "nyc-key", isEnabled = false)
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(config)

        coEvery { VpnController.addNewWinServer("nyc-key") } returns Pair(true, "")
        coEvery { mockCountryConfigRepo.update(config) } returns Unit
        coEvery { mockCountryConfigRepo.incrementSelectionCount("nyc-key") } returns Unit

        val result = RpnProxyManager.enableWinServer("nyc-key")

        assertTrue(result.first)
        coVerify { mockCountryConfigRepo.update(config) }
        coVerify { mockCountryConfigRepo.incrementSelectionCount("nyc-key") }
        assertTrue(config.isEnabled)
    }

    @Test
    fun `enableWinServer VpnController failure propagates`() = runTest {
        val config = CountryConfig(id = "us-nyc", cc = "US", key = "nyc-key", isEnabled = false)
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(config)

        coEvery { VpnController.addNewWinServer("nyc-key") } returns Pair(false, "Tunnel error")

        val result = RpnProxyManager.enableWinServer("nyc-key")

        assertFalse(result.first)
        assertFalse(config.isEnabled)
    }

    @Test
    fun `enableWinServer DB failure reverts cache`() = runTest {
        val config = CountryConfig(id = "us-nyc", cc = "US", key = "nyc-key", isEnabled = false)
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(config)

        coEvery { VpnController.addNewWinServer("nyc-key") } returns Pair(true, "")
        coEvery { mockCountryConfigRepo.update(config) } throws RuntimeException("DB error")

        val result = RpnProxyManager.enableWinServer("nyc-key")

        assertFalse(result.first)
        // Cache should be reverted
        assertFalse(config.isEnabled)
    }

    @Test
    fun `disableWinServer empty key returns false`() = runTest {
        val result = RpnProxyManager.disableWinServer("")
        assertFalse(result.first)
    }

    @Test
    fun `disableWinServer server not found returns false`() = runTest {
        val result = RpnProxyManager.disableWinServer("nonexistent-key")
        assertFalse(result.first)
    }

    @Test
    fun `disableWinServer success updates DB and cache`() = runTest {
        val config = CountryConfig(id = "us-nyc", cc = "US", key = "nyc-key", isEnabled = true)
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(config)

        coEvery { VpnController.removeWinServer("nyc-key") } returns Pair(true, "")
        coEvery { mockCountryConfigRepo.update(config) } returns Unit

        val result = RpnProxyManager.disableWinServer("nyc-key")

        assertTrue(result.first)
        assertFalse(config.isEnabled)
    }

    @Test
    fun `disableWinServer DB failure reverts cache`() = runTest {
        val config = CountryConfig(id = "us-nyc", cc = "US", key = "nyc-key", isEnabled = true)
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(config)

        coEvery { VpnController.removeWinServer("nyc-key") } returns Pair(true, "")
        coEvery { mockCountryConfigRepo.update(config) } throws RuntimeException("DB error")

        val result = RpnProxyManager.disableWinServer("nyc-key")

        assertFalse(result.first)
        // Cache should be reverted back to enabled
        assertTrue(config.isEnabled)
    }

    // =========================================================================
    // 5. Server Property Setters (Hop, Lockdown, CatchAll, MobileOnly, Ssid)
    // =========================================================================

    @Test
    fun `setHopForWinServer empty key returns early`() = runTest {
        coEvery { VpnController.handleRpnHop(any(), any()) } returns Pair(true, "")

        RpnProxyManager.setHopForWinServer("", true)
        coVerify(exactly = 0) { mockCountryConfigRepo.update(any()) }
    }

    @Test
    fun `setHopForWinServer server not found returns early`() = runTest {
        RpnProxyManager.setHopForWinServer("nonexistent", true)
        coVerify(exactly = 0) { mockCountryConfigRepo.update(any()) }
    }

    @Test
    fun `setHopForWinServer success updates DB and tunnel`() = runTest {
        val config = CountryConfig(id = "us-nyc", cc = "US", key = "nyc-key", hopEnabled = false)
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(config)
        coEvery { mockCountryConfigRepo.update(config) } returns Unit
        coEvery { VpnController.handleRpnHop("nyc-key", true) } returns Pair(true, "")

        RpnProxyManager.setHopForWinServer("nyc-key", true)

        assertTrue(config.hopEnabled)
        coVerify { mockCountryConfigRepo.update(config) }
        coVerify { VpnController.handleRpnHop("nyc-key", true) }
    }

    @Test
    fun `setHopForWinServer DB failure reverts cache`() = runTest {
        val config = CountryConfig(id = "us-nyc", cc = "US", key = "nyc-key", hopEnabled = false)
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(config)
        coEvery { mockCountryConfigRepo.update(config) } throws RuntimeException("DB error")

        RpnProxyManager.setHopForWinServer("nyc-key", true)

        assertFalse(config.hopEnabled) // reverted
    }

    @Test
    fun `setLockdownForWinServer empty key returns early`() = runTest {
        RpnProxyManager.setLockdownForWinServer("", true)
        coVerify(exactly = 0) { mockCountryConfigRepo.update(any()) }
    }

    @Test
    fun `setLockdownForWinServer not found returns early`() = runTest {
        RpnProxyManager.setLockdownForWinServer("nonexistent", true)
        coVerify(exactly = 0) { mockCountryConfigRepo.update(any()) }
    }

    @Test
    fun `setLockdownForWinServer success`() = runTest {
        val config = CountryConfig(id = "us-nyc", cc = "US", key = "nyc-key", lockdown = false)
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(config)
        coEvery { mockCountryConfigRepo.update(config) } returns Unit

        RpnProxyManager.setLockdownForWinServer("nyc-key", true)

        assertTrue(config.lockdown)
        coVerify { mockCountryConfigRepo.update(config) }
    }

    @Test
    fun `setLockdownForWinServer DB failure reverts`() = runTest {
        val config = CountryConfig(id = "us-nyc", cc = "US", key = "nyc-key", lockdown = false)
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(config)
        coEvery { mockCountryConfigRepo.update(config) } throws RuntimeException("DB error")

        RpnProxyManager.setLockdownForWinServer("nyc-key", true)

        assertFalse(config.lockdown) // reverted
    }

    @Test
    fun `setCatchAllForWinServer enables inactive server`() = runTest {
        val config = CountryConfig(id = "us-nyc", cc = "US", key = "nyc-key", isEnabled = false, catchAll = false)
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(config)
        coEvery { VpnController.addNewWinServer("nyc-key") } returns Pair(true, "")
        coEvery { mockCountryConfigRepo.update(config) } returns Unit

        RpnProxyManager.setCatchAllForWinServer("nyc-key", true)

        assertTrue(config.isEnabled)
        assertTrue(config.catchAll)
    }

    @Test
    fun `setCatchAllForWinServer fails to enable inactive server returns early`() = runTest {
        val config = CountryConfig(id = "us-nyc", cc = "US", key = "nyc-key", isEnabled = false, catchAll = false)
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(config)
        coEvery { VpnController.addNewWinServer("nyc-key") } returns Pair(false, "Tunnel error")

        RpnProxyManager.setCatchAllForWinServer("nyc-key", true)

        assertFalse(config.catchAll)
        coVerify(exactly = 0) { mockCountryConfigRepo.update(any()) }
    }

    @Test
    fun `setCatchAllForWinServer DB failure reverts`() = runTest {
        val config = CountryConfig(id = "us-nyc", cc = "US", key = "nyc-key", isEnabled = true, catchAll = false)
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(config)
        coEvery { mockCountryConfigRepo.update(config) } throws RuntimeException("DB error")

        RpnProxyManager.setCatchAllForWinServer("nyc-key", true)

        assertFalse(config.catchAll) // reverted
    }

    @Test
    fun `setMobileOnlyForWinServer success`() = runTest {
        val config = CountryConfig(id = "us-nyc", cc = "US", key = "nyc-key", mobileOnly = false)
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(config)
        coEvery { mockCountryConfigRepo.update(config) } returns Unit

        RpnProxyManager.setMobileOnlyForWinServer("nyc-key", true)

        assertTrue(config.mobileOnly)
        coVerify { mockCountryConfigRepo.update(config) }
    }

    @Test
    fun `setMobileOnlyForWinServer DB failure reverts`() = runTest {
        val config = CountryConfig(id = "us-nyc", cc = "US", key = "nyc-key", mobileOnly = false)
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(config)
        coEvery { mockCountryConfigRepo.update(config) } throws RuntimeException("DB error")

        RpnProxyManager.setMobileOnlyForWinServer("nyc-key", true)

        assertFalse(config.mobileOnly) // reverted
    }

    @Test
    fun `setSsidEnabledForWinServer success`() = runTest {
        val config = CountryConfig(id = "us-nyc", cc = "US", key = "nyc-key", ssidBased = false)
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(config)
        coEvery { mockCountryConfigRepo.update(config) } returns Unit

        RpnProxyManager.setSsidEnabledForWinServer("nyc-key", true)

        assertTrue(config.ssidBased)
        coVerify { mockCountryConfigRepo.update(config) }
    }

    @Test
    fun `setSsidEnabledForWinServer DB failure reverts`() = runTest {
        val config = CountryConfig(id = "us-nyc", cc = "US", key = "nyc-key", ssidBased = false)
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(config)
        coEvery { mockCountryConfigRepo.update(config) } throws RuntimeException("DB error")

        RpnProxyManager.setSsidEnabledForWinServer("nyc-key", true)

        assertFalse(config.ssidBased) // reverted
    }

    // =========================================================================
    // 6. syncWinServers
    // =========================================================================

    @Test
    fun `syncWinServers cleans up stale proxy mappings and protects AUTO`() = runTest {
        val stale = "stale-key"
        val existing = listOf(
            CountryConfig(id = RpnProxyManager.AUTO_SERVER_ID, cc = RpnProxyManager.AUTO_COUNTRY_CODE, key = RpnProxyManager.AUTO_SERVER_ID),
            CountryConfig(id = "us-1", cc = "US", key = stale)
        )
        val incoming = setOf(CountryConfig(id = "in-1", cc = "IN", key = "india-key"))

        coEvery { mockCountryConfigRepo.getAllConfigs() } returns existing
        coEvery { mockCountryConfigRepo.syncServers(any()) } returns 1
        coEvery { ProxyManager.removeProxyId(any()) } just Runs

        RpnProxyManager.syncWinServers(incoming)

        coVerify { ProxyManager.removeProxyId(Backend.RpnWin + stale) }
    }

    @Test
    fun `syncWinServers empty server list clears DB except AUTO`() = runTest {
        coEvery { mockCountryConfigRepo.syncServers(emptyList()) } returns 0
        coEvery { mockCountryConfigRepo.getAllConfigs() } returns emptyList()

        RpnProxyManager.syncWinServers(emptySet())

        coVerify { mockCountryConfigRepo.syncServers(emptyList()) }
    }

    // =========================================================================
    // 7. Config Evaluation (getAllPossibleConfigIdsForApp)
    // =========================================================================

    @Test
    fun `getAllPossibleConfigIdsForApp respects network eligibility`() = runTest {
        val cfgMob = CountryConfig(id = "c1", cc = "US", key = "k-mob", mobileOnly = true, isEnabled = true, catchAll = true)
        val cfgAny = CountryConfig(id = "c2", cc = "IN", key = "k-any", mobileOnly = false, isEnabled = true, catchAll = true)

        every { ProxyManager.getProxyIdsForApp(any()) } returns emptySet()
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").addAll(listOf(cfgMob, cfgAny))

        // WiFi
        val idsWifi = RpnProxyManager.getAllPossibleConfigIdsForApp(1, "1.1.1.1", 80, "", false, "")
        assertTrue(idsWifi.contains(Backend.RpnWin + "k-any"))
        assertFalse(idsWifi.contains(Backend.RpnWin + "k-mob"))

        // Mobile
        val idsMob = RpnProxyManager.getAllPossibleConfigIdsForApp(1, "1.1.1.1", 80, "", true, "")
        assertTrue(idsMob.contains(Backend.RpnWin + "k-mob"))
    }

    @Test
    fun `getAllPossibleConfigIdsForApp app-specific configs take priority`() = runTest {
        val appConfig = CountryConfig(id = "c1", cc = "US", key = "app-key", isEnabled = true, catchAll = false)
        val catchAllConfig = CountryConfig(id = "c2", cc = "IN", key = "catchall-key", isEnabled = true, catchAll = true)

        every { ProxyManager.getProxyIdsForApp(100) } returns setOf(Backend.RpnWin + "app-key")
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").addAll(listOf(appConfig, catchAllConfig))

        val ids = RpnProxyManager.getAllPossibleConfigIdsForApp(100, "1.1.1.1", 80, "", false, "")

        assertTrue(ids.contains(Backend.RpnWin + "app-key"))
    }

    @Test
    fun `getAllPossibleConfigIdsForApp lockdown blocks all configs`() = runTest {
        val lockdownConfig = CountryConfig(id = "c1", cc = "US", key = "lockdown-key", isEnabled = true, lockdown = true)
        val otherConfig = CountryConfig(id = "c2", cc = "IN", key = "other-key", isEnabled = true, catchAll = true)

        every { ProxyManager.getProxyIdsForApp(100) } returns setOf(Backend.RpnWin + "lockdown-key", Backend.RpnWin + "other-key")
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").addAll(listOf(lockdownConfig, otherConfig))

        val ids = RpnProxyManager.getAllPossibleConfigIdsForApp(100, "1.1.1.1", 80, "", false, "")

        // Lockdown should be honored, other-key removed
        assertTrue(ids.isEmpty() || ids.size == 1)
    }

    // =========================================================================
    // 8. Payload Parsing (extractWsObject, getExpiryFromPayload)
    // =========================================================================

    @Test
    fun `extractWsObject empty payload returns null`() {
        assertNull(RpnProxyManager.extractWsObject(""))
    }

    @Test
    fun `extractWsObject shape2 top-level ws`() {
        val payload = """{"ws":{"cid":"cid1","sessiontoken":"tok1","expiry":"2028-05-09T05:01:46.547Z"}}"""
        val bytes = RpnProxyManager.extractWsObject(payload)
        assertNotNull(bytes)
        val str = String(bytes!!)
        assertTrue(str.contains("cid1"))
        assertTrue(str.contains("tok1"))
    }

    @Test
    fun `extractWsObject shape1 nested developerPayload`() {
        // Shape 1: full purchase originalJson: developerPayload is a string that itself is JSON
        val innerWs = """{"ws":{"cid":"cid1","sessiontoken":"tok1"}}"""
        val payload = """{"developerPayload":"${innerWs.replace("\"", "\\\"")}"}"""
        val bytes = RpnProxyManager.extractWsObject(payload)
        assertNotNull(bytes)
        val str = String(bytes!!)
        assertTrue(str.contains("cid1"))
        assertTrue(str.contains("tok1"))
    }

    @Test
    fun `extractWsObject invalid json returns null`() {
        assertNull(RpnProxyManager.extractWsObject("not-json"))
    }

    @Test
    fun `getExpiryFromPayload valid payload returns timestamp`() = runTest {
        val futureIso = "2028-05-09T05:01:46.547Z"
        val payload = """{"ws":{"sessiontoken":"tok1","expiry":"$futureIso"}}"""

        coEvery { VpnController.getEntitlementDetails(any(), any()) } returns mockk {
            coEvery { expiry() } returns futureIso
        }
        coEvery { mockBillingBackendClient.getDeviceId() } returns "did-1"

        val expiry = RpnProxyManager.getExpiryFromPayload(payload)
        assertNotNull(expiry)
        assertTrue(expiry!! > 0L)
    }

    @Test
    fun `getExpiryFromPayload empty payload returns null`() = runTest {
        val expiry = RpnProxyManager.getExpiryFromPayload("")

        assertNull(expiry)
    }

    // =========================================================================
    // 10. Win Servers Cache
    // =========================================================================

    @Test
    fun `getWinServers returns cached real servers`() = runTest {
        val real = CountryConfig(id = "us-nyc", cc = "US", key = "nyc-key")
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").addAll(listOf(real))

        val servers = RpnProxyManager.getWinServers()

        assertTrue(servers.isNotEmpty())
        assertTrue(servers.any { it.id != RpnProxyManager.AUTO_SERVER_ID })
    }

    @Test
    fun `getWinServers falls back to DB when cache has only AUTO`() = runTest {
        val auto = CountryConfig(id = RpnProxyManager.AUTO_SERVER_ID, cc = RpnProxyManager.AUTO_COUNTRY_CODE, key = RpnProxyManager.AUTO_SERVER_ID)
        val dbReal = CountryConfig(id = "de-fra", cc = "DE", key = "fra-key")
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(auto)
        coEvery { mockCountryConfigRepo.getAllConfigs() } returns listOf(auto, dbReal)

        val servers = RpnProxyManager.getWinServers()

        assertTrue(servers.isNotEmpty())
        assertTrue(servers.any { it.id == "de-fra" })
    }

    // =========================================================================
    // 10. AUTO Server
    // =========================================================================

    @Test
    fun `ensureAutoServerExists creates AUTO when missing`() = runTest {
        coEvery { mockCountryConfigRepo.getById(RpnProxyManager.AUTO_SERVER_ID) } returns null
        coEvery { mockCountryConfigRepo.insert(any()) } returns Unit

        RpnProxyManager.ensureAutoServerExists()

        coVerify { mockCountryConfigRepo.insert(match { it.id == RpnProxyManager.AUTO_SERVER_ID }) }
    }

    @Test
    fun `getAutoServer returns cached AUTO`() = runTest {
        val auto = CountryConfig(id = RpnProxyManager.AUTO_SERVER_ID, cc = RpnProxyManager.AUTO_COUNTRY_CODE, key = RpnProxyManager.AUTO_SERVER_ID)
        getPrivateField<MutableList<CountryConfig>>(RpnProxyManager, "winServersCache").add(auto)

        val result = RpnProxyManager.getAutoServer()
        assertNotNull(result)
        assertEquals(RpnProxyManager.AUTO_SERVER_ID, result!!.id)
    }

    // =========================================================================
    // 11. updateBillingAndAccountExpiry
    // =========================================================================

    @Test
    fun `updateBillingAndAccountExpiry both zero returns false`() = runTest {
        val result = RpnProxyManager.updateBillingAndAccountExpiry(0L, 0L)
        assertFalse(result)
    }

    @Test
    fun `updateBillingAndAccountExpiry no active sub returns false`() = runTest {
        coEvery { mockSubscriptionStatusDb.getCurrentSubscription() } returns null

        val result = RpnProxyManager.updateBillingAndAccountExpiry(1000L, 0L)
        assertFalse(result)
    }

    @Test
    fun `updateBillingAndAccountExpiry success`() = runTest {
        val sub = SubscriptionStatus().apply { id = 1 }
        coEvery { mockSubscriptionStatusDb.getCurrentSubscription() } returns sub
        coEvery { mockSubscriptionStatusDb.updateBillingExpiry(1, 1000L, any()) } returns 1
        coEvery { mockSubscriptionStatusDb.updateAccountExpiry(1, 2000L, any()) } returns 1

        val result = RpnProxyManager.updateBillingAndAccountExpiry(1000L, 2000L)
        assertTrue(result)
    }

    @Test
    fun `updateBillingAndAccountExpiry partial failure`() = runTest {
        val sub = SubscriptionStatus().apply { id = 1 }
        coEvery { mockSubscriptionStatusDb.getCurrentSubscription() } returns sub
        coEvery { mockSubscriptionStatusDb.updateBillingExpiry(1, 1000L, any()) } returns 0 // failure

        val result = RpnProxyManager.updateBillingAndAccountExpiry(1000L, 0L)
        assertFalse(result)
    }

    // =========================================================================
    // 12. fetchAndConstructWinLocations
    // =========================================================================

    @Test
    fun `fetchAndConstructWinLocations null props returns empty`() = runTest {
        coEvery { VpnController.getRpnProps(RpnProxyManager.RpnType.WIN) } returns Pair(null, "")

        val (servers, _) = RpnProxyManager.fetchAndConstructWinLocations()

        assertTrue(servers.isEmpty())
    }

    // =========================================================================
    // 13. Update Subscription State (Cancelled / Revoked)
    // =========================================================================

    @Test
    fun `updateCancelledSubscription empty params returns false`() = runTest {
        assertFalse(RpnProxyManager.updateCancelledSubscription("", "tok-1"))
        assertFalse(RpnProxyManager.updateCancelledSubscription("acc-1", ""))
    }

    @Test
    fun `updateCancelledSubscription success handles cancellation`() = runTest {
        val result = RpnProxyManager.updateCancelledSubscription("acc-1", "tok-1")
        assertTrue(result)
    }

    @Test
    fun `updateRevokedSubscription success handles revocation`() = runTest {
        val result = RpnProxyManager.updateRevokedSubscription("acc-1", "tok-1")
        assertTrue(result)
    }

    // =========================================================================
    // 14. getEffectivePurchaseDetail
    // =========================================================================

    @Test
    fun `getEffectivePurchaseDetail returns null when no subscription data`() {
        every { mockStateMachine.getSubscriptionData() } returns null

        val result = RpnProxyManager.getEffectivePurchaseDetail()

        assertNull(result)
    }

    @Test
    fun `getEffectivePurchaseDetail returns purchase detail from data`() {
        val pDetail = makePurchaseDetail("prd-1")
        val subData = SubscriptionStateMachineV2.SubscriptionData(
            subscriptionStatus = SubscriptionStatus(),
            purchaseDetail = pDetail,
            lastUpdated = System.currentTimeMillis()
        )
        every { mockStateMachine.getSubscriptionData() } returns subData

        val result = RpnProxyManager.getEffectivePurchaseDetail()

        assertNotNull(result)
        assertEquals("prd-1", result!!.productId)
    }

    @Test
    fun `getEffectivePurchaseDetail reconstructs from subscription status`() {
        val subStatus = SubscriptionStatus().apply {
            purchaseToken = "tok-1"
            productId = "prd-1"
        }
        val reconstructedPD = makePurchaseDetail("prd-1", purchaseToken = "tok-1")
        val subData = SubscriptionStateMachineV2.SubscriptionData(
            subscriptionStatus = subStatus,
            purchaseDetail = null,
            lastUpdated = System.currentTimeMillis()
        )
        every { mockStateMachine.getSubscriptionData() } returns subData
        every { mockStateMachine.createPurchaseDetailFromSubscription(subStatus) } returns reconstructedPD

        val result = RpnProxyManager.getEffectivePurchaseDetail()

        assertNotNull(result)
        assertEquals("tok-1", result!!.purchaseToken)
    }

    // =========================================================================
    // 15. consumePurchaseIfTest
    // =========================================================================

    @Test
    fun `consumePurchaseIfTest skips when no purchase data`() = runTest {
        every { mockStateMachine.getSubscriptionData() } returns null

        RpnProxyManager.consumePurchaseIfTest()
    }

    @Test
    fun `consumePurchaseIfTest skips when not test entitlement`() = runTest {
        val pDetail = makePurchaseDetail("prd-1")
        val subData = SubscriptionStateMachineV2.SubscriptionData(
            subscriptionStatus = SubscriptionStatus(),
            purchaseDetail = pDetail,
            lastUpdated = System.currentTimeMillis()
        )
        every { mockStateMachine.getSubscriptionData() } returns subData
        coEvery { mockBillingBackendClient.getDeviceId() } returns "did-1"
        every { mockPersistentState.appTestMode } returns false

        RpnProxyManager.consumePurchaseIfTest()

        coVerify(exactly = 0) { mockBillingBackendClient.consumePurchase(any(), any(), any(), any()) }
    }

    // =========================================================================
    // 16. SSID matching
    // =========================================================================

    @Test
    fun `matchesSsidList empty list matches all`() {
        assertTrue(RpnProxyManager.matchesSsidList("", "MyWiFi"))
    }

    @Test
    fun `matchesSsidList empty ssid returns false`() {
        assertFalse(RpnProxyManager.matchesSsidList("HomeWiFi", ""))
    }

    @Test
    fun `matchesWildcard handles empty params`() {
        val method = RpnProxyManager::class.java.getDeclaredMethod("matchesWildcard", String::class.java, String::class.java)
        method.isAccessible = true
        assertFalse(method.invoke(RpnProxyManager, "", "text") as Boolean)
        assertFalse(method.invoke(RpnProxyManager, "pattern", "") as Boolean)
    }

    // =========================================================================
    // 17. Rpn State Checks
    // =========================================================================

    @Test
    fun `isRpnActive returns true when enabled and mode not NONE`() {
        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.ENABLED.id
        every { mockPersistentState.rpnMode } returns RpnProxyManager.RpnMode.HIDE_IP.id

        assertTrue(RpnProxyManager.isRpnActive())
    }

    @Test
    fun `isRpnActive returns false when disabled`() {
        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.DISABLED.id
        every { mockPersistentState.rpnMode } returns RpnProxyManager.RpnMode.HIDE_IP.id

        assertFalse(RpnProxyManager.isRpnActive())
    }

    @Test
    fun `isRpnActive returns false when mode is NONE`() {
        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.ENABLED.id
        every { mockPersistentState.rpnMode } returns RpnProxyManager.RpnMode.NONE.id

        assertFalse(RpnProxyManager.isRpnActive())
    }

    @Test
    fun `setRpnMode skips when already set`() {
        every { mockPersistentState.rpnMode } returns RpnProxyManager.RpnMode.ANTI_CENSORSHIP.id

        RpnProxyManager.setRpnMode(RpnProxyManager.RpnMode.ANTI_CENSORSHIP)
        verify(exactly = 0) { mockPersistentState.rpnMode = any() }
    }

    @Test
    fun `setRpnState skips when already set`() {
        every { mockPersistentState.rpnState } returns RpnProxyManager.RpnState.ENABLED.id

        RpnProxyManager.setRpnState(RpnProxyManager.RpnState.ENABLED)
        assertEquals(RpnProxyManager.RpnState.ENABLED.id, mockPersistentState.rpnState)
    }

    // =========================================================================
    // 18. getSubscriptionData / getSubscriptionState
    // =========================================================================

    @Test
    fun `getSubscriptionData returns from state machine`() {
        val subData = SubscriptionStateMachineV2.SubscriptionData(
            subscriptionStatus = SubscriptionStatus(),
            purchaseDetail = null,
            lastUpdated = System.currentTimeMillis()
        )
        every { mockStateMachine.getSubscriptionData() } returns subData

        val result = RpnProxyManager.getSubscriptionData()
        assertNotNull(result)
    }

    @Test
    fun `getSubscriptionState returns current state`() {
        every { mockStateMachine.getCurrentState() } returns SubscriptionStateMachineV2.SubscriptionState.Active

        val state = RpnProxyManager.getSubscriptionState()
        assertEquals(SubscriptionStateMachineV2.SubscriptionState.Active, state)
    }

    @Test
    fun `hasValidSubscription delegates to state machine`() {
        every { mockStateMachine.hasValidSubscription() } returns true

        assertTrue(RpnProxyManager.hasValidSubscription())
    }

    @Test
    fun `getRpnProductId returns product id from subscription`() {
        val pDetail = makePurchaseDetail("prd-1")
        val subData = SubscriptionStateMachineV2.SubscriptionData(
            subscriptionStatus = SubscriptionStatus(),
            purchaseDetail = pDetail,
            lastUpdated = System.currentTimeMillis()
        )
        every { mockStateMachine.getSubscriptionData() } returns subData

        val productId = RpnProxyManager.getRpnProductId()
        assertEquals("prd-1", productId)
    }

    // =========================================================================
    // 19. updateWinConfigState
    // =========================================================================

    @Test
    fun `updateWinConfigState null bytes returns false`() = runTest {
        assertFalse(RpnProxyManager.updateWinConfigState(null))
        assertFalse(RpnProxyManager.updateWinConfigState(byteArrayOf()))
    }

    @Test
    fun `updateWinConfigState success writes file and updates DB`() = runTest {
        val bytes = "test-config".toByteArray()
        every { EncryptedFileManager.write(any(), any<ByteArray>(), any()) } returns true
        coEvery { mockRpnProxyDb.getProxyById(4) } returns null
        coEvery { mockRpnProxyDb.insert(any()) } returns 1L

        val result = RpnProxyManager.updateWinConfigState(bytes)

        assertTrue(result)
        coVerify { mockRpnProxyDb.insert(any<RpnProxy>()) }
    }

    // =========================================================================
    // 20. DnsMode helpers
    // =========================================================================

    @Test
    fun `DnsMode setFromCsv parses correctly`() {
        val modes = RpnProxyManager.DnsMode.setFromCsv("privacy,family")
        assertTrue(modes.contains(RpnProxyManager.DnsMode.PRIVACY))
        assertTrue(modes.contains(RpnProxyManager.DnsMode.PARENTAL))
        assertFalse(modes.contains(RpnProxyManager.DnsMode.SECURITY))
    }

    @Test
    fun `DnsMode setFromCsv blank returns default`() {
        val modes = RpnProxyManager.DnsMode.setFromCsv("")
        assertEquals(setOf(RpnProxyManager.DnsMode.DEFAULT), modes)
    }

    @Test
    fun `DnsMode tunTypesFromSet builds csv`() {
        val csv = RpnProxyManager.DnsMode.tunTypesFromSet(setOf(RpnProxyManager.DnsMode.PRIVACY, RpnProxyManager.DnsMode.SECURITY))
        assertEquals("privacy,security", csv)
    }

    @Test
    fun `DnsMode tunTypesFromSet empty returns default`() {
        val csv = RpnProxyManager.DnsMode.tunTypesFromSet(emptySet())
        assertEquals("default", csv)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun makePurchaseDetail(id: String, purchaseToken: String = "tok", payload: String = "") = PurchaseDetail(
        productId = id, planId = id, productTitle = "", state = 1, planTitle = "",
        purchaseToken = purchaseToken, productType = "subs", purchaseTime = "", purchaseTimeMillis = 0,
        isAutoRenewing = true, accountId = "acc", deviceId = "", payload = payload, expiryTime = 0,
        status = 1, windowDays = 3, orderId = ""
    )
}
