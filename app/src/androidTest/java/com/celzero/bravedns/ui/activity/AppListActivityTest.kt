package com.celzero.bravedns.ui.activity

import androidx.test.espresso.Espresso.onView
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
class AppListActivityTest {

    @get:Rule
    var activityRule: ActivityScenarioRule<AppListActivity> =
        ActivityScenarioRule(AppListActivity::class.java)

    @Test
    fun testAppListDisplay() {
        onView(withId(R.id.ffa_app_list)).check(matches(isDisplayed()))
    }
}
