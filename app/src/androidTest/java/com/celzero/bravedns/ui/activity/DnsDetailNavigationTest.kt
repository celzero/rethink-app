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
class DnsDetailNavigationTest {

    @get:Rule
    var activityRule: ActivityScenarioRule<DnsDetailActivity> =
        ActivityScenarioRule(DnsDetailActivity::class.java)

    @Test
    fun testDnsTabsDisplay() {
        // Ensure initial state: "Configure" tab is displayed
        onView(withText(R.string.dns_act_configure_tab)).check(matches(isDisplayed()))

        // ViewPager should be displayed
        onView(withId(R.id.dns_detail_act_viewpager)).check(matches(isDisplayed()))
    }
}
