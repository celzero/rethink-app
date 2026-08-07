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
import androidx.work.WorkManager
import com.celzero.bravedns.database.AppInfoRepository
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TempAllowExpiryWorkerTest {

    private lateinit var context: Context
    private val repo: AppInfoRepository = mockk()

    @Before
    fun setup() {
        clearAllMocks()
        context = RuntimeEnvironment.getApplication()
        try {
            stopKoin()
        } catch (_: Exception) {
        }
        startKoin { modules(module { single { repo } }) }
    }

    @After
    fun tearDown() {
        try {
            stopKoin()
        } catch (_: Exception) {
        }
    }

    @Test
    fun `scheduleNext cancels unique work when repo returns null`() {
        every { repo.getNearestTempAllowExpiryBlocking(any()) } returns null

        mockkObject(WorkManager.Companion)
        val wm = mockk<WorkManager>(relaxed = true)
        every { WorkManager.getInstance(context) } returns wm

        TempAllowExpiryWorker.scheduleNext(context)

        verify { wm.cancelUniqueWork("fw_temp_allow_expiry") }
        unmockkObject(WorkManager.Companion)
    }

    @Test
    fun `scheduleNext enqueues work when repo returns future expiry`() {
        every { repo.getNearestTempAllowExpiryBlocking(any()) } returns System.currentTimeMillis() + 60_000L

        mockkObject(WorkManager.Companion)
        val wm = mockk<WorkManager>(relaxed = true)
        every { WorkManager.getInstance(context) } returns wm

        TempAllowExpiryWorker.scheduleNext(context)

        verify {
            wm.enqueueUniqueWork(
                "fw_temp_allow_expiry",
                androidx.work.ExistingWorkPolicy.REPLACE,
                any<androidx.work.OneTimeWorkRequest>()
            )
        }
        unmockkObject(WorkManager.Companion)
    }
}
