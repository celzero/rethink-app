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
package com.celzero.bravedns.util

enum class PcapMode(val id: Int) {
    NONE(0),
    LOGCAT(1),
    EXTERNAL_FILE(2);

    companion object {

        // const to enable/disable pcap
        const val ENABLE_PCAP_LOGCAT = "0"
        const val DISABLE_PCAP = ""

        fun getPcapType(id: Int): PcapMode {
            return when (id) {
                0 -> NONE
                1 -> LOGCAT
                2 -> EXTERNAL_FILE
                else -> NONE
            }
        }
    }
}
