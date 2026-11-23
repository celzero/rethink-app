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

import com.google.gson.JsonParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ServerOrderHistoryRepositoryTest {

    private lateinit var billingBackendClient: BillingBackendClient
    private lateinit var repository: ServerOrderHistoryRepository

    @Before
    fun setUp() {
        billingBackendClient = mockk()
        repository = ServerOrderHistoryRepository(billingBackendClient)
        coEvery { billingBackendClient.getDeviceIdForCid("cid-target") } returns "did-target"
    }

    @Test
    fun `exact token lookup uses target credentials and maps purchase option`() = runBlocking {
        val body = JsonParser.parseString(
            """
            {
              "success": true,
              "cid": "cid-target",
              "tx": [
                {
                  "purchasetoken": "token-other",
                  "cid": "cid-target",
                  "meta": {
                    "kind": "androidpublisher#productPurchaseV2",
                    "purchaseStateContext": {"purchaseState": "PURCHASED"},
                    "productLineItem": [{
                      "productId": "onetime.tier",
                      "productOfferDetails": {"purchaseOptionId": "proxy-yearly-2"}
                    }]
                  }
                },
                {
                  "purchasetoken": "token-target",
                  "cid": "cid-target",
                  "meta": {
                    "kind": "androidpublisher#productPurchaseV2",
                    "purchaseStateContext": {"purchaseState": "PURCHASED"},
                    "productLineItem": [{
                      "productId": "onetime.tier",
                      "productOfferDetails": {"purchaseOptionId": "proxy-yearly-5"}
                    }]
                  }
                }
              ]
            }
            """.trimIndent()
        ).asJsonObject
        coEvery {
            billingBackendClient.fetchPurchaseHistory(
                accountId = "cid-target",
                deviceId = "did-target",
                purchaseToken = "token-target",
                total = 20
            )
        } returns FetchOrdersRawResult.Success(body)

        val entry = repository.fetchRecentOneTimePurchase("cid-target", "token-target")

        assertNotNull(entry)
        assertEquals("token-target", entry?.purchaseToken)
        assertEquals("proxy-yearly-5", entry?.planId)
        coVerify(exactly = 1) {
            billingBackendClient.fetchPurchaseHistory("cid-target", "did-target", "token-target", 20)
        }
    }

    @Test
    fun `missing purchase option stays unresolved instead of becoming product id`() = runBlocking {
        val body = JsonParser.parseString(
            """
            {
              "success": true,
              "cid": "cid-target",
              "tx": [{
                "purchasetoken": "token-target",
                "cid": "cid-target",
                "meta": {
                  "kind": "androidpublisher#productPurchaseV2",
                  "purchaseStateContext": {"purchaseState": "PURCHASED"},
                  "productLineItem": [{"productId": "onetime.tier"}]
                }
              }]
            }
            """.trimIndent()
        ).asJsonObject
        coEvery {
            billingBackendClient.fetchPurchaseHistory(
                accountId = "cid-target",
                deviceId = "did-target",
                purchaseToken = "token-target",
                total = 20
            )
        } returns FetchOrdersRawResult.Success(body)

        val entry = repository.fetchRecentOneTimePurchase("cid-target", "token-target")

        assertNotNull(entry)
        assertEquals("", entry?.planId)
    }
}
