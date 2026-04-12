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
import com.celzero.bravedns.R
import com.celzero.bravedns.service.VpnController
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PowerProfileStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        mockkObject(VpnController)
        every { VpnController.isAppPaused() } returns false
        every { VpnController.hasTunnel() } returns true
        PowerProfileStore.defaultNameProvider = { _, index -> "Saved setup $index" }
        PowerProfileStore.defaultNoteProvider = { "Created from your current device setup." }
        PowerProfileStore.protectionStatusResolver = { "On" }
        PowerProfileStore.engineModeResolver = { _, _ -> "DNS + Firewall" }
    }

    @After
    fun tearDown() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        PowerProfileStore.defaultNameProvider =
            { currentContext, index ->
                currentContext.getString(R.string.power_saved_profile_default_name, index)
            }
        PowerProfileStore.defaultNoteProvider =
            { currentContext -> currentContext.getString(R.string.power_saved_profile_default_note) }
        PowerProfileStore.protectionStatusResolver =
            { currentContext ->
                when {
                    VpnController.isAppPaused() ->
                        currentContext.getString(R.string.power_status_paused)
                    VpnController.hasTunnel() -> currentContext.getString(R.string.power_status_on)
                    else -> currentContext.getString(R.string.power_status_off)
                }
            }
        PowerProfileStore.engineModeResolver =
            { currentContext, appConfig ->
                val braveMode = appConfig.getBraveMode()
                when {
                    braveMode.isDnsFirewallMode() ->
                        currentContext.getString(R.string.power_mode_dns_firewall)
                    braveMode.isFirewallMode() ->
                        currentContext.getString(R.string.power_mode_firewall)
                    else -> currentContext.getString(R.string.power_mode_dns)
                }
            }
        unmockkAll()
    }

    @Test
    fun saveCurrentSetup_persistsNewestFirst() {
        val appConfig = mockk<AppConfig>()
        every { appConfig.getBraveMode() } returns AppConfig.BraveMode.DNS_FIREWALL

        val first = PowerProfileStore.saveCurrentSetup(context, appConfig)
        Thread.sleep(2)
        val second = PowerProfileStore.saveCurrentSetup(context, appConfig)

        val saved = PowerProfileStore.listSavedProfiles(context)

        assertEquals(2, saved.size)
        assertEquals(second.id, saved.first().id)
        assertEquals(first.id, saved.last().id)
        assertEquals("On", saved.first().protectionStatus)
        assertEquals("DNS + Firewall", saved.first().engineMode)
    }

    @Test
    fun activateAndDeactivateProfile_updatesActiveProfileList() {
        val profile =
            PowerProfileDefinition(
                id = "smooth-browsing",
                titleText = "Smooth browsing",
                descriptionText = "desc",
                metaText = "meta",
                iconRes = android.R.drawable.ic_menu_info_details,
                sourceProvider = "uBO",
                sourceSummary = "uBO defaults",
                readyForActivation = true
            )

        val active =
            PowerProfileStore.activateProfile(
                context,
                profile,
                PowerProfileImportSummary(
                    importedCount = 10,
                    alreadyBlockedCount = 2,
                    skippedExistingCount = 1,
                    artifactRuleCount = 13,
                    supportedRuleKind = "exact-domain",
                    artifactGeneratedAtEpochMs = 123L
                )
            )

        val listed = PowerProfileStore.listActiveProfiles(context)
        assertEquals(1, listed.size)
        assertEquals(active.id, listed.first().id)
        assertTrue(PowerProfileStore.isProfileActive(context, profile.id))

        PowerProfileStore.deactivateProfile(context, profile.id)

        assertTrue(PowerProfileStore.listActiveProfiles(context).isEmpty())
        assertEquals(null, PowerProfileStore.getActiveProfile(context, profile.id))
    }
}
