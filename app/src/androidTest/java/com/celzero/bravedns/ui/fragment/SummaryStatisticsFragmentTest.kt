package com.celzero.bravedns.ui.fragment

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
class SummaryStatisticsFragmentTest {

    @get:Rule
    var activityRule: ActivityScenarioRule<HomeScreenActivity> =
        ActivityScenarioRule(HomeScreenActivity::class.java)

    @Test
    fun testStatisticsDisplay() {
        // Navigate to statistics
        onView(withId(R.id.summaryStatisticsFragment)).perform(click())
        
        // Verify statistics elements
        onView(withId(R.id.toggle_group)).check(matches(isDisplayed()))
    }
}
