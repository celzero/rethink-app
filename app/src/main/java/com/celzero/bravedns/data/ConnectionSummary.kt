/*
 * Copyright 2020 RethinkDNS and its authors
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

import java.io.Serializable

// do not use as key in map or set, as some fields are mutable
data class ConnectionSummary(
    val uid: String,
    val pid: String,
    val connId: String,
    val downloadBytes: Long,
    val uploadBytes: Long,
    val duration: Int,
    val synack: Int,
    val message: String,
    val targetIp: String?,
    var flag: String?
) : Serializable
