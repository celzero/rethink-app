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
package com.celzero.bravedns.service

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class TempAllowExpiryWorkerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        runCatching { unmockkObject(androidx.work.WorkManager) }
    }

    @Test
    fun `cancel cancels unique work`() {
        mockkObject(androidx.work.WorkManager)
        val wm = mockk<androidx.work.WorkManager>(relaxed = true)
        every { androidx.work.WorkManager.getInstance(any()) } returns wm

        TempAllowExpiryWorker.cancel(context)

        verify { wm.cancelUniqueWork("fw_temp_allow_expiry") }
    }

    @Test
    fun `enqueueAt enqueues unique work request`() {
        mockkObject(androidx.work.WorkManager)
        val wm = mockk<androidx.work.WorkManager>(relaxed = true)
        every { androidx.work.WorkManager.getInstance(any()) } returns wm

        TempAllowExpiryWorker.enqueueAt(context, System.currentTimeMillis() + 60_000L)

        verify {
            wm.enqueueUniqueWork(
                "fw_temp_allow_expiry",
                androidx.work.ExistingWorkPolicy.REPLACE,
                any<androidx.work.OneTimeWorkRequest>()
            )
        }
    }
}
