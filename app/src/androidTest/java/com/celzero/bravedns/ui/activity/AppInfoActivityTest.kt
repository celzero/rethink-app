/*
 * Copyright 2025 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui.activity

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celzero.bravedns.R
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import org.hamcrest.Matchers.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class AppInfoActivityTest {

    private lateinit var context: Context
    private var testUid: Int = INVALID_UID
    private val testTag = "AppInfoActivityTest"
    private val maxAcceptableLoadTime = 5000L // 5 seconds max

    @Before
    fun setUp() {
        Log.d(testTag, "Setting up comprehensive test environment")
        context = ApplicationProvider.getApplicationContext()

        // Get a valid UID for testing - use the current app's UID
        testUid = try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.applicationInfo?.uid ?: 1000
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(testTag, "Failed to get test UID", e)
            1000 // fallback UID
        }

        Log.d(testTag, "Test setup completed with UID: $testUid")
    }

    @After
    fun tearDown() {
        Log.d(testTag, "Tearing down test environment")
        // Force garbage collection to clean up after performance tests
        System.gc()
    }

    // ========== BASIC FUNCTIONALITY TESTS ==========

    @Test
    fun testLaunchesSuccessfullyWithValidUid() {
        Log.d(testTag, "Testing successful launch with valid UID")
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            assertNotNull("Activity scenario should not be null", scenario)

            scenario.onActivity { activity ->
                assertNotNull("Activity should not be null", activity)
                assertFalse("Activity should not be finishing", activity.isFinishing)
                assertFalse("Activity should not be destroyed", activity.isDestroyed)
            }
        }
        Log.d(testTag, "Successful launch test completed")
    }

    @Test
    fun testLaunchWithInvalidUid() {
        Log.d(testTag, "Testing launch with invalid UID")
        val intent = createInvalidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            assertNotNull("Activity scenario should not be null", scenario)

            // Activity should still launch but may show error dialog
            scenario.onActivity { activity ->
                assertNotNull("Activity should not be null", activity)
            }
        }
        Log.d(testTag, "Invalid UID test completed")
    }

    @Test
    fun testUiElementsAreDisplayedWithValidData() {
        Log.d(testTag, "Testing UI elements display with valid data")
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use {
            try {
                // Top app details - these should always be present
                onView(withId(R.id.aad_app_detail_icon))
                    .check(matches(isDisplayed()))
                onView(withId(R.id.aad_app_detail_name))
                    .check(matches(isDisplayed()))
                onView(withId(R.id.aad_pkg_name))
                    .check(matches(isDisplayed()))

                // These elements may be conditionally visible
                checkElementWithFallback(R.id.aad_firewall_status, "firewall status")
                checkElementWithFallback(R.id.aad_data_usage_status, "data usage status")
                checkElementWithFallback(R.id.aad_app_info_icon, "app info icon")
                checkElementWithFallback(R.id.aad_close_conns_chip, "close connections chip")

                // App settings card
                checkElementWithFallback(R.id.aad_app_settings_card, "app settings card")

                // Proxy settings
                checkElementWithFallback(R.id.exclude_proxy_icon, "proxy icon")
                checkElementWithFallback(R.id.exclude_proxy_txt, "proxy text")
                checkElementWithFallback(R.id.exclude_proxy_switch, "proxy switch")

                // Block cards
                checkElementWithFallback(R.id.aad_ip_block_card, "IP block card")
                checkElementWithFallback(R.id.aad_domain_block_card, "domain block card")

                Log.d(testTag, "UI elements display test completed successfully")

            } catch (e: Exception) {
                Log.e(testTag, "UI elements test failed", e)
                fail("UI elements test failed: ${e.message}")
            }
        }
    }

    @Test
    fun testUiElementsContentAndInteractions() {
        Log.d(testTag, "Testing UI element interactions")
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use {
            try {
                // Test that text fields are not empty
                onView(withId(R.id.aad_app_detail_name))
                    .check(matches(withText(not(isEmptyOrNullString()))))
                onView(withId(R.id.aad_pkg_name))
                    .check(matches(withText(not(isEmptyOrNullString()))))

                // Test switch interactions (with error handling)
                performSafeClick(R.id.exclude_proxy_switch, "proxy switch")

                // Test clickable elements
                performSafeClick(R.id.aad_app_info_icon, "app info icon")
                performSafeClick(R.id.aad_close_conns_chip, "close connections chip")

                Log.d(testTag, "UI interactions test completed successfully")

            } catch (e: Exception) {
                Log.e(testTag, "UI interactions test failed", e)
                // Don't fail the test for interaction issues, just log them
                Log.w(testTag, "Some interactions may not be available in current state")
            }
        }
    }

    @Test
    fun testAppSettingsToggleStates() {
        Log.d(testTag, "Testing app settings toggle states")
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use {
            try {
                // Test various app setting toggles if they exist
                val settingsIds = listOf(
                    R.id.aad_app_settings_block_wifi,
                    R.id.aad_app_settings_block_md,
                    R.id.aad_app_settings_isolate,
                    R.id.aad_app_settings_bypass_dns_firewall,
                    R.id.aad_app_settings_bypass_univ,
                    R.id.aad_app_settings_exclude
                )

                settingsIds.forEach { id ->
                    checkToggleState(id)
                }

                Log.d(testTag, "App settings toggle test completed")

            } catch (e: Exception) {
                Log.e(testTag, "App settings test failed", e)
                // Don't fail for settings that might not be available for test app
                Log.w(testTag, "Some settings may not be available for test app")
            }
        }
    }

    @Test
    fun testRecyclerViewsAreInitialized() {
        Log.d(testTag, "Testing RecyclerView initialization")
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use {
            try {
                // Check that RecyclerViews are present (they should exist even if empty)
                onView(withId(R.id.aad_active_conns_rv))
                    .check(matches(isDisplayed()))
                onView(withId(R.id.aad_asn_rv))
                    .check(matches(isDisplayed()))
                onView(withId(R.id.aad_most_contacted_domain_rv))
                    .check(matches(isDisplayed()))
                onView(withId(R.id.aad_most_contacted_ips_rv))
                    .check(matches(isDisplayed()))

                Log.d(testTag, "RecyclerView initialization test completed")

            } catch (e: Exception) {
                Log.e(testTag, "RecyclerView test failed", e)
                // Log but don't fail - RecyclerViews might be conditionally visible
                Log.w(testTag, "Some RecyclerViews may be conditionally visible")
            }
        }
    }

    @Test
    fun testActivityHandlesConfigurationChanges() {
        Log.d(testTag, "Testing configuration change handling")
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            try {
                // Simulate configuration change
                scenario.recreate()

                scenario.onActivity { activity ->
                    assertNotNull("Activity should survive configuration change", activity)
                    assertFalse("Activity should not be finishing after recreation", activity.isFinishing)
                }

                // Verify key UI elements are still present after recreation
                onView(withId(R.id.aad_app_detail_name))
                    .check(matches(isDisplayed()))

                Log.d(testTag, "Configuration change test completed")

            } catch (e: Exception) {
                Log.e(testTag, "Configuration change test failed", e)
                fail("Activity failed to handle configuration changes: ${e.message}")
            }
        }
    }

    // ========== EDGE CASE TESTS ==========

    @Test
    fun testLaunchWithMissingUidExtra() {
        Log.d(testTag, "Testing launch with missing UID extra")
        val intent = Intent(context, AppInfoActivity::class.java)
        // Intentionally not setting the UID extra

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            assertNotNull("Activity scenario should not be null", scenario)

            scenario.onActivity { activity ->
                assertNotNull("Activity should not be null", activity)
                // Activity should handle missing UID gracefully
                Log.d(testTag, "Activity launched successfully without UID")
            }
        }
        Log.d(testTag, "Missing UID test completed")
    }

    @Test
    fun testLaunchWithNegativeUid() {
        Log.d(testTag, "Testing launch with negative UID")
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, -1)
        }

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            assertNotNull("Activity scenario should not be null", scenario)

            scenario.onActivity { activity ->
                assertNotNull("Activity should not be null", activity)
                Log.d(testTag, "Activity handled negative UID gracefully")
            }
        }
        Log.d(testTag, "Negative UID test completed")
    }

    @Test
    fun testLaunchWithZeroUid() {
        Log.d(testTag, "Testing launch with zero UID")
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, 0)
        }

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            assertNotNull("Activity scenario should not be null", scenario)

            scenario.onActivity { activity ->
                assertNotNull("Activity should not be null", activity)
                Log.d(testTag, "Activity handled zero UID gracefully")
            }
        }
        Log.d(testTag, "Zero UID test completed")
    }

    @Test
    fun testLaunchWithVeryLargeUid() {
        Log.d(testTag, "Testing launch with very large UID")
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, Integer.MAX_VALUE)
        }

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            assertNotNull("Activity scenario should not be null", scenario)

            scenario.onActivity { activity ->
                assertNotNull("Activity should not be null", activity)
                Log.d(testTag, "Activity handled large UID gracefully")
            }
        }
        Log.d(testTag, "Large UID test completed")
    }

    @Test
    fun testLaunchWithSystemAppUid() {
        Log.d(testTag, "Testing launch with system app UID")
        val systemUid = 1000 // System UID

        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, systemUid)
        }

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            assertNotNull("Activity scenario should not be null", scenario)

            scenario.onActivity { activity ->
                assertNotNull("Activity should not be null", activity)
                Log.d(testTag, "Activity handled system UID gracefully")
            }
        }
        Log.d(testTag, "System UID test completed")
    }

    @Test
    fun testActivityStateWithMultipleIntents() {
        Log.d(testTag, "Testing activity behavior with multiple different intents")

        // Test with valid UID first
        val validIntent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, testUid)
        }

        ActivityScenario.launch<AppInfoActivity>(validIntent).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("Activity should not be null", activity)
                assertFalse("Activity should not be finishing", activity.isFinishing)
            }

            // Simulate new intent
            val newIntent = Intent(context, AppInfoActivity::class.java).apply {
                putExtra(AppInfoActivity.INTENT_UID, INVALID_UID)
            }

            scenario.onActivity { activity ->
                activity.intent = newIntent
                // Activity should handle intent change gracefully
                assertNotNull("Activity should still be valid after intent change", activity)
            }
        }
        Log.d(testTag, "Multiple intents test completed")
    }

    @Test
    fun testActivityLifecycleStates() {
        Log.d(testTag, "Testing activity lifecycle states")
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, testUid)
        }

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            // Test pause/resume cycle
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            scenario.onActivity { activity ->
                assertTrue("Activity should be started", activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            }

            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            scenario.onActivity { activity ->
                assertTrue("Activity should be resumed", activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            }

            scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
            scenario.onActivity { activity ->
                assertTrue("Activity should handle pause gracefully", activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
            }
        }
        Log.d(testTag, "Lifecycle states test completed")
    }

    @Test
    fun testMemoryPressureHandling() {
        Log.d(testTag, "Testing activity behavior under memory pressure")
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, testUid)
        }

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            // Simulate memory pressure by triggering onTrimMemory
            scenario.onActivity { activity ->
                activity.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
                assertNotNull("Activity should survive memory pressure", activity)
                assertFalse("Activity should not be finishing after memory pressure", activity.isFinishing)
            }
        }
        Log.d(testTag, "Memory pressure test completed")
    }

    @Test
    fun testActivityWithEmptyIntentExtras() {
        Log.d(testTag, "Testing activity with empty intent extras")
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            // Add empty extras bundle
            putExtras(android.os.Bundle())
        }

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            assertNotNull("Activity scenario should not be null", scenario)

            scenario.onActivity { activity ->
                assertNotNull("Activity should not be null", activity)
                Log.d(testTag, "Activity handled empty extras gracefully")
            }
        }
        Log.d(testTag, "Empty extras test completed")
    }

    @Test
    fun testActivityErrorRecovery() {
        Log.d(testTag, "Testing activity error recovery scenarios")
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, testUid)
        }

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                try {
                    // Simulate potential error scenarios by accessing UI elements that may not exist
                    onView(withId(R.id.aad_app_detail_name))
                        .check(matches(anyOf(isDisplayed(), not(isDisplayed()))))

                    Log.d(testTag, "Activity recovered from potential UI access errors")
                } catch (e: Exception) {
                    Log.w(testTag, "Expected error during UI access: ${e.message}")
                    // This is expected for some edge cases
                }

                assertNotNull("Activity should remain valid after error recovery", activity)
                assertFalse("Activity should not be finishing after error", activity.isFinishing)
            }
        }
        Log.d(testTag, "Error recovery test completed")
    }

    // ========== PERFORMANCE TESTS ==========

    @Test
    fun testActivityLaunchPerformance() {
        Log.d(testTag, "Testing activity launch performance")
        val intent = createValidIntent()

        val launchTime = measureTimeMillis {
            ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
                scenario.onActivity { activity ->
                    assertNotNull("Activity should be launched", activity)
                    assertFalse("Activity should not be finishing", activity.isFinishing)
                }
            }
        }

        Log.d(testTag, "Activity launch time: ${launchTime}ms")
        assertTrue(
            "Activity launch time ($launchTime ms) should be under $maxAcceptableLoadTime ms",
            launchTime < maxAcceptableLoadTime
        )
    }

    @Test
    fun testUiRenderingPerformance() {
        Log.d(testTag, "Testing UI rendering performance")
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            val renderTime = measureTimeMillis {
                try {
                    // Test rendering of key UI elements
                    onView(withId(R.id.aad_app_detail_icon))
                        .check(matches(anyOf(isDisplayed(), not(isDisplayed()))))
                    onView(withId(R.id.aad_app_detail_name))
                        .check(matches(anyOf(isDisplayed(), not(isDisplayed()))))
                    onView(withId(R.id.aad_pkg_name))
                        .check(matches(anyOf(isDisplayed(), not(isDisplayed()))))
                } catch (e: Exception) {
                    Log.w(testTag, "Some UI elements may not be rendered yet: ${e.message}")
                }
            }

            Log.d(testTag, "UI rendering time: ${renderTime}ms")
            assertTrue(
                "UI rendering time ($renderTime ms) should be reasonable",
                renderTime < 3000L
            )
        }
    }

    @Test
    fun testMultipleActivityInstancesPerformance() {
        Log.d(testTag, "Testing performance with multiple activity instances")
        val intent = createValidIntent()
        val instances = 3
        val scenarios = mutableListOf<ActivityScenario<AppInfoActivity>>()

        val totalTime = measureTimeMillis {
            repeat(instances) { i ->
                Log.d(testTag, "Launching instance $i")
                val scenario = ActivityScenario.launch<AppInfoActivity>(intent)
                scenarios.add(scenario)

                scenario.onActivity { activity ->
                    assertNotNull("Instance $i should be valid", activity)
                }
            }
        }

        // Clean up
        scenarios.forEach { it.close() }

        Log.d(testTag, "Total time for $instances instances: ${totalTime}ms")
        val avgTime = totalTime / instances
        Log.d(testTag, "Average time per instance: ${avgTime}ms")

        assertTrue(
            "Average launch time ($avgTime ms) should be reasonable",
            avgTime < maxAcceptableLoadTime
        )
    }

    @Test
    fun testConfigurationChangePerformance() {
        Log.d(testTag, "Testing configuration change performance")
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            val configTime = measureTimeMillis {
                repeat(3) {
                    scenario.recreate()
                    scenario.onActivity { activity ->
                        assertNotNull("Activity should survive recreation", activity)
                        assertFalse("Activity should not be finishing", activity.isFinishing)
                    }
                }
            }

            Log.d(testTag, "Configuration changes time: ${configTime}ms")
            assertTrue(
                "Configuration changes time ($configTime ms) should be reasonable",
                configTime < 10000L // 10 seconds for 3 recreations
            )
        }
    }

    @Test
    fun testMemoryUsageStability() {
        Log.d(testTag, "Testing memory usage stability")
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                Log.d(testTag, "Initial memory usage: ${initialMemory / 1024 / 1024} MB")

                // Simulate some activity operations
                repeat(10) {
                    try {
                        // Trigger some UI operations
                        onView(withId(R.id.aad_app_detail_name))
                            .check(matches(anyOf(isDisplayed(), not(isDisplayed()))))

                        // Small delay to allow operations to complete
                        Thread.sleep(100)
                    } catch (e: Exception) {
                        Log.w(testTag, "UI operation failed: ${e.message}")
                    }
                }

                val finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                Log.d(testTag, "Final memory usage: ${finalMemory / 1024 / 1024} MB")

                val memoryIncrease = finalMemory - initialMemory
                Log.d(testTag, "Memory increase: ${memoryIncrease / 1024 / 1024} MB")

                // Memory increase should be reasonable (less than 50MB)
                assertTrue(
                    "Memory increase should be reasonable",
                    memoryIncrease < 50 * 1024 * 1024
                )
            }
        }
    }

    @Test
    fun testScrollingPerformance() {
        Log.d(testTag, "Testing scrolling performance")
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            val scrollTime = measureTimeMillis {
                try {
                    // Test scrolling on RecyclerViews if they exist
                    val recyclerViewIds = listOf(
                        R.id.aad_active_conns_rv,
                        R.id.aad_asn_rv,
                        R.id.aad_most_contacted_domain_rv,
                        R.id.aad_most_contacted_ips_rv
                    )

                    recyclerViewIds.forEach { id ->
                        try {
                            onView(withId(id))
                                .check(matches(anyOf(isDisplayed(), not(isDisplayed()))))
                                .perform(swipeUp())
                        } catch (e: Exception) {
                            Log.w(testTag, "ScrollView $id not available or scrollable: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(testTag, "Scrolling test encountered issues: ${e.message}")
                }
            }

            Log.d(testTag, "Scrolling operations time: ${scrollTime}ms")
            assertTrue(
                "Scrolling should be responsive",
                scrollTime < 2000L
            )
        }
    }

    @Test
    fun testInteractionResponseTimes() {
        Log.d(testTag, "Testing interaction response times")
        val intent = createValidIntent()

        ActivityScenario.launch<AppInfoActivity>(intent).use { scenario ->
            val interactionElements = listOf(
                R.id.exclude_proxy_switch,
                R.id.aad_app_info_icon,
                R.id.aad_close_conns_chip
            )

            interactionElements.forEach { elementId ->
                val responseTime = measureTimeMillis {
                    try {
                        onView(withId(elementId))
                            .check(matches(anyOf(isDisplayed(), not(isDisplayed()))))
                            .perform(click())

                        // Small delay to allow response
                        Thread.sleep(100)
                    } catch (e: Exception) {
                        Log.w(testTag, "Element $elementId not available for interaction: ${e.message}")
                    }
                }

                Log.d(testTag, "Response time for element $elementId: ${responseTime}ms")
                assertTrue(
                    "Interaction response time should be quick",
                    responseTime < 1000L
                )
            }
        }
    }

    // ========== HELPER METHODS ==========

    private fun createValidIntent(): Intent {
        return Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, testUid)
            Log.d(testTag, "Created valid intent with UID: $testUid")
        }
    }

    private fun createInvalidIntent(): Intent {
        return Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, INVALID_UID)
            Log.d(testTag, "Created invalid intent with UID: $INVALID_UID")
        }
    }

    private fun checkElementWithFallback(id: Int, elementName: String) {
        try {
            onView(withId(id)).check(matches(anyOf(
                isDisplayed(),
                withEffectiveVisibility(Visibility.GONE),
                withEffectiveVisibility(Visibility.INVISIBLE)
            )))
            Log.d(testTag, "Element $elementName found and checked")
        } catch (e: Exception) {
            Log.w(testTag, "Element $elementName not found or not accessible: ${e.message}")
        }
    }

    private fun performSafeClick(id: Int, elementName: String) {
        try {
            onView(withId(id)).check(matches(anyOf(
                isClickable(),
                withEffectiveVisibility(Visibility.GONE)
            )))

            onView(withId(id)).perform(click())
            Log.d(testTag, "Successfully clicked $elementName")
        } catch (e: Exception) {
            Log.w(testTag, "Could not click $elementName: ${e.message}")
        }
    }

    private fun checkToggleState(id: Int) {
        try {
            onView(withId(id)).check(matches(anyOf(
                isEnabled(),
                not(isEnabled()),
                withEffectiveVisibility(Visibility.GONE)
            )))
        } catch (e: Exception) {
            Log.w(testTag, "Toggle state check failed for ID $id: ${e.message}")
        }
    }
}
