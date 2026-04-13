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

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.ActivePowerProfile
import com.celzero.bravedns.data.ImportedPowerProfileStore
import com.celzero.bravedns.data.PowerProfileActivationAction
import com.celzero.bravedns.data.PowerProfileActivationPolicy
import com.celzero.bravedns.data.PowerProfileLocalCatalogManager
import com.celzero.bravedns.data.PowerProfileArtifacts
import com.celzero.bravedns.data.PowerProfileBlocklistPreviewManager
import com.celzero.bravedns.data.PowerProfileCatalog
import com.celzero.bravedns.data.PowerProfileDefinition
import com.celzero.bravedns.data.PowerProfileManager
import com.celzero.bravedns.data.PowerProfileStore
import com.celzero.bravedns.databinding.FragmentPowerProfileDetailBinding
import com.celzero.bravedns.databinding.ViewPowerProfileSectionCardBinding
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.download.DownloadConstants.Companion.DOWNLOAD_TAG
import com.celzero.bravedns.download.DownloadConstants.Companion.FILE_TAG
import com.celzero.bravedns.customdownloader.LocalBlocklistCoordinator.Companion.CUSTOM_DOWNLOAD
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.NumberFormat
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class PowerProfileDetailFragment : Fragment(R.layout.fragment_power_profile_detail) {
    private enum class ActionButtonMode {
        ENABLE,
        DISABLE,
        NEUTRAL
    }

    private val b by viewBinding(FragmentPowerProfileDetailBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val appDownloadManager by inject<AppDownloadManager>()
    private var profileId: String = ""
    private var pendingExportProfileId: String? = null
    private var pendingLocalCatalogActivationProfileId: String? = null
    private var pendingProtectionStartProfileId: String? = null
    private var localCatalogDownloadInProgress = false
    private var localCatalogBootstrapAttempted = false
    private var bindVersion = 0
    private lateinit var startForResult: ActivityResultLauncher<android.content.Intent>
    private lateinit var notificationPermissionResult: ActivityResultLauncher<String>
    private val exportActivityResult =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            handleExportResult(uri)
        }

    companion object {
        const val ARG_PROFILE_ID = "power.profile_id"
        private const val UNKNOWN_COUNT_PLACEHOLDER = "-"
        private const val PROFILE_DESC_WORD_LIMIT = 30
        private const val PROTECTION_WAIT_ATTEMPTS = 20
        private const val PROTECTION_WAIT_INTERVAL_MS = 250L
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        profileId = arguments?.getString(ARG_PROFILE_ID).orEmpty()
        registerForActivityResult()
        observeLocalCatalogDownloads()
        setupClickListeners()
        updateExportButtonStyle()
        bindState()
        maybeBootstrapLocalCatalog(requireProfile())
    }

    override fun onResume() {
        super.onResume()
        bindState()
        maybeBootstrapLocalCatalog(requireProfile())
    }

    private fun setupClickListeners() {
        b.fppdInfoIcon.setOnClickListener { showInfoDialog() }
        b.fppdActionBtn.setOnClickListener { handleAction() }
        b.fppdExportBtn.setOnClickListener { exportProfile() }
        b.fppdDomainCard.root.setOnClickListener { openEntriesSection(PowerProfileEntriesFragment.SECTION_DOMAINS) }
        b.fppdIpCard.root.setOnClickListener { openEntriesSection(PowerProfileEntriesFragment.SECTION_IPS) }
        b.fppdAppsCard.root.setOnClickListener { openAppsSection() }
        b.fppdRethinkCard.root.setOnClickListener { openEntriesSection(PowerProfileEntriesFragment.SECTION_RETHINK) }
    }

    private fun bindState() {
        val profile = requireProfile()
        val activeProfile = PowerProfileStore.getActiveProfile(requireContext(), profile.id)
        val currentBindVersion = ++bindVersion

        b.fppdTitle.text = profile.resolveTitle(requireContext())
        b.fppdDesc.text = profile.resolveDescription(requireContext(), PROFILE_DESC_WORD_LIMIT)
        b.fppdDesc.visibility =
            if (b.fppdDesc.text.isNullOrBlank()) View.GONE else View.VISIBLE
        b.fppdSourceProvider.visibility = View.GONE
        b.fppdSourceProvider.text =
            getString(
                R.string.power_profile_detail_provider,
                profile.sourceProvider ?: getString(R.string.app_name)
            )
        b.fppdSourceSummary.text =
            getString(
                R.string.power_profile_detail_source_summary,
                profile.sourceSummary ?: profile.resolveMeta(requireContext())
            )
        b.fppdSourceTokens.text =
            getString(
                R.string.power_profile_detail_source_tokens,
                if (profile.sourceTokens.isEmpty()) "-" else profile.sourceTokens.joinToString(", ")
            )
        b.fppdExportBtn.isEnabled = profile.readyForActivation
        updateExportButtonStyle()
        bindSectionCards(profile, currentBindVersion)
        bindLoadingState()

        when {
            activeProfile != null -> bindActiveState(activeProfile)
            profile.readyForActivation -> bindReadyState(profile, currentBindVersion)
            else -> bindComingSoonState()
        }
    }

    private fun bindLoadingState() {
        b.fppdStatusCard.visibility = View.VISIBLE
        b.fppdStatusTitle.visibility = View.VISIBLE
        b.fppdStatusTitle.text = getString(R.string.power_profile_detail_status_loading)
        b.fppdStatusDesc.text = getString(R.string.power_profile_detail_loading_desc)
        b.fppdStatusMeta.text = getString(R.string.power_profile_detail_loading_meta)
        updateActionButton(
            enabled = false,
            textRes = R.string.power_profile_detail_loading_action,
            mode = ActionButtonMode.NEUTRAL
        )
    }

    private fun showInfoDialog() {
        val profile = requireProfile()
        val message =
            listOfNotNull(
                profile.resolveDescription(requireContext(), PROFILE_DESC_WORD_LIMIT),
                getString(
                    R.string.power_profile_detail_provider,
                    profile.sourceProvider ?: getString(R.string.app_name)
                ),
                getString(
                    R.string.power_profile_detail_source_summary,
                    profile.sourceSummary ?: profile.resolveMeta(requireContext())
                ),
                getString(
                    R.string.power_profile_detail_source_tokens,
                    if (profile.sourceTokens.isEmpty()) "-" else profile.sourceTokens.joinToString(", ")
                )
            ).joinToString("\n\n")

        MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
            .setTitle(profile.resolveTitle(requireContext()))
            .setMessage(message)
            .setPositiveButton(R.string.lbl_dismiss, null)
            .show()
    }

    private fun bindActiveState(activeProfile: ActivePowerProfile) {
        b.fppdStatusCard.visibility = View.VISIBLE
        b.fppdStatusTitle.visibility = View.VISIBLE
        b.fppdStatusTitle.text = getString(R.string.power_profile_detail_status_active)
        b.fppdStatusDesc.text =
            getString(
                R.string.power_profile_detail_active_desc,
                formatActiveTimestamp(activeProfile),
                activeProfile.importedRuleCount,
                activeProfile.artifactRuleCount
            )
        b.fppdStatusMeta.text =
            getString(
                R.string.power_profile_detail_rule_meta,
                activeProfile.artifactRuleCount,
                activeProfile.supportedRuleKind.ifBlank { "-" }
            )
        if (localCatalogDownloadInProgress) {
            updateActionButton(
                enabled = false,
                textRes = R.string.power_profile_download_in_progress_action,
                mode = ActionButtonMode.NEUTRAL
            )
        } else {
            updateActionButton(
                enabled = true,
                textRes = R.string.power_profile_disable_action,
                mode = ActionButtonMode.DISABLE
            )
        }
    }

    private fun bindReadyState(profile: PowerProfileDefinition, currentBindVersion: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            val artifact =
                withContext(Dispatchers.IO) {
                    PowerProfileArtifacts.loadArtifact(requireContext(), profile)
                }
            val localCatalogReady =
                withContext(Dispatchers.IO) {
                    PowerProfileLocalCatalogManager.hasCatalog(profile.localBlocklistTagIds)
                }
            val needsProtection =
                profile.localBlocklistTagIds.isNotEmpty() && !VpnController.hasTunnel()
            if (!isAdded || currentBindVersion != bindVersion) return@launch
            if (profile.localBlocklistTagIds.isNotEmpty() && !localCatalogReady) {
                b.fppdStatusCard.visibility = View.VISIBLE
                b.fppdStatusTitle.visibility = View.VISIBLE
                b.fppdStatusTitle.text = getString(R.string.power_profile_detail_status_preparing)
                b.fppdStatusDesc.text =
                    if (localCatalogDownloadInProgress) {
                        getString(
                            R.string.power_profile_local_catalog_preparing_desc,
                            formatCount(profile.localBlocklistTagIds.size)
                        )
                    } else {
                        getString(
                            R.string.power_profile_local_catalog_missing_desc,
                            formatCount(profile.localBlocklistTagIds.size)
                        )
                    }
                b.fppdStatusMeta.text =
                    getString(R.string.power_profile_local_catalog_hint)
                updateActionButton(
                    enabled = false,
                    textRes =
                        if (localCatalogDownloadInProgress) {
                            R.string.power_profile_download_in_progress_action
                        } else {
                            R.string.power_profile_enable_action
                        },
                    mode = ActionButtonMode.NEUTRAL
                )
            } else if (needsProtection) {
                b.fppdStatusCard.visibility = View.VISIBLE
                b.fppdStatusTitle.visibility = View.VISIBLE
                b.fppdStatusTitle.text = getString(R.string.power_profile_detail_status_needs_protection)
                b.fppdStatusDesc.text =
                    getString(R.string.power_profile_local_catalog_needs_protection_desc)
                b.fppdStatusMeta.text =
                    getString(R.string.power_profile_local_catalog_hint)
                updateActionButton(
                    enabled = true,
                    textRes = R.string.power_profile_enable_action,
                    mode = ActionButtonMode.ENABLE
                )
            } else {
                b.fppdStatusCard.visibility = View.GONE
                updateActionButton(
                    enabled = true,
                    textRes = R.string.power_profile_enable_action,
                    mode = ActionButtonMode.ENABLE
                )
            }
        }
    }

    private fun bindSectionCards(profile: PowerProfileDefinition, currentBindVersion: Int) {
        bindSectionCard(
            card = b.fppdDomainCard,
            title = getString(R.string.power_profile_domain_blocklist_title),
            iconRes = R.drawable.ic_dns_cache,
            count = UNKNOWN_COUNT_PLACEHOLDER,
            meta = getString(R.string.power_profile_cards_loading),
            enabled = false
        )
        bindSectionCard(
            card = b.fppdIpCard,
            title = getString(R.string.power_profile_ip_blocklist_title),
            iconRes = R.drawable.ic_firewall_shield,
            count = UNKNOWN_COUNT_PLACEHOLDER,
            meta = getString(R.string.power_profile_cards_loading),
            enabled = false
        )
        bindSectionCard(
            card = b.fppdAppsCard,
            title = getString(R.string.power_profile_apps_blocklist_title),
            iconRes = R.drawable.ic_app_info_accent,
            count = UNKNOWN_COUNT_PLACEHOLDER,
            meta = getString(R.string.power_profile_cards_loading),
            enabled = false
        )
        bindSectionCard(
            card = b.fppdRethinkCard,
            title = getString(R.string.power_profile_rethink_blocklists_title),
            iconRes = R.drawable.ic_dns_firewall,
            count =
                if (profile.localBlocklistTagIds.isEmpty()) {
                    UNKNOWN_COUNT_PLACEHOLDER
                } else {
                    formatCount(profile.localBlocklistTagIds.size)
                },
            meta = getString(R.string.power_profile_cards_loading),
            enabled = false
        )

        viewLifecycleOwner.lifecycleScope.launch {
            val artifact =
                withContext(Dispatchers.IO) {
                    PowerProfileArtifacts.loadArtifact(requireContext(), profile)
                }
            val rethinkGroups =
                withContext(Dispatchers.IO) {
                    PowerProfileBlocklistPreviewManager.loadLocalGroups(
                        requireContext(),
                        profile.localBlocklistTagIds
                    )
                }
            if (!isAdded || currentBindVersion != bindVersion) return@launch

            bindSectionCard(
                card = b.fppdDomainCard,
                title = getString(R.string.power_profile_domain_blocklist_title),
                iconRes = R.drawable.ic_dns_cache,
                count = formatCount(artifact?.domains?.size ?: 0),
                meta =
                    getString(
                        R.string.power_profile_domain_card_meta,
                        formatCount(artifact?.domains?.size ?: 0)
                    ),
                enabled = (artifact?.domains?.isNotEmpty() == true)
            )
            bindSectionCard(
                card = b.fppdIpCard,
                title = getString(R.string.power_profile_ip_blocklist_title),
                iconRes = R.drawable.ic_firewall_shield,
                count = formatCount(artifact?.ips?.size ?: 0),
                meta =
                    getString(
                        R.string.power_profile_ip_card_meta,
                        formatCount(artifact?.ips?.size ?: 0)
                    ),
                enabled = (artifact?.ips?.isNotEmpty() == true)
            )
            val appCount = artifact?.apps?.size ?: 0
            val appRuleCount = artifact?.apps?.sumOf { it.supportedRuleCount() } ?: 0
            bindSectionCard(
                card = b.fppdAppsCard,
                title = getString(R.string.power_profile_apps_blocklist_title),
                iconRes = R.drawable.ic_app_info_accent,
                count = formatCount(appCount),
                meta =
                    if (appCount == 0) {
                        getString(R.string.power_profile_apps_card_meta_empty)
                    } else {
                        getString(
                            R.string.power_profile_apps_card_meta,
                            formatCount(appCount),
                            formatCount(appRuleCount)
                        )
                    },
                enabled = appCount > 0
            )
            bindSectionCard(
                card = b.fppdRethinkCard,
                title = getString(R.string.power_profile_rethink_blocklists_title),
                iconRes = R.drawable.ic_dns_firewall,
                count = formatCount(profile.localBlocklistTagIds.size),
                meta = rethinkCardMeta(profile.localBlocklistTagIds.size, rethinkGroups.size),
                enabled = profile.localBlocklistTagIds.isNotEmpty()
            )
        }
    }

    private fun rethinkCardMeta(listCount: Int, groupCount: Int): String {
        return if (listCount > 0 && groupCount == 0) {
            getString(R.string.power_profile_rethink_card_meta_fallback, formatCount(listCount))
        } else {
            getString(
                R.string.power_profile_rethink_card_meta,
                formatCount(listCount),
                formatCount(groupCount)
            )
        }
    }

    private fun openAppsSection() {
        findNavController().navigate(
            R.id.powerProfileAppsFragment,
            Bundle().apply {
                putString(PowerProfileAppsFragment.ARG_PROFILE_ID, profileId)
            }
        )
    }

    private fun bindSectionCard(
        card: ViewPowerProfileSectionCardBinding,
        title: String,
        iconRes: Int,
        count: String,
        meta: String,
        enabled: Boolean
    ) {
        card.vppscTitle.apply {
            text = title
            setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
        }
        card.vppscCount.text = count
        card.vppscMeta.text = meta
        card.root.isEnabled = enabled
        card.root.isClickable = enabled
        card.root.isFocusable = enabled
        card.root.alpha = if (enabled) 1.0f else 0.55f
        card.vppscChevron.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
    }

    private fun bindComingSoonState() {
        b.fppdStatusCard.visibility = View.VISIBLE
        b.fppdStatusTitle.visibility = View.VISIBLE
        b.fppdStatusTitle.text = getString(R.string.power_profile_detail_status_coming_soon)
        b.fppdStatusDesc.text = getString(R.string.power_profile_detail_coming_desc)
        b.fppdStatusMeta.text = ""
        updateActionButton(
            enabled = false,
            textRes = R.string.power_profile_coming_soon_action,
            mode = ActionButtonMode.NEUTRAL
        )
        b.fppdExportBtn.isEnabled = false
        updateExportButtonStyle()
    }

    private fun updateActionButton(enabled: Boolean, textRes: Int, mode: ActionButtonMode) {
        b.fppdActionBtn.isEnabled = enabled
        b.fppdActionBtn.setText(textRes)
        when (mode) {
            ActionButtonMode.DISABLE -> {
                b.fppdActionBtn.background = ContextCompat.getDrawable(requireContext(), R.drawable.home_screen_button_start_bg)
                b.fppdActionBtn.backgroundTintList = null
                b.fppdActionBtn.setTextColor(Color.WHITE)
            }
            ActionButtonMode.ENABLE,
            ActionButtonMode.NEUTRAL -> {
                b.fppdActionBtn.background = ContextCompat.getDrawable(requireContext(), R.drawable.home_screen_button_stop_bg)
                b.fppdActionBtn.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
                b.fppdActionBtn.setTextColor(Color.BLACK)
            }
        }
        b.fppdActionBtn.alpha = if (enabled) 1.0f else 0.7f
    }

    private fun updateExportButtonStyle() {
        b.fppdExportBtn.background = ContextCompat.getDrawable(requireContext(), R.drawable.home_screen_button_stop_bg)
        b.fppdExportBtn.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        b.fppdExportBtn.setTextColor(Color.BLACK)
        b.fppdExportBtn.alpha = if (b.fppdExportBtn.isEnabled) 1.0f else 0.7f
    }

    private fun handleAction() {
        val profile = requireProfile()
        val activeProfile = PowerProfileStore.getActiveProfile(requireContext(), profile.id)
        when (
            PowerProfileActivationPolicy.resolve(
                profile = profile,
                isActive = activeProfile != null,
                hasTunnel = VpnController.hasTunnel()
            )
        ) {
            PowerProfileActivationAction.DISABLE_PROFILE -> {
                if (activeProfile != null) disableProfile(activeProfile)
            }
            PowerProfileActivationAction.START_PROTECTION_AND_ENABLE -> {
                startProtectionThenEnable(profile)
            }
            PowerProfileActivationAction.ENABLE_PROFILE -> {
                enableProfile(profile)
            }
            PowerProfileActivationAction.IGNORE -> {
                // no-op
            }
        }
    }

    private fun enableProfile(profile: PowerProfileDefinition) {
        viewLifecycleOwner.lifecycleScope.launch {
            val localCatalogReady =
                withContext(Dispatchers.IO) {
                    ensureLocalCatalogReady(profile, activateWhenReady = true)
                }
            if (!localCatalogReady) {
                bindState()
                return@launch
            }
            continueEnableProfile(profile)
        }
    }

    private fun startProtectionThenEnable(profile: PowerProfileDefinition) {
        pendingProtectionStartProfileId = profile.id
        if (prepareVpnService()) {
            startVpnService()
            awaitProtectionAndEnablePendingProfile()
        }
    }

    private fun awaitProtectionAndEnablePendingProfile() {
        val pendingProfileId = pendingProtectionStartProfileId ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val protectionStarted =
                withContext(Dispatchers.IO) {
                    waitForProtectionToStart()
                }
            val pendingProfile = PowerProfileCatalog.get(requireContext(), pendingProfileId)
            pendingProtectionStartProfileId = null
            if (pendingProfile == null) return@launch
            if (!protectionStarted) {
                bindState()
                showToastUiCentered(
                    requireContext(),
                    getString(R.string.power_profile_activation_failed_message),
                    Toast.LENGTH_SHORT
                )
                return@launch
            }
            enableProfile(pendingProfile)
        }
    }

    private suspend fun waitForProtectionToStart(): Boolean {
        repeat(PROTECTION_WAIT_ATTEMPTS) {
            if (VpnController.hasTunnel()) return true
            delay(PROTECTION_WAIT_INTERVAL_MS)
        }

        return VpnController.hasTunnel()
    }

    private suspend fun continueEnableProfile(profile: PowerProfileDefinition) {
        showToastUiCentered(
            requireContext(),
            getString(
                R.string.power_profile_activation_in_progress,
                profile.resolveTitle(requireContext())
            ),
            Toast.LENGTH_SHORT
        )
        val activatedProfile =
            withContext(Dispatchers.IO) {
                PowerProfileManager.enableProfile(requireContext(), profile)
            }
        if (activatedProfile != null) {
            showToastUiCentered(
                requireContext(),
                getString(
                    R.string.power_profile_activated_with_rules_message,
                    activatedProfile.name,
                    activatedProfile.importedRuleCount
                ),
                Toast.LENGTH_SHORT
            )
        } else {
            showToastUiCentered(
                requireContext(),
                getString(R.string.power_profile_activation_failed_message),
                Toast.LENGTH_SHORT
            )
        }
        bindState()
    }

    private fun exportProfile() {
        val profile = requireProfile()
        if (!profile.readyForActivation) return
        pendingExportProfileId = profile.id
        exportActivityResult.launch(suggestExportName(profile))
    }

    private fun handleExportResult(uri: Uri?) {
        val exportProfileId = pendingExportProfileId
        pendingExportProfileId = null
        if (uri == null || exportProfileId.isNullOrBlank()) return
        val profile = PowerProfileCatalog.get(requireContext(), exportProfileId) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val exported =
                withContext(Dispatchers.IO) {
                    val exportFile =
                        ImportedPowerProfileStore.exportToCache(requireContext(), profile) ?: return@withContext false
                    requireContext().contentResolver.openOutputStream(uri)?.use { output ->
                        exportFile.inputStream().use { input -> input.copyTo(output) }
                    } != null
                }
            val messageId =
                if (exported) {
                    R.string.power_profile_export_success_message
                } else {
                    R.string.power_profile_export_failure_message
                }
            val message =
                if (exported) {
                    getString(messageId, profile.resolveTitle(requireContext()))
                } else {
                    getString(messageId)
                }
            showToastUiCentered(
                requireContext(),
                message,
                Toast.LENGTH_SHORT
            )
        }
    }

    private fun requireProfile(): PowerProfileDefinition {
        return checkNotNull(PowerProfileCatalog.get(requireContext(), profileId)) {
            "Missing profile: $profileId"
        }
    }

    private fun openEntriesSection(section: String) {
        findNavController().navigate(
            R.id.powerProfileEntriesFragment,
            Bundle().apply {
                putString(PowerProfileEntriesFragment.ARG_PROFILE_ID, profileId)
                putString(PowerProfileEntriesFragment.ARG_SECTION, section)
            }
        )
    }

    private fun suggestExportName(profile: PowerProfileDefinition): String {
        return "${profile.id}.powerprofile.json"
    }

    private fun disableProfile(activeProfile: ActivePowerProfile) {
        viewLifecycleOwner.lifecycleScope.launch {
            val disableSummary =
                withContext(Dispatchers.IO) {
                    PowerProfileManager.disableProfile(requireContext(), activeProfile.id)
                }
            showToastUiCentered(
                requireContext(),
                getString(
                    R.string.power_profile_disabled_with_rules_message,
                    activeProfile.name,
                    disableSummary.removedRuleCount
                ),
                Toast.LENGTH_SHORT
            )
            bindState()
        }
    }

    @Throws(ActivityNotFoundException::class)
    private fun prepareVpnService(): Boolean {
        val prepareVpnIntent =
            try {
                VpnService.prepare(requireContext())
            } catch (_: NullPointerException) {
                pendingProtectionStartProfileId = null
                return false
            }

        if (prepareVpnIntent != null) {
            startForResult.launch(prepareVpnIntent)
            return false
        }

        return true
    }

    private fun startVpnService() {
        getNotificationPermissionIfNeeded()
        VpnController.start(requireContext(), true)
    }

    private fun getNotificationPermissionIfNeeded() {
        if (!Utilities.isAtleastT()) return

        if (
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        if (!persistentState.shouldRequestNotificationPermission) {
            return
        }

        notificationPermissionResult.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun registerForActivityResult() {
        startForResult =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
                when (result.resultCode) {
                    Activity.RESULT_OK -> {
                        startVpnService()
                        awaitProtectionAndEnablePendingProfile()
                    }
                    Activity.RESULT_CANCELED -> {
                        pendingProtectionStartProfileId = null
                        showToastUiCentered(
                            requireContext(),
                            getString(R.string.hsf_vpn_prepare_failure),
                            Toast.LENGTH_LONG
                        )
                    }
                    else -> {
                        pendingProtectionStartProfileId = null
                        VpnController.stop("power-profile-detail", requireContext())
                    }
                }
            }

        notificationPermissionResult =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                persistentState.shouldRequestNotificationPermission = it
            }
    }

    private fun formatActiveTimestamp(profile: ActivePowerProfile): CharSequence {
        return DateUtils.getRelativeTimeSpanString(
            profile.activatedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
    }

    private fun formatCount(value: Int): String {
        return NumberFormat.getIntegerInstance().format(value)
    }

    private fun observeLocalCatalogDownloads() {
        val workManager = WorkManager.getInstance(requireContext().applicationContext)

        workManager.getWorkInfosByTagLiveData(CUSTOM_DOWNLOAD).observe(viewLifecycleOwner) { workInfoList ->
            handleLocalCatalogWorkState(workInfoList.orEmpty())
        }
        workManager.getWorkInfosByTagLiveData(DOWNLOAD_TAG).observe(viewLifecycleOwner) { workInfoList ->
            handleLocalCatalogWorkState(workInfoList.orEmpty())
        }
        workManager.getWorkInfosByTagLiveData(FILE_TAG).observe(viewLifecycleOwner) { workInfoList ->
            handleLocalCatalogWorkState(workInfoList.orEmpty())
        }
    }

    private fun handleLocalCatalogWorkState(workInfoList: List<WorkInfo>) {
        if (workInfoList.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }) {
            if (!localCatalogDownloadInProgress) {
                localCatalogDownloadInProgress = true
                bindState()
            }
            return
        }

        if (!localCatalogDownloadInProgress && pendingLocalCatalogActivationProfileId == null) {
            return
        }

        when {
            workInfoList.any { it.state == WorkInfo.State.CANCELLED || it.state == WorkInfo.State.FAILED } -> {
                onLocalCatalogDownloadFail()
            }
            workInfoList.any { it.state == WorkInfo.State.SUCCEEDED } -> {
                onLocalCatalogDownloadSuccess()
            }
            else -> {
                // no-op
            }
        }
    }

    private fun maybeBootstrapLocalCatalog(profile: PowerProfileDefinition) {
        if (profile.localBlocklistTagIds.isEmpty() || localCatalogBootstrapAttempted) return
        localCatalogBootstrapAttempted = true
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ensureLocalCatalogReady(profile, activateWhenReady = false)
            }
            bindState()
        }
    }

    private suspend fun ensureLocalCatalogReady(
        profile: PowerProfileDefinition,
        activateWhenReady: Boolean
    ): Boolean {
        if (profile.localBlocklistTagIds.isEmpty()) return true
        if (PowerProfileLocalCatalogManager.hasCatalog(profile.localBlocklistTagIds)) return true

        val hydrated = PowerProfileLocalCatalogManager.hydrateIfDownloaded(requireContext())
        if (hydrated && PowerProfileLocalCatalogManager.hasCatalog(profile.localBlocklistTagIds)) {
            return true
        }

        if (activateWhenReady) {
            pendingLocalCatalogActivationProfileId = profile.id
        }

        if (localCatalogDownloadInProgress) return false

        val status =
            appDownloadManager.downloadLocalBlocklist(
                persistentState.localBlocklistTimestamp,
                isRedownload = false
            )
        return when (status) {
            AppDownloadManager.DownloadManagerStatus.NOT_REQUIRED -> {
                val readyAfterHydrate =
                    PowerProfileLocalCatalogManager.hydrateIfDownloaded(requireContext()) &&
                        PowerProfileLocalCatalogManager.hasCatalog(profile.localBlocklistTagIds)
                if (!readyAfterHydrate && activateWhenReady) {
                    pendingLocalCatalogActivationProfileId = null
                }
                readyAfterHydrate
            }
            AppDownloadManager.DownloadManagerStatus.STARTED,
            AppDownloadManager.DownloadManagerStatus.SUCCESS,
            AppDownloadManager.DownloadManagerStatus.IN_PROGRESS -> {
                localCatalogDownloadInProgress = true
                showToastUiCentered(
                    requireContext(),
                    getString(R.string.power_profile_local_catalog_download_started),
                    Toast.LENGTH_SHORT
                )
                false
            }
            else -> {
                if (activateWhenReady) {
                    pendingLocalCatalogActivationProfileId = null
                }
                showToastUiCentered(
                    requireContext(),
                    getString(R.string.power_profile_local_catalog_download_failed),
                    Toast.LENGTH_SHORT
                )
                false
            }
        }
    }

    private fun onLocalCatalogDownloadSuccess() {
        if (!localCatalogDownloadInProgress && pendingLocalCatalogActivationProfileId == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            localCatalogDownloadInProgress = false
            withContext(Dispatchers.IO) {
                PowerProfileLocalCatalogManager.hydrateIfDownloaded(requireContext())
            }
            bindState()
            val pendingActivationProfileId = pendingLocalCatalogActivationProfileId
            pendingLocalCatalogActivationProfileId = null
            if (pendingActivationProfileId == profileId) {
                val profile = requireProfile()
                when (
                    PowerProfileActivationPolicy.resolve(
                        profile = profile,
                        isActive = PowerProfileStore.getActiveProfile(requireContext(), profile.id) != null,
                        hasTunnel = VpnController.hasTunnel()
                    )
                ) {
                    PowerProfileActivationAction.START_PROTECTION_AND_ENABLE -> {
                        startProtectionThenEnable(profile)
                    }
                    PowerProfileActivationAction.ENABLE_PROFILE -> {
                        continueEnableProfile(profile)
                    }
                    else -> {
                        bindState()
                    }
                }
            }
        }
    }

    private fun onLocalCatalogDownloadFail() {
        if (!localCatalogDownloadInProgress && pendingLocalCatalogActivationProfileId == null) return
        localCatalogDownloadInProgress = false
        pendingLocalCatalogActivationProfileId = null
        bindState()
        showToastUiCentered(
            requireContext(),
            getString(R.string.power_profile_local_catalog_download_failed),
            Toast.LENGTH_SHORT
        )
    }
}
