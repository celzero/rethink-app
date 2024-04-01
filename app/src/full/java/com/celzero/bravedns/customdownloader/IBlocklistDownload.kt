/*
 * Copyright 2021 RethinkDNS and its authors
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
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface IBlocklistDownload {

    /*@Headers(
        "Accept-Encoding: gzip",
        "Content-Type: application/octet-stream",
        "Accept: application/wasm"
    )*/
    // above accept-encoding header is automatically sent by OkHttp, no need to add our headers
    // for gzip. ref: https://github.com/square/okhttp/issues/2132
    @GET("/{fileName}")
    @Streaming
    suspend fun downloadLocalBlocklistFile(
        @Path("fileName") fileName: String,
        @Query("vcode") vcode: Int,
        @Query("compressed") compressed: String
    ): Response<ResponseBody?>?

    @GET("/{fileName}")
    @Streaming
    suspend fun downloadRemoteBlocklistFile(
        @Path("fileName") fileName: String,
        @Query("vcode") vcode: Int,
        @Query("compressed") compressed: String
    ): Response<JsonObject?>?

    @GET("/{update}/{blocklist}")
    @Streaming
    suspend fun downloadAvailabilityCheck(
        @Path("update") update: String,
        @Path("blocklist") blocklist: String,
        @Query("tstamp") tStamp: Long,
        @Query("vcode") vcode: Int
    ): Response<JsonObject?>?
}
