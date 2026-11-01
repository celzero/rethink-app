/*
 * Copyright 2022 RethinkDNS and its authors
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

import android.content.res.Configuration
import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.blocklists.*
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.celzero.bravedns.R
import com.celzero.bravedns.data.FileTag
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.fragment.RethinkBlocklistFragment
import com.celzero.bravedns.viewmodel.RethinkBlocklistViewModel
import com.celzero.bravedns.util.Themes
import org.koin.android.ext.android.inject

class RethinkPlusFilterBottomSheet : BaseBottomSheetDialogFragment() {

    private var state = BlocklistFilterState()

    private val persistentState by inject<PersistentState>()

    private var filters: RethinkBlocklistViewModel.Filters? = null

    // Store activity reference (not in constructor to allow no-arg constructor)
    private var fragmentActivity: RethinkBlocklistFragment? = null

    companion object {
        private const val ARG_FILE_TAGS = "file_tags"
        
        // Factory method to create instance with parameters
        fun newInstance(
            activity: RethinkBlocklistFragment?,
            fileTags: List<FileTag>
        ): RethinkPlusFilterBottomSheet {
            val fragment = RethinkPlusFilterBottomSheet()
            fragment.fragmentActivity = activity
            val args = Bundle()
            args.putSerializable(ARG_FILE_TAGS, ArrayList(fileTags))
            fragment.arguments = args
            return fragment
        }
    }

    override fun getTheme(): Int =
        Themes.getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        state = BlocklistFilterState()
        return ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { BlocklistFilterScreen(state) } }
        }
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }
        initView()
        initClickListeners()
    }

    private fun initView() {
        filters = fragmentActivity?.filterObserver()?.value
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val fileTags = (arguments?.getSerializable(ARG_FILE_TAGS) as? ArrayList<FileTag>) ?: emptyList()
        makeChipSubGroup(fileTags)
    }

    private fun makeChipSubGroup(ft: List<FileTag>) {
        state.chips.clear()
        ft.distinctBy { it.subg }.forEach {
            if (it.subg.isBlank()) return@forEach
            val checked = filters?.subGroups?.contains(it.subg) == true
            state.chips.add(BlocklistFilterChip(it.subg, checked) { selected ->
                if (selected) applySubgroupFilter(it.subg) else removeSubgroupFilter(it.subg)
            })
        }
    }

    private fun initClickListeners() {
        state.apply = apply@{
            this.dismiss()
            if (filters == null) return@apply

            fragmentActivity?.filterObserver()?.postValue(filters)
        }

        state.clear = {
            fragmentActivity?.filterObserver()?.postValue(RethinkBlocklistViewModel.Filters())
            this.dismiss()
        }
    }





    private fun applySubgroupFilter(tag: String) {
        if (filters == null) {
            filters = RethinkBlocklistViewModel.Filters()
        }
        filters?.subGroups?.add(tag)
    }

    private fun removeSubgroupFilter(tag: String) {
        filters?.subGroups?.remove(tag)
    }

}
