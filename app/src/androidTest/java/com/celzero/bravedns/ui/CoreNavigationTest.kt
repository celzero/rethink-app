package com.celzero.bravedns.ui

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreNavigationTest {

    @get:Rule
    var activityRule: ActivityScenarioRule<HomeScreenActivity> =
        ActivityScenarioRule(HomeScreenActivity::class.java)

    @Test
    fun testBottomNavigation() {
        // Go to Statistics
        onView(withId(R.id.summaryStatisticsFragment)).perform(click())
        onView(withId(R.id.summaryStatisticsFragment)).check(matches(isDisplayed()))
        
        // Go to Configure
        onView(withId(R.id.configureFragment)).perform(click())
        // onView(withId(R.id.configureFragment)).check(matches(isDisplayed()))
        
        // Go to About
        onView(withId(R.id.aboutFragment)).perform(click())
        // onView(withId(R.id.aboutFragment)).check(matches(isDisplayed()))
        
        // Go back to Home
        onView(withId(R.id.homeScreenFragment)).perform(click())
        onView(withId(R.id.fhs_dns_on_off_btn)).check(matches(isDisplayed()))
    }
}
