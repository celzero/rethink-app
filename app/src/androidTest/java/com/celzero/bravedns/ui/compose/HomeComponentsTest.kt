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
class HomeComponentsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun startStopButton_displaysStart_whenNotPlaying() {
        composeTestRule.setContent {
            RethinkTheme {
                StartStopButton(
                    isPlaying = false,
                    onClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Start").assertIsDisplayed()
    }

    @Test
    fun startStopButton_displaysStop_whenPlaying() {
        composeTestRule.setContent {
            RethinkTheme {
                StartStopButton(
                    isPlaying = true,
                    onClick = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Stop").assertIsDisplayed()
    }

    @Test
    fun startStopButton_triggersOnClick() {
        var clicked = false
        composeTestRule.setContent {
            RethinkTheme {
                StartStopButton(
                    isPlaying = false,
                    onClick = { clicked = true }
                )
            }
        }

        composeTestRule.onNodeWithText("Start").performClick()
        assert(clicked)
    }

    @Test
    fun dashboardCard_displaysTitleAndContent() {
        composeTestRule.setContent {
            RethinkTheme {
                DashboardCard(
                    title = "Test Card",
                    iconId = android.R.drawable.ic_menu_info_details,
                    onClick = {}
                ) {
                    androidx.compose.material3.Text("Test Content")
                }
            }
        }

        composeTestRule.onNodeWithText("Test Card").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Content").assertIsDisplayed()
    }

    @Test
    fun statItem_displaysValueAndLabel() {
        composeTestRule.setContent {
            RethinkTheme {
                StatItem(
                    label = "Test Label",
                    value = "42"
                )
            }
        }

        composeTestRule.onNodeWithText("42").assertIsDisplayed()
        composeTestRule.onNodeWithText("Test Label").assertIsDisplayed()
    }

    @Test
    fun statItem_appliesHighlightedColor() {
        // This test verifies the composable renders without crashing
        // when isHighlighted is true
        composeTestRule.setContent {
            RethinkTheme {
                StatItem(
                    label = "Highlighted",
                    value = "100",
                    isHighlighted = true
                )
            }
        }

        composeTestRule.onNodeWithText("100").assertIsDisplayed()
    }
}
