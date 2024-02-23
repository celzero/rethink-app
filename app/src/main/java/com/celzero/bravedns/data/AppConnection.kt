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
package com.celzero.bravedns.data

data class AppConnection(
    val uid: Int,
    val ipAddress: String,
    val port: Int,
    val count: Int,
    val flag: String,
    val blocked: Boolean,
    val appOrDnsName: String?,
    val downloadBytes: Long? = 0L,
    val uploadBytes: Long? = 0L,
    val totalBytes: Long? = 0L
)
