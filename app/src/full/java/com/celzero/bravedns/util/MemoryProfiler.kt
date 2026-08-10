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
import android.os.Debug
import java.io.File
import java.io.IOException

object MemoryProfiler {

    private const val MEM_PROFILE_DIR = "mem_profile"
    private const val FILE_PREFIX = "mem_"
    private const val FILE_EXTENSION = ".pprof"

    data class ProfileResult(
        val file: File,
        val success: Boolean,
        val errorMessage: String? = null
    )

    /**
     * Captures a heap dump and writes it to a timestamped file in the mem_profile directory
     * under the app's internal files directory. Must be called from a background thread.
     *
     * @param context Application or activity context.
     * @return ProfileResult containing the output file and success status.
     */
    fun captureHeapDump(context: Context): ProfileResult {
        val dir = File(context.filesDir, MEM_PROFILE_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            val msg = "Failed to create directory: ${dir.absolutePath}"
            return ProfileResult(File(""), false, msg)
        }

        val timestamp = System.currentTimeMillis()
        val fileName = "$FILE_PREFIX$timestamp$FILE_EXTENSION"
        val file = File(dir, fileName)

        // Delete the file if it already exists (unlikely with timestamp, but safe)
        if (file.exists() && !file.delete()) {
            val msg = "Failed to overwrite existing file: ${file.absolutePath}"
            return ProfileResult(file, false, msg)
        }

        return try {
            Debug.dumpHprofData(file.absolutePath)
            ProfileResult(file, true)
        } catch (e: IOException) {
            val msg = "Heap dump failed: ${e.message}"
            // Clean up partial file on failure
            if (file.exists()) file.delete()
            ProfileResult(file, false, msg)
        } catch (e: SecurityException) {
            val msg = "Permission denied writing heap dump: ${e.message}"
            if (file.exists()) file.delete()
            ProfileResult(file, false, msg)
        } catch (e: OutOfMemoryError) {
            val msg = "Out of memory during heap dump: ${e.message}"
            if (file.exists()) file.delete()
            ProfileResult(file, false, msg)
        }
    }
}