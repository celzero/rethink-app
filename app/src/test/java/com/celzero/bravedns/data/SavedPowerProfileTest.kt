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

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedPowerProfileTest {

    @Test
    fun fromJson_roundTripsSavedProfile() {
        val profile =
            SavedPowerProfile(
                id = "saved-1",
                name = "Saved setup 1",
                note = "Created locally",
                createdAt = 1234L,
                protectionStatus = "On",
                engineMode = "DNS + Firewall"
            )

        val restored = SavedPowerProfile.fromJson(profile.toJson())

        assertEquals(profile, restored)
    }

    @Test
    fun parseStorageList_returnsEmptyListForInvalidPayload() {
        assertTrue(SavedPowerProfile.parseStorageList("not-json").isEmpty())
    }

    @Test
    fun parseStorageList_skipsMalformedItems() {
        val jsonArray =
            """
            [
              {"id":"saved-1","name":"Saved setup 1","note":"ok","createdAt":10,"protectionStatus":"On","engineMode":"DNS + Firewall"},
              {"id":"","name":"","createdAt":0}
            ]
            """.trimIndent()

        val restored = SavedPowerProfile.parseStorageList(jsonArray)

        assertEquals(1, restored.size)
        assertEquals("saved-1", restored.first().id)
    }
}
