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
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.annotation.RequiresApi
import com.celzero.bravedns.scheduler.EnhancedBugReport
import com.celzero.bravedns.util.Utilities.isAtleastQ
import java.io.File

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

    fun start2(): File? {
        val file = createCrashFile2()
        if (file == null) {
            Logger.e(LOG_TAG_BUG_REPORT, "$TAG createCrashFile2 returned null")
            return null
        }
        if (isAtleastQ()) {
            startCrashWatcher(file)
        }

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


    /**
     * Registers an inotify-based [FileObserver] on the parent directory of [file].
     *
     * Watches for [FileObserver.MODIFY] and [FileObserver.CLOSE_WRITE] events, filtering
     * by filename.  On the first matching event, stops the observer and schedules a
     * delayed [exitProcess] to let Go finish flushing the crash dump.
     *
     * This is event-driven (zero CPU overhead in normal operation) and runs *before*
     * Go is told about the file path, so the inotify watch is guaranteed to be in
     * place before Go opens the file.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startCrashWatcher(file: File) {
        val dir = file.parentFile ?: run {
            Logger.e(LOG_TAG_BUG_REPORT, "$TAG crashWatcher: parent dir is null for ${file.absolutePath}")
            return
        }
        val fileName = file.name

        crashFileObserver = object : FileObserver(dir, MODIFY or CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                if (path != fileName) return
                if (event and (MODIFY or CLOSE_WRITE or MOVED_TO) == 0) return

                // Guard against batch-delivered duplicate events: stopWatching()
                // is asynchronous at the kernel level, so events already queued
                // in this thread's event loop may fire before the kernel watch
                // is torn down.
                val observer = crashFileObserver ?: return
                crashFileObserver = null
                observer.stopWatching()

                Logger.w(LOG_TAG_BUG_REPORT, "$TAG crashWatcher: Go crash dump detected, waiting ${EXIT_DELAY_MS}ms before exit")

                Handler(Looper.getMainLooper()).postDelayed({
                    Process.killProcess(Process.myPid())
                }, EXIT_DELAY_MS)
            }
        }.also { it.startWatching() }

        Logger.d(LOG_TAG_BUG_REPORT, "$TAG crashWatcher: monitoring ${file.absolutePath}")
    }

    companion object {
        // Stored in companion to prevent GC, the GoCrashFileDescriptorReader instance
        // that creates this observer is a local variable and gets collected immediately
        // after start2() returns. If the FileObserver is GC'd, its native weak-reference
        // callback dies silently.
        @Volatile
        private var crashFileObserver: FileObserver? = null

        private const val MAX_LINE_BYTES = 4 * 1024
        private const val EXIT_DELAY_MS = 2500L
        private const val TAG = "GoCrashFd"
    }
}
