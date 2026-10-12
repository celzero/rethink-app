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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Tests for [WgAutomationNotifier]'s busy-ness guarantees using the real
 * (shadowed) NotificationManager:
 *  - a fully successful command clears the (single) failure notification
 *  - a blocked channel or disabled notifications never post
 *
 * Note: this repo's Robolectric setup does not package Android resources
 * (see TaskerAutomationBottomSheetTest), so the actual post path cannot run
 * here; these tests cover everything around it. The never-stacks guarantee
 * itself is structural: every post uses the single fixed
 * ([WgAutomationNotifier.NOTIF_TAG], [WgAutomationNotifier.NOTIF_ID]) key and
 * NotificationManager replaces same-key notifications.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WgAutomationNotifierTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val nm =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun setup() {
        mockkObject(VpnController)
        mockkObject(WireguardManager)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun failure(id: Int) = WgCommands.Failure(id, WgCommandError.VPN_OFF)

    @Test
    fun `successful command clears a previous failure notification`() {
        // seed the notification the same way the notifier would post it
        nm.notify(WgAutomationNotifier.NOTIF_TAG, WgAutomationNotifier.NOTIF_ID, Notification())
        assertNotNull(
            shadowOf(nm).getNotification(WgAutomationNotifier.NOTIF_TAG, WgAutomationNotifier.NOTIF_ID)
        )

        WgAutomationNotifier.onCommandResult(
            context,
            VpnControlReceiver.ACTION_WG_PAUSE,
            total = 1,
            failures = emptyList()
        )

        assertNull(
            "failure notification must be cleared on success",
            shadowOf(nm).getNotification(WgAutomationNotifier.NOTIF_TAG, WgAutomationNotifier.NOTIF_ID)
        )
    }

    @Test
    fun `blocked channel never posts`() {
        // user silenced the channel: importance NONE
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "wg automation", NotificationManager.IMPORTANCE_NONE)
        )

        WgAutomationNotifier.onCommandResult(
            context,
            VpnControlReceiver.ACTION_WG_PAUSE,
            total = 1,
            failures = listOf(failure(1))
        )

        assertEquals(
            "no notification may be posted while the channel is blocked",
            0,
            shadowOf(nm).size()
        )
    }

    @Test
    fun `disabled notifications never post`() {
        shadowOf(nm).setNotificationsEnabled(false)

        WgAutomationNotifier.onCommandResult(
            context,
            VpnControlReceiver.ACTION_WG_PAUSE,
            total = 1,
            failures = listOf(failure(1))
        )

        assertEquals(
            "no notification may be posted while notifications are disabled",
            0,
            shadowOf(nm).size()
        )
    }

    @Test
    fun `failure notification identity is a single fixed tag and id`() {
        // notify(tag, id) replaces same-key notifications, so a constant
        // identity is what guarantees "never more than one notification"
        assertEquals("wg_automation", WgAutomationNotifier.NOTIF_TAG)
        assertEquals(4242, WgAutomationNotifier.NOTIF_ID)
    }

    companion object {
        // mirrors WgAutomationNotifier's private channel id
        private const val CHANNEL_ID = "wg_automation_alerts"
    }
}
