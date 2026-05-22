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
package com.celzero.bravedns.customdownloader

import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Retrofit interface for **test-entitlement** billing server calls.
 *
 * This interface is the test counterpart of [IBillingServerApi].  It carries the
 * same URL shapes and parameters as [IBillingServerApi] for every endpoint that
 * supports a `test` flag, with one key difference:
 *
 * - **`test`** is a **required** query param with no default value; the compiler enforces
 *   that callers always supply it.  This prevents accidental omission of the test flag
 *   when operating against the test entitlement server path.
 *
 * ### DB session header
 * Every endpoint carries an `x-rethink-db-rpn-test-session` header whose value
 * controls which DB instance services the request:
 * - `first-primary` - always the primary DB; required for all write (POST) endpoints.
 * - `first-unconstrained` - no constraint; the server may use the nearest replica or
 *                           primary; safe for all read (GET) endpoints.
 *
 * ### Identity headers
 * CID and DID are no longer passed as URL query parameters. All endpoints send:
 *   `x-rethink-app-cid: <account id>`
 *   `x-rethink-app-did: <device id>`
 * Bootstrap endpoints (`registerCustomer`, `registerDevice`) accept nullable headers
 * so Retrofit omits them for first-time registrations.
 *
 * ### Endpoints included
 * Only the endpoints that carry a `test` query param are present:
 * `registerCustomer`, `registerDevice`, `cancelSubscription`, `revokeSubscription`,
 * `acknowledgePurchase`, `consumePurchase`.
 *
 * ### Endpoints deliberately excluded
 * - `getPublicKey` has no test variant; callers must always use [IBillingServerApi].
 *
 * ### Selection
 * [com.celzero.bravedns.iab.BillingBackendClient] resolves which interface to use per-request
 * via its internal `resolveApi()` helper.
 */
interface IBillingServerApiTest {

    /*
      * Register or resolve a customer account with the server (test path).
      * URL shape: /d/acc?test=<value>
      *
      * Headers:
      *   x-rethink-app-cid: <existing account id, or absent for new accounts>
      *   x-rethink-app-did: <existing device id,  or absent for new devices>
      *
      * Request body (JSON):
      *   {
      *     "model":      "Manufacturer Model",
      *     "appVersion": <int>
      *   }
      *
      * Response (JSON):
      * {
      *   "cid": "<server-assigned or confirmed account id>",
      *   "did": "<server-assigned or confirmed device id>"
      * }
      *
      * DB routing: write; first-primary ensure to use the primary DB.
      */
    @Headers("x-rethink-db-rpn-test-session: first-primary")
    @POST("/d/acc")
    suspend fun registerCustomer(
        @Header("x-rethink-app-cid") accountId: String?,
        @Header("x-rethink-app-did") deviceId: String?,
        @Query("test") test: String,
        @Body meta: JsonObject
    ): Response<JsonObject?>?


    /*
      * Register a device with the given account ID (test path).
      * URL shape: /d/reg?test=<value>
      *
      * Headers:
      *   x-rethink-app-cid: <account id>         (required; non-blank)
      *   x-rethink-app-did: <existing device id>  (omitted for new device registrations)
      *
      * `test` is required and must be the non-null string returned by
      * [RpnProxyManager.getIsTestEntitlement].
      * The `meta` body carries model, appVersion.
      *
      * DB routing: write; first-primary ensure to use the primary DB.
     */
    @Headers("x-rethink-db-rpn-test-session: first-primary")
    @POST("/d/reg")
    suspend fun registerDevice(
        @Header("x-rethink-app-cid") accountId: String,
        @Header("x-rethink-app-did") deviceId: String?,
        @Query("test") test: String,
        @Body meta: JsonObject? = null
    ): Response<JsonObject?>?


    /*
      * Cancel the subscription for the given account ID (test path).
      * URL shape: /g/stop?sku=xxx&purchaseToken=xxx&test=<value>
      *
      * Headers:
      *   x-rethink-app-cid: <account id>
      *   x-rethink-app-did: <device id>
      *
      * `test` is required and must be the non-null string returned by
      * [RpnProxyManager.getIsTestEntitlement] (typically "test").
      * response: {"message":"canceled subscription","purchaseId":"..."}
      *
      * DB routing: write; first-primary ensure to use the primary DB.
     */
    @Headers("x-rethink-db-rpn-test-session: first-primary")
    @POST("/g/stop")
    suspend fun cancelSubscription(
        @Header("x-rethink-app-cid") accountId: String,
        @Header("x-rethink-app-did") deviceId: String,
        @Query("sku") sku: String,
        @Query("purchaseToken") purchaseToken: String,
        @Query("test") test: String
    ): Response<JsonObject?>?

