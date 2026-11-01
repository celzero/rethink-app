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
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.Themes
import org.koin.android.ext.android.inject
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle

/**
 * Settings for external automation apps:
 *  1. allowed sender packages (which apps may broadcast control intents)
 *  2. the wireguard automation master switch (gates the WG_* actions of
 *     [com.celzero.bravedns.receiver.VpnControlReceiver])
 *  3. a reference of every supported intent action and extra
 */
class TaskerAutomationBottomSheet : BaseBottomSheetDialogFragment() {
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
        return ComposeView(requireContext()).apply {
            id = R.id.tasker_sheet_root
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { TaskerContent() } }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }
    }

    @Composable
    private fun TaskerContent() {
        var packages by rememberSaveable { mutableStateOf(persistentState.appTriggerPackages) }
        var wgEnabled by rememberSaveable { mutableStateOf(persistentState.wgTaskerAutomationEnabled) }
        val setWgEnabled: (Boolean) -> Unit = { checked ->
            wgEnabled = checked
            persistentState.wgTaskerAutomationEnabled = checked
            Logger.i(LOG_TAG_UI, "wg tasker automation set to $checked")
        }
        Column(Modifier.fillMaxWidth().background(themeColor(R.attr.background))) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 6.dp)
                .size(60.dp, 3.dp).background(themeColor(R.attr.border)))
            SelectionContainer(Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.adv_taster_title), Modifier.padding(bottom = 8.dp),
                    style = utilityTextStyle(size = 18f, weight = 700), color = themeColor(R.attr.primaryLightColorText))
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text(stringResource(R.string.tasker_allowed_apps_title), Modifier.padding(top = 8.dp),
                    style = utilityTextStyle(size = 14f), fontWeight = FontWeight.Bold, color = themeColor(R.attr.primaryTextColor))
                Text(stringResource(R.string.adv_tasker_dialog_msg), Modifier.padding(top = 2.dp),
                    style = utilityTextStyle(size = 12f), color = themeColor(R.attr.primaryLightColorText))
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = packages, onValueChange = { packages = it },
                        modifier = Modifier.weight(1f).testTag("tasker_packages_et"),
                        label = { Text(stringResource(R.string.adv_tasker_dialog_edit_hint)) },
                        singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
                    Button(onClick = { savePackages(packages) }, modifier = Modifier.padding(start = 8.dp).testTag("tasker_packages_save_btn")) {
                        Text(stringResource(R.string.lbl_save))
                    }
                }
                HorizontalDivider(Modifier.padding(top = 20.dp, bottom = 4.dp), color = themeColor(R.attr.border))
                Row(Modifier.fillMaxWidth().toggleable(value = wgEnabled, role = Role.Switch, onValueChange = setWgEnabled)
                    .testTag("tasker_wg_rl").padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.tasker_wg_automation_title), style = utilityTextStyle(size = 14f),
                            color = themeColor(R.attr.primaryTextColor))
                        Text(stringResource(R.string.tasker_wg_automation_desc), Modifier.padding(top = 4.dp),
                            style = utilityTextStyle(size = 12f), color = themeColor(R.attr.primaryLightColorText))
                    }
                    Switch(checked = wgEnabled, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp).testTag("tasker_wg_switch"))
                }
                HorizontalDivider(Modifier.padding(top = 12.dp, bottom = 4.dp), color = themeColor(R.attr.border))
                Text(stringResource(R.string.tasker_intent_reference_title), Modifier.padding(top = 8.dp),
                    style = utilityTextStyle(size = 14f), fontWeight = FontWeight.Bold, color = themeColor(R.attr.primaryTextColor))
                SelectionContainer {
                    Text(stringResource(R.string.adv_tasker_dialog_instructions), Modifier.padding(top = 6.dp),
                        fontFamily = FontFamily.Monospace, style = utilityTextStyle(size = 12f), color = themeColor(R.attr.primaryLightColorText))
                }
            }
        }
    }

    private fun savePackages(raw: CharSequence?) {
        // blank input intentionally clears the allowlist (blocks all senders)
        val pkgName = normalizePackages(raw) ?: ""
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
