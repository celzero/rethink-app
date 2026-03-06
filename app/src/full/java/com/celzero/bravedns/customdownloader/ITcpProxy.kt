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
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ITcpProxy {

    /*
        * Get the public key for the app version.
        * response: {"minvcode":"30","pubkey":"{\"key_ops\":[\"verify\"],\"ext\":true,\"kty\":\"RSA\",\"n\":\"zON5Gyeeg1QaV_CoFImhWF9TykAZo5pJm9NWd5IPTiYtlhb0WMpFm_-IotJn7ZCGszhl4NMxMHV8odyRbBhPg440qucudBkm0T460f2Id3HBtzoJVLI0SvOmSqm5kY41Zdkxcb_fkpKm-D6c_RnMsmEHvP7WI-YlK108PIpp5ZBvoY3oOA3yktGAm3uaWkjSsw6FmNq34AL3oMA-5MFER-uAq0faXMo8_yEOVcI6Rik_e8wxe4GSnPpndODApzbGyhlORJQSCWbnO6Va-1yeGgkOQ3RFICXrsyyngQbVVOSg9UcAuICzQSW-nlUNF99l_NdrHAaxHpexSSnfdFJ4IQ\",\"e\":\"AQAB\",\"alg\":\"PS384\"}","status":"ok"}
        * The `minvcode` is the minimum version code required to use the public key.
        * The `pubkey` is json encoded public key in JWK format.
        * The `status` is the status of the request, which should be "ok" if successful.
     */
    @GET("/p/{appVersion}")
    suspend fun getPublicKey(@Path("appVersion") appVersion: String): Response<JsonObject?>?

    @GET("/p") suspend fun getPaymentStatus(@Query("ref_id") refId: String): Response<JsonObject?>?

    /*
        * Check if Rethink Plus is available for the given app version.
        * The `appVersion` is the version code of the app.
        * The response is a JSON object with the following fields:
        * {"vcode":"35","minvcode":"46","cansell":false,"ip":"45.127.108.190","country":"in","asorg":"Wireless Solution India Pvt Ltd.","city":"Coimbatore","colo":"LHR","region":"Tamil Nadu","status":"ok"}
     */

    @GET("/f/{appVersion}")
    suspend fun isRethinkPlusAvailable(refId: String): Boolean


    @GET("/g/{refId}/{purchaseToken}/{appVersion}")
    suspend fun checkForPaymentAcknowledgement(
        @Path("refId") refId: String,
        @Path("purchaseToken") purchaseToken: String,
        @Path("appVersion") appVersion: String
    ): Response<JsonObject?>?

    /*
      * cancel the subscription for the given account ID.
      * /g/stop?cid&purchaseToken&test - POST
      * The `cid` is the account ID, `purchaseToken` is the purchase token, and `test` is a boolean
      * indicating whether this is a test request.
      * response: {"message":"canceled subscription","purchaseId":"c078ba1a42e042f3745e195aa52c952b3c99751f3de9880e6c754682698d5133"}
      * {"error":"cannot revoke, subscription canceled or expired","expired":false,"canceled":true,"cancelCtx":{"userInitiatedCancellation":{"cancelSurveyResult":null,"cancelTime":"2025-07-10T13:21:24.743Z"},"systemInitiatedCancellation":null,"developerInitiatedCancellation":null,"replacementCancellation":null},"purchaseId":"c078ba1a42e042f3745e195aa52c952b3c99751f3de9880e6c754682698d5133"}
     */
     @POST("/g/stop/{appVersion}")
    suspend fun cancelSubscription(
        @Path("appVersion") appVersion: String,
        @Query("cid") accountId: String,
        @Query("purchaseToken") purchaseToken: String,
        @Query("test") test: Boolean = false
    ): Response<JsonObject?>?

    /*
      * revoke the subscription for the given account ID.
      * g/refund?cid&purchaseToken&test - POST
      * The `cid` is the account ID, `purchaseToken` is the purchase token, and `test` is a boolean
      * indicating whether this is a test request.
      * This is used to revoke the subscription, which is similar to canceling it.
      * response: {"message":"canceled subscription","purchaseId":"c078ba1a42e042f3745e195aa52c952b3c99751f3de9880e6c754682698d5133"}
      * {"error":"cannot revoke, subscription canceled or expired","expired":false,"canceled":true,"cancelCtx":{"userInitiatedCancellation":{"cancelSurveyResult":null,"cancelTime":"2025-07-10T13:21:24.743Z"},"systemInitiatedCancellation":null,"developerInitiatedCancellation":null,"replacementCancellation":null},"purchaseId":"c078ba1a42e042f3745e195aa52c952b3c99751f3de9880e6c754682698d5133"}
     */
     @POST("/g/refund/{appVersion}")
    suspend fun revokeSubscription(
        @Path("appVersion") appVersion: String,
        @Query("cid") accountId: String,
        @Query("purchaseToken") purchaseToken: String,
        @Query("test") test: Boolean = false
    ): Response<JsonObject?>?


    @GET("/g/ent/{appVersion}")
    suspend fun queryEntitlement(
        @Path("appVersion") appVersion: String,
        @Query("cid") accountId: String,
        @Query("test") test: Boolean = false
    ): Response<JsonObject?>?


    @POST("/g/ack/{appVersion}")
    suspend fun acknowledgePurchase(
        @Path("appVersion") appVersion: String,
        @Query("cid") accountId: String,
        @Query("purchaseToken") purchaseToken: String,
        @Query("force") force: Boolean = false
    ): Response<JsonObject?>?

    /*@GET("/warp/renew")
    @Streaming
    suspend fun renewWarpConfig(
        @Path("fileName") fileName: String,
        @Query("vcode") vcode: Int,
        @Query("compressed") compressed: String
    ): Response<JsonObject?>?

    @GET("/warp/works")
    @Streaming
    suspend fun isWarpConfigWorking(): Response<JsonObject?>?

    @GET("/warp/quota")
    @Streaming
    suspend fun getWarpQuota(
        @Path("update") update: String,
        @Path("blocklist") blocklist: String,
        @Query("tstamp") tStamp: Long,
        @Query("vcode") vcode: Int
    ): Response<JsonObject?>?*/
}