    /*
      * Refund / revoke the subscription for the given account ID (test path).
      * URL shape: /g/refund?sku=xxx&purchaseToken=xxx&test=<value>
      *
      * Headers:
      *   x-rethink-app-cid: <account id>
      *   x-rethink-app-did: <device id>
      *
      * `test` is required and must be the non-null string returned by
      * [RpnProxyManager.getIsTestEntitlement].
      * response: {"message":"canceled subscription","purchaseId":"..."}
      *
      * DB routing: write; first-primary ensure to use the primary DB.
     */
    @Headers("x-rethink-db-rpn-test-session: first-primary")
    @POST("/g/refund")
    suspend fun revokeSubscription(
        @Header("x-rethink-app-cid") accountId: String,
        @Header("x-rethink-app-did") deviceId: String,
        @Query("sku") sku: String,
        @Query("purchaseToken") purchaseToken: String,
        @Query("test") test: String
    ): Response<JsonObject?>?

    /*
      * Acknowledge a purchase (test path). POST
      * URL shape: /g/ack?sku=xxx&purchaseToken=xxx&test=<value>
      *
      * Headers:
      *   x-rethink-app-cid: <account id>
      *   x-rethink-app-did: <device id>
      *
      * `test` is required and must be the non-null string returned by
      * [RpnProxyManager.getIsTestEntitlement].
      *
      * DB routing: write; first-primary ensure to use the primary DB.
     */
    @Headers("x-rethink-db-rpn-test-session: first-primary")
    @POST("/g/ack")
    suspend fun acknowledgePurchase(
        @Header("x-rethink-app-cid") accountId: String,
        @Header("x-rethink-app-did") deviceId: String,
        @Query("sku") sku: String,
        @Query("purchaseToken") purchaseToken: String,
        @Query("test") test: String
    ): Response<JsonObject?>?

    /*
      * Query entitlement for a purchase (test path). GET
      * URL shape: /g/ack?sku=xxx&purchaseToken=xxx&test=<value>
      *
      * Headers:
      *   x-rethink-app-cid: <account id>
      *   x-rethink-app-did: <device id>
      *
      * `test` is required and must be the non-null string returned by
      * [RpnProxyManager.getIsTestEntitlement].
      *
      * DB routing: read-only; first-unconstrained allows the server to use the nearest
      * replica (or primary) with no consistency constraint.
     */
    @Headers("x-rethink-db-rpn-test-session: first-unconstrained")
    @GET("/g/ack")
    suspend fun queryEntitlement(
        @Header("x-rethink-app-cid") accountId: String,
        @Header("x-rethink-app-did") deviceId: String,
        @Query("sku") sku: String,
        @Query("purchaseToken") purchaseToken: String,
        @Query("test") test: String
    ): Response<JsonObject?>?

    /*
      * Consume an expired one-time (INAPP) purchase server-side (test path).
      * URL shape: /g/con?sku=xxx&purchaseToken=xxx&test=<value>
      *
      * Headers:
      *   x-rethink-app-cid: <account id>
      *   x-rethink-app-did: <device id>
      *
      * `test` is required and must be the non-null string returned by
      * [RpnProxyManager.getIsTestEntitlement].
      * response: {"message":"consumed","purchaseId":"..."}
      *           {"error":"already consumed",...}
      *
      * DB routing: write; first-primary ensure to use the primary DB.
     */
    @Headers("x-rethink-db-rpn-test-session: first-primary")
    @POST("/g/con")
    suspend fun consumePurchase(
        @Header("x-rethink-app-cid") accountId: String,
        @Header("x-rethink-app-did") deviceId: String,
        @Query("sku") sku: String,
        @Query("purchaseToken") purchaseToken: String,
        @Query("test") test: String
    ): Response<JsonObject?>?

    /*
      * Fetch purchase/order history from the server (test path).
      * URL shape: /g/tx?purchaseToken=xxx&test=<value>[&tot=n][&active]
      *
      * Headers:
      *   x-rethink-app-cid: <account id>
      *   x-rethink-app-did: <device id>
      *
      * `test` is required and must be the non-null string returned by
      * [RpnProxyManager.getIsTestEntitlement].
      *
      * Response: single PlayOrder JSON object (with optional `orders` array when tot=n).
      *
      * DB routing: read-only; first-unconstrained allows the server to use the nearest
      * replica (or primary) with no consistency constraint.
     */
    @Headers("x-rethink-db-rpn-test-session: first-unconstrained")
    @GET("/g/tx")
    suspend fun getPurchaseHistory(
        @Header("x-rethink-app-cid") accountId: String,
        @Header("x-rethink-app-did") deviceId: String,
        @Query("purchaseToken") purchaseToken: String,
        @Query("test") test: String,
        @Query("tot") total: Int? = null,
        @Query("active") active: String? = null
    ): Response<JsonObject?>?
}
