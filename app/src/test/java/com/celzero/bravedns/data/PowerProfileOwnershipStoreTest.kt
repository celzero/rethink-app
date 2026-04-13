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
import androidx.test.core.app.ApplicationProvider
import java.io.File
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
class PowerProfileOwnershipStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "power-profile-ownership").deleteRecursively()
    }

    @After
    fun tearDown() {
        File(context.filesDir, "power-profile-ownership").deleteRecursively()
    }

    @Test
    fun aggregateOwnership_mergesAndDeduplicatesAcrossProfiles() {
        PowerProfileOwnershipStore.write(
            context,
            "smooth",
            PowerProfileOwnedRules(
                domains = listOf("a.example", "b.example"),
                ips = listOf("1.1.1.1"),
                appDomains = listOf(PowerProfileOwnedAppDomainRule("com.example", "app.example")),
                appIps = listOf(PowerProfileOwnedAppIpRule("com.example", "8.8.8.8", 443)),
                appFirewalls =
                    listOf(
                        PowerProfileOwnedAppFirewallRule(
                            "com.example",
                            PowerProfileFirewallValue.FIREWALL_STATUS_NONE,
                            PowerProfileFirewallValue.CONNECTION_STATUS_BOTH
                        )
                    ),
                localBlocklistTagIds = listOf(1, 2)
            )
        )
        PowerProfileOwnershipStore.write(
            context,
            "safe",
            PowerProfileOwnedRules(
                domains = listOf("b.example", "c.example"),
                ips = listOf("1.1.1.1", "2.2.2.2"),
                appDomains = listOf(PowerProfileOwnedAppDomainRule("com.example", "app.example")),
                appIps = listOf(PowerProfileOwnedAppIpRule("com.example", "8.8.4.4", 443)),
                appFirewalls =
                    listOf(
                        PowerProfileOwnedAppFirewallRule(
                            "com.example",
                            PowerProfileFirewallValue.FIREWALL_STATUS_NONE,
                            PowerProfileFirewallValue.CONNECTION_STATUS_BOTH
                        )
                    ),
                localBlocklistTagIds = listOf(2, 3)
            )
        )

        val aggregated = PowerProfileOwnershipStore.aggregateOwnership(context, listOf("smooth", "safe"))

        assertEquals(listOf("a.example", "b.example", "c.example"), aggregated.domains)
        assertEquals(listOf("1.1.1.1", "2.2.2.2"), aggregated.ips)
        assertEquals(1, aggregated.appDomains.size)
        assertEquals(2, aggregated.appIps.size)
        assertEquals(1, aggregated.appFirewalls.size)
        assertEquals(listOf(1, 2, 3), aggregated.localBlocklistTagIds)
    }

    @Test
    fun read_missingProfile_returnsEmptyRules() {
        val ownedRules = PowerProfileOwnershipStore.read(context, "missing")
        assertTrue(ownedRules.domains.isEmpty())
        assertTrue(ownedRules.ips.isEmpty())
    }

    @Test
    fun listManagedRuleSources_mapsRulesToSourceProfiles() {
        val firstProfile =
            PowerProfileDefinition(
                id = "first",
                titleText = "First profile",
                descriptionText = "desc",
                metaText = "meta",
                iconRes = android.R.drawable.ic_menu_info_details,
                readyForActivation = true
            )
        val secondProfile =
            PowerProfileDefinition(
                id = "second",
                titleText = "Second profile",
                descriptionText = "desc",
                metaText = "meta",
                iconRes = android.R.drawable.ic_menu_info_details,
                readyForActivation = true
            )
        PowerProfileStore.activateProfile(context, firstProfile)
        PowerProfileStore.activateProfile(context, secondProfile)

        PowerProfileOwnershipStore.write(
            context,
            "first",
            PowerProfileOwnedRules(
                domains = listOf("shared.example", "first.example"),
                ips = listOf("1.1.1.1"),
                appDomains = listOf(PowerProfileOwnedAppDomainRule("com.example", "app.example")),
                appIps = emptyList(),
                appFirewalls =
                    listOf(
                        PowerProfileOwnedAppFirewallRule(
                            "com.example",
                            PowerProfileFirewallValue.FIREWALL_STATUS_NONE,
                            PowerProfileFirewallValue.CONNECTION_STATUS_BOTH
                        )
                    ),
                localBlocklistTagIds = listOf(101)
            )
        )
        PowerProfileOwnershipStore.write(
            context,
            "second",
            PowerProfileOwnedRules(
                domains = listOf("shared.example"),
                ips = listOf("2.2.2.2"),
                appDomains = listOf(PowerProfileOwnedAppDomainRule("com.example", "app.example")),
                appIps = listOf(PowerProfileOwnedAppIpRule("com.example", "8.8.8.8", 443)),
                appFirewalls = emptyList(),
                localBlocklistTagIds = listOf(101, 202)
            )
        )

        val managedSources = PowerProfileOwnershipStore.listManagedRuleSources(context)

        assertEquals(2, managedSources.domains.first { it.domain == "shared.example" }.ownerProfiles.size)
        assertEquals(1, managedSources.domains.first { it.domain == "first.example" }.ownerProfiles.size)
        assertEquals(2, managedSources.appDomains.first().ownerProfiles.size)
        assertEquals(1, managedSources.appIps.first().ownerProfiles.size)
        assertEquals(2, managedSources.localBlocklists.first { it.tagId == 101 }.ownerProfiles.size)
    }
}
