/*
 * Copyright 2024 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.compose.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.ui.compose.theme.RethinkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun homeScreen_displaysCorrectInitialState() {
        val uiState = HomeScreenUiState(
            isVpnActive = false,
            dnsLatency = "-- ms",
            dnsConnectedName = "None",
            firewallUniversalRules = 0,
            appsTotal = 0,
            appsAllowed = 0,
            appsBlocked = 0
        )

        composeTestRule.setContent {
            RethinkTheme {
                HomeScreen(
                    uiState = uiState,
                    onStartStopClick = {},
                    onDnsClick = {},
                    onFirewallClick = {},
                    onProxyClick = {},
                    onLogsClick = {},
                    onAppsClick = {},
                    onSponsorClick = {}
                )
            }
        }

        // Verify Start button is displayed when VPN is inactive
        composeTestRule.onNodeWithText("Start").assertIsDisplayed()
    }

    @Test
    fun homeScreen_displaysStopButton_whenVpnIsActive() {
        val uiState = HomeScreenUiState(
            isVpnActive = true,
            dnsLatency = "24ms",
            dnsConnectedName = "Cloudflare",
            firewallUniversalRules = 12,
            appsTotal = 100,
            appsAllowed = 95,
            appsBlocked = 5
        )

        composeTestRule.setContent {
            RethinkTheme {
                HomeScreen(
                    uiState = uiState,
                    onStartStopClick = {},
                    onDnsClick = {},
                    onFirewallClick = {},
                    onProxyClick = {},
                    onLogsClick = {},
                    onAppsClick = {},
                    onSponsorClick = {}
                )
            }
        }

        // Verify Stop button is displayed when VPN is active
        composeTestRule.onNodeWithText("Stop").assertIsDisplayed()
    }

    @Test
    fun homeScreen_startStopButton_triggersCallback() {
        var clickCount = 0
        val uiState = HomeScreenUiState(isVpnActive = false)

        composeTestRule.setContent {
            RethinkTheme {
                HomeScreen(
                    uiState = uiState,
                    onStartStopClick = { clickCount++ },
                    onDnsClick = {},
                    onFirewallClick = {},
                    onProxyClick = {},
                    onLogsClick = {},
                    onAppsClick = {},
                    onSponsorClick = {}
                )
            }
        }

        // Click the Start button
        composeTestRule.onNodeWithText("Start").performClick()

        // Verify callback was triggered
        assert(clickCount == 1)
    }

    @Test
    fun homeScreen_displaysDnsCard() {
        val uiState = HomeScreenUiState(
            dnsLatency = "45ms",
            dnsConnectedName = "Google DNS"
        )

        composeTestRule.setContent {
            RethinkTheme {
                HomeScreen(
                    uiState = uiState,
                    onStartStopClick = {},
                    onDnsClick = {},
                    onFirewallClick = {},
                    onProxyClick = {},
                    onLogsClick = {},
                    onAppsClick = {},
                    onSponsorClick = {}
                )
            }
        }

        // Verify DNS latency is displayed
        composeTestRule.onNodeWithText("45ms").assertIsDisplayed()
    }

    @Test
    fun homeScreen_displaysFirewallCard() {
        val uiState = HomeScreenUiState(
            firewallUniversalRules = 15,
            firewallIpRules = 5,
            firewallDomainRules = 3
        )

        composeTestRule.setContent {
            RethinkTheme {
                HomeScreen(
                    uiState = uiState,
                    onStartStopClick = {},
                    onDnsClick = {},
                    onFirewallClick = {},
                    onProxyClick = {},
                    onLogsClick = {},
                    onAppsClick = {},
                    onSponsorClick = {}
                )
            }
        }

        // Verify firewall rules count is displayed
        composeTestRule.onNodeWithText("15").assertIsDisplayed()
    }

    @Test
    fun homeScreen_displaysAppsCard() {
        val uiState = HomeScreenUiState(
            appsTotal = 120,
            appsAllowed = 100,
            appsBlocked = 15,
            appsBypassed = 3,
            appsIsolated = 2,
            appsExcluded = 0
        )

        composeTestRule.setContent {
            RethinkTheme {
                HomeScreen(
                    uiState = uiState,
                    onStartStopClick = {},
                    onDnsClick = {},
                    onFirewallClick = {},
                    onProxyClick = {},
                    onLogsClick = {},
                    onAppsClick = {},
                    onSponsorClick = {}
                )
            }
        }

        // Verify apps count is displayed
        composeTestRule.onNodeWithText("100").assertIsDisplayed()
        composeTestRule.onNodeWithText("120").assertIsDisplayed()
    }
}
