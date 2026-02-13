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
import com.celzero.bravedns.util.Utilities.isAtleastS

/**
 * Theme management for the application.
 *
 * Note: This app may occasionally experience non-fatal TimeoutExceptions in
 * android.content.res.ResourcesImpl$ThemeImpl.finalize(). This is a known Android framework
 * limitation where theme objects cannot be garbage collected fast enough when many activities
 * and dialogs apply custom themes. This is not directly fixable at the application level.
 * See: https://github.com/celzero/rethink-app/issues/[issue-number]
 *
 * Best practices followed to minimize theme-related issues:
 * - Theme application is done once per Activity/Dialog in onCreate/getTheme
 * - Proper cleanup in onDestroyView for dialogs and fragments
 * - View bindings are nulled out to allow garbage collection
 */

// Application themes enum
enum class Themes(val id: Int) {
    SYSTEM_DEFAULT(0),
    LIGHT(1),
    DARK(2),
    TRUE_BLACK(3),
    LIGHT_PLUS(4),
    DARK_PLUS(5),
    DARK_FROST(6);

    companion object {
        fun getThemeCount(): Int {
            return entries.count()
        }

        fun getAvailableThemeCount(): Int {
            return if (isAtleastS()) {
                entries.count()
            } else {
                // Exclude LIGHT_FROST and DARK_FROST for pre-Android S devices
                entries.count() - 2
            }
        }

        fun isFrostTheme(id: Int): Boolean {
            return id == DARK_FROST.id
        }

        fun isThemeAvailable(id: Int): Boolean {
            if (isFrostTheme(id)) {
                return isAtleastS()
            }
            return true
        }

        fun getTheme(id: Int): Int {
            return when (id) {
                SYSTEM_DEFAULT.id -> 0 // system default
                LIGHT.id -> R.style.AppThemeWhite
                DARK.id -> R.style.AppTheme
                TRUE_BLACK.id -> R.style.AppThemeTrueBlack
                LIGHT_PLUS.id -> R.style.AppThemeWhitePlus
                DARK_PLUS.id -> R.style.AppThemeTrueBlackPlus
                DARK_FROST.id -> R.style.AppThemeTrueBlackFrost
                else -> 0
            }
        }

        private fun getBottomSheetTheme(id: Int): Int {
            return when (id) {
                SYSTEM_DEFAULT.id -> 0 // system default
                LIGHT.id -> R.style.BottomSheetDialogThemeWhite
                DARK.id -> R.style.BottomSheetDialogTheme
                TRUE_BLACK.id -> R.style.BottomSheetDialogThemeTrueBlack
                LIGHT_PLUS.id -> R.style.BottomSheetDialogThemeWhitePlus
                DARK_PLUS.id -> R.style.BottomSheetDialogThemeTrueBlackPlus
                // for now use same as dark, can be changed later
                DARK_FROST.id -> R.style.BottomSheetDialogThemeTrueBlack
                else -> 0
            }
        }

        fun getCurrentTheme(isDarkThemeOn: Boolean, theme: Int): Int {
            // If Frost themes are requested on pre-Android S, fallback to appropriate theme
            if (isFrostTheme(theme) && !isAtleastS()) {
                return getTheme(DARK_FROST.id)
            }

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
            } else if (theme == LIGHT_PLUS.id) {
                getTheme(theme)
            } else if (theme == DARK_PLUS.id) {
                getTheme(theme)
            } else if (theme == DARK_FROST.id) {
                getTheme(theme)
            } else {
                getTheme(TRUE_BLACK.id)
            }
        }

        fun getBottomsheetCurrentTheme(isDarkThemeOn: Boolean, theme: Int): Int {
            // If Frost themes are requested on pre-Android S, fallback to appropriate theme
            if (isFrostTheme(theme) && !isAtleastS()) {
                return getBottomSheetTheme(TRUE_BLACK.id)
            }

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
            } else if (theme == LIGHT_PLUS.id) {
                getBottomSheetTheme(theme)
            } else if (theme == DARK_PLUS.id) {
                getBottomSheetTheme(theme)
            } else if (theme == DARK_FROST.id) {
                getBottomSheetTheme(theme)
            } else {
                getBottomSheetTheme(TRUE_BLACK.id)
            }
        }
    }
}
