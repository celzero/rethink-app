/*
 * Copyright 2018 Jigsaw Operations LLC
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
package com.celzero.bravedns.net.doh

import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.firestack.backend.Backend
import java.util.Calendar

class Transaction {

    var uid: Int = INVALID_UID
    var queryTime: Long = 0L
    var qName: String = ""
    var type: Long = 0
    var latency: Long = 0
    var status: Status = Status.START
    var response: String = ""
    var responseCode: Long = 0L
    var responseCalendar: Calendar = Calendar.getInstance()
    var serverName: String = ""
    var blocklist: String = ""
    var relayName: String = ""
    var proxyId: String = ""
    var id: String = ""
    var ttl: Long = 0L
    var transportType: TransportType = TransportType.DOH
    var msg: String = ""
    var upstreamBlock: Boolean = false
    var region: String = ""
    var isCached: Boolean = false
    var dnssecOk: Boolean = false
    var dnssecValid: Boolean = false

    enum class Status(val id: Long) {
        START(Backend.Start),
        COMPLETE(Backend.Complete),
        SEND_FAIL(Backend.SendFailed),
        TRANSPORT_ERROR(Backend.TransportError),
        NO_RESPONSE(Backend.NoResponse),
        BAD_RESPONSE(Backend.BadResponse),
        BAD_QUERY(Backend.BadQuery),
        CLIENT_ERROR(Backend.ClientError),
        INTERNAL_ERROR(Backend.InternalError);

        companion object {
            fun fromId(id: Long): Status {
                for (status in enumValues<Status>()) {
                    if (status.id == id) {
                        return status
                    }
                }
                return INTERNAL_ERROR
            }
        }
    }

    enum class TransportType(val type: String) {
        DOH(Backend.DOH),
        DNS_CRYPT(Backend.DNSCrypt),
        DNS_PROXY(Backend.DNS53),
        DOT(Backend.DOT),
        ODOH(Backend.ODOH);

        companion object {
            fun getType(type: String): TransportType {
                return when (type) {
                    Backend.DOH -> DOH
                    Backend.DNSCrypt -> DNS_CRYPT
                    Backend.DNS53 -> DNS_PROXY
                    Backend.DOT -> DOT
                    Backend.ODOH -> ODOH
                    else -> DOH
                }
            }

            fun fromOrdinal(ordinal: Int): TransportType {
                return TransportType.entries[ordinal]
            }
        }
    }
}
