/*
 * Copyright 2024 RethinkDNS and its authors
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
package com.celzero.bravedns.download

import android.app.DownloadManager
import androidx.test.core.app.ApplicationProvider
import com.celzero.bravedns.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadWatcherInterpreterTest {

    @Test
    fun testClassify_successful() {
        assertEquals(
            DownloadWatcher.Interpreter.SUCCESS,
            DownloadWatcher.Interpreter.classify(DownloadManager.STATUS_SUCCESSFUL, 0)
        )
    }

    @Test
    fun testClassify_failed() {
        assertEquals(
            DownloadWatcher.Interpreter.FAILURE,
            DownloadWatcher.Interpreter.classify(DownloadManager.STATUS_FAILED, 0)
        )
    }

    @Test
    fun testClassify_inFlightAreContinue() {
        val inFlight =
            listOf(
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED
            )
        inFlight.forEach { status ->
            assertEquals(
                "status $status should be CONTINUE",
                DownloadWatcher.Interpreter.CONTINUE,
                DownloadWatcher.Interpreter.classify(status, 0)
            )
        }
    }

    @Test
    fun testClassify_unknownStatusFailsFast() {
        // Any status that is not one of the known constants (including 0 or -1) must be
        // treated as a terminal FAILURE so the Worker cannot retry forever.
        listOf(0, -1, 99, 12345).forEach { status ->
            assertEquals(
                "unknown status $status should be FAILURE",
                DownloadWatcher.Interpreter.FAILURE,
                DownloadWatcher.Interpreter.classify(status, 0)
            )
        }
    }

    @Test
    fun testReasonToResId_networkErrors() {
        val networkReasons =
            listOf(
                DownloadManager.ERROR_CANNOT_RESUME,
                DownloadManager.ERROR_HTTP_DATA_ERROR,
                DownloadManager.ERROR_TOO_MANY_REDIRECTS,
                DownloadManager.ERROR_UNHANDLED_HTTP_CODE
            )
        networkReasons.forEach { reason ->
            assertEquals(
                "reason $reason should map to network error",
                R.string.download_err_network,
                DownloadWatcher.Interpreter.reasonToResId(reason)
            )
        }
    }

    @Test
    fun testReasonToResId_storageErrors() {
        val storageReasons =
            listOf(
                DownloadManager.ERROR_FILE_ALREADY_EXISTS,
                DownloadManager.ERROR_FILE_ERROR,
                DownloadManager.ERROR_INSUFFICIENT_SPACE
            )
        storageReasons.forEach { reason ->
            assertEquals(
                "reason $reason should map to storage error",
                R.string.download_err_storage,
                DownloadWatcher.Interpreter.reasonToResId(reason)
            )
        }
    }

    @Test
    fun testReasonToResId_systemManager() {
        assertEquals(
            R.string.download_err_system_manager,
            DownloadWatcher.Interpreter.reasonToResId(DownloadManager.ERROR_DEVICE_NOT_FOUND)
        )
    }

    @Test
    fun testReasonToResId_unknownDefaultsToInternal() {
        assertEquals(
            R.string.download_err_internal,
            DownloadWatcher.Interpreter.reasonToResId(0)
        )
        assertEquals(
            R.string.download_err_internal,
            DownloadWatcher.Interpreter.reasonToResId(DownloadManager.ERROR_UNKNOWN)
        )
    }

    @Test
    fun testResIdsResolve() {
        // Ensure the resource ids referenced by the interpreter are valid (compiled) resources.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(
            ctx.getString(R.string.download_err_network),
            ctx.getString(DownloadWatcher.Interpreter.reasonToResId(DownloadManager.ERROR_HTTP_DATA_ERROR))
        )
    }
}
