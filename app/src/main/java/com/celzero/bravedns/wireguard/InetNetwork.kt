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

import java.net.Inet4Address
import java.net.InetAddress

/**
 * An Internet network, denoted by its address and netmask
 *
 * Instances of this class are immutable.
 */
class InetNetwork private constructor(val address: InetAddress, val mask: Int) {

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun equals(obj: Any?): Boolean {
        if (obj !is InetNetwork) return false
        return address == obj.address && mask == obj.mask
    }

    override fun hashCode(): Int {
        return address.hashCode() xor mask
    }

    override fun toString(): String {
        return (address.hostAddress ?: "") + '/' + mask
    }

    companion object {
        @Throws(ParseException::class)
        fun parse(network: String): InetNetwork {
            val slash = network.lastIndexOf('/')
            val maskString: String
            val rawMask: Int
            val rawAddress: String
            if (slash >= 0) {
                maskString = network.substring(slash + 1)
                rawMask =
                    try {
                        maskString.toInt(10)
                    } catch (_: NumberFormatException) {
                        throw ParseException(Int::class.java, maskString)
                    }
                rawAddress = network.substring(0, slash)
            } else {
                maskString = ""
                rawMask = -1
                rawAddress = network
            }
            val address: InetAddress = InetAddresses.parse(rawAddress)
            val maxMask = if (address is Inet4Address) 32 else 128
            if (rawMask > maxMask)
                throw ParseException(InetNetwork::class.java, maskString, "Invalid network mask")
            val mask = if (rawMask >= 0) rawMask else maxMask
            return InetNetwork(address, mask)
        }
    }
}
