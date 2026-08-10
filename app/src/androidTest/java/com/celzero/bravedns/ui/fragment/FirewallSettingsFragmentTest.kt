package com.celzero.bravedns.ui.fragment

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.activity.FirewallActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirewallSettingsFragmentTest {

    @get:Rule
    var activityRule: ActivityScenarioRule<FirewallActivity> =
        ActivityScenarioRule(FirewallActivity::class.java)

    @Test
    fun testFirewallSettingsDisplay() {
        onView(withId(R.id.universal_firewall_rl)).check(matches(isDisplayed()))
    }
}
