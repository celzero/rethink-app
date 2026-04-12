/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.data

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomDomainRepository
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.database.CustomIpRepository
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import io.mockk.runs
import io.mockk.unmockkAll
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PowerProfileManagerTest {

    private lateinit var context: Context
    private lateinit var appInfoRepository: AppInfoRepository
    private lateinit var customDomainRepository: CustomDomainRepository
    private lateinit var customIpRepository: CustomIpRepository
    private lateinit var persistentState: PersistentState

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        File(context.filesDir, "power-imported-profiles").deleteRecursively()
        File(context.filesDir, "power-profile-ownership").deleteRecursively()
        if (GlobalContext.getOrNull() != null) {
            GlobalContext.stopKoin()
        }

        appInfoRepository = mockk()
        customDomainRepository = mockk(relaxed = true)
        customIpRepository = mockk(relaxed = true)
        persistentState = mockk(relaxed = true)

        every { persistentState.localBlocklistStamp } returns ""
        PowerProfileImportManager.reloadDomainRules = {}
        PowerProfileImportManager.reloadIpRules = {}
        PowerProfileManager.computeLocalBlocklistStamp = { "stamp" }
        PowerProfileManager.syncLocalBlocklistSelections = {}
        PowerProfileManager.waitBeforeRetry = {}
        PowerProfileManager.applyAppFirewallRule = { _, _, _ -> }
        PowerProfileManager.reloadDomainRules = {}
        PowerProfileManager.reloadIpRules = {}
        PowerProfileStore.defaultNameProvider = { _, index -> "Saved setup $index" }
        PowerProfileStore.defaultNoteProvider = { "Created from your current device setup." }
        PowerProfileStore.protectionStatusResolver = { "On" }
        PowerProfileStore.engineModeResolver = { _, _ -> "DNS + Firewall" }

        startKoin {
            modules(
                module {
                    single { appInfoRepository }
                    single { customDomainRepository }
                    single { customIpRepository }
                    single { persistentState }
                }
            )
        }
    }

    @After
    fun tearDown() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        File(context.filesDir, "power-imported-profiles").deleteRecursively()
        File(context.filesDir, "power-profile-ownership").deleteRecursively()
        if (GlobalContext.getOrNull() != null) {
            GlobalContext.stopKoin()
        }
        PowerProfileImportManager.reloadDomainRules = {}
        PowerProfileImportManager.reloadIpRules = {}
        PowerProfileManager.computeLocalBlocklistStamp =
            { selectedTagIds ->
                com.celzero.bravedns.service.RethinkBlocklistManager.getStamp(
                    selectedTagIds,
                    com.celzero.bravedns.service.RethinkBlocklistManager.RethinkBlocklistType.LOCAL
                )
            }
        PowerProfileManager.syncLocalBlocklistSelections =
            { selectedTagIds ->
                com.celzero.bravedns.service.RethinkBlocklistManager.clearTagsSelectionLocal()
                if (selectedTagIds.isNotEmpty()) {
                    com.celzero.bravedns.service.RethinkBlocklistManager.updateFiletagsLocal(
                        selectedTagIds,
                        1
                    )
                }
            }
        PowerProfileManager.waitBeforeRetry = {}
        PowerProfileManager.applyAppFirewallRule = { _, _, _ -> }
        PowerProfileManager.reloadDomainRules = {}
        PowerProfileManager.reloadIpRules = {}
        PowerProfileStore.defaultNameProvider =
            { currentContext, index ->
                currentContext.getString(com.celzero.bravedns.R.string.power_saved_profile_default_name, index)
            }
        PowerProfileStore.defaultNoteProvider =
            { currentContext ->
                currentContext.getString(com.celzero.bravedns.R.string.power_saved_profile_default_note)
            }
        PowerProfileStore.protectionStatusResolver =
            { currentContext ->
                currentContext.getString(com.celzero.bravedns.R.string.power_status_on)
            }
        PowerProfileStore.engineModeResolver =
            { currentContext, _ ->
                currentContext.getString(com.celzero.bravedns.R.string.power_mode_dns_firewall)
            }
        unmockkAll()
    }

    @Test
    fun enableProfile_importsRules_andStoresOwnership() = runTest {
        val profile =
            PowerProfileDefinition(
                id = "manager-profile",
                titleText = "Manager profile",
                descriptionText = "desc",
                metaText = "meta",
                iconRes = android.R.drawable.ic_menu_info_details,
                readyForActivation = true
            )
        mockkObject(PowerProfileImportManager)
        coEvery {
            PowerProfileImportManager.importBundledRules(context, profile, any())
        } returns
            PowerProfileImportResult(
                summary =
                    PowerProfileImportSummary(
                        importedCount = 2,
                        alreadyBlockedCount = 0,
                        skippedExistingCount = 0,
                        artifactRuleCount = 2,
                        supportedRuleKind = "mixed",
                        artifactGeneratedAtEpochMs = 100L
                    ),
                ownedRules =
                    PowerProfileOwnedRules(
                        domains = listOf("ads.example"),
                        ips = listOf("2.2.2.2"),
                        appFirewalls =
                            listOf(
                                PowerProfileOwnedAppFirewallRule(
                                    packageName = "com.instagram.android",
                                    firewallStatus = PowerProfileFirewallValue.FIREWALL_STATUS_NONE,
                                    connectionStatus =
                                        PowerProfileFirewallValue.CONNECTION_STATUS_BOTH
                                )
                            )
                    )
            )

        val active = PowerProfileManager.enableProfile(context, profile)

        assertNotNull(active)
        assertTrue(PowerProfileStore.isProfileActive(context, profile.id))
        assertEquals(
            listOf("ads.example"),
            PowerProfileOwnershipStore.read(context, profile.id).domains
        )
        assertEquals(
            listOf("2.2.2.2"),
            PowerProfileOwnershipStore.read(context, profile.id).ips
        )
        assertEquals(
            listOf(
                PowerProfileOwnedAppFirewallRule(
                    packageName = "com.instagram.android",
                    firewallStatus = PowerProfileFirewallValue.FIREWALL_STATUS_NONE,
                    connectionStatus = PowerProfileFirewallValue.CONNECTION_STATUS_BOTH
                )
            ),
            PowerProfileOwnershipStore.read(context, profile.id).appFirewalls
        )
        coVerify(exactly = 1) { PowerProfileImportManager.importBundledRules(context, profile, any()) }
    }

    @Test
    fun disableProfile_preservesRulesStillOwnedByOtherProfiles() = runTest {
        val profileA =
            PowerProfileDefinition(
                id = "profile-a",
                titleText = "Profile A",
                descriptionText = "desc",
                metaText = "meta",
                iconRes = android.R.drawable.ic_menu_info_details,
                readyForActivation = true
            )
        val profileB =
            PowerProfileDefinition(
                id = "profile-b",
                titleText = "Profile B",
                descriptionText = "desc",
                metaText = "meta",
                iconRes = android.R.drawable.ic_menu_info_details,
                readyForActivation = true
            )

        PowerProfileStore.activateProfile(context, profileA)
        PowerProfileStore.activateProfile(context, profileB)
        PowerProfileOwnershipStore.write(
            context,
            profileA.id,
            PowerProfileOwnedRules(
                domains = listOf("only-a.example", "shared.example"),
                ips = listOf("2.2.2.2", "9.9.9.9")
            )
        )
        PowerProfileOwnershipStore.write(
            context,
            profileB.id,
            PowerProfileOwnedRules(domains = listOf("shared.example"), ips = listOf("9.9.9.9"))
        )

        PowerProfileManager.disableProfile(context, profileA.id)

        assertNull(PowerProfileStore.getActiveProfile(context, profileA.id))
        assertNotNull(PowerProfileStore.getActiveProfile(context, profileB.id))
        assertEquals(emptyList<String>(), PowerProfileOwnershipStore.read(context, profileA.id).domains)
        assertEquals(
            listOf("shared.example"),
            PowerProfileOwnershipStore.read(context, profileB.id).domains
        )
    }

    @Test
    fun disableProfile_resetsOwnedAppFirewallWhenNoOtherProfileOwnsIt() = runTest {
        val profile =
            PowerProfileDefinition(
                id = "focus-profile",
                titleText = "Focus profile",
                descriptionText = "desc",
                metaText = "meta",
                iconRes = android.R.drawable.ic_menu_info_details,
                readyForActivation = true
            )

        PowerProfileStore.activateProfile(context, profile)
        PowerProfileOwnershipStore.write(
            context,
            profile.id,
            PowerProfileOwnedRules(
                domains = emptyList(),
                ips = emptyList(),
                appFirewalls =
                    listOf(
                        PowerProfileOwnedAppFirewallRule(
                            packageName = "com.instagram.android",
                            firewallStatus = PowerProfileFirewallValue.FIREWALL_STATUS_NONE,
                            connectionStatus = PowerProfileFirewallValue.CONNECTION_STATUS_BOTH
                        )
                    )
            )
        )
        coEvery { appInfoRepository.getAppInfoUidForPackageName("com.instagram.android") } returns 12345
        coEvery { appInfoRepository.getAppInfoByUid(12345) } returns
            AppInfo(
                packageName = "com.instagram.android",
                appName = "Instagram",
                uid = 12345,
                isSystemApp = false,
                firewallStatus = PowerProfileFirewallValue.FIREWALL_STATUS_NONE,
                appCategory = "",
                wifiDataUsed = 0,
                mobileDataUsed = 0,
                connectionStatus = PowerProfileFirewallValue.CONNECTION_STATUS_BOTH,
                isProxyExcluded = false,
                screenOffAllowed = false,
                backgroundAllowed = false
            )
        val resets = mutableListOf<Triple<Int, Int, Int>>()
        PowerProfileManager.applyAppFirewallRule = { uid, firewallStatus, connectionStatus ->
            resets.add(Triple(uid, firewallStatus, connectionStatus))
        }

        val summary = PowerProfileManager.disableProfile(context, profile.id)

        assertEquals(1, summary.removedRuleCount)
        assertEquals(
            listOf(
                Triple(
                    12345,
                    PowerProfileFirewallValue.FIREWALL_STATUS_NONE,
                    PowerProfileFirewallValue.CONNECTION_STATUS_ALLOW
                )
            ),
            resets
        )
    }

    @Test
    fun enableProfile_withLocalBlocklists_requiresValidNativeStamp() = runTest {
        val profile =
            PowerProfileDefinition(
                id = "local-profile",
                titleText = "Local profile",
                descriptionText = "desc",
                metaText = "meta",
                iconRes = android.R.drawable.ic_menu_info_details,
                localBlocklistTagIds = listOf(101, 202),
                readyForActivation = true
            )
        mockkObject(PowerProfileImportManager)
        coEvery {
            PowerProfileImportManager.importBundledRules(context, profile, any())
        } returns
            PowerProfileImportResult(
                summary = PowerProfileImportSummary(0, 0, 0, 0, "", 0L),
                ownedRules = PowerProfileOwnedRules.empty()
            )
        PowerProfileManager.computeLocalBlocklistStamp = { "" }

        val active = PowerProfileManager.enableProfile(context, profile)

        assertNull(active)
        assertFalse(PowerProfileStore.isProfileActive(context, profile.id))
    }

    @Test
    fun enableProfile_withLocalBlocklists_updatesNativeSelectionState() = runTest {
        val profile =
            PowerProfileDefinition(
                id = "local-profile",
                titleText = "Local profile",
                descriptionText = "desc",
                metaText = "meta",
                iconRes = android.R.drawable.ic_menu_info_details,
                localBlocklistTagIds = listOf(101, 202),
                readyForActivation = true
            )
        mockkObject(PowerProfileImportManager)
        coEvery {
            PowerProfileImportManager.importBundledRules(context, profile, any())
        } returns
            PowerProfileImportResult(
                summary = PowerProfileImportSummary(0, 0, 0, 0, "", 0L),
                ownedRules = PowerProfileOwnedRules.empty()
            )

        every { persistentState.localBlocklistStamp = any() } just runs
        every { persistentState.numberOfLocalBlocklists = any() } just runs
        every { persistentState.blocklistEnabled = any() } just runs

        val active = PowerProfileManager.enableProfile(context, profile)

        assertNotNull(active)
        verify(exactly = 1) { persistentState.localBlocklistStamp = "stamp" }
        verify(exactly = 1) { persistentState.numberOfLocalBlocklists = 2 }
        verify(exactly = 1) { persistentState.blocklistEnabled = true }
        assertTrue(PowerProfileStore.isProfileActive(context, profile.id))
    }

    @Test
    fun enableProfile_withLocalBlocklists_retriesUntilStampBecomesAvailable() = runTest {
        val profile =
            PowerProfileDefinition(
                id = "local-profile",
                titleText = "Local profile",
                descriptionText = "desc",
                metaText = "meta",
                iconRes = android.R.drawable.ic_menu_info_details,
                localBlocklistTagIds = listOf(101, 202),
                readyForActivation = true
            )
        mockkObject(PowerProfileImportManager)
        coEvery {
            PowerProfileImportManager.importBundledRules(context, profile, any())
        } returns
            PowerProfileImportResult(
                summary = PowerProfileImportSummary(0, 0, 0, 0, "", 0L),
                ownedRules = PowerProfileOwnedRules.empty()
            )

        var attempts = 0
        PowerProfileManager.computeLocalBlocklistStamp = {
            attempts += 1
            if (attempts < 3) "" else "stamp"
        }
        every { persistentState.localBlocklistStamp = any() } just runs
        every { persistentState.numberOfLocalBlocklists = any() } just runs
        every { persistentState.blocklistEnabled = any() } just runs

        val active = PowerProfileManager.enableProfile(context, profile)

        assertNotNull(active)
        assertEquals(3, attempts)
    }

    @Test
    fun reconcileActiveProfiles_reappliesMissingNativeSelection() = runTest {
        val profile =
            PowerProfileDefinition(
                id = "local-profile",
                titleText = "Local profile",
                descriptionText = "desc",
                metaText = "meta",
                iconRes = android.R.drawable.ic_menu_info_details,
                localBlocklistTagIds = listOf(101, 202),
                readyForActivation = true
            )

        var computedSelection = emptySet<Int>()
        var syncedSelections = emptySet<Int>()
        every { persistentState.localBlocklistStamp } returns ""
        every { persistentState.blocklistEnabled } returns false
        every { persistentState.numberOfLocalBlocklists } returns 0
        PowerProfileManager.computeLocalBlocklistStamp = {
            computedSelection = it
            "stamp"
        }
        PowerProfileManager.syncLocalBlocklistSelections = { syncedSelections = it }

        PowerProfileStore.activateProfile(context, profile)
        PowerProfileOwnershipStore.write(
            context,
            profile.id,
            PowerProfileOwnedRules(
                domains = emptyList(),
                ips = emptyList(),
                localBlocklistTagIds = listOf(101, 202)
            )
        )

        val changed = PowerProfileManager.reconcileActiveProfiles(context)

        assertTrue(changed)
        assertTrue(PowerProfileStore.isProfileActive(context, profile.id))
        assertEquals(setOf(101, 202), computedSelection)
        assertEquals(setOf(101, 202), syncedSelections)
    }

    @Test
    fun reconcileActiveProfiles_deactivatesNativeProfilesWhenReapplyFails() = runTest {
        val profile =
            PowerProfileDefinition(
                id = "local-profile",
                titleText = "Local profile",
                descriptionText = "desc",
                metaText = "meta",
                iconRes = android.R.drawable.ic_menu_info_details,
                localBlocklistTagIds = listOf(101, 202),
                readyForActivation = true
            )

        every { persistentState.localBlocklistStamp } returns ""
        every { persistentState.blocklistEnabled } returns false
        every { persistentState.numberOfLocalBlocklists } returns 0
        PowerProfileManager.computeLocalBlocklistStamp = { "" }

        PowerProfileStore.activateProfile(context, profile)
        PowerProfileOwnershipStore.write(
            context,
            profile.id,
            PowerProfileOwnedRules(
                domains = emptyList(),
                ips = emptyList(),
                localBlocklistTagIds = listOf(101, 202)
            )
        )

        val changed = PowerProfileManager.reconcileActiveProfiles(context)

        assertTrue(changed)
        assertFalse(PowerProfileStore.isProfileActive(context, profile.id))
    }
}
