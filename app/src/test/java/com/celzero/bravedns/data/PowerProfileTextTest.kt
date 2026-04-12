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

class PowerProfileTextTest {

    @Test
    fun truncateWords_keepsShortDescriptionsUntouched() {
        val text = "A calmer browsing profile for daily use"

        val truncated = PowerProfileText.truncateWords(text, 15)

        assertEquals(text, truncated)
    }

    @Test
    fun truncateWords_appendsEllipsisWhenWordLimitIsExceeded() {
        val text =
            "A community app pack for Parrot Downloader that carries the ad network blocklist we validated during live analysis"

        val truncated = PowerProfileText.truncateWords(text, 15)

        assertEquals(
            "A community app pack for Parrot Downloader that carries the ad network blocklist we validated...",
            truncated
        )
    }
}
