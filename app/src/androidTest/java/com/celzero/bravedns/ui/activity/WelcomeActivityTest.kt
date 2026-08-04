package com.celzero.bravedns.ui.activity

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
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
class WelcomeActivityTest {

    @get:Rule
    var activityRule: ActivityScenarioRule<WelcomeActivity> =
        ActivityScenarioRule(WelcomeActivity::class.java)

    @Test
    fun testWelcomeOnboardingFlow() {
        // Slide 1
        onView(withId(R.id.btn_next)).check(matches(isDisplayed()))
        onView(withId(R.id.btn_next)).check(matches(withText(R.string.next)))
        
        // Go to Slide 2
        onView(withId(R.id.btn_next)).perform(click())
        
        // Go to Slide 3
        onView(withId(R.id.btn_next)).perform(click())
        
        // Go to Slide 4
        onView(withId(R.id.btn_next)).perform(click())
        
        // Check if button text changed to Finish
        onView(withId(R.id.btn_next)).check(matches(withText(R.string.finish)))
    }

    @Test
    fun testSkipButton() {
        onView(withId(R.id.btn_skip)).check(matches(isDisplayed()))
        onView(withId(R.id.btn_skip)).perform(click())
        // After skip, HomeScreenActivity should be launched.
        // Verifying new activity launch is typically done with Espresso-Intents.
    }
}
