/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.util

// Application themes enum
enum class Themes(val id: Int) {
    SYSTEM_DEFAULT(0),
    LIGHT(1),
    DARK(2),
    LIGHT_PLUS(4),
    DARK_PLUS(5);

    companion object {
        private const val LEGACY_TRUE_BLACK_ID = 3
        private const val LEGACY_DARK_FROST_ID = 6

        fun getThemeCount(): Int {
            return entries.count()
        }

        fun getAvailableThemeCount(): Int {
            return entries.count()
        }

        fun isFrostTheme(id: Int): Boolean {
            return false
        }

        fun isThemeAvailable(id: Int): Boolean {
            return when (id) {
                SYSTEM_DEFAULT.id,
                LIGHT.id,
                DARK.id,
                LIGHT_PLUS.id,
                DARK_PLUS.id,
                LEGACY_TRUE_BLACK_ID,
                LEGACY_DARK_FROST_ID -> true
                else -> false
            }
        }

        fun resolveThemePreference(isDarkThemeOn: Boolean, preference: Int): Int {
            return when (preference) {
                SYSTEM_DEFAULT.id -> if (isDarkThemeOn) DARK_PLUS.id else LIGHT_PLUS.id
                LIGHT.id,
                DARK.id,
                LIGHT_PLUS.id,
                DARK_PLUS.id -> preference
                LEGACY_TRUE_BLACK_ID,
                LEGACY_DARK_FROST_ID -> DARK_PLUS.id
                else -> if (isDarkThemeOn) DARK_PLUS.id else LIGHT_PLUS.id
            }
        }

        fun useDynamicColor(preference: Int): Boolean {
            return preference == SYSTEM_DEFAULT.id
        }
    }
}
