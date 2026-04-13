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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.PowerProfileAppManager
import com.celzero.bravedns.data.PowerProfileCatalog
import com.celzero.bravedns.data.PowerProfileCurrentSetupOverrideStore
import com.celzero.bravedns.data.PowerProfileManager
import com.celzero.bravedns.data.PowerProfileOwnedAppDomainRule
import com.celzero.bravedns.data.PowerProfileOwnedAppFirewallRule
import com.celzero.bravedns.data.PowerProfileOwnedAppIpRule
import com.celzero.bravedns.data.PowerProfileOwnershipStore
import com.celzero.bravedns.data.PowerProfileRuleRuntimeState
import com.celzero.bravedns.data.PowerProfileStore
import com.celzero.bravedns.databinding.FragmentPowerProfileAppsBinding
import com.celzero.bravedns.databinding.ViewPowerProfileBlocklistRowBinding
import com.celzero.bravedns.ui.activity.AppInfoActivity
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
        b.fppaInfoIcon.setOnClickListener { showInfoDialog() }
        bindState()
    }

    private fun bindState() {
        b.fppaTitle.text = getString(R.string.power_profile_apps_page_title)

        viewLifecycleOwner.lifecycleScope.launch {
            if (isMergedActiveProfile()) {
                bindMergedState()
                return@launch
            }

            val profile = requireProfile()
            val apps =
                withContext(Dispatchers.IO) {
                    PowerProfileAppManager.buildAppSummaries(requireContext(), profile)
                }
            if (!isAdded) return@launch
            renderApps(apps)
        }
    }

    private suspend fun bindMergedState() {
        val managedSources =
            withContext(Dispatchers.IO) {
                PowerProfileManager.getManagedRuleSources(requireContext())
            }
        val appEntries =
            withContext(Dispatchers.IO) {
                val packageNames =
                    buildSet {
                        managedSources.appDomains.forEach { add(it.rule.packageName) }
                        managedSources.appIps.forEach { add(it.rule.packageName) }
                        managedSources.appFirewalls.forEach { add(it.rule.packageName) }
                    }
                packageNames.map { packageName ->
                    val installedApp = PowerProfileAppManager.lookupInstalledApp(packageName)
                    MergedAppEntry(
                        packageName = packageName,
                        appName = installedApp?.appName ?: packageName,
                        domains =
                            managedSources.appDomains
                                .filter { it.rule.packageName == packageName }
                                .sortedBy { it.rule.domain },
                        ips =
                            managedSources.appIps
                                .filter { it.rule.packageName == packageName }
                                .sortedBy { "${it.rule.ipAddress}:${it.rule.port}" },
                        firewalls =
                            managedSources.appFirewalls
                                .filter { it.rule.packageName == packageName }
                                .sortedBy { it.rule.key() }
                    )
                }.sortedBy { it.appName.lowercase() }
            }
        if (!isAdded) return
        renderMergedApps(appEntries)
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

    private fun renderMergedApps(apps: List<MergedAppEntry>) {
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

        val overrides = PowerProfileCurrentSetupOverrideStore.read(requireContext())
        val inflater = LayoutInflater.from(requireContext())
        apps.forEach { app ->
            val card =
                inflater.inflate(
                    R.layout.view_active_power_profile_item,
                    b.fppaAppsContainer,
                    false
                ) as MaterialCardView
            val content = card.getChildAt(0) as LinearLayout
            val totalRuleCount = app.domains.size + app.ips.size + app.firewalls.size
            val disabledRuleCount =
                app.domains.count { managed ->
                    overrides.disabledAppDomains.any { it.key() == managed.rule.key() }
                } +
                    app.ips.count { managed ->
                        overrides.disabledAppIps.any { it.key() == managed.rule.key() }
                    } +
                    app.firewalls.count { managed ->
                        overrides.disabledAppFirewalls.any { it.key() == managed.rule.key() }
                    }

            card.findViewById<TextView>(R.id.vappi_title).text = app.appName
            card.findViewById<TextView>(R.id.vappi_desc).text = app.packageName
            card.findViewById<TextView>(R.id.vappi_meta).text =
                getString(
                    R.string.power_profile_entries_current_setup_meta,
                    formatCount(totalRuleCount - disabledRuleCount),
                    formatCount(disabledRuleCount)
                )

            app.firewalls.forEach { managedFirewall ->
                addManagedRuleRow(
                    content = content,
                    title = firewallLabel(managedFirewall.rule),
                    meta = ownerMeta(managedFirewall.ownerProfiles.map { it.name }),
                    runtimeState =
                        PowerProfileManager.getAppFirewallRuntimeState(
                            requireContext(),
                            managedFirewall.rule
                        ),
                    onToggle = { enable ->
                        PowerProfileManager.setAppFirewallEnabledInCurrentSetup(
                            requireContext(),
                            managedFirewall.rule,
                            enable
                        )
                    }
                )
            }

            app.domains.forEach { managedDomain ->
                addManagedRuleRow(
                    content = content,
                    title = managedDomain.rule.domain,
                    meta = ownerMeta(managedDomain.ownerProfiles.map { it.name }),
                    runtimeState =
                        PowerProfileManager.getAppDomainRuntimeState(
                            requireContext(),
                            managedDomain.rule
                        ),
                    onToggle = { enable ->
                        PowerProfileManager.setAppDomainEnabledInCurrentSetup(
                            requireContext(),
                            managedDomain.rule,
                            enable
                        )
                    }
                )
            }

            app.ips.forEach { managedIp ->
                addManagedRuleRow(
                    content = content,
                    title = "${managedIp.rule.ipAddress}:${managedIp.rule.port}",
                    meta = ownerMeta(managedIp.ownerProfiles.map { it.name }),
                    runtimeState =
                        PowerProfileManager.getAppIpRuntimeState(requireContext(), managedIp.rule),
                    onToggle = { enable ->
                        PowerProfileManager.setAppIpEnabledInCurrentSetup(
                            requireContext(),
                            managedIp.rule,
                            enable
                        )
                    }
                )
            }

            b.fppaAppsContainer.addView(card)
        }
    }

    private fun showInfoDialog() {
        val profile = if (isMergedActiveProfile()) null else requireProfile()
        MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
            .setTitle(R.string.power_profile_apps_page_title)
            .setMessage(
                getString(
                    R.string.power_profile_apps_page_desc,
                    profile?.resolveTitle(requireContext()) ?: getString(R.string.power_active_profiles_title)
                )
            )
            .setPositiveButton(R.string.lbl_dismiss, null)
            .show()
    }

    private fun requireProfile() =
        checkNotNull(PowerProfileCatalog.get(requireContext(), profileId)) {
            "Missing profile: $profileId"
        }

    private fun addManagedRuleRow(
        content: LinearLayout,
        title: String,
        meta: String,
        runtimeState: PowerProfileRuleRuntimeState,
        onToggle: suspend (Boolean) -> Boolean
    ) {
        val entry =
            ManagedAppRuleEntry(
                title = title,
                meta = meta,
                runtimeState = runtimeState,
                onToggle = onToggle
            )
        val rowBinding =
            ViewPowerProfileBlocklistRowBinding.inflate(
                LayoutInflater.from(requireContext()),
                content,
                false
            )
        rowBinding.vppbrTitle.text = title
        rowBinding.vppbrMeta.text = meta
        rowBinding.vppbrStatus.text = statusText(runtimeState)
        rowBinding.vppbrStatus.visibility =
            if (rowBinding.vppbrStatus.text.isNullOrBlank()) View.GONE else View.VISIBLE
        rowBinding.vppbrRoot.alpha = if (runtimeState.disabledInCurrentSetup) 0.72f else 1f
        rowBinding.vppbrRoot.setOnClickListener { showRuleDialog(entry) }
        content.addView(rowBinding.root)
    }

    private fun showRuleDialog(entry: ManagedAppRuleEntry) {
        val isDisabled = entry.runtimeState.disabledInCurrentSetup
        val ownerNames = entry.runtimeState.ownerProfiles.joinToString(", ") { it.name }
        val message =
            when {
                isDisabled ->
                    getString(R.string.power_profile_rule_reenable_dialog_message, entry.title)
                entry.runtimeState.ownerProfiles.size > 1 ->
                    getString(
                        R.string.power_profile_rule_disable_dialog_message_shared,
                        entry.title,
                        formatCount(entry.runtimeState.ownerProfiles.size),
                        ownerNames
                    )
                else ->
                    getString(R.string.power_profile_rule_disable_dialog_message, entry.title)
            }

        MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
            .setTitle(
                if (isDisabled) {
                    R.string.power_profile_rule_reenable_dialog_title
                } else {
                    R.string.power_profile_rule_disable_dialog_title
                }
            )
            .setMessage(message)
            .setPositiveButton(
                if (isDisabled) {
                    R.string.power_profile_rule_reenable_action
                } else {
                    R.string.power_profile_rule_disable_action
                }
            ) { _, _ ->
                toggleRule(entry, enable = isDisabled)
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    private fun toggleRule(entry: ManagedAppRuleEntry, enable: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            val applied = withContext(Dispatchers.IO) { entry.onToggle(enable) }
            if (!isAdded) return@launch

            if (applied) {
                showToastUiCentered(
                    requireContext(),
                    if (enable) {
                        getString(R.string.power_profile_rule_reenabled_message, entry.title)
                    } else {
                        getString(R.string.power_profile_rule_disabled_message, entry.title)
                    },
                    Toast.LENGTH_SHORT
                )
                bindState()
            } else {
                showToastUiCentered(
                    requireContext(),
                    getString(R.string.power_profile_rule_update_failed_message, entry.title),
                    Toast.LENGTH_SHORT
                )
            }
        }
    }

    private fun statusText(runtimeState: PowerProfileRuleRuntimeState): String? {
        return when {
            runtimeState.disabledInCurrentSetup ->
                getString(R.string.power_profile_blocklist_disabled_in_current_setup)
            runtimeState.ownerProfiles.size > 1 ->
                getString(
                    R.string.power_profile_rule_shared_by_profiles,
                    formatCount(runtimeState.ownerProfiles.size)
                )
            else -> null
        }
    }

    private fun ownerMeta(ownerNames: List<String>): String {
        return getString(
            R.string.power_profile_rule_entry_meta_sources,
            ownerNames.joinToString(", ")
        )
    }

    private fun firewallLabel(rule: PowerProfileOwnedAppFirewallRule): String {
        return getString(
            R.string.power_profile_app_preview_status,
            "${rule.firewallStatus}/${rule.connectionStatus}"
        )
    }

    private fun isMergedActiveProfile(): Boolean {
        return profileId == PowerProfileStore.MERGED_ACTIVE_PROFILE_ID
    }

    private fun formatCount(value: Int): String {
        return NumberFormat.getIntegerInstance().format(value)
    }

    private data class MergedAppEntry(
        val packageName: String,
        val appName: String,
        val domains: List<PowerProfileOwnershipStore.ManagedAppDomainRule>,
        val ips: List<PowerProfileOwnershipStore.ManagedAppIpRule>,
        val firewalls: List<PowerProfileOwnershipStore.ManagedAppFirewallRule>
    )

    private data class ManagedAppRuleEntry(
        val title: String,
        val meta: String,
        val runtimeState: PowerProfileRuleRuntimeState,
        val onToggle: suspend (Boolean) -> Boolean
    )
}
