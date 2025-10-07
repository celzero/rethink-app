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
package com.rethinkdns.retrixed.customdownloader

import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Streaming

interface IWireguardWarp {

    @GET("/warp/new")
    suspend fun getNewWarpConfig(
        @Query("pubkey") publicKey: String,
        @Query("device") device: String,
        @Query("locale") locale: String
    ): Response<JsonObject?>?

    @GET("/warp/renew")
    @Streaming
    suspend fun renewWarpConfig(
        @Query("id") id: String,
        @Query("token") token: Int,
        @Query("device") device: String,
        @Query("locale") locale: String
    ): Response<JsonObject?>?

    @GET("/warp/works") @Streaming suspend fun isWarpConfigWorking(): Response<JsonObject?>?

    @GET("/warp/quota")
    @Streaming
    suspend fun getWarpQuota(
        @Query("id") id: String,
        @Query("token") token: Int,
        @Query("device") device: String,
        @Query("locale") locale: String
    ): Response<JsonObject?>?
}
