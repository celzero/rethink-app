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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerProfileSecurityTest {

    @Test
    fun `sanitize portable profile drops malformed values`() {
        val sanitized =
            PowerProfileSecurity.sanitizePortableProfile(
                id = "../Bad Profile",
                name = "  Example  ",
                description = "desc",
                meta = "meta",
                provider = "provider",
                sourceSummary = "summary",
                sourceDocUrl = "https://example.com",
                sourceTokens = listOf("ads", "ads", ""),
                generatedAtEpochMs = -5L,
                supportedRuleKind = "app-specific-exact-ip",
                domains = listOf("Example.COM.", "bad domain", ""),
                ips = listOf("1.1.1.1", "not-an-ip"),
                apps =
                    listOf(
                        PowerProfileAppBlocklist(
                            packageName = "com.example.app",
                            appName = " Example App ",
                            firewallStatus = 999,
                            connectionStatus = 999,
                            domainRules =
                                listOf(
                                    PowerProfileAppDomainRule(domain = "valid.example.com."),
                                    PowerProfileAppDomainRule(domain = "bad domain")
                                ),
                            ipRules =
                                listOf(
                                    PowerProfileAppIpRule(ipAddress = "8.8.8.8", port = 443, protocol = "UDP"),
                                    PowerProfileAppIpRule(ipAddress = "bad-ip", port = 999999)
                                )
                        ),
                        PowerProfileAppBlocklist(
                            packageName = "bad package",
                            appName = "Bad",
                            firewallStatus = PowerProfileFirewallValue.FIREWALL_STATUS_ISOLATE,
                            connectionStatus = PowerProfileFirewallValue.CONNECTION_STATUS_BOTH
                        )
                    ),
                localBlocklistTagIds = listOf(-1, 10, 10)
            )

        assertEquals("", sanitized.id)
        assertEquals(listOf("example.com"), sanitized.domains)
        assertEquals(listOf("1.1.1.1"), sanitized.ips)
        assertEquals(listOf("ads"), sanitized.sourceTokens)
        assertEquals(0L, sanitized.generatedAtEpochMs)
        assertEquals(listOf(10), sanitized.localBlocklistTagIds)
        assertEquals(1, sanitized.apps.size)
        assertEquals(PowerProfileFirewallValue.FIREWALL_STATUS_NONE, sanitized.apps.first().firewallStatus)
        assertEquals(PowerProfileFirewallValue.CONNECTION_STATUS_ALLOW, sanitized.apps.first().connectionStatus)
        assertEquals(1, sanitized.apps.first().domainRules.size)
        assertEquals("valid.example.com", sanitized.apps.first().domainRules.first().domain)
        assertEquals(1, sanitized.apps.first().ipRules.size)
        assertEquals("udp", sanitized.apps.first().ipRules.first().protocol)
        assertEquals(443, sanitized.apps.first().ipRules.first().port)
    }

    @Test
    fun `sanitize artifact limits oversized app lists`() {
        val apps =
            List(300) {
                PowerProfileAppBlocklist(
                    packageName = "com.example.app$it",
                    appName = "App $it"
                )
            }

        val sanitized =
            PowerProfileSecurity.sanitizeArtifact(
                profileId = "sample-profile",
                provider = "provider",
                sourceDocUrl = "",
                generatedAtEpochMs = 1L,
                supportedRuleKind = "kind",
                domains = emptyList(),
                ips = emptyList(),
                apps = apps
            )

        assertTrue(sanitized.apps.size <= 256)
    }
}
