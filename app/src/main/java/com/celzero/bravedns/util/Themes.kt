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

import com.celzero.bravedns.R

// Application themes enum
enum class Themes(val id: Int) {
    SYSTEM_DEFAULT(0),
    LIGHT(1),
    DARK(2),
    TRUE_BLACK(3);

    companion object {
        fun getThemeCount(): Int {
            return values().count()
        }

        fun getTheme(id: Int): Int {
            return when (id) {
                SYSTEM_DEFAULT.id -> 0 // system default
                LIGHT.id -> R.style.AppThemeWhite
                DARK.id -> R.style.AppTheme
                TRUE_BLACK.id -> R.style.AppThemeTrueBlack
                else -> 0
            }
        }

        private fun getBottomSheetTheme(id: Int): Int {
            return when (id) {
                SYSTEM_DEFAULT.id -> 0 // system default
                LIGHT.id -> R.style.BottomSheetDialogThemeWhite
                DARK.id -> R.style.BottomSheetDialogTheme
                TRUE_BLACK.id -> R.style.BottomSheetDialogThemeTrueBlack
                else -> 0
            }
        }

        fun getCurrentTheme(isDarkThemeOn: Boolean, theme: Int): Int {
            return if (theme == SYSTEM_DEFAULT.id) {
                if (isDarkThemeOn) {
                    getTheme(TRUE_BLACK.id)
                } else {
                    getTheme(LIGHT.id)
                }
            } else if (theme == LIGHT.id) {
                getTheme(theme)
            } else if (theme == DARK.id) {
                getTheme(theme)
            } else {
                getTheme(TRUE_BLACK.id)
            }
        }

        fun getBottomsheetCurrentTheme(isDarkThemeOn: Boolean, theme: Int): Int {
            return if (theme == SYSTEM_DEFAULT.id) {
                if (isDarkThemeOn) {
                    getBottomSheetTheme(TRUE_BLACK.id)
                } else {
                    getBottomSheetTheme(LIGHT.id)
                }
            } else if (theme == LIGHT.id) {
                getBottomSheetTheme(theme)
            } else if (theme == DARK.id) {
                getBottomSheetTheme(theme)
            } else {
                getBottomSheetTheme(TRUE_BLACK.id)
            }
        }
    }
}
