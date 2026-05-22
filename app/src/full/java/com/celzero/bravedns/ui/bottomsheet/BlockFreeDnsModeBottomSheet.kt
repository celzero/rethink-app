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

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowInsetsControllerCompat
import com.celzero.bravedns.databinding.BottomSheetBlockFreeDnsModeBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.useTransparentNoDimBackground
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koin.android.ext.android.inject

class BlockFreeDnsModeBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBlockFreeDnsModeBinding? = null
    private val b
        get() = checkNotNull(_binding) { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()

    companion object {
        const val FRAGMENT_RESULT_KEY = "block_free_dns_mode_updated"

    }

    enum class BlockFreeDnsMode(val mode: Int) {
        FALLBACK(1),
        GLOBAL(2),
        AUTO(3);

        companion object {
            fun fromMode(mode: Int): BlockFreeDnsMode =
                entries.find { it.mode == mode } ?: FALLBACK
        }
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun getTheme(): Int =
        Themes.getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetBlockFreeDnsModeBinding.inflate(inflater, container, false)
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
        initClickListeners()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        parentFragmentManager.setFragmentResult(FRAGMENT_RESULT_KEY, Bundle())
    }

    private fun initView() {
        // If splitDns is enabled, pre-select AUTO; otherwise use the persisted mode (default FALLBACK).
        val currentMode = if (persistentState.splitDns) {
            BlockFreeDnsMode.AUTO
        } else {
            BlockFreeDnsMode.fromMode(persistentState.blockFreeDnsMode)
        }
        updateSelection(currentMode)
    }

    private fun initClickListeners() {
        b.bfdmOptionFallbackRl.setOnClickListener { selectMode(BlockFreeDnsMode.FALLBACK) }
        b.bfdmOptionGlobalRl.setOnClickListener { selectMode(BlockFreeDnsMode.GLOBAL) }
        b.bfdmOptionNoneRl.setOnClickListener { selectMode(BlockFreeDnsMode.AUTO) }
    }

    private fun selectMode(mode: BlockFreeDnsMode) {
        persistentState.blockFreeDnsMode = mode.mode
        updateSelection(mode)
    }

    private fun updateSelection(mode: BlockFreeDnsMode) {
        b.bfdmOptionFallbackRb.isChecked = (mode == BlockFreeDnsMode.FALLBACK)
        b.bfdmOptionGlobalRb.isChecked = (mode == BlockFreeDnsMode.GLOBAL)
        b.bfdmOptionNoneRb.isChecked = (mode == BlockFreeDnsMode.AUTO)
    }
}
