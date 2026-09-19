/*
 * Copyright 2023 RethinkDNS and its authors
 *
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
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
package com.celzero.bravedns.wireguard

import java.net.URI
import java.net.URISyntaxException
import java.util.regex.Pattern

/**
 * An external endpoint (host and port) used to connect to a WireGuard [Peer].
 *
 * Instances of this class are externally immutable. The host is never resolved here; DNS
 * resolution of the endpoint is delegated to the Go layer.
 */
class InetEndpoint
private constructor(val host: String, private val isResolved: Boolean, val port: Int) {

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun equals(obj: Any?): Boolean {
        if (obj !is InetEndpoint) return false
        return host == obj.host && port == obj.port
    }

    override fun hashCode(): Int {
        return host.hashCode() xor port
    }

    override fun toString(): String {
        val isBareIpv6 = isResolved && BARE_IPV6.matcher(host).matches()
        return (if (isBareIpv6) "[$host]" else host) + ':' + port
    }

    companion object {
        private val BARE_IPV6 = Pattern.compile("^[^\\[\\]]*:[^\\[\\]]*")
        private val FORBIDDEN_CHARACTERS = Pattern.compile("[/?#]")

        @Throws(ParseException::class)
        fun parse(endpoint: String): InetEndpoint {
            if (FORBIDDEN_CHARACTERS.matcher(endpoint).find())
                throw ParseException(InetEndpoint::class.java, endpoint, "Forbidden characters")
            val uri: URI =
                try {
                    URI("wg://$endpoint")
                } catch (e: URISyntaxException) {
                    throw ParseException(InetEndpoint::class.java, endpoint, e)
                }
            if (uri.port !in 0..65535)
                throw ParseException(
                    InetEndpoint::class.java,
                    endpoint,
                    "Missing/invalid port number"
                )
            return try {
                InetAddresses.parse(uri.host)
                // Parsing ths host as a numeric address worked, so we don't need to do DNS lookups.
                InetEndpoint(uri.host, true, uri.port)
            } catch (_: ParseException) {
                // Failed to parse the host as a numeric address, so it must be a DNS hostname/FQDN.
                InetEndpoint(uri.host, false, uri.port)
            }
        }
    }
}
