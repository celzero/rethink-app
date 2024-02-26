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
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.FragmentFirewallSettingsBinding
import com.celzero.bravedns.ui.activity.CustomRulesActivity
import com.celzero.bravedns.ui.activity.UniversalFirewallSettingsActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INTENT_UID
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY

class FirewallSettingsFragment : Fragment(R.layout.fragment_firewall_settings) {
    private val b by viewBinding(FragmentFirewallSettingsBinding::bind)

    companion object {
        fun newInstance() = FirewallSettingsFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupClickListeners()
    }

    private fun setupClickListeners() {
        b.customIpDomainUniversalRl.setOnClickListener { openCustomIpScreen() }

        b.universalFirewallRl.setOnClickListener { openUniversalFirewallScreen() }

        b.appWiseIpDomainRl.setOnClickListener { openAppWiseIpScreen() }
    }

    private fun openCustomIpScreen() {
        val intent = Intent(requireContext(), CustomRulesActivity::class.java)
        // this activity is either being started in a new task or bringing to the top an
        // existing task, then it will be launched as the front door of the task.
        // This will result in the application to have that task in the proper state.
        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
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
        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
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
        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        startActivity(intent)
    }
}
