/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.ui.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.PowerProfileAppManager
import com.celzero.bravedns.data.PowerProfileCatalog
import com.celzero.bravedns.databinding.FragmentPowerProfileAppsBinding
import com.celzero.bravedns.ui.activity.AppInfoActivity
import com.celzero.bravedns.util.Utilities
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PowerProfileAppsFragment : Fragment(R.layout.fragment_power_profile_apps) {
    private val b by viewBinding(FragmentPowerProfileAppsBinding::bind)
    private var profileId: String = ""

    companion object {
        const val ARG_PROFILE_ID = "power.profile_id"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        profileId = arguments?.getString(ARG_PROFILE_ID).orEmpty()
        b.fppaBackCard.setOnClickListener { findNavController().navigateUp() }
        bindState()
    }

    private fun bindState() {
        val profile = requireProfile()
        b.fppaTitle.text = getString(R.string.power_profile_apps_page_title)
        b.fppaDesc.text =
            getString(R.string.power_profile_apps_page_desc, profile.resolveTitle(requireContext()))

        viewLifecycleOwner.lifecycleScope.launch {
            val apps =
                withContext(Dispatchers.IO) {
                    PowerProfileAppManager.buildAppSummaries(requireContext(), profile)
                }
            if (!isAdded) return@launch
            renderApps(apps)
        }
    }

    private fun renderApps(apps: List<com.celzero.bravedns.data.PowerProfileAppSummary>) {
        b.fppaAppsContainer.removeAllViews()
        if (apps.isEmpty()) {
            val emptyView =
                LayoutInflater.from(requireContext()).inflate(
                    R.layout.view_active_power_profile_item,
                    b.fppaAppsContainer,
                    false
                )
            emptyView.findViewById<TextView>(R.id.vappi_title).text =
                getString(R.string.power_profile_entries_empty_title)
            emptyView.findViewById<TextView>(R.id.vappi_desc).text =
                getString(R.string.power_profile_apps_entries_empty_desc)
            emptyView.findViewById<TextView>(R.id.vappi_meta).text =
                getString(R.string.power_profile_entries_empty_meta)
            b.fppaAppsContainer.addView(emptyView)
            return
        }

        val inflater = LayoutInflater.from(requireContext())
        apps.forEach { app ->
            val card =
                inflater.inflate(
                    R.layout.view_power_profile_app_item,
                    b.fppaAppsContainer,
                    false
                )
            card.findViewById<ImageView>(R.id.vppai_icon).setImageDrawable(
                Utilities.getIcon(requireContext(), app.packageName, app.appName)
            )
            card.findViewById<TextView>(R.id.vppai_title).text = app.appName
            card.findViewById<TextView>(R.id.vppai_desc).text = app.packageName
            card.findViewById<TextView>(R.id.vppai_meta).text =
                getString(
                    R.string.power_profile_apps_item_meta,
                    formatCount(app.ipRuleCount),
                    formatCount(app.domainRuleCount)
                )
            card.setOnClickListener {
                startActivity(
                    Intent(requireContext(), AppInfoActivity::class.java).apply {
                        putExtra(AppInfoActivity.INTENT_PREVIEW_PROFILE_ID, profileId)
                        putExtra(AppInfoActivity.INTENT_PREVIEW_APP_PACKAGE, app.packageName)
                    }
                )
            }
            b.fppaAppsContainer.addView(card)
        }
    }

    private fun requireProfile() =
        checkNotNull(PowerProfileCatalog.get(requireContext(), profileId)) {
            "Missing profile: $profileId"
        }

    private fun formatCount(value: Int): String {
        return NumberFormat.getIntegerInstance().format(value)
    }
}
