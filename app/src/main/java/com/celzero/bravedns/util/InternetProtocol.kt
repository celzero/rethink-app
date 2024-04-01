/*
 * Copyright 2022 RethinkDNS and its authors
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
package com.celzero.bravedns.util

import settings.Settings

enum class InternetProtocol(val id: Int) {
    IPv4(0),
    IPv6(1),
    IPv46(2);

    fun isIPv6(): Boolean {
        return this == IPv6
    }

    fun isIPv46(): Boolean {
        return this == IPv46
    }

    companion object {
        fun getInternetProtocol(id: Int): InternetProtocol {
            return when (id) {
                0 -> IPv4
                1 -> IPv6
                2 -> IPv46
                else -> IPv4
            }
        }

        fun byProtos(has4: Boolean, has6: Boolean): InternetProtocol {
            return when {
                has4 && has6 -> IPv46
                has4 -> IPv4
                has6 -> IPv6
                else -> IPv4
            }
        }

        fun isAuto(id: Int): Boolean {
            return id == IPv46.id
        }
    }

    // preferred network engine id (go-module)
    fun value(): Long {
        return when (this) {
            IPv4 -> Settings.Ns4
            IPv6 -> Settings.Ns6
            IPv46 -> Settings.Ns46
        }
    }
}
