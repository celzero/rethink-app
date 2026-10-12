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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrimaryPurchaseResolverTest {

    /** Only token and purchase time matter to the resolver; the rest are placeholders. */
    private fun purchase(token: String, purchaseTimeMillis: Long): PurchaseDetail =
        PurchaseDetail(
            productId = "sku",
            planId = "",
            productTitle = "",
            state = 0,
            planTitle = "",
            purchaseToken = token,
            // BillingClient.ProductType.SUBS; spelled out because the billing library is
            // not on the fdroid unit-test classpath.
            productType = "subs",
            purchaseTime = purchaseTimeMillis.toString(),
            purchaseTimeMillis = purchaseTimeMillis,
            isAutoRenewing = true,
            accountId = "cid",
            payload = "",
            expiryTime = 0L,
            status = 0,
            windowDays = 0
        )

    @Test
    fun `machine token match wins over newer purchases`() {
        val older = purchase("tok-older", 1_000L)
        val newest = purchase("tok-newest", 3_000L)
        val machine = purchase("tok-machine", 2_000L)
        assertEquals(
            machine,
            PrimaryPurchaseResolver.primaryPurchase(listOf(older, newest, machine), "tok-machine")
        )
    }

    @Test
    fun `blank machine token falls back to newest purchase`() {
        val older = purchase("tok-older", 1_000L)
        val newest = purchase("tok-newest", 3_000L)
        assertEquals(newest, PrimaryPurchaseResolver.primaryPurchase(listOf(older, newest), ""))
        assertEquals(newest, PrimaryPurchaseResolver.primaryPurchase(listOf(older, newest), null))
    }

    @Test
    fun `machine token absent from list falls back to newest purchase`() {
        val older = purchase("tok-older", 1_000L)
        val newest = purchase("tok-newest", 3_000L)
        assertEquals(
            newest,
            PrimaryPurchaseResolver.primaryPurchase(listOf(older, newest), "tok-unknown")
        )
    }

    @Test
    fun `empty list resolves to null`() {
        assertNull(PrimaryPurchaseResolver.primaryPurchase(emptyList(), "tok-machine"))
        assertNull(PrimaryPurchaseResolver.primaryPurchase(emptyList(), null))
    }

    @Test
    fun `single purchase is returned regardless of machine token`() {
        val only = purchase("tok-only", 1_000L)
        assertEquals(only, PrimaryPurchaseResolver.primaryPurchase(listOf(only), "tok-only"))
        assertEquals(only, PrimaryPurchaseResolver.primaryPurchase(listOf(only), "tok-other"))
        assertEquals(only, PrimaryPurchaseResolver.primaryPurchase(listOf(only), null))
    }
}
