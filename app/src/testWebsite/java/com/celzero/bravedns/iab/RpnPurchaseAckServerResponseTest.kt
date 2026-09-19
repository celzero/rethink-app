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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RpnPurchaseAckServerResponseTest {

    @Test
    fun `one-time purchase cancelled body is classified as cancelled`() {
        val body = """
            {"error":"purchase cancelled","sku":"onetime.tier",
             "purchaseId":"8c97","state":"CANCELLED|ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
             "test":true,"allProducts":["onetime.tier"],"unconsumedProducts":["onetime.tier"],
             "ray":"a355c677"}
        """.trimIndent()

        val result = RpnPurchaseAckServerResponse.from(body, 400) as RpnPurchaseAckServerResponse.Err

        assertTrue(result.payload.isPurchaseCancelled)
        assertFalse(result.payload.isSubscriptionExpired)
        assertNull(result.payload.linkedPurchaseId)
    }

    @Test
    fun `subscription expired state is not purchase-cancelled`() {
        val body = """{"error":"subscription expired","state":"SUBSCRIPTION_STATE_EXPIRED"}"""

        val result = RpnPurchaseAckServerResponse.from(body, 400) as RpnPurchaseAckServerResponse.Err

        assertTrue(result.payload.isSubscriptionExpired)
        assertFalse(result.payload.isPurchaseCancelled)
    }

    @Test
    fun `cancelled with linkedPurchaseId is not definitive`() {
        val body = """
            {"error":"purchase cancelled","state":"CANCELLED|ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
             "linkedPurchaseId":"old-token"}
        """.trimIndent()

        val result = RpnPurchaseAckServerResponse.from(body, 400) as RpnPurchaseAckServerResponse.Err

        assertFalse(result.payload.isPurchaseCancelled)
    }

    @Test
    fun `error message alone marks cancelled`() {
        val err = RpnPurchaseAckServerResponse.from(
            """{"error":"purchase cancelled","sku":"onetime.tier"}""", 400
        ) as RpnPurchaseAckServerResponse.Err

        assertTrue(err.payload.isPurchaseCancelled)
    }

    @Test
    fun `generic business error is not cancelled`() {
        val body = """{"error":"invalid token","sku":"onetime.tier"}"""

        val result = RpnPurchaseAckServerResponse.from(body, 400) as RpnPurchaseAckServerResponse.Err

        assertFalse(result.payload.isPurchaseCancelled)
        assertFalse(result.payload.isSubscriptionExpired)
    }

    @Test
    fun `non-json 400 body from a proxy is not cancelled`() {
        val result = RpnPurchaseAckServerResponse.from("<html>Bad Request</html>", 400)
            as RpnPurchaseAckServerResponse.Err

        assertFalse(result.payload.isPurchaseCancelled)
        assertFalse(result.payload.isSubscriptionExpired)
    }

    @Test
    fun `non-json 5xx body from a proxy is not cancelled`() {
        val result = RpnPurchaseAckServerResponse.from("<html>Gateway Timeout</html>", 504)
            as RpnPurchaseAckServerResponse.Err

        assertFalse(result.payload.isPurchaseCancelled)
    }

    @Test
    fun `success response is not cancelled`() {
        val body = """{"success":true,"status":"valid","developerPayload":"ws#v1"}"""

        val result = RpnPurchaseAckServerResponse.from(body, 200)

        assertTrue(result is RpnPurchaseAckServerResponse.Ok)
        assertFalse((result as RpnPurchaseAckServerResponse.Ok).payload.isCancelled)
    }
}
