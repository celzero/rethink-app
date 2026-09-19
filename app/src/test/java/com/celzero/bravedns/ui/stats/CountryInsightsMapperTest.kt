/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.stats

import com.celzero.bravedns.util.Utilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CountryInsightsMapperTest {

    @Test
    fun `flag emoji maps to ISO alpha-2`() {
        assertEquals("US", CountryInsightsMapper.toCountryCode(Utilities.getFlag("US")))
        assertEquals("DE", CountryInsightsMapper.toCountryCode(Utilities.getFlag("DE")))
        assertEquals("IN", CountryInsightsMapper.toCountryCode(Utilities.getFlag("IN")))
    }

    @Test
    fun `unknown or malformed flags never map to a country`() {
        assertNull(CountryInsightsMapper.toCountryCode(null))
        assertNull(CountryInsightsMapper.toCountryCode(""))
        assertNull(CountryInsightsMapper.toCountryCode("--"))
        assertNull(CountryInsightsMapper.toCountryCode("??"))
        assertNull(CountryInsightsMapper.toCountryCode("US"))
        assertNull(CountryInsightsMapper.toCountryCode("abc"))
    }

    @Test
    fun `round trip is stable for all letter pairs`() {
        for (a in 'A'..'Z') {
            for (b in 'A'..'Z') {
                val code = "$a$b"
                assertEquals(code, CountryInsightsMapper.toCountryCode(Utilities.getFlag(code)))
            }
        }
    }
}
