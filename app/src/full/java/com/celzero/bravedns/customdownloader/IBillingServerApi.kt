/*
 * Copyright 2023 RethinkDNS and its authors
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
import retrofit2.http.Path
import retrofit2.http.Query

interface IBillingServerApi {

    /*
     * Get the public key for the app version.
     * response: {"minvcode":"30","pubkey":"...","status":"ok"}
     *
     * No DB session header is needed since this endpoint is not account-specific and can be served
     * by any.
    */
    @GET("/p/{appVersion}")
    suspend fun getPublicKey(@Path("appVersion") appVersion: String): Response<JsonObject?>?

    /*
      * Register or resolve a customer account with the server.
      * URL shape: /d/acc
      *
      * CID and DID are sent as request headers (omitted when blank so the server
      * assigns fresh IDs for first-time registrations):
      *   x-rethink-app-cid: <existing account id, or absent for new accounts>
      *   x-rethink-app-did: <existing device id,  or absent for new devices>
      *
      * Request body (JSON): well-formed JSON is enough to register a new customer
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
    @Headers("x-rethink-db-rpn-session: first-primary")
    @POST("/d/acc")
    suspend fun registerCustomer(
        @Header("x-rethink-app-cid") accountId: String?,
        @Header("x-rethink-app-did") deviceId: String?,
        @Body meta: JsonObject
    ): Response<JsonObject?>?

    /*
      * Register a device with the given account ID.
      * URL shape: /d/reg
      *
      * Headers:
      *   x-rethink-app-cid: <account id>         (required; non-blank)
      *   x-rethink-app-did: <existing device id>  (omitted for new device registrations)
      *
      * The `meta` body carries model, appVersion.
      *
      * DB routing: write; first-primary ensure to use the primary DB.
     */
    @Headers("x-rethink-db-rpn-session: first-primary")
    @POST("/d/reg")
    suspend fun registerDevice(
        @Header("x-rethink-app-cid") accountId: String,
        @Header("x-rethink-app-did") deviceId: String?,
        @Body meta: JsonObject? = null
    ): Response<JsonObject?>?


    /*
      * Cancel the subscription for the given account ID.
      * URL shape: /g/stop?sku=xxx&purchaseToken=xxx
      *
      * Headers:
      *   x-rethink-app-cid: <account id>
      *   x-rethink-app-did: <device id>
      *
      * response: {"message":"canceled subscription","purchaseId":"..."}
      *
      * DB routing: write; first-primary ensure to use the primary DB.
     */
    @Headers("x-rethink-db-rpn-session: first-primary")
    @POST("/g/stop")
    suspend fun cancelPurchase(
        @Header("x-rethink-app-cid") accountId: String,
        @Header("x-rethink-app-did") deviceId: String,
        @Query("sku") sku: String,
        @Query("purchaseToken") purchaseToken: String
    ): Response<JsonObject?>?

    /*
      * Refund / revoke the subscription for the given account ID.
      * URL shape: /g/refund?sku=xxx&purchaseToken=xxx
      *
      * Headers:
      *   x-rethink-app-cid: <account id>
      *   x-rethink-app-did: <device id>
      *
      * response: {"message":"canceled subscription","purchaseId":"..."}
      *
      * DB routing: write; first-primary ensure to use the primary DB.
     */
    @Headers("x-rethink-db-rpn-session: first-primary")
    @POST("/g/refund")
    suspend fun revokeSubscription(
        @Header("x-rethink-app-cid") accountId: String,
        @Header("x-rethink-app-did") deviceId: String,
        @Query("sku") sku: String,
        @Query("purchaseToken") purchaseToken: String
    ): Response<JsonObject?>?

    /*
      * Acknowledge a purchase. POST
      * URL shape: /g/ack?sku=xxx&purchaseToken=xxx
      *
      * Headers:
      *   x-rethink-app-cid: <account id>
      *   x-rethink-app-did: <device id>
      *
      * DB routing: write; first-primary ensure to use the primary DB.
     */
    @Headers("x-rethink-db-rpn-session: first-primary")
    @POST("/g/ack")
    suspend fun acknowledgePurchase(
        @Header("x-rethink-app-cid") accountId: String,
        @Header("x-rethink-app-did") deviceId: String,
        @Query("sku") sku: String,
        @Query("purchaseToken") purchaseToken: String,
    ): Response<JsonObject?>?

    /*
      * Query the entitlement status of a purchase. GET
      * URL shape: /g/ack?sku=xxx&purchaseToken=xxx
      *
      * Headers:
      *   x-rethink-app-cid: <account id>
      *   x-rethink-app-did: <device id>
      *
      * DB routing: read-only; first-unconstrained allows the server to use the nearest
      * replica (or primary) with no consistency constraint.
     */
    @Headers("x-rethink-db-rpn-session: first-unconstrained")
    @GET("/g/ack")
    suspend fun queryEntitlement(
        @Header("x-rethink-app-cid") accountId: String,
        @Header("x-rethink-app-did") deviceId: String,
        @Query("sku") sku: String,
        @Query("purchaseToken") purchaseToken: String,
    ): Response<JsonObject?>?


    /*
      * Consume an expired one-time (INAPP) purchase server-side.
      * URL shape: /g/con?sku=xxx&purchaseToken=xxx
      *
      * Headers:
      *   x-rethink-app-cid: <account id>
      *   x-rethink-app-did: <device id>
      *
      * response: {"message":"consumed","purchaseId":"..."}
      *           {"error":"already consumed",...}
      *
      * DB routing: write; first-primary ensure to use the primary DB.
     */
    @Headers("x-rethink-db-rpn-session: first-primary")
    @POST("/g/con")
    suspend fun consumePurchase(
        @Header("x-rethink-app-cid") accountId: String,
        @Header("x-rethink-app-did") deviceId: String,
        @Query("sku") sku: String,
        @Query("purchaseToken") purchaseToken: String
    ): Response<JsonObject?>?

    /*
      * Fetch purchase/order history from the server.
      * URL shape: /g/tx?cid=xxx&purchaseToken=xxx[&tot=n][&test][&active]
      *
      * - tot=n (1–20): also return up to n most recent purchases for the same cid,
      *   ordered by mtime desc. The entry for purchaseToken is always included.
      * - active: only return active purchases (if purchaseToken itself is active).
      * - test: operate on test-entitlement records.
      *
      * Response: single PlayOrder JSON object (with optional `orders` array when tot=n).
      *
      * DB routing: read-only; first-unconstrained allows the server to use the nearest
      * replica (or primary) with no consistency constraint.
     */
    @Headers("x-rethink-db-rpn-session: first-unconstrained")
    @GET("/g/tx")
    suspend fun getPurchaseHistory(
        @Header("x-rethink-app-cid") accountId: String,
        @Header("x-rethink-app-did") deviceId: String,
        @Query("purchaseToken") purchaseToken: String,
        @Query("tot") total: Int? = null,
        @Query("active") active: String? = null
    ): Response<JsonObject?>?
}
