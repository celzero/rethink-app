package com.celzero.bravedns.ui.activity

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirewallActivityTest {

    @get:Rule
    var activityRule: ActivityScenarioRule<FirewallActivity> =
        ActivityScenarioRule(FirewallActivity::class.java)

    @Test
    fun testFirewallActivityDisplay() {
        onView(withId(R.id.firewall_act_viewpager)).check(matches(isDisplayed()))
        onView(withText(R.string.firewall_act_universal_tab)).check(matches(isDisplayed()))
    }
}
