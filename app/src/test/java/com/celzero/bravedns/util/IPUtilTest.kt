package com.celzero.bravedns.util

import inet.ipaddr.IPAddressString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class IPUtilTest {

    @Test
    fun testIpV4InV6Conversion() {
        // IPv4-mapped IPv6 address
        val ip4MappedV6 = IPAddressString("::ffff:192.168.1.1").address
        val converted = IPUtil.ip4in6(ip4MappedV6!!)
        assertEquals("192.168.1.1", converted?.toCanonicalString())

        // Not a convertible address
        val pureV6 = IPAddressString("2001:db8::1").address
        assertNull(IPUtil.ip4in6(pureV6!!))
    }

    @Test
    fun testToCIDR() {
        val start = InetAddress.getByName("192.168.1.0")
        val end = InetAddress.getByName("192.168.1.255")
        val cidrs = IPUtil.toCIDR(start, end)
        assertEquals(1, cidrs?.size)
        assertEquals("192.168.1.0/24", cidrs?.get(0)?.address?.hostAddress + "/" + cidrs?.get(0)?.prefix)
    }

    @Test
    fun testMinus1Plus1() {
        val addr = InetAddress.getByName("192.168.1.10")
        assertEquals("192.168.1.9", IPUtil.minus1(addr)?.hostAddress)
        assertEquals("192.168.1.11", IPUtil.plus1(addr)?.hostAddress)
    }

    @Test
    fun testCIDRStartEnd() {
        val cidr = IPUtil.CIDR("192.168.1.123", 24)
        assertEquals("192.168.1.0", cidr.start?.hostAddress)
        assertEquals("192.168.1.255", cidr.end?.hostAddress)
    }
}
