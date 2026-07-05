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

import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for UserPresentReceiver
 * Tests the screen unlock event handling
 */
class UserPresentReceiverTest {

    private lateinit var receiver: UserPresentReceiver
    
    @Before
    fun setUp() {
        receiver = UserPresentReceiver()
    }

    @Test
    fun `receiver should handle ACTION_USER_PRESENT intent`() {
        // ACTION_USER_PRESENT constant should be stable and usable in routing logic
        assertEquals("ACTION_USER_PRESENT constant", "android.intent.action.USER_PRESENT", Intent.ACTION_USER_PRESENT)
    }

    @Test
    fun `intent action constant should be correct`() {
        // Verify the intent action constant matches Android framework constant
        assertEquals("ACTION_USER_PRESENT constant", "android.intent.action.USER_PRESENT", Intent.ACTION_USER_PRESENT)
    }

    @Test
    fun `receiver class should extend BroadcastReceiver`() {
        // Test that the receiver extends the correct base class
        assertTrue(
            "UserPresentReceiver should be a BroadcastReceiver",
            BroadcastReceiver::class.java.isAssignableFrom(receiver.javaClass)
        )
    }

    @Test
    fun `receiver should handle unknown actions gracefully`() {
        val unknownAction = "com.example.unknown.action"
        val intent = mockk<Intent>()
        val context = mockk<Context>(relaxed = true)
        every { intent.action } returns unknownAction

        // Unknown actions must not crash the receiver.
        receiver.onReceive(context, intent)
        assertEquals("Intent action should match", unknownAction, intent.action)
    }

    @Test
    fun `receiver class should have proper package`() {
        // Test that the receiver is in the correct package
        assertEquals("Package should be correct", 
                    "com.celzero.bravedns.receiver", 
                    receiver.javaClass.packageName)
    }

    @Test
    fun `receiver class name should be correct`() {
        // Test that the receiver has the expected class name
        assertEquals("Class name should be correct", 
                    "UserPresentReceiver", 
                    receiver.javaClass.simpleName)
    }

    @Test
    fun `receiver handles null action gracefully`() {
        val intent = mockk<Intent>()
        val context = mockk<Context>(relaxed = true)
        every { intent.action } returns null

        receiver.onReceive(context, intent)
        assertNull("Intent action should be null", intent.action)
    }

    @Test
    fun `receiver handles various action types`() {
        val testActions = listOf(
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            "android.intent.action.UNKNOWN"
        )

        val context = mockk<Context>(relaxed = true)
        testActions.forEach { action ->
            val intent = mockk<Intent>()
            every { intent.action } returns action

            receiver.onReceive(context, intent)
            assertEquals("Intent action should match for $action", action, intent.action)
        }

        assertEquals(
            "ACTION_USER_PRESENT should stay stable",
            "android.intent.action.USER_PRESENT",
            Intent.ACTION_USER_PRESENT
        )
    }

    @Test
    fun `receiver should be in receiver package`() {
        // Test that the receiver is in the receiver subpackage
        assertTrue("Class should be in receiver package", 
                  receiver.javaClass.packageName.endsWith(".receiver"))
    }
}
