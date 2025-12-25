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
import androidx.test.core.app.ApplicationProvider
import com.celzero.bravedns.database.AppInfoRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class TempAllowExpiryWorkerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `scheduleNext cancels unique work when repo returns null`() {
        val repo = mockk<AppInfoRepository>()
        every { repo.getNearestTempAllowExpiryBlocking(any()) } returns null

        startKoin { modules(module { single { repo } }) }

        mockkObject(androidx.work.WorkManager)
        val wm = mockk<androidx.work.WorkManager>(relaxed = true)
        every { androidx.work.WorkManager.getInstance(any()) } returns wm

        TempAllowExpiryWorker.scheduleNext(context)

        verify { wm.cancelUniqueWork("fw_temp_allow_expiry") }
    }

    @Test
    fun `scheduleNext enqueues work when repo returns future expiry`() {
        val repo = mockk<AppInfoRepository>()
        val now = System.currentTimeMillis()
        every { repo.getNearestTempAllowExpiryBlocking(any()) } returns (now + 60_000L)

        startKoin { modules(module { single { repo } }) }

        mockkObject(androidx.work.WorkManager)
        val wm = mockk<androidx.work.WorkManager>(relaxed = true)
        every { androidx.work.WorkManager.getInstance(any()) } returns wm

        TempAllowExpiryWorker.scheduleNext(context)

        verify {
            wm.enqueueUniqueWork(
                "fw_temp_allow_expiry",
                androidx.work.ExistingWorkPolicy.REPLACE,
                any<androidx.work.OneTimeWorkRequest>()
            )
        }
    }
}
