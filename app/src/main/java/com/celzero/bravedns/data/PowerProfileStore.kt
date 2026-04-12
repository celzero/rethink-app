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
package com.celzero.bravedns.data

import android.content.Context
import androidx.preference.PreferenceManager
import com.celzero.bravedns.R
import com.celzero.bravedns.service.VpnController

object PowerProfileStore {

    private const val PREF_KEY_SAVED_PROFILES = "power.saved_profiles.v1"
    private const val PREF_KEY_ACTIVE_PROFILES = "power.active_profiles.v1"
    private const val MAX_SAVED_PROFILES = 25

    fun listSavedProfiles(context: Context): List<SavedPowerProfile> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val raw = prefs.getString(PREF_KEY_SAVED_PROFILES, "").orEmpty()
        return SavedPowerProfile.parseStorageList(raw)
    }

    fun saveCurrentSetup(context: Context, appConfig: AppConfig): SavedPowerProfile {
        val existingProfiles = listSavedProfiles(context)
        val now = System.currentTimeMillis()
        val savedProfile =
            SavedPowerProfile(
                id = "saved-$now",
                name =
                    context.getString(
                        R.string.power_saved_profile_default_name,
                        existingProfiles.size + 1
                    ),
                note = context.getString(R.string.power_saved_profile_default_note),
                createdAt = now,
                protectionStatus = resolveProtectionStatus(context),
                engineMode = resolveEngineMode(context, appConfig)
            )

        val updatedProfiles = listOf(savedProfile) + existingProfiles
        persist(context, updatedProfiles.take(MAX_SAVED_PROFILES))
        return savedProfile
    }

    fun listActiveProfiles(context: Context): List<ActivePowerProfile> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val raw = prefs.getString(PREF_KEY_ACTIVE_PROFILES, "").orEmpty()
        return ActivePowerProfile.parseStorageList(raw)
    }

    fun activateProfile(context: Context, profile: PowerProfileDefinition): ActivePowerProfile {
        return activateProfile(context, profile, null)
    }

    fun activateProfile(
        context: Context,
        profile: PowerProfileDefinition,
        importSummary: PowerProfileImportSummary?
    ): ActivePowerProfile {
        val now = System.currentTimeMillis()
        val activeProfile =
            ActivePowerProfile(
                id = profile.id,
                name = context.getString(profile.titleRes),
                provider = profile.sourceProvider.orEmpty(),
                sourceSummary = profile.sourceSummary.orEmpty(),
                sourceDocUrl = profile.sourceDocUrl.orEmpty(),
                sourceTokens = profile.sourceTokens,
                activatedAt = now,
                importedRuleCount = importSummary?.importedCount ?: 0,
                alreadyPresentRuleCount = importSummary?.alreadyBlockedCount ?: 0,
                skippedExistingRuleCount = importSummary?.skippedExistingCount ?: 0,
                artifactRuleCount = importSummary?.artifactRuleCount ?: 0,
                supportedRuleKind = importSummary?.supportedRuleKind.orEmpty(),
                artifactGeneratedAtEpochMs = importSummary?.artifactGeneratedAtEpochMs ?: 0L,
                profileEnabled = true
            )

        val updatedProfiles =
            buildList {
                add(activeProfile)
                addAll(listActiveProfiles(context).filterNot { it.id == profile.id })
            }
        persistActiveProfiles(context, updatedProfiles.take(MAX_SAVED_PROFILES))
        return activeProfile
    }

    fun getActiveProfile(context: Context, profileId: String): ActivePowerProfile? {
        return listActiveProfiles(context).firstOrNull { it.id == profileId }
    }

    fun isProfileActive(context: Context, profileId: String): Boolean {
        return getActiveProfile(context, profileId) != null
    }

    fun deactivateProfile(context: Context, profileId: String) {
        val updatedProfiles = listActiveProfiles(context).filterNot { it.id == profileId }
        persistActiveProfiles(context, updatedProfiles)
    }

    private fun persist(context: Context, profiles: List<SavedPowerProfile>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs
            .edit()
            .putString(PREF_KEY_SAVED_PROFILES, SavedPowerProfile.toStorageList(profiles))
            .apply()
    }

    private fun persistActiveProfiles(context: Context, profiles: List<ActivePowerProfile>) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs
            .edit()
            .putString(PREF_KEY_ACTIVE_PROFILES, ActivePowerProfile.toStorageList(profiles))
            .apply()
    }

    private fun resolveProtectionStatus(context: Context): String {
        return when {
            VpnController.isAppPaused() -> context.getString(R.string.power_status_paused)
            VpnController.hasTunnel() -> context.getString(R.string.power_status_on)
            else -> context.getString(R.string.power_status_off)
        }
    }

    private fun resolveEngineMode(context: Context, appConfig: AppConfig): String {
        val braveMode = appConfig.getBraveMode()
        return when {
            braveMode.isDnsFirewallMode() -> context.getString(R.string.power_mode_dns_firewall)
            braveMode.isFirewallMode() -> context.getString(R.string.power_mode_firewall)
            else -> context.getString(R.string.power_mode_dns)
        }
    }
}
