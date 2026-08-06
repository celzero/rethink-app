package com.celzero.bravedns.ui.activity

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.Themes
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.test.KoinTest
import org.koin.test.inject

@RunWith(AndroidJUnit4::class)
class ThemeChangeTest : KoinTest {

    private val persistentState: PersistentState by inject()

    @Test
    fun testThemeChangeRecreation() {
        val scenario = ActivityScenario.launch(HomeScreenActivity::class.java)
        
        // Initial check
        onView(withId(R.id.fhs_dns_on_off_btn)).check(matches(isDisplayed()))
        
        // Change theme to DARK
        scenario.onActivity { 
            persistentState.theme = Themes.DARK.id
            it.recreate()
        }
        
        // Verify UI still there and functional
        onView(withId(R.id.fhs_dns_on_off_btn)).check(matches(isDisplayed()))

        // Change theme to LIGHT
        scenario.onActivity { 
            persistentState.theme = Themes.LIGHT.id
            it.recreate()
        }
        
        onView(withId(R.id.fhs_dns_on_off_btn)).check(matches(isDisplayed()))
        
        scenario.close()
    }
}
