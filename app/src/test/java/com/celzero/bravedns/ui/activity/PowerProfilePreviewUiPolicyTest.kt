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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerProfilePreviewUiPolicyTest {

    @Test
    fun readOnlyControls_coverAllEditableAppFirewallActions() {
        val controls = PowerProfilePreviewUiPolicy.readOnlyControls()

        assertTrue(controls.contains(PowerProfilePreviewUiPolicy.EditableControl.UNMETERED))
        assertTrue(controls.contains(PowerProfilePreviewUiPolicy.EditableControl.METERED))
        assertTrue(controls.contains(PowerProfilePreviewUiPolicy.EditableControl.ISOLATE))
        assertTrue(
            controls.contains(PowerProfilePreviewUiPolicy.EditableControl.BYPASS_DNS_FIREWALL)
        )
        assertTrue(
            controls.contains(PowerProfilePreviewUiPolicy.EditableControl.BYPASS_UNIVERSAL)
        )
        assertTrue(controls.contains(PowerProfilePreviewUiPolicy.EditableControl.EXCLUDE))
        assertTrue(controls.contains(PowerProfilePreviewUiPolicy.EditableControl.EXCLUDE_PROXY))
        assertTrue(controls.contains(PowerProfilePreviewUiPolicy.EditableControl.TEMP_ALLOW))
    }

    @Test
    fun readOnlyState_disablesPreviewEditing() {
        val state = PowerProfilePreviewUiPolicy.readOnlyState()

        assertFalse(state.enabled)
        assertEquals(0.5f, state.alpha)
    }

    @Test
    fun ruleCardState_onlyEnablesCardsWithEntries() {
        val enabledState = PowerProfilePreviewUiPolicy.ruleCardState(hasEntries = true)
        val disabledState = PowerProfilePreviewUiPolicy.ruleCardState(hasEntries = false)

        assertTrue(enabledState.enabled)
        assertEquals(1.0f, enabledState.alpha)
        assertFalse(disabledState.enabled)
        assertEquals(0.5f, disabledState.alpha)
    }
}
