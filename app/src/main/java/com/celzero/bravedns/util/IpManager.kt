package com.celzero.bravedns.util

import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString

class IpManager {

    companion object {
        fun isIpV6(ip: IPAddress): Boolean {
            return ip.isIPv6
        }

        fun canMakeIpv4(ip: IPAddress): Boolean {

            if (isIpV4Compatible(ip)) {
                return true
            }

            if (isIpv6Prefix64(ip)) {
                return true
            }

            if (isIpTeredo(ip)) {
                return true
            }

            return false
        }

        fun toIpV4(ip: IPAddress): IPAddress? {
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
            if (ip.isIPv6 && ip.isIPv4Convertible && ip.toIPv4() != null) return true

            return false
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
            if (segment.segmentValue == 100) {
                return true
            }

            return false
        }

        fun getIpAddress(ip: String): IPAddress? {
            return IPAddressString(ip).address
        }
    }
}
