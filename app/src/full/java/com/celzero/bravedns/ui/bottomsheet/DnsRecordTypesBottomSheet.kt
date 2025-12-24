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
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomSheetDnsRecordTypesBinding
import com.celzero.bravedns.databinding.ItemDnsRecordTypeBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.ResourceRecordTypes
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchToggleBtnColors
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.useTransparentNoDimBackground
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import org.koin.android.ext.android.inject

class DnsRecordTypesBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetDnsRecordTypesBinding? = null
    private val b get() = _binding!!

    private val persistentState by inject<PersistentState>()

    private val manuallySelectedTypes = mutableSetOf<String>()
    private lateinit var adapter: DnsRecordTypesAdapter
    private var isAutoMode = true

    override fun getTheme(): Int =
        Themes.getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDnsRecordTypesBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.useTransparentNoDimBackground()
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
        setupAutoModeCard()
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        // Notify parent fragment to update UI
        parentFragmentManager.setFragmentResult("dns_record_types_updated", android.os.Bundle())
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
        b.drbsRecycler.layoutManager = LinearLayoutManager(requireContext())
        b.drbsRecycler.adapter = adapter

        // Update UI based on Auto mode state
        updateAutoModeUI(isAutoMode, animate = false)
    }

    private fun setupAutoModeCard() {
        // Set initial checked button based on mode
        if (isAutoMode) {
            b.drbsModeToggleGroup.check(b.drbsAutoModeBtn.id)
            selectToggleBtnUi(b.drbsAutoModeBtn)
            unselectToggleBtnUi(b.drbsManualModeBtn)
        } else {
            b.drbsModeToggleGroup.check(b.drbsManualModeBtn.id)
            selectToggleBtnUi(b.drbsManualModeBtn)
            unselectToggleBtnUi(b.drbsAutoModeBtn)
        }

        // Listen for toggle changes
        b.drbsModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            when (checkedId) {
                b.drbsAutoModeBtn.id -> {
                    // Switch to Auto mode
                    isAutoMode = true
                    selectToggleBtnUi(b.drbsAutoModeBtn)
                    unselectToggleBtnUi(b.drbsManualModeBtn)
                    persistentState.dnsRecordTypesAutoMode = true
                    updateAutoModeUI(true, animate = true)
                    adapter.updateAutoMode(true)
                    adapter.notifyDataSetChanged()
                }
                b.drbsManualModeBtn.id -> {
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

    private fun selectToggleBtnUi(b: MaterialButton) {
        b.backgroundTintList =
            ColorStateList.valueOf(fetchToggleBtnColors(requireContext(), R.color.accentGood))
        b.setTextColor(UIUtils.fetchColor(requireContext(), R.attr.homeScreenHeaderTextColor))
    }

    private fun unselectToggleBtnUi(b: MaterialButton) {
        b.backgroundTintList =
            ColorStateList.valueOf(fetchToggleBtnColors(requireContext(), R.color.defaultToggleBtnBg))
        b.setTextColor(UIUtils.fetchColor(requireContext(), R.attr.primaryTextColor))
    }

    private fun updateAutoModeUI(autoMode: Boolean, animate: Boolean) {
        if (autoMode) {
            // Auto mode is ACTIVE - dim and disable manual section
            if (animate) {
                b.drbsManualSection.animate()
                    .alpha(0.4f)
                    .setDuration(200)
                    .start()
            } else {
                b.drbsManualSection.alpha = 0.4f
            }
            b.drbsManualSection.isEnabled = false

        } else {
            // Manual mode is ACTIVE - enable manual section
            if (animate) {
                b.drbsManualSection.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()
            } else {
                b.drbsManualSection.alpha = 1f
            }
            b.drbsManualSection.isEnabled = true
        }
    }

    inner class DnsRecordTypesAdapter(
        private var types: List<ResourceRecordTypes>,
        private val selected: MutableSet<String>,
        private var autoMode: Boolean
    ) : RecyclerView.Adapter<DnsRecordTypesAdapter.ViewHolder>() {

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

        inner class ViewHolder(private val itemBinding: ItemDnsRecordTypeBinding) :
            RecyclerView.ViewHolder(itemBinding.root) {

            fun bind(type: ResourceRecordTypes, isLocked: Boolean) {
                itemBinding.itemDnsRecordTypeName.text = type.name
                itemBinding.itemDnsRecordTypeDesc.text = type.desc

                if (isLocked) {
                    // Auto mode - all items are checked and locked
                    itemBinding.itemDnsRecordTypeCheckbox.isChecked = true
                    itemBinding.itemDnsRecordTypeCheckbox.isEnabled = false
                    itemBinding.root.isClickable = false
                    itemBinding.root.alpha = 0.6f

                    // Disable ripple effect
                    itemBinding.root.background = null
                } else {
                    // Manual mode - items are selectable
                    itemBinding.itemDnsRecordTypeCheckbox.isChecked = selected.contains(type.name)
                    itemBinding.itemDnsRecordTypeCheckbox.isEnabled = true
                    itemBinding.root.isClickable = true
                    itemBinding.root.alpha = 1f

                    // Re-enable ripple effect
                    val typedValue = android.util.TypedValue()
                    itemBinding.root.context.theme.resolveAttribute(
                        android.R.attr.selectableItemBackground,
                        typedValue,
                        true
                    )
                    itemBinding.root.setBackgroundResource(typedValue.resourceId)

                    itemBinding.root.setOnClickListener {
                        val isChecked = !itemBinding.itemDnsRecordTypeCheckbox.isChecked
                        itemBinding.itemDnsRecordTypeCheckbox.isChecked = isChecked

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
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemDnsRecordTypeBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(types[position], autoMode)
        }

        override fun getItemCount(): Int = types.size
    }
}
