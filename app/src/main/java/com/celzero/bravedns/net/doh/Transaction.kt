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

import dnsx.Dnsx
import java.util.Calendar

class Transaction {

    var queryTime: Long = 0L
    var name: String = ""
    var type: Long = 0
    var responseTime: Long = 0
    var status: Status = Status.START
    var response: String = ""
    var responseCalendar: Calendar = Calendar.getInstance()
    var serverName: String = ""
    var blocklist: String = ""
    var relayName: String = ""
    var id: String = ""
    var ttl: Long = 0L
    var transportType: TransportType = TransportType.DOH

    enum class Status(val id: Long) {
        START(Dnsx.Start),
        COMPLETE(Dnsx.Complete),
        SEND_FAIL(Dnsx.SendFailed),
        TRANSPORT_ERROR(Dnsx.TransportError),
        NO_RESPONSE(Dnsx.NoResponse),
        BAD_RESPONSE(Dnsx.BadResponse),
        BAD_QUERY(Dnsx.BadQuery),
        CLIENT_ERROR(Dnsx.ClientError),
        INTERNAL_ERROR(Dnsx.InternalError);

        companion object {
            fun fromId(id: Long): Status {
                for (status in values()) {
                    if (status.id == id) {
                        return status
                    }
                }
                return INTERNAL_ERROR
            }
        }
    }

    enum class TransportType(val type: String) {
        DOH(Dnsx.DOH),
        DNS_CRYPT(Dnsx.DNSCrypt),
        DNS_PROXY(Dnsx.DNS53);

        companion object {
            fun getType(type: String): TransportType {
                return when (type) {
                    Dnsx.DOH -> DOH
                    Dnsx.DNSCrypt -> DNS_CRYPT
                    Dnsx.DNS53 -> DNS_PROXY
                    else -> DOH
                }
            }
        }
    }
}
