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

import Logger
import Logger.LOG_TAG_VPN
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import inet.ipaddr.IPAddress
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

class IPUtil {

    companion object {
        fun isIpV6(ip: IPAddress): Boolean {
            return ip.isIPv6
        }

        fun ip4in6(ip: IPAddress): IPAddress? {
            if (ip.isIPv4) {
                // already v4; no need to convert
                return null
            }
            if (isIpV4Compatible(ip)) {
                return ip.toIPv4()
            }

            if (isIpv6Prefix64(ip)) {
                // get the last 4 bytes from the segment and convert into IPv4 address
                return ip.toIPv6().getEmbeddedIPv4Address(12)
            }

            if (isIpTeredo(ip)) {
                // Returns the second and third segments as an IPv4Address
                // teredo tunnelling will contain the IPv4 address in segment 3 and 4.
                // 2001::/32 (segment 0 should match 2001, segment 1 should be zero)
                // sample: 2001:0000:4136:e378:8000:63bf:3fff:fdd2 -> 65.54.227.120
                return ip.toIPv6().getEmbeddedIPv4Address(4)
            }

            // as of now, support for Routing between 6to4 and native IPv6 (protocol type 41) is
            // not supported in netstack, need to implement those changes once netstack starts
            // supporting protocol 41, block ipv4 if ipv6 prefix is 2002::
            // ref: https://en.wikipedia.org/wiki/6to4#Routing_between_6to4_and_native_IPv6

            return null
        }

        // for IPv4-Mapped IPv6 Address and IPv4 embedded IPv6 (prefix: 2001:db8::/32)
        private fun isIpV4Compatible(ip: IPAddress): Boolean {
            return ip.isIPv6 && ip.isIPv4Convertible && ip.toIPv4() != null
        }

        // for teredo tunneling
        private fun isIpTeredo(ips: IPAddress): Boolean {
            return ips.toIPv6().isTeredo
        }

        // for IPv6 address with prefix-64
        private fun isIpv6Prefix64(ip: IPAddress): Boolean {
            // find if there is a valid ipv4 address available for the ipv6 address which
            // has the first segment value as 64
            val ipv6 = ip.toIPv6()
            // get the first segment from ipv6 address
            val segment = ipv6.getSegment(0)
            // Decimal value for the 64(hexa) is 100
            return segment.segmentValue == 100
        }


        // ref: github.com/M66B/NetGuard/blob/master/app/src/main/java/eu/faircode/netguard
        @Throws(UnknownHostException::class)
        fun toCIDR(start: InetAddress?, end: InetAddress?): List<CIDR>? {
            if (start == null || end == null) return null

            val listResult: MutableList<CIDR> = ArrayList()
            Logger.d(LOG_TAG_VPN, "toCIDR(" + start.hostAddress + "," + end.hostAddress + ")")
            var from: Long = inet2long(start)
            val to: Long = inet2long(end)
            while (to >= from) {
                var prefix: Byte = 32
                while (prefix > 0) {
                    val mask: Long = prefix2mask(prefix - 1)
                    if (from and mask != from) break
                    prefix--
                }
                val max = (32 - floor(ln((to - from + 1).toDouble()) / ln(2.0))).toInt().toByte()
                if (prefix < max) prefix = max
                listResult.add(CIDR(long2inet(from)?.hostAddress, prefix.toInt()))
                from += 2.0.pow((32 - prefix).toDouble()).toLong()
            }
            if (DEBUG) {
                for (cidr in listResult) Logger.d(LOG_TAG_VPN, cidr.toString())
            }
            return listResult
        }

        private fun prefix2mask(bits: Int): Long {
            return -0x100000000L shr bits and 0xFFFFFFFFL
        }

        private fun inet2long(address: InetAddress?): Long {
            var result: Long = 0
            if (address != null)
                for (b in address.address) result = result shl 8 or (b.toInt() and 0xFF).toLong()
            return result
        }

        private fun long2inet(a: Long): InetAddress? {
            var addr = a
            return try {
                val b = ByteArray(4)
                for (i in b.indices.reversed()) {
                    b[i] = (addr and 0xFFL).toByte()
                    addr = addr shr 8
                }
                InetAddress.getByAddress(b)
            } catch (ignore: UnknownHostException) {
                null
            }
        }

        fun minus1(address: InetAddress?): InetAddress? {
            return long2inet(inet2long(address) - 1)
        }

        fun plus1(address: InetAddress?): InetAddress? {
            return long2inet(inet2long(address) + 1)
        }
    }

    class CIDR : Comparable<CIDR?> {
        var address: InetAddress? = null
        var prefix = 0

        constructor(address: InetAddress?, prefix: Int) {
            this.address = address
            this.prefix = prefix
        }

        constructor(ip: String?, prefix: Int) {
            try {
                address = InetAddress.getByName(ip)
                this.prefix = prefix
            } catch (ex: UnknownHostException) {
                Logger.e(LOG_TAG_VPN, "error parsing CIDR, $ip, $prefix, $ex")
            }
        }

        val start: InetAddress?
            get() = long2inet(inet2long(address) and prefix2mask(prefix))

        val end: InetAddress?
            get() =
                long2inet((inet2long(address) and prefix2mask(prefix)) + (1L shl 32 - prefix) - 1)

        override fun toString(): String {
            return address?.hostAddress +
                "/" +
                prefix +
                "=" +
                start?.hostAddress +
                "..." +
                end?.hostAddress
        }

        override operator fun compareTo(other: CIDR?): Int {
            val lcidr = inet2long(address)
            val lother = inet2long(other?.address)
            return lcidr.compareTo(lother)
        }
    }
}
