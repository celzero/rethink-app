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
package com.celzero.bravedns.service

import Logger
import Logger.LOG_TAG_BUG_REPORT
import android.content.Context
import android.system.Os
import com.celzero.bravedns.scheduler.EnhancedBugReport
import com.celzero.bravedns.service.GoCrashFileDescriptorReader.Companion.MAX_LINE_BYTES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileDescriptor
import kotlin.system.exitProcess

/**
 * Reads Go runtime crash logs from a duplicated file descriptor and persists them
 * to a timestamped file under the tombstone directory.
 *
 * Responsibilities (and nothing else):
 *   1. Duplicate the Go-provided fd so we own our copy independently.
 *   2. Make the dup blocking so [Os.read] blocks until data arrives.
 *   3. Create the output file upfront (before reading starts).
 *   4. Read lines from the fd and append them to the file.
 *   5. Close everything when the Go writer closes its end (EOF).
 *   6. Rotate old files so storage does not grow unbounded.
 *
 * Single-coroutine design: all I/O runs on one [Dispatchers.IO] coroutine so no
 * locks or synchronization are needed.  The coroutine is fire-and-forget; there is
 * no need to track the [Job], Go's write-end close triggers EOF which terminates
 * the read loop naturally.
 *
 */
class GoCrashFileDescriptorReader(private val context: Context?) {

    fun start2(): Pair<File?, File?>? {
        // create a file and send the file descriptor to the service
        // create an os.file observer for the file
        val file = createCrashFile2()
        if (file == null) {
            Logger.e(LOG_TAG_BUG_REPORT, "$TAG createCrashFile2 returned null")
            return null
        }
        val fltFile = createFlightRecFile()
        if (fltFile == null) {
            Logger.e(LOG_TAG_BUG_REPORT, "$TAG createCrashFile2 flt returned null")
            return null
        }

        return Pair(file, fltFile)
    }

    private fun createFlightRecFile(): File? {
        if (context == null) {
            Logger.e(LOG_TAG_BUG_REPORT, "$TAG createCrashFileFd: missing app context")
            return null
        }
        val file = EnhancedBugReport.newFlightRecorderFile(context)
        if (file == null) {
            Logger.e(LOG_TAG_BUG_REPORT, "$TAG createCrashFileFd: newFlightRecorderFile returned null")
            return null
        }
        Logger.d(LOG_TAG_BUG_REPORT, "$TAG createCrashFileFd: new file ${file.absolutePath}")
        return file
    }

    private fun createCrashFile2(): File? {
        if (context == null) {
            Logger.e(LOG_TAG_BUG_REPORT, "$TAG createCrashFileFd: missing app context")
            return null
        }
        val file = EnhancedBugReport.newGoCrashFile(context)
        if (file == null) {
            Logger.e(LOG_TAG_BUG_REPORT, "$TAG createCrashFileFd: newGoCrashFile returned null")
            return null
        }

        Logger.d(LOG_TAG_BUG_REPORT, "$TAG createCrashFileFd: new file ${file.absolutePath}")
        return file
    }


    companion object {
        private const val MAX_LINE_BYTES = 4 * 1024
        //private const val IDLE_TIMEOUT_MS = 3000
        private const val TAG = "GoCrashFd"
    }
}
