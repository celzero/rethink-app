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

object CrashReporter {
    // safe cutoff
    private const val MAX_MESSAGE_LENGTH = 1500
    // limit per log line for Crashlytics
    private const val MAX_LOG_LENGTH = 4000
    // splitter
    private const val LOG_SPLITTER = "<===>"

    /**
     * Report a Go-level crash string to Firebase.
     */
    fun recordGoCrash(message: String) {
        val msg = message.split(LOG_SPLITTER)
        val safeType = msg.getOrNull(0) ?: "No type"
        val safeMessage = msg.getOrNull(1)?.take(MAX_MESSAGE_LENGTH) ?: "No message"
        val stackTrace = msg.getOrNull(2) ?: "No stack trace"

        // Create a synthetic exception to preserve context
        val syntheticException = RuntimeException("$safeType \n $safeMessage")

        // Record it as non-fatal
        FirebaseErrorReporting.recordException(syntheticException)

        // Log stack trace in chunks to avoid truncation
        stackTrace.let { trace ->
            trace.chunked(MAX_LOG_LENGTH).forEachIndexed { index, chunk ->
                FirebaseErrorReporting.log("trace[$index]: $chunk")
            }
        }
    }
}
