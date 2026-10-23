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
package com.celzero.bravedns.util

import com.celzero.bravedns.util.Logger.LOG_GO_LOGGER_V1
import com.celzero.bravedns.util.Utilities.isFdroidFlavour

object CrashReporter {
    // safe cutoff
    private const val MAX_MESSAGE_LENGTH = 1500
    // limit per log line for Crashlytics
    private const val MAX_LOG_LENGTH = 4000

    /**
     * Report a Go-level crash string to Firebase. Returns true when the exception was
     * recorded (or reporting is intentionally skipped for the flavour), false on failure.
     *
     * The crash is parsed by [ExceptionParser] (firestack envelope or plain panic dump),
     * so Crashlytics shows the captured title as the issue title and the panicking
     * goroutine's real frames — not this reporting call-site.
     */
    fun recordGoCrash(message: String): Boolean {
        if (isFdroidFlavour()) return true
        return try {
            val parsed = ExceptionParser.parse(message)
            // log the full raw content first so it sits above the exception in the
            // Crashlytics log tab (64 KB ring buffer; chunk to keep entries intact).
            message.chunked(MAX_LOG_LENGTH).forEachIndexed { index, chunk ->
                FirebaseErrorReporting.log("trace[$index]: $chunk")
            }
            if (parsed.frames.isEmpty()) {
                // nothing parsable: fallback without the reporter call-site stack
                val fallback = RuntimeException("GoCrash\n${message.take(MAX_MESSAGE_LENGTH)}")
                fallback.stackTrace = emptyArray()
                FirebaseErrorReporting.recordException(fallback)
            } else {
                if (!parsed.createdBy.isNullOrBlank()) {
                    Logger.e(LOG_GO_LOGGER_V1, "go crash: ${parsed.message}, ${parsed.createdBy}")
                }
                FirebaseErrorReporting.recordException(parsed.toThrowable(context = "GoCrash"))
            }
            true
        } catch (e: Exception) {
            Logger.w(LOG_GO_LOGGER_V1, "err recording go crash: ${e.message}")
            false
        }
    }
}
