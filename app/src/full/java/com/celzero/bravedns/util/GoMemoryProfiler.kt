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

import android.content.Context
import android.os.Build
import android.os.FileObserver
import androidx.annotation.RequiresApi
import com.celzero.bravedns.service.VpnController
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

object GoMemoryProfiler {

    private const val MEM_PROFILE_DIR = "mem_profile"
    private const val FILE_PREFIX = "go_mem_"
    private const val FILE_EXTENSION = ".pprof"

    private const val FILE_WRITE_TIMEOUT_MS = 30_000L

    data class ProfileResult(
        val file: File,
        val success: Boolean,
        val errorMessage: String? = null
    )

    /**
     * Captures a Go memory profile and writes it to a timestamped file in the
     * mem_profile directory under the app's internal files directory.
     * Uses a FileObserver to detect when the Go runtime has finished writing
     * the profile to disk.
     *
     * Must be called from a coroutine scope.
     *
     * @param context Application or activity context.
     * @return ProfileResult containing the output file and success status.
     */
    suspend fun captureGoHeapDump(context: Context): ProfileResult {
        val dir = File(context.filesDir, MEM_PROFILE_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            val msg = "Failed to create directory: ${dir.absolutePath}"
            return ProfileResult(File(""), false, msg)
        }

        val timestamp = System.currentTimeMillis()
        val fileName = "$FILE_PREFIX$timestamp$FILE_EXTENSION"
        val file = File(dir, fileName)

        if (file.exists() && !file.delete()) {
            val msg = "Failed to overwrite existing file: ${file.absolutePath}"
            return ProfileResult(file, false, msg)
        }

        return try {
            var fileWritten = false
            var observer: FileObserver? = null

            try {
                // FileObserver must be created before the Go side starts writing,
                // so the CLOSE_WRITE event is not missed.
                observer = @RequiresApi(Build.VERSION_CODES.Q)
                object : FileObserver(dir, FileObserver.CLOSE_WRITE) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path == fileName) {
                            fileWritten = true
                        }
                    }
                }
                observer.startWatching()

                withTimeout(FILE_WRITE_TIMEOUT_MS.milliseconds) {
                    // Trigger the Go memory profile write.
                    // This is a suspend function that may return before the file
                    // is fully flushed, so we rely on the FileObserver below.
                    VpnController.memProfile(file.absolutePath)

                    // Wait for the FileObserver to detect the file write.
                    val deadline = System.currentTimeMillis() + FILE_WRITE_TIMEOUT_MS
                    while (!fileWritten && System.currentTimeMillis() < deadline) {
                        delay(100.milliseconds)
                        // Check if the file exists and has content as a fallback
                        if (!fileWritten && file.exists() && file.length() > 0) {
                            fileWritten = true
                        }
                    }
                }
            } finally {
                observer?.stopWatching()
            }

            if (fileWritten && file.exists() && file.length() > 0) {
                ProfileResult(file, true)
            } else {
                val msg = if (file.exists()) {
                    "Go profile file is empty: ${file.absolutePath}"
                } else {
                    "Go profile file was not written: ${file.absolutePath}"
                }
                if (file.exists()) file.delete()
                ProfileResult(file, false, msg)
            }
        } catch (e: TimeoutCancellationException) {
            val msg = "Go memory profile timed out after ${FILE_WRITE_TIMEOUT_MS}ms"
            if (file.exists() && file.length() == 0L) file.delete()
            ProfileResult(file, false, msg)
        } catch (e: IOException) {
            val msg = "Go heap dump failed: ${e.message}"
            if (file.exists()) file.delete()
            ProfileResult(file, false, msg)
        } catch (e: SecurityException) {
            val msg = "Permission denied: ${e.message}"
            if (file.exists()) file.delete()
            ProfileResult(file, false, msg)
        }
    }
}