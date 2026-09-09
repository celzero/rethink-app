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
package com.celzero.bravedns.ui.bottomsheet

import android.app.Dialog
import android.os.Bundle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Base class for all modal bottom sheets.
 *
 * On expanded windows (foldables in the open state, tablets, split-screen), the sheet's
 * width is capped to [MAX_WIDTH_DP] so it renders as a centered, phone-like column
 * instead of stretching edge-to-edge. The Material bottom sheet style applies
 * `center_horizontal` gravity to the sheet, so a max width is all that is needed for
 * centering. Narrow windows (regular phones) are unaffected since the cap never kicks in.
 *
 * All bottom sheets must extend this class instead of [BottomSheetDialogFragment] directly.
 */
abstract class BaseBottomSheetDialogFragment : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        val density = resources.displayMetrics.density
        dialog.behavior.maxWidth = (MAX_WIDTH_DP * density).toInt()
        return dialog
    }

    companion object {
        private const val MAX_WIDTH_DP = 600
    }
}
