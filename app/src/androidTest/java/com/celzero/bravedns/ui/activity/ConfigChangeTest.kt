package com.celzero.bravedns.ui.activity

import android.content.pm.ActivityInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.HomeScreenActivity
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConfigChangeTest {

    @Test
    fun testHomeScreenStateRetentionOnRotation() {
        val scenario = ActivityScenario.launch(HomeScreenActivity::class.java)
        
        // Initial check
        onView(withId(R.id.fhs_dns_on_off_btn)).check(matches(isDisplayed()))
        
        // Rotate to landscape
        scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        
        // Verify UI still there
        onView(withId(R.id.fhs_dns_on_off_btn)).check(matches(isDisplayed()))
        
        // Rotate back to portrait
        scenario.onActivity { activity ->
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        
        onView(withId(R.id.fhs_dns_on_off_btn)).check(matches(isDisplayed()))
        
        scenario.close()
    }
}
