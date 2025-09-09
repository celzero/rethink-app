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

import Logger
import Logger.LOG_TAG_APP
import kotlin.system.exitProcess

/**
 * Global Exception Handler for catching all uncaught exceptions
 * and reporting them to Firebase Analytics/Crashlytics
 */
class GlobalExceptionHandler private constructor(
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    companion object {
        private var instance: GlobalExceptionHandler? = null

        /**
         * Initialize the global exception handler
         * This should be called early in the application lifecycle
         */
        fun initialize() {
            if (instance != null) {
                Logger.w(LOG_TAG_APP, "err-handler already initialized")
                return
            }

            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            instance = GlobalExceptionHandler(defaultHandler)
            Thread.setDefaultUncaughtExceptionHandler(instance)

            Logger.i(LOG_TAG_APP, "err-handler initialized successfully")
        }

        /**
         * Get the current instance of the global exception handler
         */
        fun getInstance(): GlobalExceptionHandler? = instance
    }

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        try {
            val exception = Logger.throwableToException(exception)
            Logger.e(LOG_TAG_APP, "uncaught exception in thread ${thread.name}", exception)

            // Report to Firebase if available
            reportToFirebase(exception)

            // Add some custom context information
            logExceptionContext(thread, exception)

        } catch (e: Exception) {
            Logger.e(LOG_TAG_APP, "err while handling uncaught exception", e)
        } finally {
            // Call the default handler to ensure proper app termination
            defaultHandler?.uncaughtException(thread, exception) ?: run {
                // If no default handler, terminate the process
                Logger.e(LOG_TAG_APP, "no default exception handler found, terminating process")
                exitProcess(1)
            }
        }
    }

    /**
     * Report the uncaught exception to Firebase
     */
    private fun reportToFirebase(exception: Throwable) {
        try {
            FirebaseErrorReporting.recordException(exception)
        } catch (e: Exception) {
            // Firebase might not be available in all build variants (e.g., fdroid)
            Logger.w(LOG_TAG_APP, "crashlytics reporting not available: ${e.message}")
        }
    }

    /**
     * Log additional context information about the exception
     */
    private fun logExceptionContext(thread: Thread, exception: Throwable) {
        try {
            val stackTrace = exception.stackTraceToString()
            val threadInfo = "Thread: ${thread.name} (ID: ${thread.id}, State: ${thread.state})"

            Logger.e(LOG_TAG_APP, "=== UNCAUGHT EXCEPTION DETAILS ===")
            Logger.e(LOG_TAG_APP, "exception type: ${exception.javaClass.name}")
            Logger.e(LOG_TAG_APP, "exception msg: ${exception.message}")
            Logger.e(LOG_TAG_APP, threadInfo)
            Logger.e(LOG_TAG_APP, "stack trace:")
            Logger.e(LOG_TAG_APP, stackTrace)
            Logger.e(LOG_TAG_APP, "================================")

            // Log cause chain if available
            var cause = exception.cause
            var causeLevel = 1
            while (cause != null && causeLevel <= 5) { // Limit to 5 levels to avoid infinite loops
                Logger.e(LOG_TAG_APP, "caused by (level $causeLevel): ${cause.javaClass.name}: ${cause.message}")
                cause = cause.cause
                causeLevel++
            }
            // TODO: write logs to file for later analysis, bug report collector?
        } catch (e: Exception) {
            Logger.e(LOG_TAG_APP, "err while logging exception context", e)
        }
    }
}
