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
package com.celzero.bravedns.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerProfileActivationPolicyTest {
    @Test
    fun `active profile resolves to disable`() {
        val profile = profileDefinition()

        assertEquals(
            PowerProfileActivationAction.DISABLE_PROFILE,
            PowerProfileActivationPolicy.resolve(profile, isActive = true, hasTunnel = false)
        )
    }

    @Test
    fun `unready profile resolves to ignore`() {
        val profile = profileDefinition(readyForActivation = false)

        assertEquals(
            PowerProfileActivationAction.IGNORE,
            PowerProfileActivationPolicy.resolve(profile, isActive = false, hasTunnel = true)
        )
    }

    @Test
    fun `native profile without tunnel starts protection first`() {
        val profile = profileDefinition(localBlocklistTagIds = listOf(1))

        assertEquals(
            PowerProfileActivationAction.START_PROTECTION_AND_ENABLE,
            PowerProfileActivationPolicy.resolve(profile, isActive = false, hasTunnel = false)
        )
    }

    @Test
    fun `native profile with tunnel enables directly`() {
        val profile = profileDefinition(localBlocklistTagIds = listOf(1))

        assertEquals(
            PowerProfileActivationAction.ENABLE_PROFILE,
            PowerProfileActivationPolicy.resolve(profile, isActive = false, hasTunnel = true)
        )
    }

    private fun profileDefinition(
        readyForActivation: Boolean = true,
        localBlocklistTagIds: List<Int> = emptyList()
    ): PowerProfileDefinition {
        return PowerProfileDefinition(
            id = "test-profile",
            titleText = "Test Profile",
            descriptionText = "Test description",
            iconRes = 0,
            readyForActivation = readyForActivation,
            localBlocklistTagIds = localBlocklistTagIds
        )
    }
}
