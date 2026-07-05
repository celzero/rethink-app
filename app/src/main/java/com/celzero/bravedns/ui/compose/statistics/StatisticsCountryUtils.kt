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
package com.celzero.bravedns.ui.compose.statistics

import java.util.Locale

private const val REGIONAL_INDICATOR_A = 0x1F1E6
private const val LATIN_CAPITAL_A = 0x41
private const val LATIN_CAPITAL_Z = 0x5A

internal fun countryNameFromFlag(flag: String?): String? {
    if (flag.isNullOrBlank()) return null

    val codepoints = flag.codePoints().toArray()
    if (codepoints.size != 2) return null

    val first = codepoints[0] - REGIONAL_INDICATOR_A + LATIN_CAPITAL_A
    val second = codepoints[1] - REGIONAL_INDICATOR_A + LATIN_CAPITAL_A
    if (first !in LATIN_CAPITAL_A..LATIN_CAPITAL_Z || second !in LATIN_CAPITAL_A..LATIN_CAPITAL_Z) {
        return null
    }

    val countryCode = "${first.toChar()}${second.toChar()}"
    val countryLocale =
        runCatching { Locale.Builder().setRegion(countryCode).build() }.getOrNull() ?: return null
    val displayName = countryLocale.getDisplayCountry(Locale.getDefault())
    return displayName.takeIf { it.isNotBlank() && !it.equals(countryCode, ignoreCase = true) }
}
