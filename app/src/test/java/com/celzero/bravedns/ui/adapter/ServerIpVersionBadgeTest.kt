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
package com.celzero.bravedns.ui.adapter

import com.celzero.bravedns.database.CountryConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerIpVersionBadgeTest {

    @Test
    fun `badge reflects supported ip versions across grouped servers`() {
        assertNull(serverIpVersionBadge(listOf(CountryConfig(id = "none", cc = "US"))))
        assertEquals(
            "IP4",
            serverIpVersionBadge(listOf(CountryConfig(id = "v4", cc = "US", ipv4 = true)))
        )
        assertEquals(
            "IP6",
            serverIpVersionBadge(listOf(CountryConfig(id = "v6", cc = "US", ipv6 = true)))
        )
        assertEquals(
            "IP46",
            serverIpVersionBadge(
                listOf(
                    CountryConfig(id = "v4", cc = "US", ipv4 = true),
                    CountryConfig(id = "v6", cc = "US", ipv6 = true)
                )
            )
        )
    }
}
