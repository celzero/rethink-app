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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPackageManager

/**
 * Unit tests for AppInfoActivity that run without requiring a device or emulator.
 * These tests use Robolectric for Android framework mocking.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28]) // Target Android API 28
class AppInfoActivityUnitTest {

    private lateinit var context: Context
    private lateinit var shadowPackageManager: ShadowPackageManager
    private val testTag = "AppInfoActivityUnitTest"

    @Before
    fun setUp() {
        println("$testTag: Setting up AppInfoActivityUnitTest environment")
        
        context = RuntimeEnvironment.getApplication()
        println("$testTag: Created Robolectric context: ${context.javaClass.simpleName}")
        
        shadowPackageManager = shadowOf(context.packageManager)
        println("$testTag: Created shadow package manager: ${shadowPackageManager.javaClass.simpleName}")

        // Set up test package info using Robolectric shadows
        val mockPackageInfo = PackageInfo().apply {
            packageName = "com.test.package"
            applicationInfo = ApplicationInfo().apply {
                uid = 12345
                packageName = "com.test.package"
            }
        }

        // Add the package info to the shadow package manager
        shadowPackageManager.addPackage(mockPackageInfo)
        println("$testTag: Added test package: ${mockPackageInfo.packageName} with UID: ${mockPackageInfo.applicationInfo?.uid}")
        
        println("$testTag: Test setup completed successfully")
    }

    @Test
    fun testIntentCreationWithValidUid() {
        println("$testTag: Starting testIntentCreationWithValidUid")
        
        val testUid = 12345
        println("$testTag: Creating intent with valid UID: $testUid")
        
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, testUid)
        }
        
        println("$testTag: Intent created successfully")
        println("$testTag: Intent component: ${intent.component}")
        println("$testTag: Intent UID extra: ${intent.getIntExtra(AppInfoActivity.INTENT_UID, INVALID_UID)}")

        assertEquals("Intent should contain correct UID", testUid, intent.getIntExtra(AppInfoActivity.INTENT_UID, INVALID_UID))
        assertEquals("Intent should target AppInfoActivity", AppInfoActivity::class.java.name, intent.component?.className)
        
        println("$testTag: testIntentCreationWithValidUid completed successfully")
    }

    @Test
    fun testIntentCreationWithInvalidUid() {
        println("$testTag: Starting testIntentCreationWithInvalidUid")
        
        println("$testTag: Creating intent with invalid UID: $INVALID_UID")
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, INVALID_UID)
        }
        
        val extractedUid = intent.getIntExtra(AppInfoActivity.INTENT_UID, 0)
        println("$testTag: Extracted UID from intent: $extractedUid")

        assertEquals("Intent should contain invalid UID", INVALID_UID, extractedUid)
        
        println("$testTag: testIntentCreationWithInvalidUid completed successfully")
    }

    @Test
    fun testIntentCreationWithoutUid() {
        println("$testTag: Starting testIntentCreationWithoutUid")
        
        println("$testTag: Creating intent without UID parameter")
        val intent = Intent(context, AppInfoActivity::class.java)
        
        val extractedUid = intent.getIntExtra(AppInfoActivity.INTENT_UID, INVALID_UID)
        println("$testTag: Extracted UID from intent (should be default): $extractedUid")

        assertEquals("Intent without UID should return default value", INVALID_UID, extractedUid)
        
        println("$testTag: testIntentCreationWithoutUid completed successfully")
    }

    @Test
    fun testIntentWithExtraParameters() {
        println("$testTag: Starting testIntentWithExtraParameters")
        
        val testUid = 12345
        val testActiveConns = true
        val testAsn = "AS12345"
        
        println("$testTag: Creating intent with multiple parameters:")
        println("$testTag:   - UID: $testUid")
        println("$testTag:   - Active Connections: $testActiveConns")
        println("$testTag:   - ASN: $testAsn")

        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, testUid)
            putExtra(AppInfoActivity.INTENT_ACTIVE_CONNS, testActiveConns)
            putExtra(AppInfoActivity.INTENT_ASN, testAsn)
        }
        
        val extractedUid = intent.getIntExtra(AppInfoActivity.INTENT_UID, INVALID_UID)
        val extractedActiveConns = intent.getBooleanExtra(AppInfoActivity.INTENT_ACTIVE_CONNS, false)
        val extractedAsn = intent.getStringExtra(AppInfoActivity.INTENT_ASN)
        
        println("$testTag: Extracted parameters:")
        println("$testTag:   - UID: $extractedUid")
        println("$testTag:   - Active Connections: $extractedActiveConns")
        println("$testTag:   - ASN: $extractedAsn")

        assertEquals("UID should match", testUid, extractedUid)
        assertEquals("Active connections flag should match", testActiveConns, extractedActiveConns)
        assertEquals("ASN should match", testAsn, extractedAsn)
        
        println("$testTag: testIntentWithExtraParameters completed successfully")
    }

    @Test
    fun testEdgeCaseUidValues() {
        println("$testTag: Starting testEdgeCaseUidValues")
        
        val edgeCaseUids = listOf(
            -1,                    // Negative UID
            0,                     // Zero UID
            1,                     // Minimum valid UID
            1000,                  // System UID
            10000,                 // First user app UID
            Integer.MAX_VALUE,     // Maximum integer value
            INVALID_UID           // Defined invalid UID constant
        )
        
        println("$testTag: Testing ${edgeCaseUids.size} edge case UID values: $edgeCaseUids")

        edgeCaseUids.forEach { uid ->
            println("$testTag: Testing UID: $uid")
            
            val intent = Intent(context, AppInfoActivity::class.java).apply {
                putExtra(AppInfoActivity.INTENT_UID, uid)
            }

            val extractedUid = intent.getIntExtra(AppInfoActivity.INTENT_UID, -999)
            println("$testTag:   - Expected: $uid, Extracted: $extractedUid")

            assertEquals("UID $uid should be preserved in intent", uid, extractedUid)
        }
        
        println("$testTag: All edge case UIDs tested successfully")
        println("$testTag: testEdgeCaseUidValues completed successfully")
    }

    @Test
    fun testIntentComponentResolution() {
        println("$testTag: Starting testIntentComponentResolution")
        
        println("$testTag: Creating intent and testing component resolution")
        val intent = Intent(context, AppInfoActivity::class.java)
        
        val component = intent.component
        println("$testTag: Intent component: $component")
        println("$testTag: Component package name: ${component?.packageName}")
        println("$testTag: Component class name: ${component?.className}")
        println("$testTag: Context package name: ${context.packageName}")

        assertNotNull("Intent component should not be null", component)
        assertEquals("Package name should match context", context.packageName, component?.packageName)
        assertEquals("Class name should match AppInfoActivity", AppInfoActivity::class.java.name, component?.className)
        
        println("$testTag: testIntentComponentResolution completed successfully")
    }

    @Test
    fun testIntentFlags() {
        println("$testTag: Starting testIntentFlags")
        
        println("$testTag: Creating intent and adding flags")
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        
        val flags = intent.flags
        println("$testTag: Intent flags: $flags")
        println("$testTag: NEW_TASK flag: ${Intent.FLAG_ACTIVITY_NEW_TASK}")
        println("$testTag: CLEAR_TOP flag: ${Intent.FLAG_ACTIVITY_CLEAR_TOP}")
        println("$testTag: Has NEW_TASK: ${flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0}")
        println("$testTag: Has CLEAR_TOP: ${flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0}")

        assertTrue("Intent should have NEW_TASK flag", flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue("Intent should have CLEAR_TOP flag", flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        
        println("$testTag: testIntentFlags completed successfully")
    }

    @Test
    fun testIntentDataIntegrity() {
        println("$testTag: Starting testIntentDataIntegrity")
        
        val testUid = 54321
        println("$testTag: Creating intent with UID: $testUid")
        
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, testUid)
        }

        // Simulate intent being passed through system
        val bundle = intent.extras
        println("$testTag: Intent extras bundle: $bundle")
        println("$testTag: Bundle keys: ${bundle?.keySet()}")

        assertNotNull("Intent extras should not be null", bundle)

        val extractedUid = bundle?.getInt(AppInfoActivity.INTENT_UID, INVALID_UID)
        println("$testTag: Extracted UID from bundle: $extractedUid")
        
        assertEquals("UID should remain intact through bundle operations", testUid, extractedUid)
        
        println("$testTag: testIntentDataIntegrity completed successfully")
    }

    @Test
    fun testMultipleIntentCreation() {
        println("$testTag: Starting testMultipleIntentCreation")
        
        val uids = listOf(1001, 1002, 1003, 1004, 1005)
        val intents = mutableListOf<Intent>()
        
        println("$testTag: Creating ${uids.size} intents with UIDs: $uids")

        uids.forEach { uid ->
            println("$testTag: Creating intent with UID: $uid")
            val intent = Intent(context, AppInfoActivity::class.java).apply {
                putExtra(AppInfoActivity.INTENT_UID, uid)
            }
            intents.add(intent)
        }
        
        println("$testTag: Created ${intents.size} intents successfully")

        assertEquals("Should create correct number of intents", uids.size, intents.size)

        intents.forEachIndexed { index, intent ->
            val extractedUid = intent.getIntExtra(AppInfoActivity.INTENT_UID, INVALID_UID)
            println("$testTag: Intent $index - Expected UID: ${uids[index]}, Extracted UID: $extractedUid")
            assertEquals("Intent $index should have correct UID", uids[index], extractedUid)
        }
        
        println("$testTag: testMultipleIntentCreation completed successfully")
    }

    @Test
    fun testIntentSerialization() {
        println("$testTag: Starting testIntentSerialization")
        
        val testUid = 99999
        println("$testTag: Creating original intent with UID: $testUid")
        
        val originalIntent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, testUid)
            putExtra("test_string", "test_value")
            putExtra("test_boolean", true)
        }
        
        println("$testTag: Original intent created with extras: ${originalIntent.extras?.keySet()}")

        // Simulate intent serialization/deserialization through bundle
        val bundle = originalIntent.extras
        println("$testTag: Extracted bundle from original intent")
        
        val recreatedIntent = Intent(context, AppInfoActivity::class.java).apply {
            if (bundle != null) {
                putExtras(bundle)
            }
        }
        
        println("$testTag: Recreated intent from bundle")
        
        val recreatedUid = recreatedIntent.getIntExtra(AppInfoActivity.INTENT_UID, INVALID_UID)
        val recreatedString = recreatedIntent.getStringExtra("test_string")
        val recreatedBoolean = recreatedIntent.getBooleanExtra("test_boolean", false)
        
        println("$testTag: Recreated intent values:")
        println("$testTag:   - UID: $recreatedUid (original: $testUid)")
        println("$testTag:   - String: $recreatedString (original: test_value)")
        println("$testTag:   - Boolean: $recreatedBoolean (original: true)")

        assertEquals("UID should survive serialization", testUid, recreatedUid)
        assertEquals("String extra should survive serialization", "test_value", recreatedString)
        assertEquals("Boolean extra should survive serialization", true, recreatedBoolean)
        
        println("$testTag: testIntentSerialization completed successfully")
    }

    @Test
    fun testConstantValues() {
        println("$testTag: Starting testConstantValues")
        
        println("$testTag: Verifying AppInfoActivity constants:")
        println("$testTag:   - INTENT_UID: '${AppInfoActivity.INTENT_UID}'")
        println("$testTag:   - INTENT_ACTIVE_CONNS: '${AppInfoActivity.INTENT_ACTIVE_CONNS}'")
        println("$testTag:   - INTENT_ASN: '${AppInfoActivity.INTENT_ASN}'")

        // Verify that the constants are properly defined and accessible
        assertNotNull("INTENT_UID constant should be defined", AppInfoActivity.INTENT_UID)
        assertNotNull("INTENT_ACTIVE_CONNS constant should be defined", AppInfoActivity.INTENT_ACTIVE_CONNS)
        assertNotNull("INTENT_ASN constant should be defined", AppInfoActivity.INTENT_ASN)

        // Test that constants are strings (for intent extras)
        assertTrue("INTENT_UID should be a non-empty string", AppInfoActivity.INTENT_UID.isNotEmpty())
        assertTrue("INTENT_ACTIVE_CONNS should be a non-empty string", AppInfoActivity.INTENT_ACTIVE_CONNS.isNotEmpty())
        assertTrue("INTENT_ASN should be a non-empty string", AppInfoActivity.INTENT_ASN.isNotEmpty())
        
        println("$testTag: All constants verified successfully")
        println("$testTag: testConstantValues completed successfully")
    }

    @Test
    fun testInvalidUidConstant() {
        println("$testTag: Starting testInvalidUidConstant")
        
        println("$testTag: Testing INVALID_UID constant: $INVALID_UID")
        
        // Verify INVALID_UID constant behavior
        val intent = Intent(context, AppInfoActivity::class.java).apply {
            putExtra(AppInfoActivity.INTENT_UID, INVALID_UID)
        }
        
        val extractedUid = intent.getIntExtra(AppInfoActivity.INTENT_UID, -999)
        println("$testTag: Extracted INVALID_UID from intent: $extractedUid")
        println("$testTag: INVALID_UID <= 0: ${INVALID_UID <= 0}")
        
        assertEquals("INVALID_UID should be preserved", INVALID_UID, extractedUid)
        assertTrue("INVALID_UID should be a negative value or zero", INVALID_UID <= 0)
        
        println("$testTag: testInvalidUidConstant completed successfully")
    }

    @Test
    fun testPerformanceWithLargeDataSets() {
        println("$testTag: Starting testPerformanceWithLargeDataSets")
        
        val intentCount = 1000
        println("$testTag: Performance test: creating $intentCount intents")
        
        val startTime = System.currentTimeMillis()

        // Create 1000 intents to test performance
        repeat(intentCount) { index ->
            val uid = index + 10000
            val intent = Intent(context, AppInfoActivity::class.java).apply {
                putExtra(AppInfoActivity.INTENT_UID, uid)
                putExtra("index", index)
            }

            // Verify the intent was created correctly
            val extractedUid = intent.getIntExtra(AppInfoActivity.INTENT_UID, INVALID_UID)
            assertEquals("Intent should have correct UID", uid, extractedUid)
            
            if (index % 200 == 0) {
                println("$testTag: Created ${index + 1} intents so far...")
            }
        }

        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        println("$testTag: Performance results:")
        println("$testTag:   - Total intents created: $intentCount")
        println("$testTag:   - Total time: ${duration}ms")
        println("$testTag:   - Average time per intent: ${duration.toFloat() / intentCount}ms")

        assertTrue("Intent creation should be fast (< 1 second for 1000 intents)", duration < 1000)
        println("Created 1000 intents in ${duration}ms")
        
        println("$testTag: testPerformanceWithLargeDataSets completed successfully")
    }

    @Test
    fun testMemoryEfficiency() {
        println("$testTag: Starting testMemoryEfficiency")
        
        val initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        println("$testTag: Initial memory usage: ${initialMemory / 1024 / 1024} MB")
        
        val intents = mutableListOf<Intent>()
        val intentCount = 100

        println("$testTag: Creating $intentCount intents with 1KB data each")

        // Create many intents and measure memory usage
        repeat(intentCount) { index ->
            val intent = Intent(context, AppInfoActivity::class.java).apply {
                putExtra(AppInfoActivity.INTENT_UID, index + 20000)
                putExtra("large_data", "x".repeat(1000)) // 1KB string per intent
            }
            intents.add(intent)

            if (index % 20 == 0) {
                println("$testTag: Created ${index + 1} intents...")
            }
        }

        val finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val memoryIncrease = finalMemory - initialMemory

        println("$testTag: Memory usage results:")
        println("$testTag:   - Final memory usage: ${finalMemory / 1024 / 1024} MB")
        println("$testTag:   - Memory increase: ${memoryIncrease / 1024 / 1024} MB")
        println("$testTag:   - Memory per intent: ${memoryIncrease / intentCount} bytes")

        // Memory increase should be reasonable (less than 10MB for 100 intents with 1KB each)
        assertTrue("Memory usage should be reasonable", memoryIncrease < 10 * 1024 * 1024)

        // Clean up
        intents.clear()
        System.gc()

        println("$testTag: Memory cleanup completed")
        println("$testTag: testMemoryEfficiency completed successfully")
    }

    @Test
    fun testRobolectricPackageManagerIntegration() {
        println("$testTag: Starting testRobolectricPackageManagerIntegration")

        // Test that we can properly interact with Robolectric's package manager
        val packageManager = context.packageManager
        println("$testTag: Package manager: ${packageManager.javaClass.simpleName}")

        assertNotNull("Package manager should not be null", packageManager)

        try {
            println("$testTag: Attempting to get package info for: com.test.package")
            val packageInfo = packageManager.getPackageInfo("com.test.package", 0)

            println("$testTag: Package info retrieved successfully:")
            println("$testTag:   - Package name: ${packageInfo.packageName}")
            println("$testTag:   - Application info: ${packageInfo.applicationInfo}")
            println("$testTag:   - UID: ${packageInfo.applicationInfo?.uid}")

            assertNotNull("Package info should be available", packageInfo)
            assertEquals("Package name should match", "com.test.package", packageInfo.packageName)
            assertNotNull("Application info should be available", packageInfo.applicationInfo)
            assertEquals("UID should match", 12345, packageInfo.applicationInfo?.uid)

        } catch (e: PackageManager.NameNotFoundException) {
            println("$testTag: ERROR - Package not found: ${e.message}")
            fail("Test package should be available in shadow package manager")
        }

        println("$testTag: testRobolectricPackageManagerIntegration completed successfully")
    }

    @Test
    fun testContextPackageNameAccess() {
        println("$testTag: Starting testContextPackageNameAccess")

        // Test that we can access the context's package name
        val packageName = context.packageName
        println("$testTag: Context package name: '$packageName'")

        assertNotNull("Context package name should not be null", packageName)
        assertTrue("Context package name should not be empty", packageName.isNotEmpty())

        // Create intent and verify component resolution works
        println("$testTag: Testing intent component resolution")
        val intent = Intent(context, AppInfoActivity::class.java)
        val component = intent.component

        println("$testTag: Intent component: $component")
        println("$testTag: Component resolvable: ${component != null}")

        assertNotNull("Intent component should be resolvable", component)

        println("$testTag: testContextPackageNameAccess completed successfully")
    }
}
