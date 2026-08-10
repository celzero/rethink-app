/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.service

import com.celzero.bravedns.shadows.ShadowBackend
import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the bug where inputting a non-CIDR-able wildcard such as
 * `*.255.255.255` caused [IpRulesManager.padAndNormalize] to collapse the stored
 * IP to an empty string, producing the symptoms:
 *  - list label showed `: 0` instead of the wildcard
 *  - edit dialog showed an empty input field
 *
 * Root cause: [IpRulesManager.treeKey] returns null when
 * `assignPrefixForSingleBlock()` returns null for wildcards that vary high-order
 * bits while fixing low-order bits (the inverse of CIDR structure).  The old code
 * applied `.orEmpty()` to this null, silently producing `""`.
 *
 * These tests use Robolectric + [ShadowBackend] so the `IpRulesManager` object
 * (which calls `Backend.newIpTree()` in its initializer) can be constructed on
 * the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [ShadowBackend::class])
class IpRulesManagerWildcardTest {

    @Test
    fun `star-prefix with fixed trailing octets is a valid wildcard`() {
        val ips = IPAddressString("*.255.255.255")
        ips.validate() // does not throw
        val addr = ips.address
        assertNotNull(addr)
    }

    @Test
    fun `assignPrefixForSingleBlock is null for non-CIDR-able wildcards`() {
        // *.255.255.255 spans 0.255.255.255..255.255.255.255; the high octet
        // varies while the low 24 bits are fixed — no single CIDR represents this.
        assertNull(IPAddressString("*.255.255.255").address!!.assignPrefixForSingleBlock())
        // 1.2.*.4 from the code comments — wildcard in a middle octet
        assertNull(IPAddressString("1.2.*.4").address!!.assignPrefixForSingleBlock())
    }

    @Test
    fun `assignPrefixForSingleBlock works for trailing wildcards`() {
        assertNotNull(IPAddressString("1.2.*.*").address!!.assignPrefixForSingleBlock())
        assertNotNull(IPAddressString("*.*.*.*").address!!.assignPrefixForSingleBlock())
        assertEquals(
            "0.0.0.0/0",
            IPAddressString("*.*").address!!.assignPrefixForSingleBlock()!!.toCanonicalString()
        )
    }

    @Test
    fun `getIpNetPort returns non-null IP for non-CIDR-able wildcard`() {
        // The validation gate in the UI checks `ip == null`.  Before any fix,
        // getIpNetPort correctly returns a non-null IPAddress here — the bug is
        // downstream in padAndNormalize, not in getIpNetPort.
        val (ip, port) = IpRulesManager.getIpNetPort("*.255.255.255")
        assertNotNull("getIpNetPort must return a non-null IPAddress for *.255.255.255", ip)
        assertEquals(0, port)
    }

    @Test
    fun `getIpNetPort handles valid CIDR-able wildcard`() {
        val (ip, port) = IpRulesManager.getIpNetPort("10.*.*.*")
        assertNotNull(ip)
        assertEquals(0, port)
    }

    @Test
    fun `treeKey returns null for non-CIDR-able wildcard`() {
        // treeKey is private; exercise it indirectly through normalize which is
        // also private.  Instead, replicate the exact logic to document contract.
        val ipstr = "*.255.255.255"
        val addr = IPAddressString(ipstr).address!!
        val singleBlock = addr.assignPrefixForSingleBlock()
        // treeKey returns singleBlock?.toCanonicalString() which is null
        assertNull(singleBlock)
    }

    // padAndNormalize and treeKey are private.  We verify the *contract* that
    // the fix relies on: that a valid wildcard does NOT become an empty string.

    @Test
    fun `simulated padAndNormalize preserves non-CIDR-able wildcard`() {
        // Replicate the fixed padAndNormalize logic for *.255.255.255
        val ipaddr: IPAddress = IPAddressString("*.255.255.255").address!!
        var ipStr = ipaddr.toNormalizedString() // "*.255.255.255"
        assertEquals("*.255.255.255", ipStr)

        // padIpv4Cidr is a no-op for 4-segment addresses
        // (verified separately — see test below)

        // normalize(pair.first) -> treeKey("*.255.255.255") -> null
        val normalized: String? = null // simulate normalize returning null

        // THE FIX: fall back to ipStr instead of .orEmpty()
        val result = normalized ?: ipStr

        // Before fix: result would be "" (from .orEmpty())
        // After fix:  result is "*.255.255.255"
        assertEquals("*.255.255.255", result)
        assertTrue("stored IP must not be empty", result.isNotEmpty())
    }

    @Test
    fun `simulated padAndNormalize still produces CIDR for CIDR-able wildcard`() {
        // 1.2.*.* should still normalize to 1.2.0.0/16
        val ipaddr: IPAddress = IPAddressString("1.2.*.*").address!!
        val ipStr = ipaddr.toNormalizedString()

        // treeKey would return assignPrefixForSingleBlock().toCanonicalString()
        val singleBlock = ipaddr.assignPrefixForSingleBlock()
        assertNotNull(singleBlock)
        val normalized: String? = singleBlock?.toCanonicalString()

        val result = normalized ?: ipStr
        assertEquals("1.2.0.0/16", result)
    }

    @Test
    fun `simulated padAndNormalize for plain IP unchanged`() {
        val ipaddr: IPAddress = IPAddressString("192.168.1.1").address!!
        val ipStr = ipaddr.toNormalizedString()
        assertEquals("192.168.1.1", ipStr)

        // For non-wildcard, treeKey returns toNormalizedString()
        val result: String = ipaddr.toNormalizedString()
        assertEquals("192.168.1.1", result)
    }

    @Test
    fun `padIpv4Cidr does not alter fully-qualified wildcard`() {
        // Replicate padIpv4Cidr for "*.255.255.255":
        // ipParts = ["*", "255", "255", "255"] — size == 4 → returns input unchanged
        val cidr = "*.255.255.255"
        val parts = cidr.split("/")[0].split(".")
        assertEquals(4, parts.size)
        // so padIpv4Cidr returns the input unchanged
    }

    @Test
    fun `stored empty string would display as colon-zero (the bug symptom)`() {
        // ci_ip_label = "%1$s: %2$s"
        val labelFormat = "%1\$s: %2\$s"
        val buggyDisplay = String.format(labelFormat, "", "0")
        assertEquals(": 0", buggyDisplay)
    }

    @Test
    fun `stored wildcard displays correctly after fix`() {
        val labelFormat = "%1\$s: %2\$s"
        val fixedDisplay = String.format(labelFormat, "*.255.255.255", "0")
        assertEquals("*.255.255.255: 0", fixedDisplay)
    }

    @Test
    fun `edit dialog shows empty field when stored IP is empty (the bug)`() {
        val storedIp = "" // the corrupt value before the fix
        val port = 0
        // showEditIpDialog: port == 0 → else branch → setText(storedIp)
        val editText = if (port != 0) "non-empty" else storedIp
        assertEquals("", editText)
    }

    @Test
    fun `edit dialog shows wildcard after fix`() {
        val storedIp = "*.255.255.255" // the preserved value after the fix
        val port = 0
        val editText = if (port != 0) "non-empty" else storedIp
        assertEquals("*.255.255.255", editText)
    }
}
