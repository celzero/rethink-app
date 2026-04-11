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
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.ActivePowerProfile
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.PowerProfileStore
import com.celzero.bravedns.data.SavedPowerProfile
import com.celzero.bravedns.databinding.FragmentActiveProfilesBinding
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.AppListActivity
import com.celzero.bravedns.ui.activity.FirewallActivity
import com.celzero.bravedns.ui.activity.NetworkLogsActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import org.koin.android.ext.android.inject

class ActiveProfilesFragment : Fragment(R.layout.fragment_active_profiles) {

    private val b by viewBinding(FragmentActiveProfilesBinding::bind)
    private val appConfig by inject<AppConfig>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindState()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        bindState()
    }

    private fun bindState() {
        val braveMode = appConfig.getBraveMode()
        val activeProfiles = PowerProfileStore.listActiveProfiles(requireContext())
        val savedProfiles = PowerProfileStore.listSavedProfiles(requireContext())

        if (activeProfiles.isNotEmpty()) {
            val latestProfile = activeProfiles.first()
            b.fapEmptyTitle.text =
                getString(R.string.power_active_profiles_live_title, activeProfiles.size)
            b.fapEmptyDesc.text =
                getString(
                    R.string.power_active_profiles_live_desc,
                    latestProfile.name,
                    formatActiveTimestamp(latestProfile),
                    latestProfile.sourceSummary
                )
        } else if (savedProfiles.isEmpty()) {
            b.fapEmptyTitle.text = getString(R.string.power_active_profiles_empty_title)
            b.fapEmptyDesc.text = getString(R.string.power_active_profiles_empty_desc)
        } else {
            val latestProfile = savedProfiles.first()
            b.fapEmptyTitle.text =
                getString(R.string.power_active_profiles_saved_title, savedProfiles.size)
            b.fapEmptyDesc.text =
                getString(
                    R.string.power_active_profiles_saved_desc,
                    latestProfile.name,
                    formatProfileTimestamp(latestProfile),
                    latestProfile.engineMode
                )
        }

        b.fapSetupStatusValue.text =
            when {
                VpnController.isAppPaused() -> getString(R.string.power_status_paused)
                VpnController.hasTunnel() -> getString(R.string.power_status_on)
                else -> getString(R.string.power_status_off)
            }

        b.fapSetupModeValue.text =
            when {
                braveMode.isDnsFirewallMode() -> getString(R.string.power_mode_dns_firewall)
                braveMode.isFirewallMode() -> getString(R.string.power_mode_firewall)
                else -> getString(R.string.power_mode_dns)
            }
    }

    private fun setupClickListeners() {
        b.fapBackCard.setOnClickListener { findNavController().navigateUp() }

        b.fapOpenFirewallCard.setOnClickListener {
            val intent = Intent(requireContext(), FirewallActivity::class.java)
            intent.putExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, FirewallActivity.Tabs.UNIVERSAL.screen)
            startActivity(intent)
        }

        b.fapOpenAppsCard.setOnClickListener {
            startActivity(Intent(requireContext(), AppListActivity::class.java))
        }

        b.fapOpenLogsCard.setOnClickListener {
            startActivity(Intent(requireContext(), NetworkLogsActivity::class.java))
        }

        b.fapSaveCurrentSetupCard.setOnClickListener {
            saveCurrentSetup()
        }
    }

    private fun saveCurrentSetup() {
        val savedProfile = PowerProfileStore.saveCurrentSetup(requireContext(), appConfig)
        bindState()
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

    private fun formatActiveTimestamp(profile: ActivePowerProfile): CharSequence {
        return DateUtils.getRelativeTimeSpanString(
            profile.activatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
    }
}
