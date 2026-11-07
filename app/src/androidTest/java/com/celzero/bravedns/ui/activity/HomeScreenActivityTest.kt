package com.celzero.bravedns.ui.activity

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.HomeScreenActivity
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only controller obligation: requires a separately reviewed disposable Application and
 * strict VPN/permission/manager fixture. The default production runner is NOT approved to run. */
@RunWith(AndroidJUnit4::class)
class HomeScreenActivityTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun enableAccessibilityChecks() {
            AccessibilityChecks.enable()
        }
    }

    @get:Rule(order = 0)
    var activityRule: ActivityScenarioRule<HomeScreenActivity> =
        ActivityScenarioRule(HomeScreenActivity::class.java)

    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Test
    fun testHomeScreenDisplay() {
        compose.onNodeWithTag("fhs_dns_on_off_btn").assertIsDisplayed()
        compose.onNodeWithTag("fhs_card_dns_ll").assertIsDisplayed()
        compose.onNodeWithTag("fhs_card_firewall_ll").assertIsDisplayed()
    }

    @Test
    fun testVpnToggleClick() {
        // Clicking the start button might trigger a system VPN dialog, which Espresso can't handle directly.
        // But we can check if the button is clickable.
        compose.onNodeWithTag("fhs_dns_on_off_btn").performClick()
    }
}
