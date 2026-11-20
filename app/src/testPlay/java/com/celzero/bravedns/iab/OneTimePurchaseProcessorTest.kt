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
package com.celzero.bravedns.iab

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OneTimePurchaseProcessorTest {

    @Test
    fun `exact token option overrides cached first option and rescues five year purchase`() = runBlocking {
        val now = System.currentTimeMillis()
        val purchaseTime = now - 3 * 365 * 24 * 60 * 60 * 1000L
        val token = "token-five-year"
        val accountId = "cid-purchase"
        val request = mutableListOf<Pair<String, String>>()
        val processor = processor(
            cachedPlanId = "proxy-yearly-2",
            fetchOrder = { cid, purchaseToken ->
                request += cid to purchaseToken
                serverOrder(token, accountId, "proxy-yearly-5")
            }
        )

        val detail = processor.buildPurchaseDetail(mockPurchase(token, accountId, purchaseTime))

        assertNotNull(detail)
        assertEquals("proxy-yearly-5", detail?.planId)
        assertTrue(detail?.isPlanIdServerConfirmed == true)
        assertTrue((detail?.expiryTime ?: 0L) > now)
        assertEquals(listOf(accountId to token), request)
    }

    @Test
    fun `missing server option remains unresolved and uses target token lookup`() = runBlocking {
        val now = System.currentTimeMillis()
        val token = "token-option-missing"
        val accountId = "cid-purchase"
        val processor = processor(
            cachedPlanId = "proxy-yearly-5",
            fetchOrder = { _, _ -> serverOrder(token, accountId, "") }
        )

        val detail = processor.buildPurchaseDetail(mockPurchase(token, accountId, now - 86_400_000L))

        assertNotNull(detail)
        assertEquals("onetime.tier", detail?.planId)
        assertFalse(detail?.isPlanIdServerConfirmed == true)
    }

    @Test
    fun `existing stored one-time window survives unavailable order history`() = runBlocking {
        val now = System.currentTimeMillis()
        val purchaseTime = now - 3 * 365 * 24 * 60 * 60 * 1000L
        val token = "token-existing-five-year"
        val accountId = "cid-purchase"
        val storedExpiry = now + 2 * 365 * 24 * 60 * 60 * 1000L
        val stored = PurchaseDetail(
            productId = "onetime.tier",
            planId = "proxy-yearly-5",
            productTitle = "5 years",
            state = Purchase.PurchaseState.PURCHASED,
            planTitle = "5 years",
            purchaseToken = token,
            productType = BillingClient.ProductType.INAPP,
            purchaseTime = "",
            purchaseTimeMillis = purchaseTime,
            isAutoRenewing = false,
            accountId = accountId,
            payload = "",
            expiryTime = storedExpiry,
            status = SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id,
            windowDays = InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_5YRS_DAYS,
            isPlanIdServerConfirmed = false
        )
        val processor = processor(
            cachedPlanId = "proxy-yearly-2",
            storedDetail = stored,
            fetchOrder = { _, _ -> null }
        )

        val detail = processor.buildPurchaseDetail(mockPurchase(token, accountId, purchaseTime))

        assertNotNull(detail)
        assertEquals("proxy-yearly-5", detail?.planId)
        assertEquals(storedExpiry, detail?.expiryTime)
    }

    private fun processor(
        cachedPlanId: String,
        storedDetail: PurchaseDetail? = null,
        fetchOrder: suspend (String, String) -> ServerOrderEntry?
    ): OneTimePurchaseProcessor {
        val machine = mockk<SubscriptionStateMachineV2>(relaxed = true)
        coEvery { machine.getPersistedInAppPurchaseDetail(any()) } returns storedDetail
        val cachedProduct = ProductDetail(
            productId = "onetime.tier",
            planId = cachedPlanId,
            productTitle = "One-time",
            productType = BillingClient.ProductType.INAPP,
            pricingDetails = emptyList()
        )
        val cachedOffer = mockk<ProductDetails.OneTimePurchaseOfferDetails>(relaxed = true)
        val cachedEntry = QueryProductDetail(
            productDetail = cachedProduct,
            productDetails = mockk(relaxed = true),
            offerDetails = null,
            oneTimeOfferDetails = cachedOffer
        )
        return OneTimePurchaseProcessor(
            storeProductDetails = CopyOnWriteArrayList(listOf(cachedEntry)),
            subscriptionStateMachine = machine,
            acknowledgePurchase = { _, _, _, _ -> false to "" },
            activateRpn = {},
            getProductType = { BillingClient.ProductType.INAPP },
            getDeviceId = { "did-purchase" },
            mintDeviceId = { "did-purchase" },
            fetchRecentOneTimeOrder = fetchOrder,
            reconcileCidDid = {},
            validatePayloadAndFetchIfRequired = { it }
        )
    }

    private fun mockPurchase(token: String, accountId: String, purchaseTime: Long): Purchase =
        mockk<Purchase>().also { purchase ->
            every { purchase.products } returns listOf("onetime.tier")
            every { purchase.purchaseTime } returns purchaseTime
            every { purchase.accountIdentifiers?.obfuscatedAccountId } returns accountId
            every { purchase.purchaseState } returns Purchase.PurchaseState.PURCHASED
            every { purchase.purchaseToken } returns token
            every { purchase.developerPayload } returns ""
            every { purchase.orderId } returns "order-test"
        }

    private fun serverOrder(token: String, accountId: String, planId: String) = ServerOrderEntry(
        purchaseToken = token,
        cid = accountId,
        sku = "onetime.tier",
        mtime = System.currentTimeMillis(),
        kind = ServerOrderEntry.KIND_OTP,
        isSubscription = false,
        orderId = "order-test",
        startTimeMs = 0L,
        expiryTimeMs = 0L,
        purchaseTimeMs = System.currentTimeMillis(),
        subscriptionState = null,
        purchaseState = ServerOrderEntry.PURCHASE_STATE_PURCHASED,
        autoRenewEnabled = false,
        isTestPurchase = false,
        productId = "onetime.tier",
        planId = planId
    )
}
