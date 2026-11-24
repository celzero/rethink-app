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
package com.celzero.bravedns.wireguard

import com.celzero.firestack.backend.WgKey
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class WgInterfaceTest {

    private val testPrivateKeyHex = "aa".repeat(32)

    private fun newInterface(configure: WgInterface.Builder.() -> Unit = {}): WgInterface {
        val key = mockk<WgKey>()
        every { key.hex() } returns testPrivateKeyHex
        every { key.base64() } returns "test-key"
        every { key.mult() } returns mockk()
        val keyPair = mockk<KeyPair>()
        every { keyPair.getPrivateKey() } returns key
        every { keyPair.getPublicKey() } returns mockk()
        val builder = WgInterface.Builder().setKeyPair(keyPair)
        builder.configure()
        return builder.build()
    }

    @Test
    fun `userspace string injects default dns when config has none`() {
        val wgInterface =
            newInterface {
                parseAddresses("10.0.101.3/32")
                parseMtu("1280")
            }
        val userspace = wgInterface.toWgUserspaceString(true)
        assertTrue(userspace.contains("dns=${WgInterface.DEFAULT_DNS}\n"))
    }

    @Test
    fun `userspace string keeps configured dns untouched`() {
        val wgInterface =
            newInterface {
                parseAddresses("10.0.101.3/32")
                parseDnsServers("8.8.8.8")
            }
        val userspace = wgInterface.toWgUserspaceString(true)
        assertTrue(userspace.contains("dns=8.8.8.8\n"))
        assertFalse(userspace.contains(WgInterface.DEFAULT_DNS))
    }

    @Test
    fun `userspace string does not inject default when only search domains set`() {
        val wgInterface =
            newInterface {
                parseAddresses("10.0.101.3/32")
                parseDnsServers("example.internal")
            }
        val userspace = wgInterface.toWgUserspaceString(true)
        assertTrue(userspace.contains("dns=example.internal\n"))
        assertFalse(userspace.contains(WgInterface.DEFAULT_DNS))
    }

    @Test
    fun `userspace string keeps search domains alongside numeric resolvers`() {
        val wgInterface =
            newInterface {
                parseAddresses("10.0.101.3/32")
                parseDnsServers("8.8.8.8,example.internal")
            }
        val userspace = wgInterface.toWgUserspaceString(true)
        assertTrue(userspace.contains("dns=8.8.8.8,example.internal\n"))
        assertFalse(userspace.contains(WgInterface.DEFAULT_DNS))
    }

    @Test
    fun `quick string is unaffected by the userspace dns fallback`() {
        val wgInterface = newInterface { parseAddresses("10.0.101.3/32") }
        assertFalse(wgInterface.toWgQuickString().contains("DNS"))
    }
}
