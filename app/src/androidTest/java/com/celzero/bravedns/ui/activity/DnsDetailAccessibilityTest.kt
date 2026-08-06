package com.celzero.bravedns.ui.activity

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.R
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DnsDetailAccessibilityTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun enableAccessibilityChecks() {
            AccessibilityChecks.enable()
        }
    }

    @get:Rule
    var activityRule: ActivityScenarioRule<DnsDetailActivity> =
        ActivityScenarioRule(DnsDetailActivity::class.java)

    @Test
    fun testDnsDetailAccessibility() {
        onView(withId(R.id.dns_detail_act_viewpager)).check(matches(isDisplayed()))
    }
}
