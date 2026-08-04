package com.celzero.bravedns.ui.activity

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.HomeScreenActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenActivityTest {

    @get:Rule
    var activityRule: ActivityScenarioRule<HomeScreenActivity> =
        ActivityScenarioRule(HomeScreenActivity::class.java)

    @Test
    fun testHomeScreenDisplay() {
        onView(withId(R.id.fhs_dns_on_off_btn)).check(matches(isDisplayed()))
        onView(withId(R.id.fhs_card_dns_ll)).check(matches(isDisplayed()))
        onView(withId(R.id.fhs_card_firewall_ll)).check(matches(isDisplayed()))
    }

    @Test
    fun testVpnToggleClick() {
        // Clicking the start button might trigger a system VPN dialog, which Espresso can't handle directly.
        // But we can check if the button is clickable.
        onView(withId(R.id.fhs_dns_on_off_btn)).perform(click())
    }
}
