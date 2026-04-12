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
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomDomainRepository
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.database.CustomIpRepository
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.util.Constants
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
class PowerProfileImportManagerTest {

    private lateinit var context: Context
    private lateinit var appInfoRepository: AppInfoRepository
    private lateinit var customDomainRepository: CustomDomainRepository
    private lateinit var customIpRepository: CustomIpRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "power-imported-profiles").deleteRecursively()
        if (GlobalContext.getOrNull() != null) {
            GlobalContext.stopKoin()
        }

        appInfoRepository = mockk()
        customDomainRepository = mockk()
        customIpRepository = mockk()

        PowerProfileImportManager.reloadDomainRules = {}
        PowerProfileImportManager.reloadIpRules = {}

        startKoin {
            modules(
                module {
                    single { appInfoRepository }
                    single { customDomainRepository }
                    single { customIpRepository }
                }
            )
        }
    }

    @After
    fun tearDown() {
        File(context.filesDir, "power-imported-profiles").deleteRecursively()
        if (GlobalContext.getOrNull() != null) {
            GlobalContext.stopKoin()
        }
        PowerProfileImportManager.reloadDomainRules = {}
        PowerProfileImportManager.reloadIpRules = {}
        unmockkAll()
    }

    @Test
    fun importBundledRules_insertsOnlyMissingRules_andCountsExistingAndSkipped() = runTest {
        val profile = writeProfileArtifact()

        every { customDomainRepository.getDomainsByUID(Constants.UID_EVERYBODY) } returns
            listOf(
                CustomDomain(
                    domain = "already.example",
                    uid = Constants.UID_EVERYBODY,
                    ips = "",
                    type = DomainRulesManager.DomainType.DOMAIN.id,
                    status = DomainRulesManager.Status.BLOCK.id,
                    proxyId = "",
                    proxyCC = "",
                    modifiedTs = 1L,
                    deletedTs = 0L,
                    version = CustomDomain.getCurrentVersion()
                ),
                CustomDomain(
                    domain = "skip.example",
                    uid = Constants.UID_EVERYBODY,
                    ips = "",
                    type = DomainRulesManager.DomainType.DOMAIN.id,
                    status = DomainRulesManager.Status.TRUST.id,
                    proxyId = "",
                    proxyCC = "",
                    modifiedTs = 1L,
                    deletedTs = 0L,
                    version = CustomDomain.getCurrentVersion()
                )
            )
        coEvery { customIpRepository.getRulesByUid(Constants.UID_EVERYBODY) } returns
            listOf(
                CustomIp().apply {
                    uid = Constants.UID_EVERYBODY
                    ipAddress = "1.1.1.1"
                    port = Constants.UNSPECIFIED_PORT
                    status = IpRulesManager.IpRuleStatus.BLOCK.id
                }
            )
        coEvery { appInfoRepository.getAppInfoUidForPackageName("com.example.app") } returns 12345
        every { customDomainRepository.getDomainsByUID(12345) } returns emptyList()
        coEvery { customIpRepository.getRulesByUid(12345) } returns emptyList()

        val insertedDomains = slot<List<CustomDomain>>()
        val insertedIps = slot<List<CustomIp>>()
        coEvery { customDomainRepository.insertAll(capture(insertedDomains)) } just runs
        coEvery { customIpRepository.insertAll(capture(insertedIps)) } just runs

        val result = PowerProfileImportManager.importBundledRules(context, profile)

        assertEquals(4, result?.summary?.importedCount)
        assertEquals(2, result?.summary?.alreadyBlockedCount)
        assertEquals(1, result?.summary?.skippedExistingCount)
        assertEquals(7, result?.summary?.artifactRuleCount)
        assertEquals(
            listOf("appdomain.example", "fresh.example"),
            insertedDomains.captured.map { it.domain }.sorted()
        )
        assertEquals(
            listOf("2.2.2.2", "8.8.8.8"),
            insertedIps.captured.map { it.ipAddress }.sorted()
        )
        assertTrue(result?.ownedRules?.domains?.contains("fresh.example") == true)
        assertTrue(result?.ownedRules?.ips?.contains("2.2.2.2") == true)
        assertTrue(result?.ownedRules?.appDomains?.any { it.packageName == "com.example.app" } == true)
        assertTrue(result?.ownedRules?.appIps?.any { it.packageName == "com.example.app" } == true)

        coVerify(exactly = 1) { customDomainRepository.insertAll(any()) }
        coVerify(exactly = 1) { customIpRepository.insertAll(any()) }
        coVerify(exactly = 1) { customIpRepository.getRulesByUid(12345) }
    }

    private fun writeProfileArtifact(): PowerProfileDefinition {
        val directory = File(context.filesDir, "power-imported-profiles")
        directory.mkdirs()
        File(directory, "test-profile.json")
            .writeText(
                """
                {
                  "profileId": "test-profile",
                  "provider": "Tests",
                  "sourceDocUrl": "https://example.com",
                  "generatedAtEpochMs": 100,
                  "supportedRuleKind": "mixed",
                  "domains": ["fresh.example", "already.example", "skip.example"],
                  "ips": ["2.2.2.2", "1.1.1.1"],
                  "apps": [
                    {
                      "packageName": "com.example.app",
                      "appName": "Example App",
                      "firewallStatus": 5,
                      "connectionStatus": 3,
                      "domainRules": [{"domain": "appdomain.example"}],
                      "ipRules": [{"ipAddress": "8.8.8.8", "port": 443}]
                    }
                  ]
                }
                """.trimIndent()
            )

        return PowerProfileDefinition(
            id = "test-profile",
            titleText = "Test profile",
            descriptionText = "desc",
            metaText = "meta",
            iconRes = android.R.drawable.ic_menu_info_details,
            localArtifactFileName = "test-profile.json",
            readyForActivation = true
        )
    }
}
