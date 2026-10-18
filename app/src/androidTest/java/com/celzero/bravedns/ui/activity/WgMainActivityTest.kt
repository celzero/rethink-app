package com.celzero.bravedns.ui.activity

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WgMainActivityTest {
    @get:Rule val activityRule = createAndroidComposeRule<WgMainActivity>()
    @Test fun testWgMainDisplay() {
        activityRule.onNodeWithTag("wg_add_fab").assertIsDisplayed()
    }
}
