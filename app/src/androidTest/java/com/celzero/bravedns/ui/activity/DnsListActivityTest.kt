package com.celzero.bravedns.ui.activity

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DnsListActivityTest {

    @get:Rule
    var activityRule: ActivityScenarioRule<DnsListActivity> =
        ActivityScenarioRule(DnsListActivity::class.java)

    @Test
    fun testDnsOptionsDisplay() {
        onView(withId(R.id.card_doh)).check(matches(isDisplayed()))
        onView(withId(R.id.card_dnscrypt)).check(matches(isDisplayed()))
        onView(withId(R.id.card_dot)).check(matches(isDisplayed()))
        onView(withId(R.id.card_odoh)).check(matches(isDisplayed()))
        onView(withId(R.id.card_rethink_dns)).check(matches(isDisplayed()))
    }
}
