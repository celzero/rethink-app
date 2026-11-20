/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.util

import android.app.Notification
import android.app.NotificationManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class NotificationExtTest {

    @Test
    fun `notifySafely delegates to notify with tag and id`() {
        val nm = mockk<NotificationManager>(relaxed = true)
        val notif = Notification()

        nm.notifySafely("tag", 7, notif)

        verify(exactly = 1) { nm.notify("tag", 7, notif) }
    }

    @Test
    fun `notifySafely id-only variant posts with null tag`() {
        val nm = mockk<NotificationManager>(relaxed = true)
        val notif = Notification()

        nm.notifySafely(7, notif)

        verify(exactly = 1) { nm.notify(null, 7, notif) }
    }

    @Test
    fun `notifySafely swallows system-server enqueue exceptions`() {
        val nm = mockk<NotificationManager>()
        every { nm.notify(any(), any(), any<Notification>()) } throws
            IllegalStateException("Existing wake lock uid changed: 10210 to 10203")

        nm.notifySafely("tag", 1, Notification())

        verify(exactly = 1) { nm.notify(any(), any(), any<Notification>()) }
    }
}
