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
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomSheetTaskerAutomationBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.Themes
import org.koin.android.ext.android.inject

/**
 * Settings for external automation apps:
 *  1. allowed sender packages (which apps may broadcast control intents)
 *  2. the wireguard automation master switch (gates the WG_* actions of
 *     [com.celzero.bravedns.receiver.VpnControlReceiver])
 *  3. a reference of every supported intent action and extra
 */
class TaskerAutomationBottomSheet : BaseBottomSheetDialogFragment() {
    private var _binding: BottomSheetTaskerAutomationBinding? = null

    private val b
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()

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
        _binding = BottomSheetTaskerAutomationBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }
        init()
    }

    private fun init() {
        b.taskerPackagesEt.setText(persistentState.appTriggerPackages)
        b.taskerPackagesSaveBtn.setOnClickListener { savePackages() }

        b.taskerWgSwitch.setOnCheckedChangeListener(null)
        b.taskerWgSwitch.isChecked = persistentState.wgTaskerAutomationEnabled
        b.taskerWgSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.wgTaskerAutomationEnabled = isChecked
            Logger.i(LOG_TAG_UI, "wg tasker automation set to $isChecked")
        }
        b.taskerWgRl.setOnClickListener { b.taskerWgSwitch.performClick() }
    }

    private fun savePackages() {
        // blank input intentionally clears the allowlist (blocks all senders)
        val pkgName = normalizePackages(b.taskerPackagesEt.text) ?: ""
        persistentState.appTriggerPackages = pkgName
        Logger.i(LOG_TAG_UI, "app trigger packages set to $pkgName")
        dismiss()
    }

    companion object {
        const val TAG = "TaskerAutomationBottomSheet"

        /**
         * Returns the trimmed package list to persist, or null when the input is
         * blank / whitespace only (which the caller persists as an empty allowlist).
         */
        fun normalizePackages(raw: CharSequence?): String? {
            return raw?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}
