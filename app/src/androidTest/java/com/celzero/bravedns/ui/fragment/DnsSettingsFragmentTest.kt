package com.celzero.bravedns.ui.fragment

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.activity.DnsDetailActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DnsSettingsFragmentTest {

    @get:Rule
    var activityRule: ActivityScenarioRule<DnsDetailActivity> =
        ActivityScenarioRule(DnsDetailActivity::class.java)

    @Test
    fun testDnsSettingsDisplay() {
        onView(withId(R.id.network_dns_rb)).check(matches(isDisplayed()))
        onView(withId(R.id.custom_dns_rb)).check(matches(isDisplayed()))
    }
}
