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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PowerProfileCurrentSetupOverrideStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "power-profile-current-setup-overrides.json").delete()
    }

    @After
    fun tearDown() {
        File(context.filesDir, "power-profile-current-setup-overrides.json").delete()
    }

    @Test
    fun readWrite_roundTripsDisabledRules() {
        PowerProfileCurrentSetupOverrideStore.write(
            context,
            PowerProfileCurrentSetupOverrides(
                disabledDomains = listOf("ads.example"),
                disabledIps = listOf("1.1.1.1"),
                disabledAppDomains =
                    listOf(PowerProfileOwnedAppDomainRule("com.example", "app.example")),
                disabledAppIps =
                    listOf(PowerProfileOwnedAppIpRule("com.example", "8.8.8.8", 443)),
                disabledAppFirewalls =
                    listOf(
                        PowerProfileOwnedAppFirewallRule(
                            "com.example",
                            PowerProfileFirewallValue.FIREWALL_STATUS_NONE,
                            PowerProfileFirewallValue.CONNECTION_STATUS_BOTH
                        )
                    ),
                disabledLocalBlocklistTagIds = listOf(101, 202)
            )
        )

        val restored = PowerProfileCurrentSetupOverrideStore.read(context)

        assertEquals(listOf("ads.example"), restored.disabledDomains)
        assertEquals(listOf("1.1.1.1"), restored.disabledIps)
        assertEquals(
            listOf(PowerProfileOwnedAppDomainRule("com.example", "app.example")),
            restored.disabledAppDomains
        )
        assertEquals(
            listOf(PowerProfileOwnedAppIpRule("com.example", "8.8.8.8", 443)),
            restored.disabledAppIps
        )
        assertEquals(
            listOf(
                PowerProfileOwnedAppFirewallRule(
                    "com.example",
                    PowerProfileFirewallValue.FIREWALL_STATUS_NONE,
                    PowerProfileFirewallValue.CONNECTION_STATUS_BOTH
                )
            ),
            restored.disabledAppFirewalls
        )
        assertEquals(listOf(101, 202), restored.disabledLocalBlocklistTagIds)
    }
}
