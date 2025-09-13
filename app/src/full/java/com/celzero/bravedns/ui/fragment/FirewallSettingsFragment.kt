/*
 * Copyright 2020 RethinkDNS and its authors
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

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.database.RefreshDatabase
import com.celzero.bravedns.databinding.FragmentFirewallSettingsBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.activity.CustomRulesActivity
import com.celzero.bravedns.ui.activity.UniversalFirewallSettingsActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INTENT_UID
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class FirewallSettingsFragment : Fragment(R.layout.fragment_firewall_settings), KoinComponent {
    private val b by viewBinding(FragmentFirewallSettingsBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val rdb by inject<RefreshDatabase>()

    companion object {
        fun newInstance() = FirewallSettingsFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.settingsHeading.text = getString(R.string.title_settings).lowercase()
        setupClickListeners()
    }

    private fun setupClickListeners() {
        b.customIpDomainUniversalRl.setOnClickListener { openCustomIpScreen() }

        b.universalFirewallRl.setOnClickListener { openUniversalFirewallScreen() }

        b.appWiseIpDomainRl.setOnClickListener { openAppWiseIpScreen() }

        b.tombstoneAppRl.setOnClickListener {
            b.tombstoneAppSwitch.isChecked = !b.tombstoneAppSwitch.isChecked
        }

        b.tombstoneAppSwitch.setOnCheckedChangeListener { _, isChecked ->
            persistentState.tombstoneApps = isChecked
            io { rdb.refresh(RefreshDatabase.ACTION_REFRESH_FORCE) }
        }
    }

    private fun openCustomIpScreen() {
        val intent = Intent(requireContext(), CustomRulesActivity::class.java)
        intent.putExtra(
            Constants.VIEW_PAGER_SCREEN_TO_LOAD,
            CustomRulesActivity.Tabs.IP_RULES.screen
        )
        intent.putExtra(
            CustomRulesActivity.INTENT_RULES,
            CustomRulesActivity.RULES.APP_SPECIFIC_RULES.type
        )
        intent.putExtra(INTENT_UID, UID_EVERYBODY)
        startActivity(intent)
    }

    private fun openAppWiseIpScreen() {
        val intent = Intent(requireContext(), CustomRulesActivity::class.java)
        intent.putExtra(
            Constants.VIEW_PAGER_SCREEN_TO_LOAD,
            CustomRulesActivity.Tabs.IP_RULES.screen
        )
        intent.putExtra(CustomRulesActivity.INTENT_RULES, CustomRulesActivity.RULES.ALL_RULES.type)
        intent.putExtra(INTENT_UID, UID_EVERYBODY)
        startActivity(intent)
    }

    private fun openUniversalFirewallScreen() {
        val intent = Intent(requireContext(), UniversalFirewallSettingsActivity::class.java)
        startActivity(intent)
    }

    private fun io(f: suspend () -> Unit) = lifecycleScope.launch(Dispatchers.IO) { f() }

}
