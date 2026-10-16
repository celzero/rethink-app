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
package com.celzero.bravedns.util

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.celzero.bravedns.util.Logger.LOG_TAG_APP

/**
 * Guards against the MIUI floating-toolbar crash on secondary-user installs
 * (Dual Apps / cloned app / work profile): MIUI's text-selection toolbar adds
 * its popup window with a cross-user accessibility lookup that Android rejects
 * with a fatal SecurityException (see [Utilities.isMiuiFloatingToolbarBugAffected]).
 *
 * On affected installs this disables long-press text selection on TextViews so the
 * OEM floating toolbar is never created. Users there lose long-press copy/select;
 * explicit copy buttons keep working. Everywhere else every call here is a no-op.
 *
 * Note: an Activity-level `onWindowStartingActionMode` hook cannot suppress the
 * toolbar — AppCompat returns `null` for `TYPE_FLOATING` before consulting the
 * Activity — so the view-tree walk is the only effective suppression point.
 */
object TextSelectionGuard {

    /** Logs the one-time diagnostic so bug reports explain why select is disabled. */
    private var loggedOnce = false

    /**
     * Clears [TextView.setTextIsSelectable] across the view tree rooted at [root].
     * No-op when the install is not affected by the MIUI bug.
     */
    fun apply(root: View?, affected: Boolean = Utilities.isMiuiFloatingToolbarBugAffected()) {
        if (!affected || root == null) return

        logGuardActive()
        clearTextSelection(root)
    }

    private fun clearTextSelection(view: View) {
        // EditText is skipped: setTextIsSelectable is a no-op there, and its selection
        // toolbar cannot be suppressed via any public API (accepted residual risk).
        if (view is TextView && view !is EditText) {
            view.setTextIsSelectable(false)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                clearTextSelection(view.getChildAt(i))
            }
        }
    }

    @Synchronized
    private fun logGuardActive() {
        if (loggedOnce) return
        loggedOnce = true
        Logger.i(
            LOG_TAG_APP,
            "miui secondary-user install detected; text-selection toolbar suppressed to avoid floating-toolbar crash"
        )
    }
}
