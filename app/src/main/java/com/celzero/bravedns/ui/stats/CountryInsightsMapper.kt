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

/**
 * Maps the flag emoji stored in the connection/DNS logs back to its ISO
 * 3166-1 alpha-2 country code, so stats rows can be matched against the
 * offline world-map polygons (see [com.celzero.bravedns.util.WorldMapPaths]).
 * This is the inverse of [com.celzero.bravedns.util.Utilities.getFlag].
 */
object CountryInsightsMapper {

    // regional indicator symbols U+1F1E6..U+1F1FF map to 'A'..'Z'
    private const val REGIONAL_INDICATOR_A = 0x1F1E6
    private const val LETTER_A = 'A'

    /**
     * Returns the ISO alpha-2 code for a flag emoji, or null when [flag] is
     * not exactly one flag emoji (empty, "--", "??", plain text, etc.).
     */
    fun toCountryCode(flag: String?): String? {
        if (flag.isNullOrEmpty()) return null
        val cps = flag.codePoints().toArray()
        if (cps.size != 2) return null
        val a = cps[0] - REGIONAL_INDICATOR_A
        val b = cps[1] - REGIONAL_INDICATOR_A
        if (a !in 0..25 || b !in 0..25) return null
        return "${LETTER_A + a}${LETTER_A + b}"
    }
}
