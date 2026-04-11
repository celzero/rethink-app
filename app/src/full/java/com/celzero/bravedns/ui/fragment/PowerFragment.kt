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
package com.celzero.bravedns.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.PowerProfileStore
import com.celzero.bravedns.data.SavedPowerProfile
import com.celzero.bravedns.databinding.FragmentPowerBinding
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.AppListActivity
import com.celzero.bravedns.ui.activity.FirewallActivity
import com.celzero.bravedns.ui.activity.NetworkLogsActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import org.koin.android.ext.android.inject

class PowerFragment : Fragment(R.layout.fragment_power) {

    private val b by viewBinding(FragmentPowerBinding::bind)
    private val appConfig by inject<AppConfig>()
    private var activeProfilesDescView: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activeProfilesDescView = b.fpActiveProfilesCard.findViewById(R.id.fp_active_profiles_desc)
        setupClickListeners()
        bindState()
    }

    override fun onResume() {
        super.onResume()
        bindState()
    }

    private fun setupClickListeners() {
        b.fpActiveProfilesCard.setOnClickListener {
            findNavController().navigate(R.id.activeProfilesFragment)
        }
        b.fpDiscoverProfilesCard.setOnClickListener {
            findNavController().navigate(R.id.discoverProfilesFragment)
        }
        b.fpSaveSetupCard.setOnClickListener { saveCurrentSetup() }

        b.fpAppsCard.setOnClickListener {
            startActivity(Intent(requireContext(), AppListActivity::class.java))
        }

        b.fpFirewallCard.setOnClickListener {
            val intent = Intent(requireContext(), FirewallActivity::class.java)
            intent.putExtra(
                Constants.VIEW_PAGER_SCREEN_TO_LOAD,
                FirewallActivity.Tabs.UNIVERSAL.screen
            )
            startActivity(intent)
        }

        b.fpLogsCard.setOnClickListener {
            startActivity(Intent(requireContext(), NetworkLogsActivity::class.java))
        }

        b.fpDashboardCard.setOnClickListener {
            findNavController().navigate(R.id.homeScreenFragment)
        }

        b.fpConfigureCard.setOnClickListener {
            findNavController().navigate(R.id.configureFragment)
        }
    }

    private fun bindState() {
        updateProtectionStatus()
        updateSavedProfilesSummary()
    }

    private fun updateProtectionStatus() {
        val braveMode = appConfig.getBraveMode()
        val statusText =
            when {
                VpnController.isAppPaused() -> getString(R.string.power_status_paused)
                VpnController.hasTunnel() -> getString(R.string.power_status_on)
                else -> getString(R.string.power_status_off)
            }

        val modeText =
            when {
                braveMode.isDnsFirewallMode() -> getString(R.string.power_mode_dns_firewall)
                braveMode.isFirewallMode() -> getString(R.string.power_mode_firewall)
                else -> getString(R.string.power_mode_dns)
            }

        b.fpStatusValue.text = statusText
        b.fpStatusDesc.text = getString(R.string.power_status_desc, modeText)
    }

    private fun updateSavedProfilesSummary() {
        val profiles = PowerProfileStore.listSavedProfiles(requireContext())
        if (profiles.isEmpty()) {
            activeProfilesDescView?.text = getString(R.string.power_active_profiles_desc)
            return
        }

        activeProfilesDescView?.text =
            getString(
                R.string.power_active_profiles_saved_summary,
                profiles.size,
                formatProfileTimestamp(profiles.first())
            )
    }

    private fun saveCurrentSetup() {
        val savedProfile = PowerProfileStore.saveCurrentSetup(requireContext(), appConfig)
        updateSavedProfilesSummary()
        showToastUiCentered(
            requireContext(),
            getString(R.string.power_saved_profile_saved_message, savedProfile.name),
            Toast.LENGTH_SHORT
        )
    }

    private fun formatProfileTimestamp(profile: SavedPowerProfile): CharSequence {
        return DateUtils.getRelativeTimeSpanString(
            profile.createdAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
    }
}
