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

import com.celzero.bravedns.net.dns.DnsPacket
import dnsx.Dnsx
import java.util.*

class Transaction(query: DnsPacket, timestamp: Long) {

    var queryTime: Long = timestamp
    var name: String = ""
    var type: Short = 0
    var responseTime: Long = 0
    var status: Status = Status.START
    var response: ByteArray = byteArrayOf()
    var responseCalendar: Calendar = Calendar.getInstance()
    var serverIp: String = ""
    var blocklist: String = ""
    var relayIp: String = ""
    var queryType: QueryType = QueryType.DOH

    init {
        name = query.queryName
        type = query.queryType
        queryTime = timestamp
    }

    enum class Status(val id: Long) {
        START(Dnsx.Start),
        COMPLETE(Dnsx.Complete),
        SEND_FAIL(Dnsx.SendFailed),
        TRANSPORT_ERROR(Dnsx.TransportError),
        NO_RESPONSE(Dnsx.NoResponse),
        BAD_RESPONSE(Dnsx.BadResponse),
        BAD_QUERY(Dnsx.BadQuery),
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

    enum class QueryType(val type: String) {
        DOH(Dnsx.DOH),
        DNS_CRYPT(Dnsx.DNSCrypt),
        DNS_PROXY(Dnsx.DNS53);

        fun isDnsCrypt(): Boolean {
            return this == DNS_CRYPT
        }

        companion object {
            fun getType(type: String): QueryType {
                when (type) {
                    Dnsx.DOH -> return DOH
                    Dnsx.DNSCrypt -> return DNS_CRYPT
                    Dnsx.DNS53 -> return DNS_PROXY
                }
                return DOH
            }
        }
    }
}
