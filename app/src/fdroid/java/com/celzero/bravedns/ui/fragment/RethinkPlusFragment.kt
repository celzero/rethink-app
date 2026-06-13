/*
 * Copyright 2024 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.fragment

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.FragmentRethinkPlusBinding
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords

/**
 * F-Droid flavour, Rethink+ is not yet available on F-Droid.
 *
 * Shows a "Coming Soon" placeholder so the screen is navigable but communicates
 * clearly that billing is not supported in this build.
 */
class RethinkPlusFragment : Fragment(R.layout.fragment_rethink_plus) {
    private val b by viewBinding(FragmentRethinkPlusBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        showComingSoon()
    }

    private fun showComingSoon() {
        // show only the static "Coming Soon" banner.
        b.pendingPurchaseLayout.isVisible = false

        // Re-use the not-available layout to surface the coming-soon message.
        b.notAvailableLayout.isVisible = true
        val text = getString(R.string.coming_soon_toast)
        b.titleUnavailable.text = text.capitalizeWords()
    }
}
