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
package com.celzero.bravedns.ui.bottomsheet

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.celzero.bravedns.R
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.about.DnsRecordTypesState
import com.celzero.bravedns.ui.compose.about.DnsRecordTypesContent
import com.celzero.bravedns.ui.compose.about.DnsRecordRowState
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.ResourceRecordTypes
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchToggleBtnColors
import org.koin.android.ext.android.inject

class DnsRecordTypesBottomSheet : BaseBottomSheetDialogFragment() {
    private var _binding: DnsRecordTypesState? = null
    private val b
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()

    private val manuallySelectedTypes = mutableSetOf<String>()
    private lateinit var adapter: DnsRecordTypesAdapter
    private var isAutoMode = true

    override fun getTheme(): Int =
        Themes.getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val state = DnsRecordTypesState(inflater.context)
        _binding = state
        return ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { DnsRecordTypesContent(state) } }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }
        initView()
        setupAutoModeCard()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        // Notify parent fragment to update UI
        parentFragmentManager.setFragmentResult("dns_record_types_updated", android.os.Bundle())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        _binding?.saveMode(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        if (savedInstanceState != null) b.restoreMode(savedInstanceState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun initView() {
        // Load Auto mode state
        isAutoMode = persistentState.dnsRecordTypesAutoMode

        // Load manually selected types (only used when Auto mode is off)
        manuallySelectedTypes.clear()
        if (!isAutoMode) {
            manuallySelectedTypes.addAll(persistentState.getAllowedDnsRecordTypes())
        } else {
            // Load stored manual selection for when user switches to manual mode
            val storedSelection = persistentState.allowedDnsRecordTypesString
            if (storedSelection.isNotEmpty()) {
                manuallySelectedTypes.addAll(storedSelection.split(",").filter { it.isNotEmpty() })
            } else {
                // Default selection
                manuallySelectedTypes.addAll(setOf(
                    ResourceRecordTypes.A.name,
                    ResourceRecordTypes.AAAA.name,
                    ResourceRecordTypes.CNAME.name,
                    ResourceRecordTypes.HTTPS.name,
                    ResourceRecordTypes.SVCB.name
                ))
            }
        }

        // Get all enum entries except UNKNOWN
        val allTypes = ResourceRecordTypes.entries.filter { it != ResourceRecordTypes.UNKNOWN }

        // Sort types - selected first, then alphabetically
        val sortedTypes = allTypes.sortedWith(compareByDescending<ResourceRecordTypes> {
            if (isAutoMode) true else manuallySelectedTypes.contains(it.name)
        }.thenBy {
            it.name
        })

        adapter = DnsRecordTypesAdapter(sortedTypes, manuallySelectedTypes, isAutoMode)
        adapter.notifyDataSetChanged()

        // Update UI based on Auto mode state
        updateAutoModeUI(isAutoMode, animate = false)
    }

    private fun setupAutoModeCard() {
        // Set initial checked button based on mode
        if (isAutoMode) {
            b.drbsModeToggleGroup.check(R.id.drbs_auto_mode_btn)
            selectToggleBtnUi(b.drbsAutoModeBtn)
            unselectToggleBtnUi(b.drbsManualModeBtn)
        } else {
            b.drbsModeToggleGroup.check(R.id.drbs_manual_mode_btn)
            selectToggleBtnUi(b.drbsManualModeBtn)
            unselectToggleBtnUi(b.drbsAutoModeBtn)
        }

        // Listen for toggle changes
        b.drbsModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            when (checkedId) {
                R.id.drbs_auto_mode_btn -> {
                    // Switch to Auto mode
                    isAutoMode = true
                    selectToggleBtnUi(b.drbsAutoModeBtn)
                    unselectToggleBtnUi(b.drbsManualModeBtn)
                    persistentState.dnsRecordTypesAutoMode = true
                    updateAutoModeUI(true, animate = true)
                    adapter.updateAutoMode(true)
                    adapter.notifyDataSetChanged()
                }
                R.id.drbs_manual_mode_btn -> {
                    // Switch to Manual mode
                    isAutoMode = false
                    selectToggleBtnUi(b.drbsManualModeBtn)
                    unselectToggleBtnUi(b.drbsAutoModeBtn)
                    persistentState.dnsRecordTypesAutoMode = false
                    updateAutoModeUI(false, animate = true)
                    adapter.updateAutoMode(false)
                    adapter.sortBySelection()
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun selectToggleBtnUi(b: WgControlState) {
        b.backgroundTintList =
            ColorStateList.valueOf(fetchToggleBtnColors(requireContext(), R.color.accentGood))
        b.setTextColor(UIUtils.fetchColor(requireContext(), R.attr.homeScreenHeaderTextColor))
    }

    private fun unselectToggleBtnUi(b: WgControlState) {
        b.backgroundTintList =
            ColorStateList.valueOf(fetchToggleBtnColors(requireContext(), R.color.defaultToggleBtnBg))
        b.setTextColor(UIUtils.fetchColor(requireContext(), R.attr.primaryTextColor))
    }

    private fun updateAutoModeUI(autoMode: Boolean, animate: Boolean) {
        b.animateMode = animate
        b.drbsManualSection.alpha = if (autoMode) 0.4f else 1f
        b.drbsManualSection.isEnabled = !autoMode
    }

    inner class DnsRecordTypesAdapter(
        private var types: List<ResourceRecordTypes>,
        private val selected: MutableSet<String>,
        private var autoMode: Boolean
    ) {

        fun updateAutoMode(newAutoMode: Boolean) {
            autoMode = newAutoMode
            // Re-sort when switching modes
            if (!newAutoMode) {
                sortBySelection()
            }
        }

        fun sortBySelection() {
            // Sort: selected items first, then alphabetically
            types = types.sortedWith(compareByDescending<ResourceRecordTypes> {
                selected.contains(it.name)
            }.thenBy {
                it.name
            })
        }

        fun notifyDataSetChanged() {
            b.rows = types.map { type ->
                DnsRecordRowState(type.name, type.desc,
                    selected = autoMode || selected.contains(type.name), locked = autoMode,
                    onSelect = {
                        if (!autoMode) {
                            val isChecked = !selected.contains(type.name)
                            if (isChecked) {
                                selected.add(type.name)
                            } else {
                                selected.remove(type.name)
                            }

                            // Save manual selection
                            persistentState.setAllowedDnsRecordTypes(selected)

                            // Re-sort and refresh the list to show selected items at top
                            sortBySelection()
                            notifyDataSetChanged()
                        }
                    }
                )
            }
        }
    }
}
