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
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.data.FileTag
import com.celzero.bravedns.databinding.BottomSheetRethinkPlusFilterBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.fragment.RethinkBlocklistFragment
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.useTransparentNoDimBackground
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import org.koin.android.ext.android.inject

class RethinkPlusFilterBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetRethinkPlusFilterBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b: BottomSheetRethinkPlusFilterBinding
        get() = _binding ?: throw IllegalStateException("Binding is only valid between onCreateView and onDestroyView")

    private val persistentState by inject<PersistentState>()

    private var filters: RethinkBlocklistFragment.Filters? = null

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
        Themes.getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetRethinkPlusFilterBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.useTransparentNoDimBackground()
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            if (isAtleastQ()) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.isAppearanceLightNavigationBars = false
                window.isNavigationBarContrastEnforced = false
            }
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
        b.rpfFilterChipSubGroup.removeAllViews()
        ft.distinctBy { it.subg }
            .forEach {
                if (it.subg.isBlank()) return@forEach

                val checked = filters?.subGroups?.contains(it.subg) == true
                b.rpfFilterChipSubGroup.addView(remakeChipSubgroup(it.subg, checked))
            }
    }

    private fun initClickListeners() {
        b.rpfApply.setOnClickListener {
            this.dismiss()
            if (filters == null) return@setOnClickListener

            fragmentActivity?.filterObserver()?.postValue(filters)
        }

        b.rpfClear.setOnClickListener {
            fragmentActivity?.filterObserver()?.postValue(RethinkBlocklistFragment.Filters())
            this.dismiss()
        }
    }

    private fun remakeChipSubgroup(label: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
        chip.tag = label
        chip.text = label
        chip.isChecked = checked

        chip.setOnCheckedChangeListener { button: CompoundButton, isSelected: Boolean ->
            if (isSelected) {
                applySubgroupFilter(button.tag as String)
                colorUpChipIcon(chip)
            } else {
                removeSubgroupFilter(button.tag as String)
            }
        }

        return chip
    }

    private fun colorUpChipIcon(chip: Chip) {
        val colorFilter =
            PorterDuffColorFilter(
                ContextCompat.getColor(requireContext(), R.color.primaryText),
                PorterDuff.Mode.SRC_IN
            )
        chip.checkedIcon?.colorFilter = colorFilter
        chip.chipIcon?.colorFilter = colorFilter
    }

    private fun applySubgroupFilter(tag: String) {
        if (filters == null) {
            filters = RethinkBlocklistFragment.Filters()
        }
        filters?.subGroups?.add(tag)
    }

    private fun removeSubgroupFilter(tag: String) {
        filters?.subGroups?.remove(tag)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
