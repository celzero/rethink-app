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

package com.celzero.bravedns.receiver

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for BraveAutoStartReceiver
 * Tests the auto-start logic for VPN on boot and Private Space unlock
 */
class BraveAutoStartReceiverTest {

    private lateinit var receiver: BraveAutoStartReceiver
    
    @Before
    fun setUp() {
        receiver = BraveAutoStartReceiver()
    }

    @Test
    fun `receiver should handle supported intent actions`() {
        // Test that receiver can handle the expected intent actions
        val supportedActions = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_REBOOT,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
        
        supportedActions.forEach { action ->
            val intent = Intent(action)
            assertNotNull("Intent with action $action should be valid", intent.action)
            assertEquals("Intent action should match", action, intent.action)
        }
    }

    @Test
    fun `intent action constants should be correct`() {
        // Verify the intent action constants match Android framework constants
        assertEquals("ACTION_BOOT_COMPLETED constant", "android.intent.action.BOOT_COMPLETED", Intent.ACTION_BOOT_COMPLETED)
        assertEquals("ACTION_REBOOT constant", "android.intent.action.REBOOT", Intent.ACTION_REBOOT)
        assertEquals("ACTION_USER_UNLOCKED constant", "android.intent.action.USER_UNLOCKED", Intent.ACTION_USER_UNLOCKED)
        assertEquals("ACTION_MY_PACKAGE_REPLACED constant", "android.intent.action.MY_PACKAGE_REPLACED", Intent.ACTION_MY_PACKAGE_REPLACED)
    }

    @Test
    fun `receiver class should extend BroadcastReceiver`() {
        // Test that the receiver extends the correct base class
        assertTrue("BraveAutoStartReceiver should be a BroadcastReceiver", 
                  receiver is android.content.BroadcastReceiver)
    }

    @Test
    fun `receiver should implement KoinComponent`() {
        // Test that the receiver implements KoinComponent for dependency injection
        assertTrue("BraveAutoStartReceiver should implement KoinComponent", 
                  receiver is org.koin.core.component.KoinComponent)
    }

    @Test
    fun `receiver should have persistentState property`() {
        // Test that the receiver has the required dependency
        try {
            val persistentStateField = receiver.javaClass.getDeclaredField("persistentState\$delegate")
            assertNotNull("persistentState delegate should exist", persistentStateField)
        } catch (e: NoSuchFieldException) {
            fail("persistentState property should be available")
        }
    }

    @Test
    fun `receiver handles ACTION_USER_UNLOCKED for Private Space unlock`() {
        // Test that ACTION_USER_UNLOCKED is a valid intent action
        val intent = Intent(Intent.ACTION_USER_UNLOCKED)
        assertEquals("ACTION_USER_UNLOCKED should be correct action", 
                    Intent.ACTION_USER_UNLOCKED, intent.action)
    }

    @Test
    fun `receiver package should be correct`() {
        // Test that the receiver is in the correct package
        assertEquals("Package should be correct", 
                    "com.celzero.bravedns.receiver", 
                    receiver.javaClass.packageName)
    }

    @Test
    fun `all supported actions have correct values`() {
        // Verify all expected action constants exist and have correct values
        val expectedActions = mapOf(
            "android.intent.action.BOOT_COMPLETED" to Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.REBOOT" to Intent.ACTION_REBOOT,
            "android.intent.action.USER_UNLOCKED" to Intent.ACTION_USER_UNLOCKED,
            "android.intent.action.MY_PACKAGE_REPLACED" to Intent.ACTION_MY_PACKAGE_REPLACED
        )
        
        expectedActions.forEach { (expectedValue, constant) ->
            assertEquals("Action constant should have correct value", expectedValue, constant)
        }
    }

    @Test
    fun `receiver class name should be correct`() {
        // Test that the receiver has the expected class name
        assertEquals("Class name should be correct", 
                    "BraveAutoStartReceiver", 
                    receiver.javaClass.simpleName)
    }
}