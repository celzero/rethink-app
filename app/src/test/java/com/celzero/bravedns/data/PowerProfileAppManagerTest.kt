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
import com.celzero.bravedns.database.AppInfo
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PowerProfileAppManagerTest {

    private lateinit var context: Context
    private lateinit var profile: PowerProfileDefinition
    private lateinit var artifact: BundledDomainProfileArtifact

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        profile =
            PowerProfileDefinition(
                id = "app-horse",
                titleText = "App Horse",
                descriptionText = "desc",
                metaText = "meta",
                iconRes = android.R.drawable.ic_menu_info_details,
                readyForActivation = true
            )
        artifact =
            BundledDomainProfileArtifact(
                profileId = "app-horse",
                provider = "Tests",
                sourceDocUrl = "",
                generatedAtEpochMs = 1L,
                supportedRuleKind = "app-specific-exact-ip",
                domains = emptyList(),
                ips = emptyList(),
                apps =
                    listOf(
                        PowerProfileAppBlocklist(
                            packageName = "com.example.app",
                            appName = "Example App",
                            ipRules = listOf(PowerProfileAppIpRule("8.8.8.8", 443)),
                            domainRules = listOf(PowerProfileAppDomainRule("ads.example"))
                        )
                    )
            )

        mockkObject(PowerProfileCatalog)
        mockkObject(PowerProfileArtifacts)
        every { PowerProfileCatalog.get(context, "app-horse") } returns profile
        every { PowerProfileArtifacts.loadArtifact(context, profile) } returns artifact
        PowerProfileAppManager.lookupInstalledApp = { null }
    }

    @After
    fun tearDown() {
        PowerProfileAppManager.lookupInstalledApp = { null }
        unmockkAll()
    }

    @Test
    fun buildAppSummaries_prefersInstalledAppNameWhenAvailable() = kotlinx.coroutines.runBlocking {
        PowerProfileAppManager.lookupInstalledApp = { packageName ->
            if (packageName == "com.example.app") {
                AppInfo(
                    packageName = "com.example.app",
                    appName = "Installed App",
                    uid = 12345,
                    isSystemApp = false,
                    firewallStatus = PowerProfileFirewallValue.FIREWALL_STATUS_NONE,
                    appCategory = "",
                    wifiDataUsed = 0,
                    mobileDataUsed = 0,
                    connectionStatus = PowerProfileFirewallValue.CONNECTION_STATUS_ALLOW,
                    isProxyExcluded = false,
                    screenOffAllowed = false,
                    backgroundAllowed = false
                )
            } else {
                null
            }
        }

        val summaries = PowerProfileAppManager.buildAppSummaries(context, profile)

        assertEquals(1, summaries.size)
        assertEquals("Installed App", summaries.first().appName)
        assertEquals(12345, summaries.first().uid)
        assertEquals(1, summaries.first().ipRuleCount)
        assertEquals(1, summaries.first().domainRuleCount)
    }

    @Test
    fun buildPreview_returnsProfileAndAppBlocklist() = kotlinx.coroutines.runBlocking {
        PowerProfileAppManager.lookupInstalledApp = { null }

        val preview = PowerProfileAppManager.buildPreview(context, "app-horse", "com.example.app")

        assertNotNull(preview)
        assertEquals("app-horse", preview?.profile?.id)
        assertEquals("com.example.app", preview?.appBlocklist?.packageName)
        assertNull(preview?.installedAppInfo)
    }
}
