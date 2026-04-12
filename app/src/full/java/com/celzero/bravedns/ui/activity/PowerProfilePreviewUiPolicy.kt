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
package com.celzero.bravedns.ui.activity

import android.view.View
import android.widget.CompoundButton
import com.celzero.bravedns.databinding.ActivityAppDetailsBinding

object PowerProfilePreviewUiPolicy {
    const val DISABLED_ALPHA = 0.5f
    const val ENABLED_ALPHA = 1.0f

    enum class EditableControl {
        UNMETERED,
        METERED,
        ISOLATE,
        BYPASS_DNS_FIREWALL,
        BYPASS_UNIVERSAL,
        EXCLUDE,
        EXCLUDE_PROXY,
        TEMP_ALLOW
    }

    data class ControlState(val enabled: Boolean, val alpha: Float)

    fun readOnlyControls(): Set<EditableControl> {
        return setOf(
            EditableControl.UNMETERED,
            EditableControl.METERED,
            EditableControl.ISOLATE,
            EditableControl.BYPASS_DNS_FIREWALL,
            EditableControl.BYPASS_UNIVERSAL,
            EditableControl.EXCLUDE,
            EditableControl.EXCLUDE_PROXY,
            EditableControl.TEMP_ALLOW
        )
    }

    fun readOnlyState(): ControlState {
        return ControlState(enabled = false, alpha = DISABLED_ALPHA)
    }

    fun ruleCardState(hasEntries: Boolean): ControlState {
        return ControlState(
            enabled = hasEntries,
            alpha = if (hasEntries) ENABLED_ALPHA else DISABLED_ALPHA
        )
    }

    fun applyReadOnlyFirewallControls(binding: ActivityAppDetailsBinding) {
        listOf(
            binding.aadAppSettingsBlockWifi,
            binding.aadAppSettingsBlockMd,
            binding.aadAppSettingsIsolate,
            binding.aadAppSettingsBypassDnsFirewall,
            binding.aadAppSettingsBypassUniv,
            binding.aadAppSettingsExclude
        ).forEach { disableView(it) }

        disableView(binding.excludeProxyRl)
        disableView(binding.excludeProxySwitch)
        disableView(binding.tempAllowRl)
        disableView(binding.tempAllowSwitch)
    }

    fun applyRulePreviewState(
        binding: ActivityAppDetailsBinding,
        hasIpRules: Boolean,
        hasDomainRules: Boolean
    ) {
        applyEntryCardState(binding.aadIpBlockCard, hasIpRules)
        applyEntryCardState(binding.aadDomainBlockCard, hasDomainRules)
    }

    private fun applyEntryCardState(view: View, enabled: Boolean) {
        val state = ruleCardState(enabled)
        view.isEnabled = state.enabled
        view.isClickable = state.enabled
        view.isFocusable = state.enabled
        view.alpha = state.alpha
    }

    private fun disableView(view: View) {
        val state = readOnlyState()
        view.setOnClickListener(null)
        if (view is CompoundButton) {
            view.setOnCheckedChangeListener(null)
        }
        view.isEnabled = state.enabled
        view.isClickable = state.enabled
        view.isFocusable = state.enabled
        view.alpha = state.alpha
    }
}
